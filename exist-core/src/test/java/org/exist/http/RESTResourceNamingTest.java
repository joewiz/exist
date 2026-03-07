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
import java.net.URL;

import org.apache.commons.codec.binary.Base64;
import org.eclipse.jetty.http.HttpStatus;
import org.exist.test.ExistWebServer;
import org.exist.xquery.util.URIUtils;
import org.exist.xmldb.XmldbURI;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

/**
 * Tests that the REST API correctly handles resource names containing special
 * characters. Each test stores a document via PUT with a percent-encoded name,
 * retrieves it via GET, and verifies the content survives the round-trip.
 *
 * <p>This complements the existing {@code doGetEncodedPath}/{@code doPutEncodedPath}
 * tests in {@link RESTServiceTest} which only test a single encoded name ("AéB").
 * Here we systematically test spaces, Unicode, RFC 3986 reserved characters,
 * and other problematic characters.</p>
 *
 * Related: <a href="https://github.com/eXist-db/exist/issues/3795">#3795</a>
 */
public class RESTResourceNamingTest {

    @ClassRule
    public static final ExistWebServer existWebServer = new ExistWebServer(true, false, true, true);

    private static final String XML_DATA = "<root><data>hello</data></root>";
    private static final String COLLECTION_NAME = "rest-naming-test";

    private static String credentials;

    private static String getServerUri() {
        return "http://localhost:" + existWebServer.getPort() + "/rest";
    }

    private static String getCollectionUri() {
        return getServerUri() + XmldbURI.ROOT_COLLECTION + "/" + COLLECTION_NAME;
    }

    @BeforeClass
    public static void setup() throws IOException {
        credentials = Base64.encodeBase64String("admin:".getBytes(UTF_8));

        // Create the test collection via PUT of a dummy doc, then delete the doc
        final String setupUri = getCollectionUri() + "/setup.xml";
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
        // Delete the test collection
        final HttpURLConnection connect = getConnection(getCollectionUri());
        try {
            connect.setRequestProperty("Authorization", "Basic " + credentials);
            connect.setRequestMethod("DELETE");
            connect.connect();
            connect.getResponseCode(); // consume response
        } finally {
            connect.disconnect();
        }
    }

    // --- Spaces ---

    @Test
    public void putAndGet_withSpaceInName() throws IOException {
        assertRestRoundTrip("my file.xml");
    }

    @Test
    public void putAndGet_withMultipleSpaces() throws IOException {
        assertRestRoundTrip("a b c.xml");
    }

    // --- Unicode ---

    @Test
    public void putAndGet_withAccentedLatin() throws IOException {
        assertRestRoundTrip("r\u00E9sum\u00E9.xml");
    }

    @Test
    public void putAndGet_withCJK() throws IOException {
        assertRestRoundTrip("\u4E16\u754C.xml");
    }

    @Test
    public void putAndGet_withKorean() throws IOException {
        assertRestRoundTrip("\uC5F4.xml");
    }

    @Test
    public void putAndGet_withGermanUmlauts() throws IOException {
        assertRestRoundTrip("\u00E4\u00F6\u00FC.xml");
    }

    // --- RFC 3986 reserved characters (gen-delims) ---

    @Test
    public void putAndGet_withSquareBrackets() throws IOException {
        assertRestRoundTrip("data[1].xml");
    }

    @Test
    public void putAndGet_withAtSign() throws IOException {
        assertRestRoundTrip("user@host.xml");
    }

    @Test
    public void putAndGet_withColon() throws IOException {
        assertRestRoundTrip("a:b.xml");
    }

    // --- RFC 3986 reserved characters (sub-delims) ---

    @Test
    public void putAndGet_withAmpersand() throws IOException {
        assertRestRoundTrip("a&b.xml");
    }

    @Test
    public void putAndGet_withParentheses() throws IOException {
        assertRestRoundTrip("data(1).xml");
    }

    @Test
    public void putAndGet_withExclamation() throws IOException {
        assertRestRoundTrip("important!.xml");
    }

    @Test
    public void putAndGet_withPlus() throws IOException {
        assertRestRoundTrip("a+b.xml");
    }

    @Test
    public void putAndGet_withComma() throws IOException {
        assertRestRoundTrip("a,b.xml");
    }

    @Test
    public void putAndGet_withSemicolon() throws IOException {
        assertRestRoundTrip("a;b.xml");
    }

    @Test
    public void putAndGet_withEquals() throws IOException {
        assertRestRoundTrip("a=b.xml");
    }

    @Test
    public void putAndGet_withApostrophe() throws IOException {
        assertRestRoundTrip("it's.xml");
    }

    @Test
    public void putAndGet_withDollar() throws IOException {
        assertRestRoundTrip("a$b.xml");
    }

    // --- Other problematic characters ---

    @Test
    public void putAndGet_withCurlyBraces() throws IOException {
        assertRestRoundTrip("a{b}.xml");
    }

    @Test
    public void putAndGet_withPipe() throws IOException {
        assertRestRoundTrip("a|b.xml");
    }

    @Test
    public void putAndGet_withCaret() throws IOException {
        assertRestRoundTrip("a^b.xml");
    }

    @Test
    public void putAndGet_withBacktick() throws IOException {
        assertRestRoundTrip("a`b.xml");
    }

    @Test
    public void putAndGet_withPercent() throws IOException {
        assertRestRoundTrip("100%.xml");
    }

    @Test
    public void putAndGet_withTilde() throws IOException {
        assertRestRoundTrip("file~1.xml");
    }

    // --- Mixed special characters ---

    @Test
    public void putAndGet_withMixedSpecialChars() throws IOException {
        assertRestRoundTrip("t[e s]t\u00E0\uC5F4.xml");
    }

    // --- DELETE with special names ---

    @Test
    public void delete_withSpecialName() throws IOException {
        final String name = "to delete [1].xml";
        final String encodedName = URIUtils.encodeForURI(name);
        final String docUri = getCollectionUri() + "/" + encodedName;

        // PUT
        doPut(docUri, XML_DATA, HttpStatus.CREATED_201);

        // DELETE
        final HttpURLConnection deleteConn = getConnection(docUri);
        try {
            deleteConn.setRequestProperty("Authorization", "Basic " + credentials);
            deleteConn.setRequestMethod("DELETE");
            deleteConn.connect();
            assertEquals("DELETE should succeed", HttpStatus.OK_200, deleteConn.getResponseCode());
        } finally {
            deleteConn.disconnect();
        }

        // Verify it's gone
        final HttpURLConnection getConn = getConnection(docUri);
        try {
            getConn.setRequestMethod("GET");
            getConn.connect();
            assertEquals("GET after DELETE should return 404",
                    HttpStatus.NOT_FOUND_404, getConn.getResponseCode());
        } finally {
            getConn.disconnect();
        }
    }

    // --- Helpers ---

    /**
     * Stores a document via REST PUT with the given decoded resource name
     * (which gets percent-encoded for the URL), then retrieves it via GET
     * and verifies the content matches.
     */
    private void assertRestRoundTrip(final String decodedName) throws IOException {
        final String encodedName = URIUtils.encodeForURI(decodedName);
        final String docUri = getCollectionUri() + "/" + encodedName;

        // PUT the document
        doPut(docUri, XML_DATA, HttpStatus.CREATED_201);

        // GET the document back
        final HttpURLConnection getConn = getConnection(docUri);
        try {
            getConn.setRequestMethod("GET");
            getConn.connect();

            final int status = getConn.getResponseCode();
            assertEquals("GET for '" + decodedName + "' should return 200", HttpStatus.OK_200, status);

            final String response = readResponse(getConn.getInputStream());
            assertTrue("Response should contain the stored XML for '" + decodedName + "'",
                    response.contains("<root>"));
        } finally {
            getConn.disconnect();
        }
    }

    private void doPut(final String uri, final String data, final int expectedStatus) throws IOException {
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
            assertEquals("PUT should return " + expectedStatus, expectedStatus, connect.getResponseCode());
        } finally {
            connect.disconnect();
        }
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
