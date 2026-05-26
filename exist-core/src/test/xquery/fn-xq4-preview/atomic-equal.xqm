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
 : Tests for fn:atomic-equal (XQuery 4.0). Cases adapted from
 : https://github.com/qt4cg/qt4tests/blob/master/fn/atomic-equal.xml.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-atomic-equal";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertTrue function t:atomic-equal-strings-equal() { fn:atomic-equal("abc", "abc") };
declare %test:assertFalse function t:atomic-equal-strings-different() { fn:atomic-equal("abc", "abd") };
declare %test:assertTrue function t:atomic-equal-integers-equal() { fn:atomic-equal(1, 1) };
declare %test:assertFalse function t:atomic-equal-integers-different() { fn:atomic-equal(1, 2) };
declare %test:assertTrue function t:atomic-equal-int-vs-decimal() { fn:atomic-equal(xs:integer(1), xs:decimal(1)) };
declare %test:assertTrue function t:atomic-equal-bool-true() { fn:atomic-equal(true(), true()) };
declare %test:assertFalse function t:atomic-equal-bool-false-true() { fn:atomic-equal(false(), true()) };

(: NaN is NOT equal to itself under value-equality, but atomic-equal must treat them as equal :)
declare %test:assertTrue function t:atomic-equal-nan-self() { fn:atomic-equal(xs:double("NaN"), xs:double("NaN")) };

(: Incomparable types raise XPTY0004 :)
declare %test:assertError function t:atomic-equal-string-vs-int-errors() { fn:atomic-equal("1", 1) };
