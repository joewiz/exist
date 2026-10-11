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
package org.exist.scratch;

import org.exist.test.ExistXmldbEmbeddedServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.modules.XQueryService;

public class Issue4825ReproTest {

    @RegisterExtension
    public static final ExistXmldbEmbeddedServer server = new ExistXmldbEmbeddedServer(false, true, true);

    private static final String SETUP = """
        declare variable $conf := document {
          <collection xmlns="http://exist-db.org/collection-config/1.0">
            <index><create qname="entry" type="xs:string"/></index>
          </collection>
        };
        xmldb:create-collection("/db", "t4825"),
        xmldb:create-collection("/db/system/config/db", "t4825"),
        xmldb:store("/db/system/config/db/t4825", "collection.xconf", $conf)
        """;

    private void run(final String label, final String xml) throws Exception {
        final XQueryService svc = server.getRoot().getService(XQueryService.class);
        svc.query(SETUP);
        try {
            svc.query("xmldb:store('/db/t4825', 'd.xml', '" + xml + "')");
            final ResourceSet rs = svc.query(
                "string-join((count(collection('/db/t4825')//entry), " +
                "for $e in collection('/db/t4825')//entry return '[' || string($e) || ']', " +
                "count(collection('/db/t4825')//entry[. = 'somethingItem'])), ' ')");
            System.err.println("REPRO " + label + " OK: " + rs.getResource(0).getContent());
        } catch (final Exception e) {
            System.err.println("REPRO " + label + " FAILED: " + e);
            Throwable c = e; while (c.getCause() != null) c = c.getCause();
            System.err.println("REPRO   root cause: " + c);
            for (StackTraceElement s : c.getStackTrace()) { if (s.getClassName().startsWith("org.exist")) { System.err.println("REPRO     at " + s); } }
        } finally {
            try { svc.query("xmldb:remove('/db/t4825')"); } catch (final Exception ignore) {}
        }
    }

    @Test public void textThenCdata() throws Exception { run("text+CDATA", "<entry>something<![CDATA[Item]]></entry>"); }
    @Test public void cdataOnly() throws Exception { run("CDATA only", "<entry><![CDATA[somethingItem]]></entry>"); }
    @Test public void cdataThenText() throws Exception { run("CDATA+text", "<entry><![CDATA[something]]>Item</entry>"); }
    @Test public void nested() throws Exception { run("nested", "<root><entry>something<![CDATA[Item]]></entry></root>"); }
    @Test public void twoCdata() throws Exception { run("text+CDATA+text+CDATA", "<entry>so<![CDATA[me]]>thing<![CDATA[Item]]></entry>"); }
}
