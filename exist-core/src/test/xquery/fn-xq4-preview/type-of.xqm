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
 : Tests for fn:type-of (XQuery 4.0). Adapted from
 : https://github.com/qt4cg/qt4tests/blob/master/fn/type-of.xml.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-type-of";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertEquals("xs:integer") function t:type-of-int() { fn:type-of(42) };
declare %test:assertEquals("xs:string") function t:type-of-string() { fn:type-of("hello") };
declare %test:assertEquals("xs:boolean") function t:type-of-bool() { fn:type-of(true()) };
declare %test:assertEquals("xs:decimal") function t:type-of-decimal() { fn:type-of(xs:decimal("3.14")) };
declare %test:assertEquals("xs:double") function t:type-of-double() { fn:type-of(xs:double("1.5")) };

(: Empty input -> "empty-sequence()" :)
declare %test:assertEquals("empty-sequence()") function t:type-of-empty() { fn:type-of(()) };
