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
 : Tests for fn:replicate (XQuery 4.0).
 :
 : Cases adapted from the QT4 conformance test set "fn-replicate"
 : (https://github.com/qt4cg/qt4tests/blob/master/fn/replicate.xml).
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-replicate";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertEmpty function t:replicate-empty-zero() { fn:replicate((), 0) };
declare %test:assertEmpty function t:replicate-empty-one() { fn:replicate((), 1) };
declare %test:assertEmpty function t:replicate-seq-zero() { fn:replicate((1 to 10), 0) };

declare %test:assertEquals("a") function t:replicate-single-one() { fn:replicate("a", 1) };
declare %test:assertEquals("a", "a") function t:replicate-single-two() { fn:replicate("a", 2) };
declare %test:assertEquals("a", "b") function t:replicate-seq-one() { fn:replicate(("a", "b"), 1) };
declare %test:assertEquals("a", "b", "a", "b") function t:replicate-seq-two() { fn:replicate(("a", "b"), 2) };

declare %test:assertEquals(1984) function t:replicate-count() { fn:count(fn:replicate("a", 1984)) };
declare %test:assertEquals(23432) function t:replicate-first-item() { fn:replicate(23432, 10000)[1] };

declare %test:assertError function t:replicate-negative-count() { fn:replicate("a", -1) };
