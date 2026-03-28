/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.storage;

import org.exist.collections.CollectionCache;
import org.exist.collections.CollectionConfigurationManager;
import org.exist.collections.triggers.CollectionTrigger;
import org.exist.collections.triggers.DocumentTrigger;
import org.exist.collections.triggers.TriggerProxy;
import org.exist.dom.persistent.SymbolTable;
import org.exist.indexing.IndexManager;
import org.exist.storage.blob.BlobStore;
import org.exist.storage.blob.BlobStoreService;
import org.exist.storage.journal.JournalManager;
import org.exist.storage.lock.LockManager;
import org.exist.storage.sync.Sync;
import org.exist.storage.txn.TransactionManager;
import org.exist.util.XMLReaderPool;
import org.exist.xquery.PerformanceStats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default implementation of {@link DatabaseInstance} that holds all
 * per-database state for a single database within the eXist-db server.
 *
 * <p>In a single-database deployment, BrokerPool creates one instance
 * of this class called "default". BrokerPool's per-database getters
 * delegate to this instance, preserving backward compatibility.
 *
 * <p>Fields are package-private so that BrokerPool can assign them
 * during initialization (the services are created and registered with
 * BrokerPoolServicesManager, then stored here as references).
 *
 * @since 7.0
 */
public class DefaultDatabaseInstance implements DatabaseInstance {

    private final String name;

    // --- Lock management ---
    LockManager lockManager;

    // --- Transaction and journal ---
    TransactionManager transactionManager;
    Optional<JournalManager> journalManager = Optional.empty();

    // --- Storage ---
    BlobStoreService blobStoreService;
    SymbolTable symbols;

    // --- Indexes ---
    IndexManager indexManager;
    CollectionConfigurationManager collectionConfigurationManager;

    // --- Caching ---
    CollectionCache collectionCache;
    DefaultCacheManager cacheManager;
    XQueryPool xQueryPool;
    XMLReaderPool xmlReaderPool;
    long reservedMem;

    // --- Monitoring ---
    ProcessMonitor processMonitor;
    PerformanceStats xqueryStats;
    NotificationService notificationService;

    // --- State ---
    final AtomicBoolean readOnly = new AtomicBoolean();
    boolean syncRequired = false;
    Sync syncEvent = Sync.MINOR;
    boolean checkpoint = false;
    long lastMajorSync = System.currentTimeMillis();

    // --- Service mode ---
    // NOTE: these are accessed from synchronized blocks in BrokerPool
    // and must remain visible to BrokerPool
    // (left in BrokerPool for now — tightly coupled to broker pool management)

    // --- Triggers ---
    final List<TriggerProxy<? extends DocumentTrigger>> documentTriggers = new ArrayList<>();
    final List<TriggerProxy<? extends CollectionTrigger>> collectionTriggers = new ArrayList<>();

    // --- Startup ---
    StartupTriggersManager startupTriggersManager;

    /**
     * Creates a new database instance with the given name.
     *
     * @param name the instance name (e.g., "default")
     */
    public DefaultDatabaseInstance(final String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public LockManager getLockManager() {
        return lockManager;
    }

    @Override
    public TransactionManager getTransactionManager() {
        return transactionManager;
    }

    @Override
    public Optional<JournalManager> getJournalManager() {
        return journalManager;
    }

    @Override
    public BlobStore getBlobStore() {
        return blobStoreService.getBlobStore();
    }

    @Override
    public SymbolTable getSymbols() {
        return symbols;
    }

    @Override
    public IndexManager getIndexManager() {
        return indexManager;
    }

    @Override
    public CollectionConfigurationManager getConfigurationManager() {
        return collectionConfigurationManager;
    }

    @Override
    public CollectionCache getCollectionsCache() {
        return collectionCache;
    }

    @Override
    public CacheManager getCacheManager() {
        return cacheManager;
    }

    @Override
    public XQueryPool getXQueryPool() {
        return xQueryPool;
    }

    @Override
    public long getReservedMem() {
        return reservedMem;
    }

    @Override
    public ProcessMonitor getProcessMonitor() {
        return processMonitor;
    }

    @Override
    public PerformanceStats getPerformanceStats() {
        return xqueryStats;
    }

    @Override
    public NotificationService getNotificationService() {
        return notificationService;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly.get();
    }

    @Override
    public void setReadOnly() {
        readOnly.set(true);
    }

    @Override
    public Collection<TriggerProxy<? extends DocumentTrigger>> getDocumentTriggers() {
        return documentTriggers;
    }

    @Override
    public Collection<TriggerProxy<? extends CollectionTrigger>> getCollectionTriggers() {
        return collectionTriggers;
    }

    @Override
    public void registerDocumentTrigger(final Class<? extends DocumentTrigger> clazz) {
        documentTriggers.add(new org.exist.collections.triggers.DocumentTriggerProxy(clazz));
    }

    @Override
    public void registerCollectionTrigger(final Class<? extends CollectionTrigger> clazz) {
        collectionTriggers.add(new org.exist.collections.triggers.CollectionTriggerProxy(clazz));
    }
}
