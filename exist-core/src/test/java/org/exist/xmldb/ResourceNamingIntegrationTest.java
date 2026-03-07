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
package org.exist.xmldb;

import org.exist.test.ExistXmldbEmbeddedServer;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.Resource;
import org.xmldb.api.base.XMLDBException;
import org.xmldb.api.modules.BinaryResource;
import org.xmldb.api.modules.CollectionManagementService;
import org.xmldb.api.modules.XMLResource;
import org.xmldb.api.modules.XQueryService;
import org.xmldb.api.base.ResourceSet;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

/**
 * Integration tests for storing and retrieving resources with special characters
 * in their names. These tests exercise the full embedded database stack (storage,
 * indexing, XQuery retrieval) to verify that resource names containing spaces,
 * Unicode, and URI-reserved characters survive the round-trip.
 *
 * Related: <a href="https://github.com/eXist-db/exist/issues/3795">#3795</a>
 */
public class ResourceNamingIntegrationTest {

    @ClassRule
    public static final ExistXmldbEmbeddedServer existEmbeddedServer =
            new ExistXmldbEmbeddedServer(false, true, true);

    private static final String TEST_COLLECTION = "testResourceNaming";
    private static final String TEST_XML_CONTENT = "<root><data>hello</data></root>";
    private static final byte[] TEST_BINARY_CONTENT = "binary content".getBytes(UTF_8);

    private Collection testCollection;

    @Before
    public void setUp() throws XMLDBException {
        final CollectionManagementService cms =
                existEmbeddedServer.getRoot().getService(CollectionManagementService.class);
        testCollection = cms.createCollection(TEST_COLLECTION);
        assertNotNull(testCollection);
    }

    @After
    public void tearDown() throws XMLDBException {
        final CollectionManagementService cms =
                existEmbeddedServer.getRoot().getService(CollectionManagementService.class);
        cms.removeCollection(TEST_COLLECTION);
        testCollection = null;
    }

    // --- XML resources with special character names ---

    @Test
    public void storeAndRetrieve_xmlResource_withSpaceInName() throws XMLDBException {
        assertXmlRoundTrip("my document.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withUnicodeAccents() throws XMLDBException {
        assertXmlRoundTrip("r\u00E9sum\u00E9.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withCJK() throws XMLDBException {
        assertXmlRoundTrip("\u4E16\u754C.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withKorean() throws XMLDBException {
        assertXmlRoundTrip("\uC5F4.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withSquareBrackets() throws XMLDBException {
        assertXmlRoundTrip("data[1].xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withParentheses() throws XMLDBException {
        assertXmlRoundTrip("data(1).xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withAmpersand() throws XMLDBException {
        assertXmlRoundTrip("a&b.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withAtSign() throws XMLDBException {
        assertXmlRoundTrip("user@host.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withExclamation() throws XMLDBException {
        assertXmlRoundTrip("important!.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withPlus() throws XMLDBException {
        assertXmlRoundTrip("a+b.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withComma() throws XMLDBException {
        assertXmlRoundTrip("a,b.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withSemicolon() throws XMLDBException {
        assertXmlRoundTrip("a;b.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withEquals() throws XMLDBException {
        assertXmlRoundTrip("a=b.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withApostrophe() throws XMLDBException {
        assertXmlRoundTrip("it's.xml");
    }

    @Test
    public void storeAndRetrieve_xmlResource_withMixedSpecialChars() throws XMLDBException {
        // Same pattern as TestConstants.DECODED_SPECIAL_NAME
        assertXmlRoundTrip("t[e s]t\u00E0\uC5F4.xml");
    }

    // --- Binary resources with special character names ---

    @Test
    public void storeAndRetrieve_binaryResource_withSpaceInName() throws XMLDBException {
        assertBinaryRoundTrip("my file.txt");
    }

    @Test
    public void storeAndRetrieve_binaryResource_withUnicode() throws XMLDBException {
        assertBinaryRoundTrip("r\u00E9sum\u00E9.txt");
    }

    @Test
    public void storeAndRetrieve_binaryResource_withSquareBrackets() throws XMLDBException {
        assertBinaryRoundTrip("data[1].bin");
    }

    // --- Collection names with special characters ---

    @Test
    public void createCollection_withSpaceInName() throws XMLDBException {
        final CollectionManagementService cms =
                testCollection.getService(CollectionManagementService.class);
        final Collection sub = cms.createCollection("my collection");
        assertNotNull("collection with space in name", sub);

        // Store a resource in it to verify it's functional
        final XMLResource res = sub.createResource("test.xml", XMLResource.class);
        res.setContent(TEST_XML_CONTENT);
        sub.storeResource(res);
        assertEquals(1, sub.getResourceCount());

        // Verify we can list it as a child
        final List<String> children = testCollection.listChildCollections();
        assertTrue("child collection should be listed", children.contains("my collection"));

        cms.removeCollection("my collection");
    }

    @Test
    public void createCollection_withUnicodeInName() throws XMLDBException {
        final CollectionManagementService cms =
                testCollection.getService(CollectionManagementService.class);
        final String collName = "\u00E4\u00F6\u00FC";  // äöü
        final Collection sub = cms.createCollection(collName);
        assertNotNull("collection with Unicode in name", sub);

        final XMLResource res = sub.createResource("test.xml", XMLResource.class);
        res.setContent(TEST_XML_CONTENT);
        sub.storeResource(res);
        assertEquals(1, sub.getResourceCount());

        cms.removeCollection(collName);
    }

    // --- Resource listing with special character names ---

    @Test
    public void listResources_includesResourcesWithSpecialNames() throws XMLDBException {
        final String[] names = {"normal.xml", "with space.xml", "r\u00E9sum\u00E9.xml", "data[1].xml"};

        for (final String name : names) {
            final XMLResource res = testCollection.createResource(name, XMLResource.class);
            res.setContent(TEST_XML_CONTENT);
            testCollection.storeResource(res);
        }

        final List<String> listed = testCollection.listResources();
        assertEquals("resource count", names.length, listed.size());

        for (final String name : names) {
            assertTrue("listed resources should contain: " + name, listed.contains(name));
        }
    }

    // --- XQuery access to resources with special names ---

    @Test
    public void xquery_docFunction_withSpecialName() throws XMLDBException {
        final String resourceName = "my document.xml";
        final XMLResource res = testCollection.createResource(resourceName, XMLResource.class);
        res.setContent(TEST_XML_CONTENT);
        testCollection.storeResource(res);

        final XQueryService xqs = testCollection.getService(XQueryService.class);
        final ResourceSet result = xqs.query(
                "doc('/db/" + TEST_COLLECTION + "/my document.xml')/root/data/text()");
        assertEquals("XQuery doc() should find resource with space in name", 1, result.getSize());
        assertEquals("hello", result.getResource(0).getContent().toString());
    }

    @Test
    public void xquery_collectionFunction_listsSpecialNames() throws XMLDBException {
        final String[] names = {"a.xml", "b c.xml", "\u00E9.xml"};

        for (final String name : names) {
            final XMLResource res = testCollection.createResource(name, XMLResource.class);
            res.setContent(TEST_XML_CONTENT);
            testCollection.storeResource(res);
        }

        final XQueryService xqs = testCollection.getService(XQueryService.class);
        final ResourceSet result = xqs.query(
                "count(collection('/db/" + TEST_COLLECTION + "'))");
        assertEquals("collection() should return all documents",
                String.valueOf(names.length), result.getResource(0).getContent().toString());
    }

    // --- Remove resources with special names ---

    @Test
    public void removeResource_withSpecialName() throws XMLDBException {
        final String resourceName = "to delete[1].xml";
        final XMLResource res = testCollection.createResource(resourceName, XMLResource.class);
        res.setContent(TEST_XML_CONTENT);
        testCollection.storeResource(res);

        assertEquals(1, testCollection.getResourceCount());

        final Resource toRemove = testCollection.getResource(resourceName);
        assertNotNull("resource should exist before removal", toRemove);
        testCollection.removeResource(toRemove);

        assertEquals(0, testCollection.getResourceCount());
        assertNull("resource should not exist after removal", testCollection.getResource(resourceName));
    }

    // --- Copy/Move resources with special names ---

    @Test
    public void copyResource_withSpecialName() throws XMLDBException {
        final String resourceName = "source [data].xml";
        final XMLResource res = testCollection.createResource(resourceName, XMLResource.class);
        res.setContent(TEST_XML_CONTENT);
        testCollection.storeResource(res);

        // Create destination collection
        final CollectionManagementService cms =
                testCollection.getService(CollectionManagementService.class);
        cms.createCollection("destination");

        // Copy
        final EXistCollectionManagementService ecms =
                testCollection.getService(EXistCollectionManagementService.class);
        ecms.copyResource(XmldbURI.create(resourceName),
                XmldbURI.create("/db/" + TEST_COLLECTION + "/destination"),
                XmldbURI.create(resourceName));

        // Verify the copy exists
        final Collection destCol = testCollection.getChildCollection("destination");
        assertNotNull(destCol);
        final Resource copied = destCol.getResource(resourceName);
        assertNotNull("copied resource should exist in destination", copied);
        assertEquals(TEST_XML_CONTENT, copied.getContent().toString());

        cms.removeCollection("destination");
    }

    @Test
    public void moveResource_withSpecialName() throws XMLDBException {
        final String resourceName = "to move (1).xml";
        final XMLResource res = testCollection.createResource(resourceName, XMLResource.class);
        res.setContent(TEST_XML_CONTENT);
        testCollection.storeResource(res);

        // Create destination collection
        final CollectionManagementService cms =
                testCollection.getService(CollectionManagementService.class);
        cms.createCollection("destination");

        // Move
        final EXistCollectionManagementService ecms =
                testCollection.getService(EXistCollectionManagementService.class);
        ecms.moveResource(XmldbURI.create(resourceName),
                XmldbURI.create("/db/" + TEST_COLLECTION + "/destination"),
                XmldbURI.create(resourceName));

        // Verify it's gone from the source
        assertNull("moved resource should not exist in source", testCollection.getResource(resourceName));

        // Verify it's in the destination
        final Collection destCol = testCollection.getChildCollection("destination");
        assertNotNull(destCol);
        final Resource moved = destCol.getResource(resourceName);
        assertNotNull("moved resource should exist in destination", moved);
        assertEquals(TEST_XML_CONTENT, moved.getContent().toString());

        cms.removeCollection("destination");
    }

    // --- Helpers ---

    private void assertXmlRoundTrip(final String resourceName) throws XMLDBException {
        // Store
        final XMLResource res = testCollection.createResource(resourceName, XMLResource.class);
        res.setContent(TEST_XML_CONTENT);
        testCollection.storeResource(res);

        // Retrieve by name
        final Resource retrieved = testCollection.getResource(resourceName);
        assertNotNull("getResource should find: " + resourceName, retrieved);
        assertEquals("content should match", TEST_XML_CONTENT, retrieved.getContent().toString());
        assertEquals("resource ID should match name", resourceName, retrieved.getId());
    }

    private void assertBinaryRoundTrip(final String resourceName) throws XMLDBException {
        // Store
        final BinaryResource res = testCollection.createResource(resourceName, BinaryResource.class);
        res.setContent(TEST_BINARY_CONTENT);
        testCollection.storeResource(res);

        // Retrieve by name
        final Resource retrieved = testCollection.getResource(resourceName);
        assertNotNull("getResource should find: " + resourceName, retrieved);
        assertArrayEquals("content should match", TEST_BINARY_CONTENT, (byte[]) retrieved.getContent());
        assertEquals("resource ID should match name", resourceName, retrieved.getId());
    }
}
