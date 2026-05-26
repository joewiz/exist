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
 : Tests for fn:insert-separator (XQuery 4.0).
 :
 : Cases adapted from the QT4 conformance test set "fn-insert-separator"
 : (https://github.com/qt4cg/qt4tests/blob/master/fn/insert-separator.xml).
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-insert-separator";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertEmpty function t:insert-separator-both-empty() { fn:insert-separator((), ()) };
declare %test:assertEmpty function t:insert-separator-empty-input() { fn:insert-separator((), 1) };

declare %test:assertEquals(1) function t:insert-separator-single-empty-sep() { fn:insert-separator(1, ()) };
declare %test:assertEquals(1, 2) function t:insert-separator-seq-empty-sep() { fn:insert-separator((1, 2), ()) };
declare %test:assertEquals(1) function t:insert-separator-single-input() { fn:insert-separator(1, "a") };

(: Sequence input with string separator interleaves :)
declare %test:assertEquals(1, "a", 2) function t:insert-separator-string-sep() { fn:insert-separator((1, 2), "a") };
declare %test:assertEquals(1, "a", 2, "a", 3) function t:insert-separator-three-input() { fn:insert-separator((1, 2, 3), "a") };

(: Separator may itself be a sequence; expanded between items :)
declare %test:assertEquals(1, "a", "b", 2) function t:insert-separator-seq-sep() { fn:insert-separator((1, 2), ("a", "b")) };
