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
package org.exist.security;

import org.apache.commons.codec.binary.Base64;
import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import org.eclipse.jetty.http.HttpStatus;
import org.exist.security.internal.SecurityManagerImpl;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistWebServer;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

/**
 * Verifies the instance-wide guest access clamp ({@code allow-guest-access}).
 *
 * <p>When guest access is clamped, every externally-reachable entry point must refuse to proceed
 * as the {@code guest} subject (sending a 401 challenge so the caller can authenticate), while
 * authenticated requests and the database's own internal guest-leased work keep functioning.</p>
 *
 * <p>The clamp is driven by a {@code config.xml} attribute bound through the generic
 * {@link org.exist.config.Configurator} (the same path as the existing {@code version} attribute,
 * exercised by {@code org.exist.config.ConfigurableTest}); a runtime edit takes effect via
 * {@code ConfigurationImpl#checkForUpdates} re-running {@code Configurator.configure} on the live
 * instance. This test toggles the resolved predicate directly so it can assert the enforcement
 * behavior at each surface without restarting the server, mirroring that no-restart property.</p>
 */
public class GuestAccessTest {

    @ClassRule
    public static final ExistWebServer existWebServer = new ExistWebServer(true, false, true, true);

    private static final String ADMIN_CREDENTIALS = Base64.encodeBase64String("admin:".getBytes(UTF_8));

    private static String guestQueryUri() {
        return "http://localhost:" + existWebServer.getPort() + "/rest/db?_query=1";
    }

    @Test
    public void restGuestBlockedWhenClampedAndRestoredAfterwards() throws Exception {
        final BrokerPool pool = BrokerPool.getInstance();

        // Baseline: guest access allowed (the default) -> a guest query succeeds.
        assertEquals(HttpStatus.OK_200, responseCode(getConnection(guestQueryUri(), null)));

        setGuestAccessAllowed(pool, false);
        try {
            // Clamped: an unauthenticated (guest) request is challenged.
            assertEquals(HttpStatus.UNAUTHORIZED_401, responseCode(getConnection(guestQueryUri(), null)));
            // Clamped: an authenticated request is unaffected.
            assertEquals(HttpStatus.OK_200, responseCode(getConnection(guestQueryUri(), ADMIN_CREDENTIALS)));
        } finally {
            setGuestAccessAllowed(pool, true);
        }

        // Restored at runtime without a restart -> a guest query succeeds again.
        assertEquals(HttpStatus.OK_200, responseCode(getConnection(guestQueryUri(), null)));
    }

    @Test
    public void xmlRpcGuestBlockedWhenClampedAndRestoredAfterwards() throws Exception {
        final BrokerPool pool = BrokerPool.getInstance();

        // Baseline: a guest XML-RPC call succeeds.
        assertNotNull(guestXmlRpcClient().execute("getVersion", List.of()));

        setGuestAccessAllowed(pool, false);
        try {
            // Clamped: the guest is rejected at authentication, before any method dispatch.
            final XmlRpcClient guest = guestXmlRpcClient();
            assertThrows(XmlRpcException.class, () -> guest.execute("getVersion", List.of()));
        } finally {
            setGuestAccessAllowed(pool, true);
        }

        // Restored at runtime without a restart -> a guest call succeeds again.
        assertNotNull(guestXmlRpcClient().execute("getVersion", List.of()));
    }

    /**
     * Enforcement must live at the per-protocol authentication entry points, NOT at the broker
     * lease: internal subsystems (scheduler, sanity report, autodeploy, triggers) legitimately
     * lease a guest broker and must keep working while the clamp is on.
     */
    @Test
    public void internalGuestBrokerLeaseStillWorksWhenClamped() throws Exception {
        final BrokerPool pool = BrokerPool.getInstance();

        setGuestAccessAllowed(pool, false);
        try (final DBBroker broker = pool.get(Optional.empty())) {
            assertEquals(pool.getSecurityManager().getGuestSubject(), broker.getCurrentSubject());
        } finally {
            setGuestAccessAllowed(pool, true);
        }
    }

    private static int responseCode(final HttpURLConnection connection) throws IOException {
        try {
            connection.setRequestMethod("GET");
            connection.connect();
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection getConnection(final String url, final String credentials) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if (credentials != null) {
            connection.setRequestProperty("Authorization", "Basic " + credentials);
        }
        return connection;
    }

    private static XmlRpcClient guestXmlRpcClient() throws MalformedURLException {
        final XmlRpcClient client = new XmlRpcClient();
        final XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
        config.setEnabledForExtensions(true);
        config.setServerURL(new URL("http://localhost:" + existWebServer.getPort() + "/xmlrpc"));
        // No basic credentials: the server resolves the request to the guest subject.
        client.setConfig(config);
        return client;
    }

    /**
     * Toggles the resolved guest-access predicate on the live security manager. The
     * {@code config.xml} attribute binding and its runtime reload are generic
     * {@link org.exist.config.Configurator} machinery; this reaches the same field the reload path
     * sets, so the enforcement assertions exercise exactly the runtime state a config edit produces.
     */
    private static void setGuestAccessAllowed(final BrokerPool pool, final boolean allowed) throws ReflectiveOperationException {
        final SecurityManagerImpl securityManager = (SecurityManagerImpl) pool.getSecurityManager();
        final Field field = SecurityManagerImpl.class.getDeclaredField("allowGuestAccess");
        field.setAccessible(true);
        field.setBoolean(securityManager, allowed);
    }
}
