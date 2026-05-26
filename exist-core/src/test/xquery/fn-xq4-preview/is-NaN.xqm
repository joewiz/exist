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
 : Tests for fn:is-NaN (XQuery 4.0). Cases adapted from
 : https://github.com/qt4cg/qt4tests/blob/master/fn/is-NaN.xml.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-is-NaN";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertTrue function t:is-nan-double-nan() { fn:is-NaN(xs:double("NaN")) };
declare %test:assertTrue function t:is-nan-float-nan() { fn:is-NaN(xs:float("NaN")) };
declare %test:assertFalse function t:is-nan-double-zero() { fn:is-NaN(xs:double(0)) };
declare %test:assertFalse function t:is-nan-double-pi() { fn:is-NaN(xs:double(3.14)) };
declare %test:assertFalse function t:is-nan-int() { fn:is-NaN(42) };
declare %test:assertFalse function t:is-nan-string() { fn:is-NaN("NaN") };
declare %test:assertFalse function t:is-nan-double-inf() { fn:is-NaN(xs:double("INF")) };
declare %test:assertFalse function t:is-nan-decimal() { fn:is-NaN(xs:decimal("1.0")) };
