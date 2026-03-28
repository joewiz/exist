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
import org.exist.storage.journal.JournalManager;
import org.exist.storage.lock.LockManager;
import org.exist.storage.txn.TransactionManager;
import org.exist.xquery.PerformanceStats;

import java.util.Collection;
import java.util.Optional;

/**
 * Represents per-database state within an eXist-db server.
 *
 * <p>A DatabaseInstance owns all storage, locks, indexes, caches, and
 * monitoring state for a single logical database. In a single-database
 * deployment (the default), there is one instance named "default" that
 * corresponds to the current {@code /db} collection hierarchy.
 *
 * <p>This interface separates per-database state from the global services
 * (security, scheduler, XQuery engine) that remain in {@link BrokerPool}.
 * The separation enables future multi-database support where each database
 * has independent storage, indexes, and locks.
 *
 * @since 7.0
 */
public interface DatabaseInstance {

    // --- Identity ---

    /**
     * Returns the name of this database instance (e.g., "default").
     *
     * @return the instance name
     */
    String getName();

    // --- Lock management ---

    /**
     * Returns the lock manager for this database instance.
     *
     * @return the lock manager
     */
    LockManager getLockManager();

    // --- Transaction and journal ---

    /**
     * Returns the transaction manager for this database instance.
     *
     * @return the transaction manager
     */
    TransactionManager getTransactionManager();

    /**
     * Returns the journal manager for this database instance, if
     * recovery is enabled.
     *
     * @return an Optional containing the journal manager, or empty
     */
    Optional<JournalManager> getJournalManager();

    // --- Storage ---

    /**
     * Returns the blob store for this database instance.
     *
     * @return the blob store
     */
    BlobStore getBlobStore();

    /**
     * Returns the symbol table for this database instance.
     * The symbol table maps QNames to integer IDs for compact storage.
     *
     * @return the symbol table
     */
    SymbolTable getSymbols();

    // --- Indexes ---

    /**
     * Returns the index manager for this database instance.
     *
     * @return the index manager
     */
    IndexManager getIndexManager();

    /**
     * Returns the collection configuration manager for this database instance.
     *
     * @return the collection configuration manager
     */
    CollectionConfigurationManager getConfigurationManager();

    // --- Caching ---

    /**
     * Returns the collection cache for this database instance.
     *
     * @return the collection cache
     */
    CollectionCache getCollectionsCache();

    /**
     * Returns the cache manager for this database instance.
     *
     * @return the cache manager
     */
    CacheManager getCacheManager();

    /**
     * Returns the compiled XQuery cache for this database instance.
     *
     * @return the XQuery pool
     */
    XQueryPool getXQueryPool();

    /**
     * Returns the reserved memory threshold for this database instance.
     * When free memory drops below this level, memory pressure actions
     * are triggered.
     *
     * @return the reserved memory in bytes
     */
    long getReservedMem();

    // --- Monitoring ---

    /**
     * Returns the process monitor for this database instance.
     *
     * @return the process monitor
     */
    ProcessMonitor getProcessMonitor();

    /**
     * Returns the performance statistics for this database instance.
     *
     * @return the performance stats
     */
    PerformanceStats getPerformanceStats();

    /**
     * Returns the notification service for this database instance.
     *
     * @return the notification service
     */
    NotificationService getNotificationService();

    // --- State ---

    /**
     * Returns whether this database instance is in read-only mode.
     *
     * @return true if read-only
     */
    boolean isReadOnly();

    /**
     * Sets this database instance to read-only mode.
     */
    void setReadOnly();

    // --- Triggers ---

    /**
     * Returns the document triggers registered for this database instance.
     *
     * @return the document triggers
     */
    Collection<TriggerProxy<? extends DocumentTrigger>> getDocumentTriggers();

    /**
     * Returns the collection triggers registered for this database instance.
     *
     * @return the collection triggers
     */
    Collection<TriggerProxy<? extends CollectionTrigger>> getCollectionTriggers();

    /**
     * Registers a document trigger for this database instance.
     *
     * @param clazz the trigger class
     */
    void registerDocumentTrigger(Class<? extends DocumentTrigger> clazz);

    /**
     * Registers a collection trigger for this database instance.
     *
     * @param clazz the trigger class
     */
    void registerCollectionTrigger(Class<? extends CollectionTrigger> clazz);
}
