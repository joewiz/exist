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
package org.exist.xquery;

import org.exist.test.ExistXmldbEmbeddedServer;
import org.junit.ClassRule;
import org.junit.Test;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.base.XMLDBException;
import org.xmldb.api.modules.XQueryService;

import static org.junit.Assert.assertEquals;

/**
 * Tests for XQuery 4.0 combined axes:
 * following-or-self, following-sibling-or-self,
 * preceding-or-self, preceding-sibling-or-self.
 */
public class XQ4AxesTest {

    @ClassRule
    public static final ExistXmldbEmbeddedServer server =
            new ExistXmldbEmbeddedServer(false, true, true);

    private static final String DATA =
            "<root>" +
            "  <a id='1'>" +
            "    <b id='2'/>" +
            "    <c id='3'><d id='4'/></c>" +
            "    <e id='5'/>" +
            "  </a>" +
            "  <f id='6'/>" +
            "</root>";

    private String query(final String xquery) throws XMLDBException {
        final XQueryService qs = server.getRoot().getService(XQueryService.class);
        final String fullQuery = "let $data := " + DATA +
                " return string-join(" + xquery + ", ',')";
        final ResourceSet result = qs.query(fullQuery);
        return result.getResource(0).getContent().toString();
    }

    // --- following-or-self ---

    @Test
    public void followingOrSelf() throws XMLDBException {
        // following::* from c excludes descendants of c (d=4 is a child of c, not following)
        assertEquals("3,5,6", query("$data//c/following-or-self::*/@id/string()"));
    }

    @Test
    public void followingOrSelfFromFirst() throws XMLDBException {
        // following::* from a excludes descendants of a, so only f=6 is following. Plus self a=1.
        assertEquals("1,6", query("$data/a/following-or-self::*/@id/string()"));
    }

    // --- following-sibling-or-self ---

    @Test
    public void followingSiblingOrSelf() throws XMLDBException {
        assertEquals("3,5", query("$data/a/c/following-sibling-or-self::*/@id/string()"));
    }

    @Test
    public void followingSiblingOrSelfFromFirst() throws XMLDBException {
        assertEquals("2,3,5", query("$data/a/b/following-sibling-or-self::*/@id/string()"));
    }

    // --- preceding-or-self ---

    @Test
    public void precedingOrSelf() throws XMLDBException {
        // preceding::* from c excludes ancestors (a=1 is ancestor, not preceding). Only b=2 precedes c.
        assertEquals("2,3", query("$data//c/preceding-or-self::*/@id/string()"));
    }

    // --- preceding-sibling-or-self ---

    @Test
    public void precedingSiblingOrSelf() throws XMLDBException {
        assertEquals("2,3", query("$data/a/c/preceding-sibling-or-self::*/@id/string()"));
    }

    @Test
    public void precedingSiblingOrSelfNameTest() throws XMLDBException {
        assertEquals("3", query("$data/a/c/preceding-sibling-or-self::c/@id/string()"));
    }

    // --- self included in name-specific test ---

    @Test
    public void followingSiblingOrSelfNameMatch() throws XMLDBException {
        // c has no following sibling named 'c', but self is 'c'
        assertEquals("3", query("$data/a/c/following-sibling-or-self::c/@id/string()"));
    }
}
