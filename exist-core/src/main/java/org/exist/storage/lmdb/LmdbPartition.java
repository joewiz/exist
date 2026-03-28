package org.exist.storage.lmdb;

import org.exist.storage.engine.CloseableIterator;
import org.exist.storage.engine.Partition;
import org.exist.storage.engine.ReadTransaction;
import org.exist.storage.engine.WriteTransaction;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.KeyRange;
import org.lmdbjava.Txn;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.AbstractMap;

public class LmdbPartition implements Partition {
    private final Dbi<ByteBuffer> dbi;

    LmdbPartition(final Dbi<ByteBuffer> dbi) {
        this.dbi = dbi;
    }

    @Override
    @Nullable
    public byte[] get(final ReadTransaction txn, final byte[] key) {
        final Txn<ByteBuffer> lmdbTxn = unwrapTxn(txn);
        final ByteBuffer keyBuf = toDirectBuffer(key);
        final ByteBuffer val = dbi.get(lmdbTxn, keyBuf);
        if (val == null) {
            return null;
        }
        return toByteArray(val);
    }

    @Override
    public void put(final WriteTransaction txn, final byte[] key, final byte[] value) {
        final Txn<ByteBuffer> lmdbTxn = ((LmdbWriteTransaction) txn).txn;
        final ByteBuffer keyBuf = toDirectBuffer(key);
        final ByteBuffer valBuf = toDirectBuffer(value);
        dbi.put(lmdbTxn, keyBuf, valBuf);
    }

    @Override
    public void delete(final WriteTransaction txn, final byte[] key) {
        final Txn<ByteBuffer> lmdbTxn = ((LmdbWriteTransaction) txn).txn;
        final ByteBuffer keyBuf = toDirectBuffer(key);
        dbi.delete(lmdbTxn, keyBuf);
    }

    @Override
    public void scan(final ReadTransaction txn, final byte[] startKey, final byte[] endKey,
                     final java.util.function.BiConsumer<byte[], byte[]> visitor) {
        final Txn<ByteBuffer> lmdbTxn = unwrapTxn(txn);
        final ByteBuffer startBuf = toDirectBuffer(startKey);

        final CursorIterable<ByteBuffer> iterable;
        if (endKey != null) {
            final ByteBuffer endBuf = toDirectBuffer(endKey);
            iterable = dbi.iterate(lmdbTxn, KeyRange.closedOpen(startBuf, endBuf));
        } else {
            iterable = dbi.iterate(lmdbTxn, KeyRange.atLeast(startBuf));
        }

        try (iterable) {
            for (final CursorIterable.KeyVal<ByteBuffer> kv : iterable) {
                visitor.accept(toByteArray(kv.key()), toByteArray(kv.val()));
            }
        }
    }

    private static Txn<ByteBuffer> unwrapTxn(final ReadTransaction txn) {
        if (txn instanceof LmdbWriteTransaction) {
            return ((LmdbWriteTransaction) txn).txn;
        }
        return ((LmdbReadTransaction) txn).txn;
    }

    private static ByteBuffer toDirectBuffer(final byte[] data) {
        final ByteBuffer buf = ByteBuffer.allocateDirect(data.length);
        buf.put(data).flip();
        return buf;
    }

    private static byte[] toByteArray(final ByteBuffer buf) {
        final byte[] arr = new byte[buf.remaining()];
        buf.get(arr);
        return arr;
    }
}
