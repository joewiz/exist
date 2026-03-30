package org.exist.storage.lmdb;

import org.exist.storage.engine.Partition;
import org.exist.storage.engine.ReadTransaction;
import org.exist.storage.engine.StorageException;
import org.exist.storage.engine.WriteTransaction;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.KeyRange;
import org.lmdbjava.Txn;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;

public class LmdbPartition implements Partition {
    private final Dbi<ByteBuffer> dbi;

    LmdbPartition(final Dbi<ByteBuffer> dbi) {
        this.dbi = dbi;
    }

    @Override
    @Nullable
    public byte[] get(final ReadTransaction txn, final byte[] key) throws StorageException {
        try {
            final Txn<ByteBuffer> lmdbTxn = unwrapTxn(txn);
            final ByteBuffer keyBuf = toDirectBuffer(key);
            final ByteBuffer val = dbi.get(lmdbTxn, keyBuf);
            if (val == null) {
                return null;
            }
            return toByteArray(val);
        } catch (final Exception e) {
            throw new StorageException("LMDB get failed", e);
        }
    }

    @Override
    public boolean exists(final ReadTransaction txn, final byte[] key) throws StorageException {
        try {
            final Txn<ByteBuffer> lmdbTxn = unwrapTxn(txn);
            final ByteBuffer keyBuf = toDirectBuffer(key);
            return dbi.get(lmdbTxn, keyBuf) != null;
        } catch (final Exception e) {
            throw new StorageException("LMDB exists check failed", e);
        }
    }

    @Override
    public void put(final WriteTransaction txn, final byte[] key, final byte[] value) throws StorageException {
        try {
            final Txn<ByteBuffer> lmdbTxn = ((LmdbWriteTransaction) txn).txn;
            final ByteBuffer keyBuf = toDirectBuffer(key);
            final ByteBuffer valBuf = toDirectBuffer(value);
            dbi.put(lmdbTxn, keyBuf, valBuf);
        } catch (final Exception e) {
            if (isMapFull(e)) {
                throw new StorageException("LMDB map full — environment needs resize", e);
            }
            throw new StorageException("LMDB put failed", e);
        }
    }

    @Override
    public void delete(final WriteTransaction txn, final byte[] key) throws StorageException {
        try {
            final Txn<ByteBuffer> lmdbTxn = ((LmdbWriteTransaction) txn).txn;
            final ByteBuffer keyBuf = toDirectBuffer(key);
            dbi.delete(lmdbTxn, keyBuf);
        } catch (final Exception e) {
            throw new StorageException("LMDB delete failed", e);
        }
    }

    @Override
    public void scan(final ReadTransaction txn, final byte[] startKey, @Nullable final byte[] endKey,
                     final BiConsumer<byte[], byte[]> visitor) throws StorageException {
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
        } catch (final Exception e) {
            throw new StorageException("LMDB scan failed", e);
        }
    }

    private static boolean isMapFull(final Exception e) {
        final String msg = e.getMessage();
        return msg != null && (msg.contains("MDB_MAP_FULL") || msg.contains("map full"));
    }

    private static Txn<ByteBuffer> unwrapTxn(final ReadTransaction txn) {
        if (txn instanceof LmdbWriteTransaction) {
            return ((LmdbWriteTransaction) txn).txn;
        }
        return ((LmdbReadTransaction) txn).txn;
    }

    static ByteBuffer toDirectBuffer(final byte[] data) {
        final ByteBuffer buf = ByteBuffer.allocateDirect(data.length);
        buf.put(data).flip();
        return buf;
    }

    static byte[] toByteArray(final ByteBuffer buf) {
        final byte[] arr = new byte[buf.remaining()];
        buf.get(arr);
        return arr;
    }
}
