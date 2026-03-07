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
package org.exist.http;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.commons.codec.binary.Base64;
import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.eclipse.jetty.http.HttpStatus;
import org.exist.test.ExistWebServer;
import org.exist.xquery.util.URIUtils;
import org.exist.xmldb.XmldbURI;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static java.lang.Boolean.TRUE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

/**
 * Cross-API consistency tests for resource naming. These tests verify that a
 * resource stored via one API (REST, XML-RPC) can be retrieved via another API,
 * ensuring that all interfaces agree on how resource names are encoded and stored.
 *
 * <p>This is the most critical test class for issue #3795: if different APIs
 * encode/decode names differently, resources become inaccessible when accessed
 * through a different interface than the one that created them.</p>
 *
 * Related: <a href="https://github.com/eXist-db/exist/issues/3795">#3795</a>
 */
@RunWith(Parameterized.class)
public class CrossApiResourceNamingTest {

    @ClassRule
    public static final ExistWebServer existWebServer = new ExistWebServer(true, false, true, true);

    private static final String XML_DATA = "<root><data>cross-api-test</data></root>";
    private static final String COLLECTION_NAME = "cross-api-naming-test";

    private static String credentials;

    @Parameterized.Parameters(name = "{1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"simple.xml", "plain ASCII"},
                {"my file.xml", "space"},
                {"r\u00E9sum\u00E9.xml", "accented Latin"},
                {"\u4E16\u754C.xml", "CJK characters"},
                {"data[1].xml", "square brackets"},
                {"a&b.xml", "ampersand"},
                {"a+b.xml", "plus sign"},
                {"a,b.xml", "comma"},
                {"user@host.xml", "at sign"},
                {"it's.xml", "apostrophe"},
                {"100%.xml", "percent sign"},
                {"t[e s]t\u00E0\uC5F4.xml", "mixed special"},
        });
    }

    private final String decodedName;
    private final String description;

    public CrossApiResourceNamingTest(final String decodedName, final String description) {
        this.decodedName = decodedName;
        this.description = description;
    }

    private static String getRestUri() {
        return "http://localhost:" + existWebServer.getPort() + "/rest";
    }

    private static String getXmlRpcUri() {
        return "http://localhost:" + existWebServer.getPort() + "/xmlrpc";
    }

    private static String getCollectionRestUri() {
        return getRestUri() + XmldbURI.ROOT_COLLECTION + "/" + COLLECTION_NAME;
    }

    private static String getCollectionPath() {
        return XmldbURI.ROOT_COLLECTION + "/" + COLLECTION_NAME;
    }

    @BeforeClass
    public static void setup() throws IOException {
        credentials = Base64.encodeBase64String("admin:".getBytes(UTF_8));

        // Create the test collection
        final String setupUri = getCollectionRestUri() + "/setup.xml";
        final HttpURLConnection connect = getConnection(setupUri);
        try {
            connect.setRequestProperty("Authorization", "Basic " + credentials);
            connect.setRequestMethod("PUT");
            connect.setDoOutput(true);
            connect.setRequestProperty("ContentType", "application/xml");
            try (final Writer writer = new OutputStreamWriter(connect.getOutputStream(), UTF_8)) {
                writer.write("<setup/>");
            }
            connect.connect();
            assertEquals(HttpStatus.CREATED_201, connect.getResponseCode());
        } finally {
            connect.disconnect();
        }
    }

    @AfterClass
    public static void cleanup() throws IOException {
        final HttpURLConnection connect = getConnection(getCollectionRestUri());
        try {
            connect.setRequestProperty("Authorization", "Basic " + credentials);
            connect.setRequestMethod("DELETE");
            connect.connect();
            connect.getResponseCode();
        } finally {
            connect.disconnect();
        }
    }

    // --- Store via REST, retrieve via XML-RPC ---

    @Test
    public void storeViaRest_retrieveViaXmlRpc() throws IOException, XmlRpcException {
        final String testName = "rest-to-xmlrpc-" + decodedName;
        final String encodedName = URIUtils.encodeForURI(testName);
        final String docUri = getCollectionRestUri() + "/" + encodedName;

        // Store via REST
        restPut(docUri, XML_DATA);

        // Retrieve via XML-RPC
        final XmlRpcClient xmlrpc = getXmlRpcClient();
        final String xmlrpcPath = getCollectionPath() + "/" + testName;
        final List<Object> params = new ArrayList<>();
        params.add(xmlrpcPath);
        params.add(java.util.Map.of());

        final byte[] result = (byte[]) xmlrpc.execute("getDocument", params);
        assertNotNull("XML-RPC should find resource stored via REST: " + description, result);
        final String content = new String(result, UTF_8);
        assertTrue("Content should match for: " + description, content.contains("<data>cross-api-test</data>"));

        // Cleanup
        restDelete(docUri);
    }

    // --- Store via XML-RPC, retrieve via REST ---

    @Test
    public void storeViaXmlRpc_retrieveViaRest() throws IOException, XmlRpcException {
        final String testName = "xmlrpc-to-rest-" + decodedName;
        final String xmlrpcPath = getCollectionPath() + "/" + testName;

        // Store via XML-RPC
        final XmlRpcClient xmlrpc = getXmlRpcClient();
        final List<Object> params = new ArrayList<>();
        params.add(XML_DATA);
        params.add(xmlrpcPath);
        params.add(0);

        assertThat_xmlrpc(xmlrpc.execute("parse", params));

        // Retrieve via REST
        final String encodedName = URIUtils.encodeForURI(testName);
        final String docUri = getCollectionRestUri() + "/" + encodedName;

        final HttpURLConnection getConn = getConnection(docUri);
        try {
            getConn.setRequestMethod("GET");
            getConn.connect();

            assertEquals("REST should find resource stored via XML-RPC: " + description,
                    HttpStatus.OK_200, getConn.getResponseCode());

            final String response = readResponse(getConn.getInputStream());
            assertTrue("Content should match for: " + description,
                    response.contains("<data>cross-api-test</data>"));
        } finally {
            getConn.disconnect();
        }

        // Cleanup via XML-RPC
        params.clear();
        params.add(xmlrpcPath);
        xmlrpc.execute("remove", params);
    }

    // --- Helpers ---

    private void restPut(final String uri, final String data) throws IOException {
        final HttpURLConnection connect = getConnection(uri);
        try {
            connect.setRequestProperty("Authorization", "Basic " + credentials);
            connect.setRequestMethod("PUT");
            connect.setDoOutput(true);
            connect.setRequestProperty("ContentType", "application/xml");
            try (final Writer writer = new OutputStreamWriter(connect.getOutputStream(), UTF_8)) {
                writer.write(data);
            }
            connect.connect();
            final int status = connect.getResponseCode();
            assertTrue("PUT should succeed (got " + status + ") for: " + description,
                    status == HttpStatus.CREATED_201 || status == HttpStatus.OK_200);
        } finally {
            connect.disconnect();
        }
    }

    private void restDelete(final String uri) throws IOException {
        final HttpURLConnection connect = getConnection(uri);
        try {
            connect.setRequestProperty("Authorization", "Basic " + credentials);
            connect.setRequestMethod("DELETE");
            connect.connect();
            connect.getResponseCode();
        } finally {
            connect.disconnect();
        }
    }

    private XmlRpcClient getXmlRpcClient() throws MalformedURLException {
        final XmlRpcClient client = new XmlRpcClient();
        final XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
        config.setEnabledForExtensions(true);
        config.setServerURL(new URL(getXmlRpcUri()));
        config.setBasicUserName("admin");
        config.setBasicPassword("");
        client.setConfig(config);
        return client;
    }

    private static void assertThat_xmlrpc(final Object result) {
        assertEquals("XML-RPC parse should return true", TRUE, result);
    }

    private String readResponse(final InputStream is) throws IOException {
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(is, UTF_8))) {
            String line;
            final StringBuilder out = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                out.append(line);
                out.append("\r\n");
            }
            return out.toString();
        }
    }

    private static HttpURLConnection getConnection(final String url) throws IOException {
        final URL u = new URL(url);
        return (HttpURLConnection) u.openConnection();
    }
}
