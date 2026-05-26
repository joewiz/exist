(:
 : eXist-db Open Source Native XML Database
 : Copyright (C) 2001 The eXist-db Authors
 :
 : info@exist-db.org
 : http://www.exist-db.org
 :
 : This library is free software; you can redistribute it and/or
 : modify it under the terms of the GNU Lesser General Public
 : License as published by the Free Software Foundation; either
 : version 2.1 of the License, or (at your option) any later version.
 :
 : This library is distributed in the hope that it will be useful,
 : but WITHOUT ANY WARRANTY; without even the implied warranty of
 : MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 : Lesser General Public License for more details.
 :
 : You should have received a copy of the GNU Lesser General Public
 : License along with this library; if not, write to the Free Software
 : Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 :)
xquery version "3.1";

(:~
 : Tests for fn:divide-decimals (XQuery 4.0). Adapted from
 : https://github.com/qt4cg/qt4tests/blob/master/fn/divide-decimals.xml.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-divide-decimals";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertEquals(3) function t:divide-decimals-quotient-exact() {
    fn:divide-decimals(xs:decimal("9"), xs:decimal("3"))?quotient
};

declare %test:assertEquals(0) function t:divide-decimals-remainder-exact() {
    fn:divide-decimals(xs:decimal("9"), xs:decimal("3"))?remainder
};

declare %test:assertEquals(3) function t:divide-decimals-quotient-with-remainder() {
    fn:divide-decimals(xs:decimal("10"), xs:decimal("3"))?quotient
};

declare %test:assertEquals(1) function t:divide-decimals-remainder-nonzero() {
    fn:divide-decimals(xs:decimal("10"), xs:decimal("3"))?remainder
};

(: precision arg shifts the quotient's scale :)
declare %test:assertTrue function t:divide-decimals-precision-applies() {
    let $r := fn:divide-decimals(xs:decimal("10"), xs:decimal("3"), 2)
    return $r?quotient = xs:decimal("3.33")
};

(: Negative dividend :)
declare %test:assertEquals("-4") function t:divide-decimals-negative-dividend() {
    fn:string(fn:divide-decimals(xs:decimal("-12"), xs:decimal("3"))?quotient)
};

(: Division by zero :)
declare %test:assertError function t:divide-decimals-by-zero-errors() {
    fn:divide-decimals(xs:decimal("1"), xs:decimal("0"))
};
