/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.proto;

import static com.google.common.base.Charsets.UTF_8;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.HashedWheelTimer;

import java.io.IOException;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.bookkeeper.net.BookieSocketAddress;

import org.apache.bookkeeper.auth.AuthProviderFactoryFactory;
import org.apache.bookkeeper.auth.ClientAuthProvider;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.ReadEntryCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.util.OrderedSafeExecutor;
import org.apache.bookkeeper.util.SafeRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ExtensionRegistry;

/**
 * Implements the client-side part of the BookKeeper protocol.
 *
 */
public class BookieClient implements PerChannelBookieClientFactory {
    static final Logger LOG = LoggerFactory.getLogger(BookieClient.class);

    // This is global state that should be across all BookieClients
    AtomicLong totalBytesOutstanding = new AtomicLong();

    OrderedSafeExecutor executor;
    EventLoopGroup eventLoopGroup;
    final ConcurrentHashMap<BookieSocketAddress, PerChannelBookieClientPool> channels =
            new ConcurrentHashMap<BookieSocketAddress, PerChannelBookieClientPool>();
    final HashedWheelTimer requestTimer;

    final private ClientAuthProvider.Factory authProviderFactory;
    final private ExtensionRegistry registry;

    private final ClientConfiguration conf;
    private volatile boolean closed;
    private final ReentrantReadWriteLock closeLock;
    private final StatsLogger statsLogger;
    private final int numConnectionsPerBookie;

    public BookieClient(ClientConfiguration conf, EventLoopGroup eventLoopGroup,
                        OrderedSafeExecutor executor)
            throws IOException {
        this(conf, eventLoopGroup, executor, NullStatsLogger.INSTANCE);
    }

    public BookieClient(ClientConfiguration conf, EventLoopGroup eventLoopGroup,
                        OrderedSafeExecutor executor, StatsLogger statsLogger) throws IOException {
        this.conf = conf;
        this.eventLoopGroup = eventLoopGroup;
        this.executor = executor;
        this.closed = false;
        this.closeLock = new ReentrantReadWriteLock();

        this.registry = ExtensionRegistry.newInstance();
        this.authProviderFactory = AuthProviderFactoryFactory.newClientAuthProviderFactory(conf, registry);

        this.statsLogger = statsLogger;
        this.numConnectionsPerBookie = conf.getNumChannelsPerBookie();
        this.requestTimer = new HashedWheelTimer(
                new ThreadFactoryBuilder().setNameFormat("BookieClientTimer-%d").build(),
                conf.getPCBCTimeoutTimerTickDurationMs(), TimeUnit.MILLISECONDS,
                conf.getPCBCTimeoutTimerNumTicks());
    }

    private int getRc(int rc) {
        if (BKException.Code.OK == rc) {
            return rc;
        } else {
            if (closed) {
                return BKException.Code.ClientClosedException;
            } else {
                return rc;
            }
        }
    }

    @Override
    public PerChannelBookieClient create(BookieSocketAddress address) {
        return new PerChannelBookieClient(conf, executor, eventLoopGroup, address,
                requestTimer, authProviderFactory, registry, statsLogger);
    }

    private PerChannelBookieClientPool lookupClient(BookieSocketAddress addr, Object key) {
        PerChannelBookieClientPool clientPool = channels.get(addr);
        if (null == clientPool) {
            closeLock.readLock().lock();
            try {
                if (closed) {
                    return null;
                }
                PerChannelBookieClientPool newClientPool =
                    new DefaultPerChannelBookieClientPool(this, addr, numConnectionsPerBookie);
                PerChannelBookieClientPool oldClientPool = channels.putIfAbsent(addr, newClientPool);
                if (null == oldClientPool) {
                    clientPool = newClientPool;
                    // initialize the pool only after we put the pool into the map
                    clientPool.intialize();
                } else {
                    clientPool = oldClientPool;
                    newClientPool.close(false);
                }
            } finally {
                closeLock.readLock().unlock();
            }
        }
        return clientPool;
    }

    public void closeClients(Set<BookieSocketAddress> addrs) {
        final HashSet<PerChannelBookieClientPool> clients =
                new HashSet<PerChannelBookieClientPool>();
        closeLock.readLock().lock();
        try {
            for (BookieSocketAddress a : addrs) {
                PerChannelBookieClientPool c = channels.get(a);
                if (c != null) {
                    clients.add(c);
                }
            }

            if (clients.size() == 0) {
                return;
            }
        } finally {
            closeLock.readLock().unlock();
        }
        for (PerChannelBookieClientPool c : clients) {
            c.disconnect(false);
        }
    }

    public void addEntry(final BookieSocketAddress addr, final long ledgerId, final byte[] masterKey,
            final long entryId, final ByteBuf toSend, final WriteCallback cb, final Object ctx, final int options) {
        closeLock.readLock().lock();
        try {
            final PerChannelBookieClientPool client = lookupClient(addr, entryId);
            if (client == null) {
                cb.writeComplete(getRc(BKException.Code.BookieHandleNotAvailableException),
                                 ledgerId, entryId, addr, ctx);
                return;
            }

            // Retain the buffer, since the connection could be obtained after the PendingAddOp might have already
            // failed
            toSend.retain();

            client.obtain(new GenericCallback<PerChannelBookieClient>() {
                @Override
                public void operationComplete(final int rc, PerChannelBookieClient pcbc) {
                    if (rc != BKException.Code.OK) {
                        try {
                            executor.submitOrdered(ledgerId, new SafeRunnable() {
                                @Override
                                public void safeRun() {
                                    cb.writeComplete(rc, ledgerId, entryId, addr, ctx);
                                }
                            });
                        } catch (RejectedExecutionException re) {
                            cb.writeComplete(getRc(BKException.Code.InterruptedException),
                                    ledgerId, entryId, addr, ctx);
                        }
                    } else {
                        pcbc.addEntry(ledgerId, masterKey, entryId, toSend, cb, ctx, options);
                    }

                    toSend.release();
                }
            });
        } finally {
            closeLock.readLock().unlock();
        }
    }

    public void readEntryAndFenceLedger(final BookieSocketAddress addr,
                                        final long ledgerId,
                                        final byte[] masterKey,
                                        final long entryId,
                                        final ReadEntryCallback cb,
                                        final Object ctx) {
        closeLock.readLock().lock();
        try {
            final PerChannelBookieClientPool client = lookupClient(addr, entryId);
            if (client == null) {
                cb.readEntryComplete(getRc(BKException.Code.BookieHandleNotAvailableException),
                                     ledgerId, entryId, null, ctx);
                return;
            }

            client.obtain(new GenericCallback<PerChannelBookieClient>() {
                @Override
                public void operationComplete(final int rc, PerChannelBookieClient pcbc) {
                    if (rc != BKException.Code.OK) {
                        try {
                            executor.submitOrdered(ledgerId, new SafeRunnable() {
                                @Override
                                public void safeRun() {
                                    cb.readEntryComplete(rc, ledgerId, entryId, null, ctx);
                                }
                            });
                        } catch (RejectedExecutionException re) {
                            cb.readEntryComplete(getRc(BKException.Code.InterruptedException),
                                    ledgerId, entryId, null, ctx);
                        }
                        return;
                    }
                    pcbc.readEntryAndFenceLedger(ledgerId, masterKey, entryId, cb, ctx);
                }
            });
        } finally {
            closeLock.readLock().unlock();
        }
    }

    public void readEntry(final BookieSocketAddress addr, final long ledgerId, final long entryId,
                          final ReadEntryCallback cb, final Object ctx) {
        closeLock.readLock().lock();
        try {
            final PerChannelBookieClientPool client = lookupClient(addr, entryId);
            if (client == null) {
                cb.readEntryComplete(getRc(BKException.Code.BookieHandleNotAvailableException),
                                     ledgerId, entryId, null, ctx);
                return;
            }

            client.obtain(new GenericCallback<PerChannelBookieClient>() {
                @Override
                public void operationComplete(final int rc, PerChannelBookieClient pcbc) {
                    if (rc != BKException.Code.OK) {
                        try {
                            executor.submitOrdered(ledgerId, new SafeRunnable() {
                                @Override
                                public void safeRun() {
                                    cb.readEntryComplete(rc, ledgerId, entryId, null, ctx);
                                }
                            });
                        } catch (RejectedExecutionException re) {
                            cb.readEntryComplete(getRc(BKException.Code.InterruptedException),
                                    ledgerId, entryId, null, ctx);
                        }
                        return;
                    }
                    pcbc.readEntry(ledgerId, entryId, cb, ctx);
                }
            });
        } finally {
            closeLock.readLock().unlock();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        closeLock.writeLock().lock();
        try {
            closed = true;
            for (PerChannelBookieClientPool pool : channels.values()) {
                pool.close(true);
            }
            channels.clear();
        } finally {
            closeLock.writeLock().unlock();
        }
        // Shut down the timeout executor.
        this.requestTimer.stop();
    }

    private static class Counter {
        int i;
        int total;

        synchronized void inc() {
            i++;
            total++;
        }

        synchronized void dec() {
            i--;
            notifyAll();
        }

        synchronized void wait(int limit) throws InterruptedException {
            while (i > limit) {
                wait();
            }
        }

        synchronized int total() {
            return total;
        }
    }

    /**
     * @param args
     * @throws IOException
     * @throws NumberFormatException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws NumberFormatException, IOException, InterruptedException {
        if (args.length != 3) {
            System.err.println("USAGE: BookieClient bookieHost port ledger#");
            return;
        }
        WriteCallback cb = new WriteCallback() {

            public void writeComplete(int rc, long ledger, long entry, BookieSocketAddress addr, Object ctx) {
                Counter counter = (Counter) ctx;
                counter.dec();
                if (rc != 0) {
                    System.out.println("rc = " + rc + " for " + entry + "@" + ledger);
                }
            }
        };
        Counter counter = new Counter();
        byte hello[] = "hello".getBytes(UTF_8);
        long ledger = Long.parseLong(args[2]);
        EventLoopGroup eventLoopGroup = new NioEventLoopGroup(1);
        OrderedSafeExecutor executor = new OrderedSafeExecutor(1,
                "BookieClientWorker");
        BookieClient bc = new BookieClient(new ClientConfiguration(), eventLoopGroup, executor);
        BookieSocketAddress addr = new BookieSocketAddress(args[0], Integer.parseInt(args[1]));

        for (int i = 0; i < 100000; i++) {
            counter.inc();
            bc.addEntry(addr, ledger, new byte[0], i, Unpooled.wrappedBuffer(hello), cb, counter, 0);
        }
        counter.wait(0);
        System.out.println("Total = " + counter.total());
        eventLoopGroup.shutdownGracefully();
        executor.shutdown();
    }
}
