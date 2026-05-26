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
 : Tests for fn:duplicate-values (XQuery 4.0). Cases adapted from
 : https://github.com/qt4cg/qt4tests/blob/master/fn/duplicate-values.xml.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-duplicate-values";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertEmpty function t:dup-empty() { fn:duplicate-values(()) };
declare %test:assertEmpty function t:dup-no-duplicates() { fn:duplicate-values((1, 2, 3)) };
declare %test:assertEquals(1) function t:dup-single() { fn:duplicate-values((1, 1, 2, 3)) };
declare %test:assertEquals("a") function t:dup-string-single() { fn:duplicate-values(("a", "b", "a")) };

(: order of result follows order of duplicate occurrence in the input :)
declare %test:assertEquals(1, 2) function t:dup-multiple() { fn:duplicate-values((1, 2, 1, 2, 3)) };
declare %test:assertEquals(1) function t:dup-once-no-matter-how-many() { fn:count(fn:duplicate-values((1, 1, 1, 1))) };

(: Case-sensitive default :)
declare %test:assertEmpty function t:dup-case-sensitive-default() { fn:duplicate-values(("ABC", "abc")) };
