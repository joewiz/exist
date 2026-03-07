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
package org.exist.xquery.functions.xmldb;

import org.exist.test.ExistXmldbEmbeddedServer;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.base.XMLDBException;
import org.xmldb.api.modules.CollectionManagementService;
import org.xmldb.api.modules.XMLResource;
import org.xmldb.api.modules.XQueryService;

import static org.junit.Assert.*;

/**
 * Tests that XQuery functions (xmldb:store, xmldb:rename, xmldb:copy,
 * xmldb:move, xmldb:remove, fn:doc, fn:doc-available) correctly handle
 * resource names containing special characters.
 *
 * <p>These tests exercise the XQuery→XmldbURI conversion path, which
 * goes through AnyURIValue and is distinct from the Java XML:DB API path
 * tested in {@link org.exist.xmldb.ResourceNamingIntegrationTest}.</p>
 *
 * Related: <a href="https://github.com/eXist-db/exist/issues/3795">#3795</a>
 */
public class XQueryResourceNamingTest {

    @ClassRule
    public static final ExistXmldbEmbeddedServer existEmbeddedServer =
            new ExistXmldbEmbeddedServer(false, true, true);

    private static final String TEST_COLLECTION = "xquery-naming-test";
    private static final String TEST_XML = "<root><data>hello</data></root>";

    private Collection testCollection;
    private XQueryService xqs;

    @Before
    public void setUp() throws XMLDBException {
        final CollectionManagementService cms =
                existEmbeddedServer.getRoot().getService(CollectionManagementService.class);
        testCollection = cms.createCollection(TEST_COLLECTION);
        assertNotNull(testCollection);
        xqs = testCollection.getService(XQueryService.class);
    }

    @After
    public void tearDown() throws XMLDBException {
        final CollectionManagementService cms =
                existEmbeddedServer.getRoot().getService(CollectionManagementService.class);
        cms.removeCollection(TEST_COLLECTION);
        testCollection = null;
        xqs = null;
    }

    // --- xmldb:store with special names ---

    @Test
    public void xmldbStore_withSpace() throws XMLDBException {
        assertXQueryStore("my document.xml");
    }

    @Test
    public void xmldbStore_withUnicode() throws XMLDBException {
        assertXQueryStore("r\u00E9sum\u00E9.xml");
    }

    @Test
    public void xmldbStore_withCJK() throws XMLDBException {
        assertXQueryStore("\u4E16\u754C.xml");
    }

    @Test
    public void xmldbStore_withSquareBrackets() throws XMLDBException {
        assertXQueryStore("data[1].xml");
    }

    @Test
    public void xmldbStore_withAmpersand() throws XMLDBException {
        assertXQueryStore("a&amp;b.xml");
    }

    @Test
    public void xmldbStore_withPlus() throws XMLDBException {
        assertXQueryStore("a+b.xml");
    }

    @Test
    public void xmldbStore_withParentheses() throws XMLDBException {
        assertXQueryStore("data(1).xml");
    }

    @Test
    public void xmldbStore_withAtSign() throws XMLDBException {
        assertXQueryStore("user@host.xml");
    }

    @Test
    public void xmldbStore_withApostrophe() throws XMLDBException {
        // Use double-quote delimited XQuery string to avoid escaping issues
        final String collPath = "/db/" + TEST_COLLECTION;
        final String xquery = "xmldb:store(\"" + collPath + "\", \"it's.xml\", " + TEST_XML + ")";
        final ResourceSet result = xqs.query(xquery);
        assertEquals(1, result.getSize());

        // Verify via fn:doc
        final ResourceSet docResult = xqs.query("doc(\"" + collPath + "/it's.xml\")/root/data/text()");
        assertEquals("hello", docResult.getResource(0).getContent().toString());
    }

    // --- fn:doc-available with special names ---

    @Test
    public void docAvailable_withSpecialName() throws XMLDBException {
        final String name = "check me.xml";
        storeViaJavaApi(name);

        final String collPath = "/db/" + TEST_COLLECTION;
        final ResourceSet result = xqs.query(
                "doc-available('" + collPath + "/" + name + "')");
        assertEquals("doc-available should return true",
                "true", result.getResource(0).getContent().toString());
    }

    @Test
    public void docAvailable_withUnicode() throws XMLDBException {
        final String name = "\u00E4\u00F6\u00FC.xml";
        storeViaJavaApi(name);

        final String collPath = "/db/" + TEST_COLLECTION;
        final ResourceSet result = xqs.query(
                "doc-available('" + collPath + "/" + name + "')");
        assertEquals("doc-available should return true for Unicode name",
                "true", result.getResource(0).getContent().toString());
    }

    // --- xmldb:rename with special names ---

    @Test
    public void xmldbRename_toSpecialName() throws XMLDBException {
        final String originalName = "original.xml";
        storeViaJavaApi(originalName);

        final String newName = "renamed file.xml";
        final String collPath = "/db/" + TEST_COLLECTION;
        xqs.query("xmldb:rename('" + collPath + "', '" + originalName + "', '" + newName + "')");

        // Verify old name is gone, new name exists
        final ResourceSet oldResult = xqs.query(
                "doc-available('" + collPath + "/" + originalName + "')");
        assertEquals("false", oldResult.getResource(0).getContent().toString());

        final ResourceSet newResult = xqs.query(
                "doc-available('" + collPath + "/" + newName + "')");
        assertEquals("true", newResult.getResource(0).getContent().toString());
    }

    @Test
    public void xmldbRename_fromSpecialName() throws XMLDBException {
        final String originalName = "special [1].xml";
        storeViaJavaApi(originalName);

        final String newName = "plain.xml";
        final String collPath = "/db/" + TEST_COLLECTION;
        xqs.query("xmldb:rename('" + collPath + "', '" + originalName + "', '" + newName + "')");

        final ResourceSet newResult = xqs.query(
                "doc-available('" + collPath + "/" + newName + "')");
        assertEquals("true", newResult.getResource(0).getContent().toString());
    }

    // --- xmldb:copy with special names ---

    @Test
    public void xmldbCopy_withSpecialName() throws XMLDBException {
        final String name = "to copy [data].xml";
        storeViaJavaApi(name);

        // Create destination collection
        final CollectionManagementService cms =
                testCollection.getService(CollectionManagementService.class);
        cms.createCollection("dest");

        final String srcPath = "/db/" + TEST_COLLECTION;
        final String destPath = srcPath + "/dest";
        xqs.query("xmldb:copy-resource('" + srcPath + "', '" + name + "', '" + destPath + "', '" + name + "')");

        // Verify the copy
        final ResourceSet result = xqs.query(
                "doc('" + destPath + "/" + name + "')/root/data/text()");
        assertEquals("hello", result.getResource(0).getContent().toString());

        cms.removeCollection("dest");
    }

    // --- xmldb:move with special names ---

    @Test
    public void xmldbMove_withSpecialName() throws XMLDBException {
        final String name = "to move (1).xml";
        storeViaJavaApi(name);

        final CollectionManagementService cms =
                testCollection.getService(CollectionManagementService.class);
        cms.createCollection("dest");

        final String srcPath = "/db/" + TEST_COLLECTION;
        final String destPath = srcPath + "/dest";
        xqs.query("xmldb:move('" + srcPath + "', '" + destPath + "', '" + name + "')");

        // Verify moved
        final ResourceSet srcResult = xqs.query(
                "doc-available('" + srcPath + "/" + name + "')");
        assertEquals("false", srcResult.getResource(0).getContent().toString());

        final ResourceSet destResult = xqs.query(
                "doc('" + destPath + "/" + name + "')/root/data/text()");
        assertEquals("hello", destResult.getResource(0).getContent().toString());

        cms.removeCollection("dest");
    }

    // --- xmldb:remove with special names ---

    @Test
    public void xmldbRemove_withSpecialName() throws XMLDBException {
        final String name = "to remove [special].xml";
        storeViaJavaApi(name);

        final String collPath = "/db/" + TEST_COLLECTION;
        xqs.query("xmldb:remove('" + collPath + "', '" + name + "')");

        final ResourceSet result = xqs.query(
                "doc-available('" + collPath + "/" + name + "')");
        assertEquals("false", result.getResource(0).getContent().toString());
    }

    // --- xmldb:create-collection with special names ---

    @Test
    public void xmldbCreateCollection_withSpecialName() throws XMLDBException {
        final String collName = "sub collection";
        final String parentPath = "/db/" + TEST_COLLECTION;
        xqs.query("xmldb:create-collection('" + parentPath + "', '" + collName + "')");

        // Store a doc in it to verify it's functional
        final String childPath = parentPath + "/" + collName;
        xqs.query("xmldb:store('" + childPath + "', 'test.xml', " + TEST_XML + ")");

        final ResourceSet result = xqs.query(
                "doc('" + childPath + "/test.xml')/root/data/text()");
        assertEquals("hello", result.getResource(0).getContent().toString());

        xqs.query("xmldb:remove('" + childPath + "')");
    }

    // --- fn:collection with special-named resources ---

    @Test
    public void collection_listsSpecialNamedResources() throws XMLDBException {
        final String[] names = {"a.xml", "b c.xml", "\u00E9.xml", "d[1].xml"};
        for (final String name : names) {
            storeViaJavaApi(name);
        }

        final String collPath = "/db/" + TEST_COLLECTION;
        final ResourceSet result = xqs.query(
                "count(collection('" + collPath + "'))");
        assertEquals("collection() should return all documents",
                String.valueOf(names.length), result.getResource(0).getContent().toString());
    }

    // --- fn:uri-collection with special names ---

    @Test
    public void uriCollection_includesSpecialNames() throws XMLDBException {
        storeViaJavaApi("normal.xml");
        storeViaJavaApi("with space.xml");

        final String collPath = "/db/" + TEST_COLLECTION;
        final ResourceSet result = xqs.query(
                "count(uri-collection('" + collPath + "'))");
        final int count = Integer.parseInt(result.getResource(0).getContent().toString());
        assertTrue("uri-collection should list at least 2 resources", count >= 2);
    }

    // --- Helpers ---

    private void assertXQueryStore(final String resourceName) throws XMLDBException {
        final String collPath = "/db/" + TEST_COLLECTION;
        final String xquery = "xmldb:store('" + collPath + "', '" + resourceName + "', " + TEST_XML + ")";
        final ResourceSet storeResult = xqs.query(xquery);
        assertEquals("xmldb:store should return the stored path", 1, storeResult.getSize());

        // Verify via fn:doc
        final ResourceSet docResult = xqs.query(
                "doc('" + collPath + "/" + resourceName + "')/root/data/text()");
        assertEquals("fn:doc should retrieve the stored content for: " + resourceName,
                "hello", docResult.getResource(0).getContent().toString());
    }

    private void storeViaJavaApi(final String name) throws XMLDBException {
        final XMLResource res = testCollection.createResource(name, XMLResource.class);
        res.setContent(TEST_XML);
        testCollection.storeResource(res);
    }
}
