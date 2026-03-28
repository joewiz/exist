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
package org.exist.webdav;

import jakarta.servlet.ServletException;
import org.apache.jackrabbit.webdav.*;
import org.apache.jackrabbit.webdav.server.AbstractWebdavServlet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.EXistException;
import org.exist.security.SecurityManager;
import org.exist.security.Subject;
import org.exist.storage.BrokerPool;

/**
 * WebDAV servlet based on Apache Jackrabbit WebDAV library.
 * Extends {@link AbstractWebdavServlet} which handles all HTTP method
 * dispatching (PROPFIND, COPY, MOVE, LOCK, etc.) and delegates to
 * the resource factory for resource creation.
 *
 * <p>Authentication is handled by the servlet container (Jetty JAAS).
 * The servlet maps request URIs under {@code /webdav} to eXist-db
 * database paths starting at {@code /db}.</p>
 *
 * @author Joe Wicentowski
 */
public class ExistWebdavServlet extends AbstractWebdavServlet {

    private static final Logger LOG = LogManager.getLogger(ExistWebdavServlet.class);

    private DavSessionProvider davSessionProvider;
    private DavLocatorFactory locatorFactory;
    private DavResourceFactory resourceFactory;

    @Override
    public void init() throws ServletException {
        super.init();

        try {
            final BrokerPool pool = BrokerPool.getInstance();

            // Create the locator factory - maps request URIs to resource paths
            // The prefix is the servlet context path + servlet path (e.g. "/webdav")
            locatorFactory = new ExistDavLocatorFactory("/webdav");

            // Create the resource factory - creates DavResource objects from locators
            resourceFactory = new ExistDavResourceFactory(pool);

            // Create the session provider - attaches DavSession to requests
            davSessionProvider = new ExistDavSessionProvider(pool);

            LOG.info("eXist-db Jackrabbit WebDAV servlet initialized successfully");

        } catch (final EXistException e) {
            LOG.error("Unable to initialize WebDAV servlet", e);
            throw new ServletException("Unable to initialize WebDAV servlet", e);
        }
    }

    @Override
    protected void service(final jakarta.servlet.http.HttpServletRequest request,
            final jakarta.servlet.http.HttpServletResponse response)
            throws jakarta.servlet.ServletException, java.io.IOException {
        try {
            super.service(request, response);
        } catch (final Throwable t) {
            LOG.error("WebDAV request failed: {} {}", request.getMethod(), request.getRequestURI(), t);
            throw t;
        }
    }

    @Override
    protected boolean isPreconditionValid(final WebdavRequest request, final DavResource resource) {
        return !resource.exists() || request.matchesIfHeader(resource);
    }

    @Override
    public DavSessionProvider getDavSessionProvider() {
        return davSessionProvider;
    }

    @Override
    public void setDavSessionProvider(final DavSessionProvider davSessionProvider) {
        this.davSessionProvider = davSessionProvider;
    }

    @Override
    public DavLocatorFactory getLocatorFactory() {
        return locatorFactory;
    }

    @Override
    public void setLocatorFactory(final DavLocatorFactory locatorFactory) {
        this.locatorFactory = locatorFactory;
    }

    @Override
    public DavResourceFactory getResourceFactory() {
        return resourceFactory;
    }

    @Override
    public void setResourceFactory(final DavResourceFactory resourceFactory) {
        this.resourceFactory = resourceFactory;
    }

    // -----------------------------------------------------------------------
    // Inner classes
    // -----------------------------------------------------------------------

    /**
     * DavLocatorFactory implementation that maps WebDAV request URIs
     * to eXist-db database paths. The prefix (e.g., "/webdav") is stripped
     * and paths are resolved relative to the database root "/db".
     */
    static class ExistDavLocatorFactory implements DavLocatorFactory {

        private final String prefix;

        ExistDavLocatorFactory(final String prefix) {
            this.prefix = prefix;
        }

        @Override
        public DavResourceLocator createResourceLocator(final String serverPrefix, final String href) {
            // href is the full path from the request
            // Strip the webdav prefix to get the resource path
            String resourcePath = href;
            if (resourcePath != null && resourcePath.startsWith(prefix)) {
                resourcePath = resourcePath.substring(prefix.length());
            }
            if (resourcePath == null || resourcePath.isEmpty()) {
                resourcePath = "/db";
            }
            // Ensure path starts with /db
            if (!resourcePath.startsWith("/db")) {
                resourcePath = "/db" + resourcePath;
            }
            // Strip trailing slash
            if (resourcePath.length() > 1 && resourcePath.endsWith("/")) {
                resourcePath = resourcePath.substring(0, resourcePath.length() - 1);
            }
            return new ExistDavLocator(serverPrefix, prefix, resourcePath, this);
        }

        @Override
        public DavResourceLocator createResourceLocator(final String serverPrefix,
                final String workspacePath, final String resourcePath) {
            return createResourceLocator(serverPrefix, workspacePath, resourcePath, true);
        }

        @Override
        public DavResourceLocator createResourceLocator(final String serverPrefix,
                final String workspacePath, final String path, final boolean isResourcePath) {
            String resourcePath = path;
            if (resourcePath == null || resourcePath.isEmpty()) {
                resourcePath = "/db";
            }
            if (!resourcePath.startsWith("/db")) {
                resourcePath = "/db" + resourcePath;
            }
            if (resourcePath.length() > 1 && resourcePath.endsWith("/")) {
                resourcePath = resourcePath.substring(0, resourcePath.length() - 1);
            }
            return new ExistDavLocator(serverPrefix, prefix, resourcePath, this);
        }
    }

    /**
     * DavResourceLocator implementation for eXist-db resources.
     */
    static class ExistDavLocator implements DavResourceLocator {

        private final String serverPrefix;
        private final String webdavPrefix;
        private final String resourcePath;
        private final DavLocatorFactory factory;

        ExistDavLocator(final String serverPrefix, final String webdavPrefix,
                final String resourcePath, final DavLocatorFactory factory) {
            this.serverPrefix = serverPrefix;
            this.webdavPrefix = webdavPrefix;
            this.resourcePath = resourcePath;
            this.factory = factory;
        }

        @Override
        public String getPrefix() {
            return serverPrefix + webdavPrefix;
        }

        @Override
        public String getResourcePath() {
            return resourcePath;
        }

        @Override
        public String getWorkspacePath() {
            return "/db";
        }

        @Override
        public String getWorkspaceName() {
            return "db";
        }

        @Override
        public boolean isSameWorkspace(final DavResourceLocator locator) {
            return locator != null && isSameWorkspace(locator.getWorkspacePath());
        }

        @Override
        public boolean isSameWorkspace(final String workspaceName) {
            return "/db".equals(workspaceName) || "db".equals(workspaceName);
        }

        @Override
        public String getHref(final boolean isCollection) {
            // The serverPrefix (from AbstractWebdavServlet) already includes
            // the context path and servlet path. The href should be just the
            // database path mapped to the WebDAV namespace.
            final String path = resourcePath.startsWith("/db")
                    ? resourcePath.substring("/db".length())
                    : resourcePath;
            final String href = path.isEmpty() ? "/" : path;
            if (isCollection && !href.endsWith("/")) {
                return href + "/";
            }
            return href;
        }

        @Override
        public boolean isRootLocation() {
            return "/db".equals(resourcePath);
        }

        @Override
        public DavLocatorFactory getFactory() {
            return factory;
        }

        @Override
        public String getRepositoryPath() {
            return resourcePath;
        }

        @Override
        public int hashCode() {
            return resourcePath.hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof ExistDavLocator other) {
                return resourcePath.equals(other.resourcePath);
            }
            return false;
        }
    }

    /**
     * DavSessionProvider that creates ExistDavSession objects.
     * Authentication is handled by the servlet container; this provider
     * extracts the authenticated user principal and maps it to an
     * eXist-db Subject.
     */
    static class ExistDavSessionProvider implements DavSessionProvider {

        private final BrokerPool pool;

        ExistDavSessionProvider(final BrokerPool pool) {
            this.pool = pool;
        }

        @Override
        public boolean attachSession(final WebdavRequest request) throws DavException {
            final SecurityManager securityManager = pool.getSecurityManager();
            Subject subject = null;

            // Try Basic Auth from the Authorization header
            final String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Basic ")) {
                final String encoded = authHeader.substring(6);
                final String decoded = new String(java.util.Base64.getDecoder().decode(encoded));
                final int colon = decoded.indexOf(':');
                if (colon >= 0) {
                    final String username = decoded.substring(0, colon);
                    final String password = decoded.substring(colon + 1);
                    try {
                        subject = securityManager.authenticate(username, password);
                    } catch (final org.exist.security.AuthenticationException e) {
                        LOG.debug("Authentication failed for user '{}': {}", username, e.getMessage());
                    }
                }
            }

            // Fall back to container principal
            if (subject == null) {
                final java.security.Principal principal = request.getUserPrincipal();
                if (principal != null && securityManager.hasAccount(principal.getName())) {
                    subject = securityManager.getSystemSubject();
                }
            }

            // Fall back to guest
            if (subject == null) {
                subject = securityManager.getGuestSubject();
            }

            // Reject guest access for write operations
            if (subject.equals(securityManager.getGuestSubject())) {
                // Allow read operations (PROPFIND, GET, OPTIONS) for guest
                final String method = request.getMethod();
                if ("PUT".equals(method) || "DELETE".equals(method) || "MKCOL".equals(method)
                        || "MOVE".equals(method) || "COPY".equals(method) || "LOCK".equals(method)) {
                    // Return 401 to trigger auth challenge
                    throw new DavException(DavServletResponse.SC_UNAUTHORIZED, "Authentication required");
                }
            }

            final ExistDavSession session = new ExistDavSession(subject);
            request.setDavSession(session);
            return true;
        }

        @Override
        public void releaseSession(final WebdavRequest request) {
            request.setDavSession(null);
        }
    }
}
