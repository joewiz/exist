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
 : Tests for fn:seconds (XQuery 4.0). Adapted from
 : https://github.com/qt4cg/qt4tests/blob/master/fn/seconds.xml.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-seconds";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertEquals("PT0S") function t:seconds-zero() { fn:string(fn:seconds(0)) };
declare %test:assertEquals("PT1S") function t:seconds-one() { fn:string(fn:seconds(1)) };
declare %test:assertEquals("PT1M") function t:seconds-sixty() { fn:string(fn:seconds(60)) };
declare %test:assertEquals("PT1H") function t:seconds-3600() { fn:string(fn:seconds(3600)) };
declare %test:assertEquals("PT0.5S") function t:seconds-fractional() { fn:string(fn:seconds(0.5)) };
declare %test:assertEquals("-PT1S") function t:seconds-negative() { fn:string(fn:seconds(-1)) };
declare %test:assertEmpty function t:seconds-empty() { fn:seconds(()) };
