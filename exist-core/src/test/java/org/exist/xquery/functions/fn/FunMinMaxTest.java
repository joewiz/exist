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
package org.exist.xquery.functions.fn;

import org.exist.test.ExistXmldbEmbeddedServer;
import org.junit.ClassRule;
import org.junit.Test;
import org.xmldb.api.base.ResourceSet;
import org.xmldb.api.base.XMLDBException;
import org.xmldb.api.modules.XPathQueryService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for fn:min and fn:max XQ 3.1 type-promotion semantics.
 * These tests mirror W3C XQTS cases that flagged as regressions when the
 * fn:min / fn:max implementations were rewritten for XQuery 4.0 in PR #6218.
 */
public class FunMinMaxTest {

    @ClassRule
    public static final ExistXmldbEmbeddedServer server = new ExistXmldbEmbeddedServer(true, true, true);

    private String firstResult(final String query) throws XMLDBException {
        final XPathQueryService q = server.getRoot().getService(XPathQueryService.class);
        final ResourceSet rs = q.query(query);
        assertEquals("expected one result for: " + query, 1, rs.getSize());
        return rs.getResource(0).getContent().toString();
    }

    private void expectError(final String code, final String query) {
        try {
            final XPathQueryService q = server.getRoot().getService(XPathQueryService.class);
            q.query(query);
            fail("expected " + code + " for: " + query);
        } catch (final XMLDBException e) {
            final String msg = e.getMessage() == null ? "" : e.getMessage();
            assertTrue("expected " + code + " in error, got: " + msg, msg.contains(code));
        }
    }

    // K-SeqMINFunc-14: integer + float + decimal must promote to xs:float
    @Test
    public void min_integerFloatDecimal_promotesToFloat() throws XMLDBException {
        assertEquals("true",
            firstResult("min((1, xs:float(2), xs:decimal(3))) instance of xs:float"));
    }

    // K2-SeqMINFunc-7
    @Test
    public void min_integerDouble_promotesToDouble() throws XMLDBException {
        assertEquals("true",
            firstResult("min((5, 5.0e0)) instance of xs:double"));
    }

    // K2-SeqMINFunc-9
    @Test
    public void min_integerDouble_promotesToDouble2() throws XMLDBException {
        assertEquals("true",
            firstResult("min((3, 5.0e0)) instance of xs:double"));
    }

    // K-SeqMINFunc-18
    @Test
    public void min_floatNaN_untyped_double_isDouble() throws XMLDBException {
        assertEquals("true",
            firstResult("min((xs:float('NaN'), xs:untypedAtomic('3'), xs:double(2))) instance of xs:double"));
    }

    // K-SeqMINFunc-19
    @Test
    public void min_floatNaN_doubleNaN_isDouble() throws XMLDBException {
        assertEquals("true",
            firstResult("min((xs:float('NaN'), 1, 1, 2, xs:double('NaN'))) instance of xs:double"));
    }

    // K-SeqMINFunc-38
    @Test
    public void min_qname_raisesError() {
        expectError("FORG0006",
            "min(QName('example.com/', 'ncname'))");
    }

    // cbcl-min-009: numeric promotion through FLWOR
    @Test
    public void min_flwor_mixedNumeric_isDouble() throws XMLDBException {
        final String q = "declare function local:f($x as xs:integer) { "
            + "(xs:decimal(1.1), xs:float(2.2), xs:double(1.4), xs:integer(2))[$x] }; "
            + "min(for $x in (1,2,3) return local:f($x)) instance of xs:double";
        assertEquals("true", firstResult(q));
    }

    // fn-min-8: incompatible duration subtypes -> FORG0006
    @Test
    public void min_yearMonthAndDayTimeDuration_raisesError() {
        expectError("FORG0006",
            "min((xs:yearMonthDuration('P1Y'), xs:dayTimeDuration('P1D')))");
    }

    // fn-min-9: plain xs:duration -> FORG0006
    @Test
    public void min_plainDuration_raisesError() {
        expectError("FORG0006",
            "min(xs:duration('P1Y1M1D'))");
    }

    // Mirror: fn:max behaves the same way for the typing
    @Test
    public void max_integerFloatDecimal_promotesToFloat() throws XMLDBException {
        assertEquals("true",
            firstResult("max((1, xs:float(2), xs:decimal(3))) instance of xs:float"));
    }

    @Test
    public void max_integerDouble_promotesToDouble() throws XMLDBException {
        assertEquals("true",
            firstResult("max((5, 5.0e0)) instance of xs:double"));
    }

    @Test
    public void max_qname_raisesError() {
        expectError("FORG0006",
            "max(QName('example.com/', 'ncname'))");
    }

    @Test
    public void max_yearMonthAndDayTimeDuration_raisesError() {
        expectError("FORG0006",
            "max((xs:yearMonthDuration('P1Y'), xs:dayTimeDuration('P1D')))");
    }

    @Test
    public void max_plainDuration_raisesError() {
        expectError("FORG0006",
            "max(xs:duration('P1Y1M1D'))");
    }
}
