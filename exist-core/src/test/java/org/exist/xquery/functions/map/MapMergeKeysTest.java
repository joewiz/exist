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
package org.exist.xquery.functions.map;

import org.exist.EXistException;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.xquery.CompiledXQuery;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQuery;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.Sequence;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Tests for map:merge key enumeration regression.
 *
 * Tests both the direct Java API path and the broker-compiled path
 * to detect bugs that only manifest in specific execution contexts.
 */
public class MapMergeKeysTest {

    @ClassRule
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private Sequence executeQuery(final String xquery) throws EXistException, PermissionDeniedException, XPathException, IOException {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final XQuery xqueryService = pool.getXQueryService();
            final XQueryContext context = new XQueryContext(pool);
            final CompiledXQuery compiled = xqueryService.compile(context, xquery);
            return xqueryService.execute(broker, compiled, null);
        }
    }

    @Test
    public void mergeFlworEntryMapWithLiteral() throws Exception {
        final Sequence result = executeQuery(
                "let $m1 := map:merge(\n" +
                "    for $key in ('a', 'b')\n" +
                "    return map:entry($key, 'value-' || $key)\n" +
                ")\n" +
                "let $m2 := map { 'c': 'C', 'd': 'D' }\n" +
                "let $merged := map:merge(($m1, $m2))\n" +
                "return count(map:keys($merged))");
        assertEquals("merged map should have 4 keys", "4", result.getStringValue());
    }

    @Test
    public void sizeEqualsKeysCount() throws Exception {
        final Sequence result = executeQuery(
                "let $m1 := map:merge(\n" +
                "    for $key in ('a', 'b')\n" +
                "    return map:entry($key, 'value-' || $key)\n" +
                ")\n" +
                "let $m2 := map { 'c': 'C', 'd': 'D' }\n" +
                "let $merged := map:merge(($m1, $m2))\n" +
                "return map:size($merged) = count(map:keys($merged))");
        assertEquals("size should equal keys count", "true", result.getStringValue());
    }

    @Test
    public void forEachIteratesAllKeys() throws Exception {
        final Sequence result = executeQuery(
                "let $m1 := map:merge(\n" +
                "    for $key in ('a', 'b')\n" +
                "    return map:entry($key, 'value-' || $key)\n" +
                ")\n" +
                "let $m2 := map { 'c': 'C', 'd': 'D' }\n" +
                "let $merged := map:merge(($m1, $m2))\n" +
                "return count(map:for-each($merged, function($k, $v) { $k }))");
        assertEquals("for-each should iterate 4 keys", "4", result.getStringValue());
    }

    @Test
    public void chainedMerge() throws Exception {
        final Sequence result = executeQuery(
                "let $m1 := map:merge(\n" +
                "    for $key in ('a', 'b', 'c')\n" +
                "    return map:entry($key, 'value-' || $key)\n" +
                ")\n" +
                "let $step1 := map:merge((\n" +
                "    $m1,\n" +
                "    map { 'd': 'D', 'e': 'E' }\n" +
                "))\n" +
                "let $step2 := map:merge((\n" +
                "    $step1,\n" +
                "    map { 'f': 'F' }\n" +
                "))\n" +
                "return count(map:keys($step2))");
        assertEquals("chained merge should have 6 keys", "6", result.getStringValue());
    }

    @Test
    public void threeWayMerge() throws Exception {
        final Sequence result = executeQuery(
                "let $m1 := map:merge(\n" +
                "    for $key in ('a', 'b')\n" +
                "    return map:entry($key, 'value-' || $key)\n" +
                ")\n" +
                "let $m2 := map { 'c': 'C', 'd': 'D' }\n" +
                "let $m3 := map { 'e': 'E', 'f': 'F' }\n" +
                "let $merged := map:merge(($m1, $m2, $m3))\n" +
                "return count(map:keys($merged))");
        assertEquals("three-way merge should have 6 keys", "6", result.getStringValue());
    }

    @Test
    public void allKeysAccessible() throws Exception {
        final Sequence result = executeQuery(
                "let $m1 := map:merge(\n" +
                "    for $key in ('a', 'b')\n" +
                "    return map:entry($key, 'value-' || $key)\n" +
                ")\n" +
                "let $m2 := map { 'c': 'C', 'd': 'D' }\n" +
                "let $merged := map:merge(($m1, $m2))\n" +
                "let $keys := map:keys($merged)\n" +
                "return $keys = 'a' and $keys = 'b' and $keys = 'c' and $keys = 'd'");
        assertEquals("all keys should be in keys result", "true", result.getStringValue());
    }

    @Test
    public void mergeWithDuplicatesOption() throws Exception {
        final Sequence result = executeQuery(
                "let $m1 := map:merge(\n" +
                "    for $key in ('a', 'b')\n" +
                "    return map:entry($key, 'value-' || $key)\n" +
                ")\n" +
                "let $m2 := map { 'c': 'C', 'd': 'D' }\n" +
                "let $merged := map:merge(($m1, $m2), map { 'duplicates': 'use-last' })\n" +
                "return count(map:keys($merged))");
        assertEquals("merge with duplicates option should have 4 keys", "4", result.getStringValue());
    }

    /**
     * Store a library module in the database and import it — this is the
     * exact code path used by site-shell/Jinks where the bug was reported.
     */
    @Test
    public void storedModuleMerge() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final XQuery xqueryService = pool.getXQueryService();

            // Store a library module in /db/test/ using xmldb:store
            final String storeQuery =
                    "let $module := \n" +
                    "\"xquery version '3.1';\n" +
                    "module namespace mm = 'http://test/map-merge';\n" +
                    "declare function mm:build-config() {\n" +
                    "    let $base := map:merge(\n" +
                    "        for $key in ('a', 'b', 'c')\n" +
                    "        return map:entry($key, 'val-' || $key)\n" +
                    "    )\n" +
                    "    let $overrides := map { 'd': 'D', 'e': 'E' }\n" +
                    "    return map:merge(($base, $overrides))\n" +
                    "};\"\n" +
                    "return (\n" +
                    "    xmldb:create-collection('/db', 'test'),\n" +
                    "    xmldb:store('/db/test', 'map-merge-lib.xqm', $module, 'application/xquery')\n" +
                    ")";

            final XQueryContext storeCtx = new XQueryContext(pool);
            final CompiledXQuery storeCompiled = xqueryService.compile(storeCtx, storeQuery);
            xqueryService.execute(broker, storeCompiled, null);

            // Now execute a query that imports the stored module
            final String query =
                    "import module namespace mm = 'http://test/map-merge'\n" +
                    "    at 'xmldb:exist:///db/test/map-merge-lib.xqm';\n" +
                    "let $config := mm:build-config()\n" +
                    "return (\n" +
                    "    count(map:keys($config)),\n" +
                    "    map:size($config),\n" +
                    "    count(map:keys($config)) = map:size($config)\n" +
                    ")";

            final XQueryContext context = new XQueryContext(pool);
            final CompiledXQuery compiled = xqueryService.compile(context, query);
            final Sequence result = xqueryService.execute(broker, compiled, null);

            assertEquals("should return 3 values", 3, result.getItemCount());
            assertEquals("map:keys count", "5", result.itemAt(0).getStringValue());
            assertEquals("map:size", "5", result.itemAt(1).getStringValue());
            assertEquals("keys count = size", "true", result.itemAt(2).getStringValue());
        }
    }

    /**
     * Chained merge across module boundary: the stored module returns a merged map,
     * and the caller merges it again. This is the exact Jinks pattern.
     */
    @Test
    public void storedModuleChainedMerge() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final XQuery xqueryService = pool.getXQueryService();

            // Store module
            final String storeQuery =
                    "let $module := \n" +
                    "\"xquery version '3.1';\n" +
                    "module namespace mm = 'http://test/map-merge2';\n" +
                    "declare function mm:base-config() {\n" +
                    "    map:merge(\n" +
                    "        for $key in ('a', 'b', 'c')\n" +
                    "        return map:entry($key, 'val-' || $key)\n" +
                    "    )\n" +
                    "};\"\n" +
                    "return (\n" +
                    "    xmldb:create-collection('/db', 'test'),\n" +
                    "    xmldb:store('/db/test', 'map-merge-lib2.xqm', $module, 'application/xquery')\n" +
                    ")";

            final XQueryContext storeCtx = new XQueryContext(pool);
            xqueryService.execute(broker, xqueryService.compile(storeCtx, storeQuery), null);

            // Caller merges the module's returned map with more data — the Jinks pattern
            final String query =
                    "import module namespace mm = 'http://test/map-merge2'\n" +
                    "    at 'xmldb:exist:///db/test/map-merge-lib2.xqm';\n" +
                    "let $base := mm:base-config()\n" +
                    "let $step1 := map:merge(($base, map { 'd': 'D', 'e': 'E' }))\n" +
                    "let $step2 := map:merge(($step1, map { 'f': 'F' }))\n" +
                    "return (\n" +
                    "    count(map:keys($step2)),\n" +
                    "    map:size($step2),\n" +
                    "    string-join(for $k in map:keys($step2) order by $k return $k, ',')\n" +
                    ")";

            final XQueryContext context = new XQueryContext(pool);
            final Sequence result = xqueryService.execute(broker, xqueryService.compile(context, query), null);

            assertEquals("should return 3 values", 3, result.getItemCount());
            assertEquals("map:keys count", "6", result.itemAt(0).getStringValue());
            assertEquals("map:size", "6", result.itemAt(1).getStringValue());
            assertEquals("all keys enumerated", "a,b,c,d,e,f", result.itemAt(2).getStringValue());
        }
    }

    @Test
    public void iteratorConsistency() throws Exception {
        // Test that the Bifurcan map iterator sees all entries
        final Sequence result = executeQuery(
                "let $m1 := map:merge(\n" +
                "    for $key in ('x', 'y', 'z')\n" +
                "    return map:entry($key, upper-case($key))\n" +
                ")\n" +
                "let $m2 := map:merge(\n" +
                "    for $key in ('a', 'b')\n" +
                "    return map:entry($key, upper-case($key))\n" +
                ")\n" +
                "let $merged := map:merge(($m1, $m2))\n" +
                "return string-join(\n" +
                "    for $k in map:keys($merged) order by $k return $k, ',')\n");
        assertEquals("all keys should enumerate", "a,b,x,y,z", result.getStringValue());
    }
}
