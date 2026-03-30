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
package org.exist.xquery.lock;

import org.exist.EXistException;
import org.exist.security.PermissionDeniedException;
import org.exist.source.StringSource;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.CompiledXQuery;
import org.exist.xquery.Expression;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQuery;
import org.exist.xquery.XQueryContext;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Tests for {@link LockTargetCollector} — verifies that the expression tree
 * visitor correctly identifies document and collection targets from XQuery
 * expressions at compile time.
 */
public class LockTargetCollectorTest {

    @ClassRule
    public static final ExistEmbeddedServer existEmbeddedServer =
            new ExistEmbeddedServer(true, true);

    @Test
    public void staticDocCall() throws EXistException, PermissionDeniedException,
            XPathException, IOException, URISyntaxException {
        final LockTargetCollector collector = collectTargets(
                "doc('/db/test/data.xml')//item");

        assertFalse(collector.requiresGlobalLock());
        assertTrue(collector.getDocumentTargets().contains(
                XmldbURI.xmldbUriFor("/db/test/data.xml")));
        assertTrue(collector.getCollectionTargets().isEmpty());
    }

    @Test
    public void staticCollectionCall() throws EXistException, PermissionDeniedException,
            XPathException, IOException, URISyntaxException {
        final LockTargetCollector collector = collectTargets(
                "collection('/db/test')//item");

        assertFalse(collector.requiresGlobalLock());
        assertTrue(collector.getCollectionTargets().contains(
                XmldbURI.xmldbUriFor("/db/test")));
        assertTrue(collector.getDocumentTargets().isEmpty());
    }

    @Test
    public void dynamicDocCallRequiresGlobalLock() throws EXistException,
            PermissionDeniedException, XPathException, IOException, URISyntaxException {
        final LockTargetCollector collector = collectTargets(
                "let $path := '/db/test/data.xml' return doc($path)//item");

        assertTrue(collector.requiresGlobalLock());
    }

    @Test
    public void multipleStaticDocCalls() throws EXistException, PermissionDeniedException,
            XPathException, IOException, URISyntaxException {
        final LockTargetCollector collector = collectTargets(
                "doc('/db/test/a.xml')//x | doc('/db/test/b.xml')//y");

        assertFalse("Global lock should not be required for static doc calls",
                collector.requiresGlobalLock());
        assertEquals("Should find 2 doc targets, found: " + collector.getDocumentTargets(),
                2, collector.getDocumentTargets().size());
        assertTrue(collector.getDocumentTargets().contains(
                XmldbURI.xmldbUriFor("/db/test/a.xml")));
        assertTrue(collector.getDocumentTargets().contains(
                XmldbURI.xmldbUriFor("/db/test/b.xml")));
    }

    @Test
    public void mixedStaticAndDynamicRequiresGlobalLock() throws EXistException,
            PermissionDeniedException, XPathException, IOException, URISyntaxException {
        final LockTargetCollector collector = collectTargets(
                "let $p := '/db/dynamic.xml' " +
                "return (doc('/db/test/static.xml'), doc($p))");

        // Even though one target is static, the dynamic one forces global lock
        assertTrue(collector.requiresGlobalLock());
        // Static target should still be collected
        assertTrue(collector.getDocumentTargets().contains(
                XmldbURI.xmldbUriFor("/db/test/static.xml")));
    }

    @Test
    public void noDocOrCollectionCalls() throws EXistException, PermissionDeniedException,
            XPathException, IOException, URISyntaxException {
        final LockTargetCollector collector = collectTargets(
                "1 + 2");

        assertFalse(collector.requiresGlobalLock());
        assertTrue(collector.getDocumentTargets().isEmpty());
        assertTrue(collector.getCollectionTargets().isEmpty());
        assertFalse(collector.hasTargets());
    }

    @Test
    public void docInFLWOR() throws EXistException, PermissionDeniedException,
            XPathException, IOException, URISyntaxException {
        final LockTargetCollector collector = collectTargets(
                "for $x in doc('/db/test/data.xml')//item " +
                "return $x/name");

        assertFalse(collector.requiresGlobalLock());
        assertTrue(collector.getDocumentTargets().contains(
                XmldbURI.xmldbUriFor("/db/test/data.xml")));
    }

    @Test
    public void docInConditional() throws EXistException, PermissionDeniedException,
            XPathException, IOException, URISyntaxException {
        final LockTargetCollector collector = collectTargets(
                "if (true()) then doc('/db/test/a.xml') else doc('/db/test/b.xml')");

        assertFalse(collector.requiresGlobalLock());
        assertEquals(2, collector.getDocumentTargets().size());
        assertTrue(collector.getDocumentTargets().contains(
                XmldbURI.xmldbUriFor("/db/test/a.xml")));
        assertTrue(collector.getDocumentTargets().contains(
                XmldbURI.xmldbUriFor("/db/test/b.xml")));
    }

    @Test
    public void zeroArgCollectionRequiresGlobalLock() throws EXistException,
            PermissionDeniedException, XPathException, IOException, URISyntaxException {
        final LockTargetCollector collector = collectTargets("collection()");

        assertTrue(collector.requiresGlobalLock());
    }

    // --- Gap 1: xmldb write functions should be detected ---

    @Test
    public void xmldbStoreDetectsCollectionTarget() throws Exception {
        final LockTargetCollector collector = collectTargets(
                "xmldb:store('/db/test', 'new.xml', <root>data</root>)");
        assertTrue("xmldb:store should detect collection target or require global lock",
                collector.hasTargets());
    }

    @Test
    public void xmldbRemoveDetectsCollectionTarget() throws Exception {
        final LockTargetCollector collector = collectTargets(
                "xmldb:remove('/db/test', 'old.xml')");
        assertTrue("xmldb:remove should detect collection target or require global lock",
                collector.hasTargets());
    }

    @Test
    public void xmldbMoveDetectsSourceAndTarget() throws Exception {
        final LockTargetCollector collector = collectTargets(
                "xmldb:move('/db/source', '/db/target', 'doc.xml')");
        assertTrue("xmldb:move should detect targets or require global lock",
                collector.hasTargets());
    }

    @Test
    public void xmldbCopyCollectionDetectsSourceAndTarget() throws Exception {
        final LockTargetCollector collector = collectTargets(
                "xmldb:copy-collection('/db/source', '/db/target')");
        assertTrue("xmldb:copy-collection should detect targets or require global lock",
                collector.hasTargets());
    }

    // --- Gap 2: util:eval should trigger global fallback ---

    @Test
    public void utilEvalRequiresGlobalLock() throws Exception {
        final LockTargetCollector collector = collectTargets(
                "util:eval('xmldb:store(\"/db/test\", \"dynamic.xml\", <root/>)')");
        assertTrue("util:eval should require global lock (cannot analyze dynamic queries)",
                collector.requiresGlobalLock());
    }

    @Test
    public void utilEvalInlineRequiresGlobalLock() throws Exception {
        final LockTargetCollector collector = collectTargets(
                "util:eval-inline((), 'doc(\"/db/test/data.xml\")')");
        assertTrue("util:eval-inline should require global lock",
                collector.requiresGlobalLock());
    }

    // --- Gap 3: User function bodies should be analyzed ---

    @Test
    public void userFunctionBodyAnalyzed() throws Exception {
        final LockTargetCollector collector = collectTargets(
                "declare function local:write() {\n" +
                "    xmldb:store('/db/test', 'fromfunc.xml', <root/>)\n" +
                "};\n" +
                "local:write()");
        assertTrue("Function body writes should be detected or require global lock",
                collector.hasTargets());
    }

    // --- Gap 4: TryCatch should analyze catch clauses ---

    @Test
    public void tryCatchAnalyzesCatchBranch() throws Exception {
        final LockTargetCollector collector = collectTargets(
                "try { doc('/db/test/a.xml') } catch * { doc('/db/test/b.xml') }");
        assertEquals("Should find doc targets in both try and catch branches",
                2, collector.getDocumentTargets().size());
        assertTrue(collector.getDocumentTargets().contains(
                XmldbURI.xmldbUriFor("/db/test/b.xml")));
    }

    // --- Helper ---

    private LockTargetCollector collectTargets(final String xquery)
            throws EXistException, PermissionDeniedException, XPathException, IOException, URISyntaxException {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final XQuery xqueryService = pool.getXQueryService();
            final XQueryContext context = new XQueryContext(pool);
            final CompiledXQuery compiled = xqueryService.compile(context,
                    new StringSource(xquery));

            final LockTargetCollector collector = new LockTargetCollector();
            // CompiledXQuery is implemented by PathExpr which is an Expression
            collector.collect((Expression) compiled);
            return collector;
        }
    }
}
