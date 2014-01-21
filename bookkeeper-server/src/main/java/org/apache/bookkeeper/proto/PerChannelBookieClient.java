/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.bookkeeper.proto;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.bookkeeper.auth.ClientAuthProvider;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeperClientStats;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.proto.BookieProtocol.PacketHeader;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.ReadEntryCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;

import org.apache.bookkeeper.proto.DataFormats.AuthMessage;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.stats.OpStatsLogger;
import org.apache.bookkeeper.util.MathUtils;
import org.apache.bookkeeper.util.OrderedSafeExecutor;
import org.apache.bookkeeper.util.SafeRunnable;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBufferOutputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.handler.codec.frame.CorruptedFrameException;
import org.jboss.netty.handler.codec.frame.LengthFieldBasedFrameDecoder;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.timeout.ReadTimeoutException;
import org.jboss.netty.handler.timeout.ReadTimeoutHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.protobuf.ExtensionRegistry;

/**
 * This class manages all details of connection to a particular bookie. It also
 * has reconnect logic if a connection to a bookie fails.
 *
 */

@ChannelPipelineCoverage("one")
public class PerChannelBookieClient extends SimpleChannelHandler implements ChannelPipelineFactory {

    static final Logger LOG = LoggerFactory.getLogger(PerChannelBookieClient.class);

    static final long maxMemory = Runtime.getRuntime().maxMemory() / 5;
    public static final int MAX_FRAME_LENGTH = 110 * 1024 * 1024; // increased max netty frame size to 110M


    InetSocketAddress addr;
    AtomicLong totalBytesOutstanding;
    ClientSocketChannelFactory channelFactory;
    OrderedSafeExecutor executor;

    ConcurrentHashMap<CompletionKey, AddCompletion> addCompletions = new ConcurrentHashMap<CompletionKey, AddCompletion>();
    ListMultimap<CompletionKey, ReadCompletion> readCompletions = LinkedListMultimap.create();

    private final StatsLogger statsLogger;
    private final OpStatsLogger readEntryOpLogger;
    private final OpStatsLogger readTimeoutOpLogger;
    private final OpStatsLogger addEntryOpLogger;
    private final OpStatsLogger addTimeoutOpLogger;

    /**
     * The following member variables do not need to be concurrent, or volatile
     * because they are always updated under a lock
     */
    Queue<GenericCallback<Void>> pendingOps = new ArrayDeque<GenericCallback<Void>>();
    volatile Channel channel = null;

    enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATING, CLOSED
            };

    private long authStartTime;

    volatile ConnectionState state;
    private final ClientConfiguration conf;
    private final ClientAuthProvider.Factory authProviderFactory;
    volatile private ClientAuthProvider authProvider = null;
    private final ExtensionRegistry extRegistry;

    private final int readTimeout;

    public PerChannelBookieClient(ClientConfiguration conf, OrderedSafeExecutor executor,
                                  ClientSocketChannelFactory channelFactory, InetSocketAddress addr,
                                  AtomicLong totalBytesOutstanding,
                                  ClientAuthProvider.Factory authProviderFactory, ExtensionRegistry extRegistry) {
        this(conf, executor, channelFactory, addr, totalBytesOutstanding,
             NullStatsLogger.INSTANCE, authProviderFactory, extRegistry);
    }

    public PerChannelBookieClient(ClientConfiguration conf, OrderedSafeExecutor executor,
                                  ClientSocketChannelFactory channelFactory, InetSocketAddress addr,
                                  AtomicLong totalBytesOutstanding,
                                  StatsLogger parentStatsLogger,
                                  ClientAuthProvider.Factory authProviderFactory, ExtensionRegistry extRegistry) {
        this.conf = conf;
        this.addr = addr;
        this.executor = executor;
        this.totalBytesOutstanding = totalBytesOutstanding;
        this.channelFactory = channelFactory;
        this.state = ConnectionState.DISCONNECTED;
        this.readTimeout = conf.getReadTimeout();

        this.authProviderFactory = authProviderFactory;
        this.extRegistry = extRegistry;

        StringBuilder nameBuilder = new StringBuilder();
        nameBuilder.append(addr.getHostName().replace('.', '_').replace('-', '_'))
            .append("_").append(addr.getPort());

        this.statsLogger = parentStatsLogger.scope(BookKeeperClientStats.CHANNEL_SCOPE)
            .scope(nameBuilder.toString());

        readEntryOpLogger = statsLogger.getOpStatsLogger(BookKeeperClientStats.CHANNEL_READ_OP);
        addEntryOpLogger = statsLogger.getOpStatsLogger(BookKeeperClientStats.CHANNEL_ADD_OP);
        readTimeoutOpLogger = statsLogger.getOpStatsLogger(BookKeeperClientStats.CHANNEL_TIMEOUT_READ);
        addTimeoutOpLogger = statsLogger.getOpStatsLogger(BookKeeperClientStats.CHANNEL_TIMEOUT_ADD);
    }

    private void authComplete(int rc) {
        Queue<GenericCallback<Void>> oldPendingOps;

        synchronized (this) {
            if (state != ConnectionState.AUTHENTICATING) {
                return;
            }
            if (rc == BKException.Code.OK) {
                LOG.info("Successfully authorized with bookie: " + addr);
                state = ConnectionState.CONNECTED;
            } else {
                LOG.info("Authorization failed with bookie: {}, rc = {}",
                        addr, rc);
                // closing the set state to disconnected
                // and complete the pending ops
                closeChannel(channel);
            }

            // trick to not do operations under the lock, take the list
            // of pending ops and assign it to a new variable, while
            // emptying the pending ops by just assigning it to a new
            // list
            oldPendingOps = pendingOps;
            pendingOps = new ArrayDeque<GenericCallback<Void>>();
        }

        for (GenericCallback<Void> pendingOp : oldPendingOps) {
            pendingOp.operationComplete(rc, null);
        }
    }

    private void connectComplete(ChannelFuture future) {
        Queue<GenericCallback<Void>> opsToErr = null;

        synchronized (PerChannelBookieClient.this) {
            if (future.isSuccess() && state == ConnectionState.CONNECTING) {
                LOG.info("Successfully connected to bookie: {}", future.getChannel());
                channel = future.getChannel();
                state = ConnectionState.AUTHENTICATING;
                authStartTime = MathUtils.now();
            } else if (future.isSuccess() && (state == ConnectionState.CLOSED || state == ConnectionState.DISCONNECTED)) {
                LOG.error("Closed before connection completed, clean up: " + addr);
                LOG.warn("Closed before connection completed, clean up: {}, current state {}",
                         future.getChannel(), state);
                closeChannel(future.getChannel());

                channel = null;
            } else if (future.isSuccess() && (state == ConnectionState.CONNECTED
                                              || state == ConnectionState.AUTHENTICATING)) {
                LOG.debug("Already connected with another channel({}), so close the new channel({})",
                          channel, future.getChannel());
                closeChannel(future.getChannel());
                return; // pendingOps should have been completed when other channel connected
            } else {
                LOG.error("Could not connect to bookie: {}/{}, current state {} : ",
                        new Object[] { future.getChannel(), addr, state, future.getCause() });
                closeChannel(future.getChannel());

                channel = null;
                if (state != ConnectionState.CLOSED) {
                    state = ConnectionState.DISCONNECTED;
                }

                // trick to not do operations under the lock, take the list
                // of pending ops and assign it to a new variable, while
                // emptying the pending ops by just assigning it to a new
                // list
                opsToErr = pendingOps;
                pendingOps = new ArrayDeque<GenericCallback<Void>>();
            }
        }
        if (opsToErr == null) {
            authProvider = authProviderFactory.newProvider(addr,
                    new GenericCallback<Void>() {
                        public void operationComplete(int rc, Void v) {
                            authComplete(rc);
                        }
                    });
            authProvider.init(new GenericCallback<AuthMessage>() {
                    public void operationComplete(int rc, AuthMessage am) {
                        if (rc != BKException.Code.OK) {
                            authComplete(rc);
                        }
                        writeAuthMessage(am);
                    }
                });
        } else {
            for (GenericCallback<Void> pendingOp : opsToErr) {
                pendingOp.operationComplete(BKException.Code.BookieHandleNotAvailableException,
                                            null);
            }
        }
    }

    private void writeAuthMessage(AuthMessage am) {
        Channel channel = this.channel;
        if (channel == null) {
            authComplete(BKException.Code.UnauthorizedAccessException);
        }

        int totalHeaderSize = 4 // for the length of the packet
                              + 4; // for request type

        int totalSize = totalHeaderSize + am.getSerializedSize();

        ChannelBuffer msg = channel.getConfig().getBufferFactory()
            .getBuffer(totalHeaderSize + am.getSerializedSize());
        try {
            ChannelBufferOutputStream bufStream = new ChannelBufferOutputStream(msg);
            bufStream.writeInt(totalSize - 4);
            bufStream.writeInt(new PacketHeader(BookieProtocol.CURRENT_PROTOCOL_VERSION,
                                                BookieProtocol.AUTH,
                                                BookieProtocol.FLAG_NONE).toInt());
            am.writeTo(bufStream);
        } catch (IOException ioe) {
            LOG.error("Error generating auth message", ioe);
            authComplete(BKException.Code.UnauthorizedAccessException);
            return;
        }

        ChannelFuture future = channel.write(msg);
        future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        LOG.debug("Successfully wrote auth message to bookie");
                    } else {
                        LOG.error("Failed to write auth message to channel {}", addr);
                        authComplete(BKException.Code.UnauthorizedAccessException);
                    }
                }
            });
    }

    private void handleAuthMessage(AuthMessage am) {
        if (!am.hasAuthPluginName()
            || !am.getAuthPluginName().equals(authProviderFactory.getPluginName())) {
            LOG.error("Received message from incompatible auth plugin. Local = {}, Remote = {}",
                      authProviderFactory.getPluginName(), am.getAuthPluginName());
            authComplete(BKException.Code.UnauthorizedAccessException);
            return;
        }
        authProvider.process(am,
                             new GenericCallback<AuthMessage>() {
                                 public void operationComplete(int rc, AuthMessage am) {
                                     if (rc != BKException.Code.OK) {
                                         authComplete(rc);
                                     }
                                     writeAuthMessage(am);
                                 }
                             });
    }

    private void connect() {
        LOG.info("Connecting to bookie: {}", addr);

        // Set up the ClientBootStrap so we can create a new Channel connection
        // to the bookie.
        ClientBootstrap bootstrap = new ClientBootstrap(channelFactory);
        bootstrap.setPipelineFactory(this);
        bootstrap.setOption("tcpNoDelay", conf.getClientTcpNoDelay());
        bootstrap.setOption("keepAlive", true);

        ChannelFuture future = bootstrap.connect(addr);
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                connectComplete(future);
            }
        });
    }

    void connectIfNeededAndDoOp(GenericCallback<Void> op) {
        boolean completeOpNow = false;
        int opRc = BKException.Code.OK;
        // common case without lock first
        if (channel != null && state == ConnectionState.CONNECTED) {
            completeOpNow = true;
        } else {

            synchronized (this) {
                // check the channel status again under lock
                if (channel != null && state == ConnectionState.CONNECTED) {
                    completeOpNow = true;
                    opRc = BKException.Code.OK;
                } else if (state == ConnectionState.CLOSED) {
                    completeOpNow = true;
                    opRc = BKException.Code.BookieHandleNotAvailableException;
                } else {
                    // channel is either null (first connection attempt), or the
                    // channel is disconnected. Connection attempt is still in
                    // progress, queue up this op. Op will be executed when
                    // connection attempt either fails or succeeds
                    LOG.debug("Enqueuing op {}", op);
                    pendingOps.add(op);

                    if (state == ConnectionState.CONNECTING
                        || state == ConnectionState.AUTHENTICATING) {
                        // just return as connection request has already send
                        // and waiting for the response.
                        return;
                    }
                    authProvider = null;
                    // switch state to connecting and do connection attempt
                    state = ConnectionState.CONNECTING;
                }
            }
            if (!completeOpNow) {
                // Start connection attempt to the input server host.
                connect();
            }
        }

        if (completeOpNow) {
            op.operationComplete(opRc, null);
        }

    }

    /**
     * This method should be called only after connection has been checked for
     * {@link #connectIfNeededAndDoOp(GenericCallback)}
     *
     * @param ledgerId
     * @param masterKey
     * @param entryId
     * @param lastConfirmed
     * @param macCode
     * @param data
     * @param cb
     * @param ctx
     */
    void addEntry(final long ledgerId, byte[] masterKey, final long entryId, ChannelBuffer toSend, WriteCallback cb,
                  Object ctx, final int options) {
        final int entrySize = toSend.readableBytes();

        final CompletionKey completionKey = new CompletionKey(ledgerId, entryId);
        addCompletions.put(completionKey, new AddCompletion(addEntryOpLogger, cb, ctx));

        int totalHeaderSize = 4 // for the length of the packet
                              + 4 // for the type of request
                              + BookieProtocol.MASTER_KEY_LENGTH; // for the master key

        try{
            ChannelBuffer header = channel.getConfig().getBufferFactory().getBuffer(totalHeaderSize);

            header.writeInt(totalHeaderSize - 4 + entrySize);
            header.writeInt(new PacketHeader(BookieProtocol.CURRENT_PROTOCOL_VERSION,
                                             BookieProtocol.ADDENTRY, (short)options).toInt());
            header.writeBytes(masterKey, 0, BookieProtocol.MASTER_KEY_LENGTH);

            ChannelBuffer wrappedBuffer = ChannelBuffers.wrappedBuffer(header, toSend);

            ChannelFuture future = channel.write(wrappedBuffer);
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Successfully wrote request for adding entry: " + entryId + " ledger-id: " + ledgerId
                                                            + " bookie: " + channel.getRemoteAddress() + " entry length: " + entrySize);
                        }
                        // totalBytesOutstanding.addAndGet(entrySize);
                    } else {
                        LOG.error("Error writing entry: " + completionKey);
                        errorOutAddKey(completionKey);
                    }
                }
            });
        } catch (Throwable e) {
            LOG.warn("Add entry operation failed", e);
            errorOutAddKey(completionKey);
        }
    }

    /**
     * This method should be called only after connection has been checked for
     * {@link #connectIfNeededAndDoOp(GenericCallback)}
     * 
     * @param ledgerId
     * @param masterKey
     * @param lastEntryId
     * @param cb
     * @param ctx
     * @param options
     */
    void trim(final long ledgerId, byte[] masterKey, final long lastEntryId, final int options) {
        int totalHeaderSize = 4 // for the length of the packet
                            + 4 // for request type
                            + 8 // for ledgerId
                            + 8; // for entryId

        try {
            ChannelBuffer tmpEntry = channel.getConfig().getBufferFactory().getBuffer(totalHeaderSize);
            tmpEntry.writeInt(totalHeaderSize - 4);

            tmpEntry.writeInt(new PacketHeader(BookieProtocol.CURRENT_PROTOCOL_VERSION, BookieProtocol.TRIM,
                    BookieProtocol.FLAG_NONE).toInt());
            tmpEntry.writeLong(ledgerId);
            tmpEntry.writeLong(lastEntryId);

            channel.write(tmpEntry);
        } catch (Throwable e) {
            LOG.warn("Trim entry operation failed", e);
        }
    }

    public void readEntryAndFenceLedger(final long ledgerId, byte[] masterKey,
                                        final long entryId,
                                        ReadEntryCallback cb, Object ctx) {
        final CompletionKey key = new CompletionKey(ledgerId, entryId);
        synchronized (readCompletions) {
            readCompletions.put(key, new ReadCompletion(readEntryOpLogger, cb, ctx));
        }

        int totalHeaderSize = 4 // for the length of the packet
                              + 4 // for request type
                              + 8 // for ledgerId
                              + 8 // for entryId
                              + BookieProtocol.MASTER_KEY_LENGTH; // for masterKey

        ChannelBuffer tmpEntry = channel.getConfig().getBufferFactory().getBuffer(totalHeaderSize);
        tmpEntry.writeInt(totalHeaderSize - 4);

        tmpEntry.writeInt(new PacketHeader(BookieProtocol.CURRENT_PROTOCOL_VERSION,
                                           BookieProtocol.READENTRY,
                                           BookieProtocol.FLAG_DO_FENCING).toInt());
        tmpEntry.writeLong(ledgerId);
        tmpEntry.writeLong(entryId);
        tmpEntry.writeBytes(masterKey, 0, BookieProtocol.MASTER_KEY_LENGTH);

        ChannelFuture future = channel.write(tmpEntry);
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Successfully wrote request for reading entry: " + entryId + " ledger-id: "
                                  + ledgerId + " bookie: " + channel.getRemoteAddress());
                    }
                } else {
                    errorOutReadKey(key);
                }
            }
        });
    }

    public void readEntry(final long ledgerId, final long entryId, ReadEntryCallback cb, Object ctx) {
        final CompletionKey key = new CompletionKey(ledgerId, entryId);
        synchronized (readCompletions) {
            readCompletions.put(key, new ReadCompletion(readEntryOpLogger, cb, ctx));
        }

        int totalHeaderSize = 4 // for the length of the packet
                              + 4 // for request type
                              + 8 // for ledgerId
                              + 8; // for entryId

        try{
            ChannelBuffer tmpEntry = channel.getConfig().getBufferFactory().getBuffer(totalHeaderSize);
            tmpEntry.writeInt(totalHeaderSize - 4);

            tmpEntry.writeInt(new PacketHeader(BookieProtocol.CURRENT_PROTOCOL_VERSION,
                                               BookieProtocol.READENTRY, BookieProtocol.FLAG_NONE).toInt());
            tmpEntry.writeLong(ledgerId);
            tmpEntry.writeLong(entryId);

            ChannelFuture future = channel.write(tmpEntry);
            future.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Successfully wrote request for reading entry: " + entryId + " ledger-id: "
                                                            + ledgerId + " bookie: " + channel.getRemoteAddress());
                        }
                    } else {
                        errorOutReadKey(key);
                    }
                }
            });
        } catch(Throwable e) {
            LOG.warn("Read entry operation failed", e);
            errorOutReadKey(key);
        }
    }

    /**
     * Disconnects the bookie client. It can be reused.
     */
    public void disconnect() {
        closeInternal(false);
    }

    /**
     * Closes the bookie client permanently. It cannot be reused.
     */
    public void close() {
        closeInternal(true);
    }

    private void closeInternal(boolean permanent) {
        Channel toClose = null;
        synchronized (this) {
            if (permanent) {
                state = ConnectionState.CLOSED;
            } else if (state != ConnectionState.CLOSED) {
                state = ConnectionState.DISCONNECTED;
            }
            toClose = channel;
            channel = null;
        }
        if (toClose != null) {
            closeChannel(toClose).awaitUninterruptibly();
        }
    }

    private ChannelFuture closeChannel(Channel c) {
        LOG.debug("Closing channel {}", c);
        ReadTimeoutHandler timeout = c.getPipeline().get(ReadTimeoutHandler.class);
        if (timeout != null) {
            timeout.releaseExternalResources();
        }
        return c.close();
    }

    void errorOutReadKey(final CompletionKey key) {
        executor.submitOrdered(key.ledgerId, new SafeRunnable() {
            @Override
            public void safeRun() {
                final List<ReadCompletion> completions;
                synchronized (readCompletions) {
                    completions = readCompletions.removeAll(key);
                }

                for (ReadCompletion readCompletion : completions) {
                    String bAddress = "null";
                    Channel c = channel;

                    if (c != null) {
                        bAddress = c.getRemoteAddress().toString();
                    }

                    LOG.error("Could not write  request for reading entry: " + key.entryId + " ledger-id: "
                              + key.ledgerId + " bookie: " + bAddress);

                    if (readCompletion != null) {
                        LOG.debug("Could not write request for reading entry: {}" + " ledger-id: {} bookie: {}",
                                  new Object[] { key.entryId, key.ledgerId, bAddress });

                    readCompletion.cb.readEntryComplete(BKException.Code.BookieHandleNotAvailableException,
                                                        key.ledgerId, key.entryId, null, readCompletion.ctx);
                    }
                }
            }
        });
    }

    void errorOutAddKey(final CompletionKey key) {
        executor.submitOrdered(key.ledgerId, new SafeRunnable() {
            @Override
            public void safeRun() {

                AddCompletion addCompletion = addCompletions.remove(key);

                if (addCompletion != null) {
                    String bAddress = "null";
                    Channel c = channel;
                    if(c != null) {
                        bAddress = c.getRemoteAddress().toString();
                    }
                    LOG.debug("Could not write request for adding entry: {} ledger-id: {} bookie: {}",
                              new Object[] { key.entryId, key.ledgerId, bAddress });

                    addCompletion.cb.writeComplete(BKException.Code.BookieHandleNotAvailableException, key.ledgerId,
                                                   key.entryId, addr, addCompletion.ctx);
                    LOG.debug("Invoked callback method: {}", key.entryId);
                }
            }

        });

    }

    /**
     * Errors out pending entries. We call this method from one thread to avoid
     * concurrent executions to QuorumOpMonitor (implements callbacks). It seems
     * simpler to call it from BookieHandle instead of calling directly from
     * here.
     */

    void errorOutOutstandingEntries() {

        // DO NOT rewrite these using Map.Entry iterations. We want to iterate
        // on keys and see if we are successfully able to remove the key from
        // the map. Because the add and the read methods also do the same thing
        // in case they get a write failure on the socket. The one who
        // successfully removes the key from the map is the one responsible for
        // calling the application callback.

        for (CompletionKey key : addCompletions.keySet()) {
            errorOutAddKey(key);
        }

        for (CompletionKey key : readCompletions.keySet()) {
            errorOutReadKey(key);
        }
    }

    /**
     * In the netty pipeline, we need to split packets based on length, so we
     * use the {@link LengthFieldBasedFrameDecoder}. Other than that all actions
     * are carried out in this class, e.g., making sense of received messages,
     * prepending the length to outgoing packets etc.
     */
    @Override
    public ChannelPipeline getPipeline() throws Exception {
        ChannelPipeline pipeline = Channels.pipeline();

        pipeline.addLast("readTimeout", new ReadTimeoutHandler(new HashedWheelTimer(),
                                                               readTimeout));
        pipeline.addLast("lengthbasedframedecoder", new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4));
        pipeline.addLast("mainhandler", this);
        return pipeline;
    }

    /**
     * If our channel has disconnected, we just error out the pending entries
     */
    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        Channel c = ctx.getChannel();
        LOG.info("Disconnected from bookie channel {}", c);
        if (c != null) {
            closeChannel(c);
        }

        errorOutOutstandingEntries();

        Queue<GenericCallback<Void>> oldPendingOps;
        
        int rc = BKException.Code.BookieHandleNotAvailableException;
        synchronized (this) {
            oldPendingOps = pendingOps;
            pendingOps = new ArrayDeque<GenericCallback<Void>>();

            if (state == ConnectionState.AUTHENTICATING) {
                // Connection must have been closed for problems during authentication
                rc = BKException.Code.UnauthorizedAccessException;
            }

            if (this.channel == c && state != ConnectionState.CLOSED) {
                state = ConnectionState.DISCONNECTED;
            }
        }

        for (GenericCallback<Void> pendingOp : oldPendingOps) {
            pendingOp.operationComplete(rc, null);
        }
        
        // we don't want to reconnect right away. If someone sends a request to
        // this address, we will reconnect.
    }

    /**
     * Called by netty when an exception happens in one of the netty threads
     * (mostly due to what we do in the netty threads)
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        Throwable t = e.getCause();
        if (t instanceof CorruptedFrameException || t instanceof TooLongFrameException) {
            LOG.error("Corrupted frame received from bookie: {}",
                      e.getChannel().getRemoteAddress());
            return;
        }
        if (t instanceof ReadTimeoutException) {
            if (state == ConnectionState.AUTHENTICATING
                && (conf.getAuthTimeout() < (MathUtils.now() - authStartTime))) {
                authComplete(BKException.Code.AuthTimeoutException);
            }

            for (CompletionKey key : addCompletions.keySet()) {
                if (key.shouldTimeout()) {
                    errorOutAddKey(key);
                    addTimeoutOpLogger.registerSuccessfulEvent(key.elapsedTime());
                }
            }
            for (CompletionKey key : readCompletions.keySet()) {
                if (key.shouldTimeout()) {
                    errorOutReadKey(key);
                    readTimeoutOpLogger.registerSuccessfulEvent(key.elapsedTime());
                }
            }
            return;
        }

        if (t instanceof IOException) {
            // these are thrown when a bookie fails, logging them just pollutes
            // the logs (the failure is logged from the listeners on the write
            // operation), so I'll just ignore it here.
            if (state == ConnectionState.AUTHENTICATING) {
                authComplete(BKException.Code.AuthTimeoutException);
            }
            return;
        }

        synchronized (this) {
            if (state == ConnectionState.CLOSED) {
                LOG.debug("Unexpected exception caught by bookie client channel handler, "
                          + "but the client is closed, so it isn't important", t);
            } else {
                LOG.error("Unexpected exception caught by bookie client channel handler", t);
            }
        }
        // Since we are a library, cant terminate App here, can we?
    }

    /**
     * Called by netty when a message is received on a channel
     */
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (!(e.getMessage() instanceof ChannelBuffer)) {
            ctx.sendUpstream(e);
            return;
        }

        final ChannelBuffer buffer = (ChannelBuffer) e.getMessage();
        final int rc;
        final long ledgerId, entryId;
        final PacketHeader header;

        try {
            header = PacketHeader.fromInt(buffer.readInt());
            if (header.getOpCode() == BookieProtocol.AUTH) {
                ClientAuthProvider authProvider = this.authProvider;
                if (authProvider != null) {
                    ChannelBufferInputStream bufStream = new ChannelBufferInputStream(buffer);
                    AuthMessage.Builder builder = AuthMessage.newBuilder();
                    builder.mergeFrom(bufStream, extRegistry);
                    handleAuthMessage(builder.build());
                }
                return;
            }

            rc = buffer.readInt();
            ledgerId = buffer.readLong();
            entryId = buffer.readLong();
        } catch (IndexOutOfBoundsException ex) {
            LOG.error("Unparseable response from bookie: " + addr, ex);
            return;
        }

        executor.submitOrdered(ledgerId, new SafeRunnable() {
            @Override
            public void safeRun() {
                switch (header.getOpCode()) {
                case BookieProtocol.ADDENTRY:
                    handleAddResponse(ledgerId, entryId, rc);
                    break;
                case BookieProtocol.READENTRY:
                    handleReadResponse(ledgerId, entryId, rc, buffer);
                    break;
                default:
                    LOG.error("Unexpected response, type: " + header.getOpCode() 
                              + " received from bookie: " + addr + " , ignoring");
                }
            }
        });
    }

    void handleAddResponse(long ledgerId, long entryId, int rc) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Got response for add request from bookie: {} for ledger: {} entry: {}"
                      + " rc: {}", new Object[] { addr, ledgerId, entryId, rc });
        }

        // convert to BKException code because thats what the uppper
        // layers expect. This is UGLY, there should just be one set of
        // error codes.
        switch (rc) {
        case BookieProtocol.EOK:
            rc = BKException.Code.OK;
            break;
        case BookieProtocol.EBADVERSION:
            rc = BKException.Code.ProtocolVersionException;
            break;
        case BookieProtocol.EFENCED:
            rc = BKException.Code.LedgerFencedException;
            break;
        case BookieProtocol.EUA:
            rc = BKException.Code.UnauthorizedAccessException;
            break;
        case BookieProtocol.EREADONLY:
            rc = BKException.Code.WriteOnReadOnlyBookieException;
            break;
        default:
            LOG.warn("Add for ledger: {}, entry: {} failed on bookie: {}"
                    + " with unknown code: {}", new Object[] { ledgerId, entryId, addr, rc });
            rc = BKException.Code.WriteException;
            break;
        }

        AddCompletion ac;
        ac = addCompletions.remove(new CompletionKey(ledgerId, entryId));
        if (ac == null) {
            LOG.debug("Unexpected add response received from bookie: {} for ledger: {}"
                    + ", entry: {}, ignoring", new Object[] { addr,  ledgerId, entryId });
            return;
        }

        // totalBytesOutstanding.addAndGet(-ac.size);

        ac.cb.writeComplete(rc, ledgerId, entryId, addr, ac.ctx);

    }

    void handleReadResponse(long ledgerId, long entryId, int rc, ChannelBuffer buffer) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Got response for read request from bookie: {} for ledger: {} entry: {}"
                    + " rc: {} entry length: {}",
                    new Object[] { addr, ledgerId, entryId, rc, buffer.readableBytes() });
        }

        // convert to BKException code because thats what the uppper
        // layers expect. This is UGLY, there should just be one set of
        // error codes.
        if (rc == BookieProtocol.EOK) {
            rc = BKException.Code.OK;
        } else if (rc == BookieProtocol.ENOENTRY || rc == BookieProtocol.ENOLEDGER) {
            rc = BKException.Code.NoSuchEntryException;
        } else if (rc == BookieProtocol.ETRIMMED) {
            rc = BKException.Code.EntryTrimmedException;
        } else if (rc == BookieProtocol.EBADVERSION) {
            rc = BKException.Code.ProtocolVersionException;
        } else if (rc == BookieProtocol.EUA) {
            rc = BKException.Code.UnauthorizedAccessException;
        } else {
            LOG.warn("Read for ledger: {}, entry: {} failed on bookie: {}"
                    + " with unknown code: {}", new Object[] { ledgerId, entryId, addr, rc });
            rc = BKException.Code.ReadException;
        }

        CompletionKey key = new CompletionKey(ledgerId, entryId);
        ReadCompletion readCompletion = null;
        synchronized (readCompletions) {
            List<ReadCompletion> completions = readCompletions.get(key);
            if (completions.size() > 0) {
                readCompletion = completions.remove(0);
            }

            if (readCompletion == null) {
                /*
                 * This is a special case. When recovering a ledger, a client submits a read request with id -1, and
                 * receives a response with a different entry id.
                 */

                completions = readCompletions.get(new CompletionKey(ledgerId, BookieProtocol.LAST_ADD_CONFIRMED));
                if (completions.size() > 0) {
                    readCompletion = completions.remove(0);
                }
            }
        }

        if (readCompletion == null) {
            LOG.debug("Unexpected read response received from bookie: {} for ledger: {}"
                    + ", entry: {} , ignoring", new Object[] { addr, ledgerId, entryId });
            return;
        }

        readCompletion.cb.readEntryComplete(rc, ledgerId, entryId, buffer.slice(), readCompletion.ctx);
    }

    /**
     * Boiler-plate wrapper classes follow
     *
     */

    // visible for testing
    static abstract class CompletionValue {
        final Object ctx;

        public CompletionValue(Object ctx) {
            this.ctx = ctx;
        }
    }

    // visible for testing
    static class ReadCompletion extends CompletionValue {
        final ReadEntryCallback cb;

        public ReadCompletion(ReadEntryCallback cb, Object ctx) {
            this(null, cb, ctx);
        }

        public ReadCompletion(final OpStatsLogger readEntryOpLogger,
                              final ReadEntryCallback originalCallback,
                              final Object originalCtx) {
            super(originalCtx);
            final long requestTimeMillis = MathUtils.now();
            this.cb = null == readEntryOpLogger ? originalCallback : new ReadEntryCallback() {
                @Override
                public void readEntryComplete(int rc, long ledgerId, long entryId, ChannelBuffer buffer, Object ctx) {
                    long latencyMillis = MathUtils.now() - requestTimeMillis;
                    if (rc != BKException.Code.OK) {
                        readEntryOpLogger.registerFailedEvent(latencyMillis);
                    } else {
                        readEntryOpLogger.registerSuccessfulEvent(latencyMillis);
                    }
                    originalCallback.readEntryComplete(rc, ledgerId, entryId, buffer, originalCtx);
                }
            };
        }
    }

    // visible for testing
    static class AddCompletion extends CompletionValue {
        final WriteCallback cb;

        public AddCompletion(WriteCallback cb, Object ctx) {
            this(null, cb, ctx);
        }

        public AddCompletion(final OpStatsLogger addEntryOpLogger,
                             final WriteCallback originalCallback,
                             final Object originalCtx) {
            super(originalCtx);
            final long requestTimeMillis = MathUtils.now();
            this.cb = null == addEntryOpLogger ? originalCallback : new WriteCallback() {
                @Override
                public void writeComplete(int rc, long ledgerId, long entryId, InetSocketAddress addr, Object ctx) {
                    long latencyMillis = MathUtils.now() - requestTimeMillis;
                    if (rc != BKException.Code.OK) {
                        addEntryOpLogger.registerFailedEvent(latencyMillis);
                    } else {
                        addEntryOpLogger.registerSuccessfulEvent(latencyMillis);
                    }
                    originalCallback.writeComplete(rc, ledgerId, entryId, addr, originalCtx);
                }
            };
        }
    }

    // visable for testing
    CompletionKey newCompletionKey(long ledgerId, long entryId) {
        return new CompletionKey(ledgerId, entryId);
    }

    // visable for testing
    class CompletionKey {
        long ledgerId;
        long entryId;
        final long timeoutAt;
        final long startTime;

        CompletionKey(long ledgerId, long entryId) {
            this.ledgerId = ledgerId;
            this.entryId = entryId;
            this.startTime = MathUtils.nowInNano();
            this.timeoutAt = MathUtils.now() + (readTimeout*1000);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof CompletionKey) || obj == null) {
                return false;
            }
            CompletionKey that = (CompletionKey) obj;
            return this.ledgerId == that.ledgerId && this.entryId == that.entryId;
        }

        @Override
        public int hashCode() {
            return ((int) ledgerId << 16) ^ ((int) entryId);
        }

        public String toString() {
            return String.format("LedgerEntry(%d, %d)", ledgerId, entryId);
        }

        public boolean shouldTimeout() {
            return this.timeoutAt <= MathUtils.now();
        }

        public long elapsedTime() {
            return MathUtils.elapsedMSec(startTime);
        }
    }

}
