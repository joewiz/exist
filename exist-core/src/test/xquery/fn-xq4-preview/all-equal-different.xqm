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
 : Tests for fn:all-equal and fn:all-different (XQuery 4.0).
 : (https://github.com/qt4cg/qt4tests/blob/master/fn/all-equal.xml,
 :  https://github.com/qt4cg/qt4tests/blob/master/fn/all-different.xml)
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-all-equal-different";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertTrue function t:all-equal-empty() { fn:all-equal(()) };
declare %test:assertTrue function t:all-equal-single() { fn:all-equal((1)) };
declare %test:assertTrue function t:all-equal-uniform() { fn:all-equal((1, 1, 1)) };
declare %test:assertFalse function t:all-equal-mixed() { fn:all-equal((1, 2, 1)) };
declare %test:assertTrue function t:all-equal-strings() { fn:all-equal(("a", "a", "a")) };

declare %test:assertTrue function t:all-different-empty() { fn:all-different(()) };
declare %test:assertTrue function t:all-different-single() { fn:all-different((1)) };
declare %test:assertTrue function t:all-different-distinct() { fn:all-different((1, 2, 3)) };
declare %test:assertFalse function t:all-different-with-duplicate() { fn:all-different((1, 2, 1)) };
declare %test:assertTrue function t:all-different-strings() { fn:all-different(("a", "b", "c")) };
