package org.exist.storage.lmdb;

import org.exist.storage.engine.StorageEngine;
import org.exist.storage.engine.StorageEngineTest;

/**
 * LMDB implementation of the engine-agnostic StorageEngine tests.
 */
public class LmdbStorageEngineTest extends StorageEngineTest {

    @Override
    protected StorageEngine createEngine() {
        return new LmdbStorageEngine();
    }
}
