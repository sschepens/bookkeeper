package org.apache.bookkeeper.bookie.storage.ldb;

import static junit.framework.Assert.assertEquals;
import static org.apache.bookkeeper.bookie.storage.ldb.LedgerMetadataIndex.fromArray;
import static org.apache.bookkeeper.bookie.storage.ldb.LedgerMetadataIndex.toArray;

import java.io.File;
import java.util.List;
import java.util.Map.Entry;

import org.apache.bookkeeper.bookie.storage.ldb.KeyValueStorage;
import org.apache.bookkeeper.bookie.storage.ldb.KeyValueStorage.CloseableIterator;
import org.apache.bookkeeper.bookie.storage.ldb.KeyValueStorageLevelDB;
import org.junit.Test;

import com.google.common.collect.Lists;

public class KeyValueStorageLevelDbTest {

    @Test
    public void simple() throws Exception {
        File tmpDir = File.createTempFile("bookie", "test");
        tmpDir.delete();

        KeyValueStorage db = new KeyValueStorageLevelDB(tmpDir.getAbsolutePath());

        assertEquals(null, db.getFloor(toArray(3)));
        assertEquals(0, db.count());

        db.put(toArray(5), toArray(5));

        assertEquals(null, db.getFloor(toArray(3)));
        assertEquals(1, db.count());

        assertEquals(null, db.getFloor(toArray(5)));
        assertEquals(5, fromArray(db.getFloor(toArray(6)).getKey()));

        db.put(toArray(3), toArray(3));

        assertEquals(null, db.getFloor(toArray(3)));
        assertEquals(2, db.count());

        // //

        db.put(toArray(5), toArray(5));
        assertEquals(2, db.count());

        assertEquals(null, db.getFloor(toArray(1)));
        assertEquals(null, db.getFloor(toArray(3)));
        assertEquals(3, fromArray(db.getFloor(toArray(5)).getKey()));
        assertEquals(5, fromArray(db.getFloor(toArray(6)).getKey()));
        assertEquals(5, fromArray(db.getFloor(toArray(10)).getKey()));

        // Iterate
        List<Long> foundKeys = Lists.newArrayList();
        CloseableIterator<Entry<byte[], byte[]>> iter = db.iterator();
        try {
            while (iter.hasNext()) {
                foundKeys.add(fromArray(iter.next().getKey()));
            }
        } finally {
            iter.close();
        }

        assertEquals(Lists.newArrayList(3l, 5l), foundKeys);

        // Iterate over keys
        foundKeys = Lists.newArrayList();
        CloseableIterator<byte[]> iter2 = db.keys();
        try {
            while (iter2.hasNext()) {
                foundKeys.add(fromArray(iter2.next()));
            }
        } finally {
            iter2.close();
        }

        assertEquals(Lists.newArrayList(3l, 5l), foundKeys);

        // Scan with limits
        foundKeys = Lists.newArrayList();
        iter2 = db.keys(toArray(1), toArray(4));
        try {
            while (iter2.hasNext()) {
                foundKeys.add(fromArray(iter2.next()));
            }
        } finally {
            iter2.close();
        }

        assertEquals(Lists.newArrayList(3l), foundKeys);

        // Test deletion
        db.put(toArray(10), toArray(10));
        db.put(toArray(11), toArray(11));
        db.put(toArray(12), toArray(12));
        db.put(toArray(14), toArray(14));

        assertEquals(6, db.count());

        assertEquals(10l, fromArray(db.get(toArray(10))));
        db.delete(toArray(10));
        assertEquals(null, db.get(toArray(10)));
        assertEquals(5, db.count());

        db.delete(Lists.newArrayList(toArray(11), toArray(12), toArray(13)));
        assertEquals(null, db.get(toArray(11)));
        assertEquals(null, db.get(toArray(12)));
        assertEquals(null, db.get(toArray(13)));
        assertEquals(14l, fromArray(db.get(toArray(14))));

        assertEquals(3, db.count());

        db.close();
        tmpDir.delete();
    }
}