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
 : Tests for fn:parse-integer (XQuery 4.0). Adapted from
 : https://github.com/qt4cg/qt4tests/blob/master/fn/parse-integer.xml.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-parse-integer";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

declare %test:assertEquals(0) function t:parse-integer-zero() { fn:parse-integer("0") };
declare %test:assertEquals(42) function t:parse-integer-ascii() { fn:parse-integer("42") };
declare %test:assertEquals("-7") function t:parse-integer-negative() { fn:string(fn:parse-integer("-7")) };

(: Radix 2 :)
declare %test:assertEquals(5) function t:parse-integer-binary() { fn:parse-integer("101", 2) };

(: Radix 16 :)
declare %test:assertEquals(255) function t:parse-integer-hex() { fn:parse-integer("FF", 16) };

(: Radix 16 lower-case :)
declare %test:assertEquals(255) function t:parse-integer-hex-lower() { fn:parse-integer("ff", 16) };

(: Radix 8 :)
declare %test:assertEquals(8) function t:parse-integer-octal() { fn:parse-integer("10", 8) };

(: Empty input -> empty result :)
declare %test:assertEmpty function t:parse-integer-empty() { fn:parse-integer(()) };

(: Invalid digits :)
declare %test:assertError function t:parse-integer-invalid-digit() { fn:parse-integer("Z", 10) };

(: Out-of-range radix :)
declare %test:assertError function t:parse-integer-bad-radix() { fn:parse-integer("10", 1) };
