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
 : Tests for fn:void (XQuery 4.0). Cases adapted from
 : https://github.com/qt4cg/qt4tests/blob/master/fn/void.xml.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-void";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertEmpty function t:void-zero-arg() { fn:void() };
declare %test:assertEmpty function t:void-empty-arg() { fn:void(()) };
declare %test:assertEmpty function t:void-string-arg() { fn:void("anything") };
declare %test:assertEmpty function t:void-sequence-arg() { fn:void((1, 2, 3)) };
declare %test:assertEmpty function t:void-discards-side-effect() { fn:void(fn:trace("ignored")) };
