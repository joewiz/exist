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
 : Tests for fn:items-at (XQuery 4.0).
 :
 : Cases adapted from the QT4 conformance test set "fn-items-at"
 : (https://github.com/qt4cg/qt4tests/blob/master/fn/items-at.xml).
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-items-at";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertEmpty function t:items-at-empty-input() { fn:items-at((), 1) };
declare %test:assertEmpty function t:items-at-empty-positions() { fn:items-at(("a", "b", "c"), ()) };

declare %test:assertEquals("a") function t:items-at-single() { fn:items-at(("a", "b", "c"), 1) };
declare %test:assertEquals("b") function t:items-at-middle() { fn:items-at(("a", "b", "c"), 2) };
declare %test:assertEquals("c") function t:items-at-last() { fn:items-at(("a", "b", "c"), 3) };

declare %test:assertEquals("a", "c") function t:items-at-multiple() { fn:items-at(("a", "b", "c"), (1, 3)) };
declare %test:assertEquals("c", "a") function t:items-at-reorder() { fn:items-at(("a", "b", "c"), (3, 1)) };
declare %test:assertEquals("a", "a", "b") function t:items-at-duplicates() { fn:items-at(("a", "b", "c"), (1, 1, 2)) };

(: Out-of-range positions yield empty :)
declare %test:assertEmpty function t:items-at-out-of-range-high() { fn:items-at(("a", "b"), 5) };
declare %test:assertEmpty function t:items-at-zero-position() { fn:items-at(("a", "b"), 0) };
