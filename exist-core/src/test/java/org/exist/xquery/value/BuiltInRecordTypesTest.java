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
package org.exist.xquery.value;

import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.xquery.XQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies XQ4 built-in record-type matching for {@code instance of} on
 * the named record types defined in {@link BuiltInRecordTypes}.
 *
 * <p>Each test runs an XQuery and asserts the boolean result, mirroring
 * a QT4 prod-SequenceType test case.</p>
 */
public class BuiltInRecordTypesTest {

    private static ExistEmbeddedServer server;

    @BeforeAll
    static void startDb() throws Exception {
        server = new ExistEmbeddedServer(true, true);
        server.startDb();
    }

    @AfterAll
    static void stopDb() {
        if (server != null) {
            server.stopDb();
        }
    }

    private boolean run(final String xquery) throws Exception {
        final BrokerPool pool = server.getBrokerPool();
        try (final DBBroker broker = pool.get(java.util.Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final XQuery engine = pool.getXQueryService();
            final Sequence result = engine.execute(broker, "xquery version \"4.0\";\n" + xquery, null);
            return result.effectiveBooleanValue();
        }
    }

    @Test
    void manualMapMatchesParsedCsvStructure() throws Exception {
        // Mirrors qt4 built-in-record-type-003 (assert-true).
        assertTrue(run(
                "{'columns':('a','b','c'),\n" +
                " 'column-index':{'a':1, 'b':2, 'c':3},\n" +
                " 'rows':(['p','q','r'], ['s','t','u']),\n" +
                " 'get':fn($row as xs:positiveInteger,\n" +
                "         $col as (xs:positiveInteger|xs:string)) as xs:string? {'banana'}\n" +
                "} instance of fn:parsed-csv-structure-record"));
    }

    @Test
    void parseCsvMatchesParsedCsvStructure() throws Exception {
        // Mirrors qt4 built-in-record-type-103 (assert-true).
        assertTrue(run("parse-csv('a,b,c') instance of fn:parsed-csv-structure-record"));
    }

    @Test
    void manualMapMatchesLoadXQueryModule() throws Exception {
        // Mirrors qt4 built-in-record-type-002 (assert-true).
        assertTrue(run(
                "{'variables':{xs:QName('temp'): 0},\n" +
                " 'functions':{xs:QName('abs'): {1: fn:abs#1}}\n" +
                "} instance of fn:load-xquery-module-record"));
    }

    @Test
    void extraKeyRejectedByLoadXQueryModule() throws Exception {
        // Mirrors qt4 built-in-record-type-202 (assert-false): not extensible.
        assertFalse(run(
                "{'variables':{xs:QName('temp'): 0},\n" +
                " 'functions':{xs:QName('abs'): {1: fn:abs#1}},\n" +
                " 'extra':42\n" +
                "} instance of fn:load-xquery-module-record"));
    }
}
