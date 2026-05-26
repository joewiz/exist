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
 : Tests for fn:hash (XQuery 4.0). Adapted from
 : https://github.com/qt4cg/qt4tests/blob/master/fn/hash.xml.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-hash";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

(: Empty input -> empty result :)
declare %test:assertEmpty function t:hash-empty() { fn:hash(()) };

(: MD5 of empty string :)
declare %test:assertEquals("d41d8cd98f00b204e9800998ecf8427e")
function t:hash-md5-empty-string() {
    fn:lower-case(xs:string(fn:hash("", "MD5")))
};

(: SHA-1 of "abc" :)
declare %test:assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d")
function t:hash-sha1-abc() {
    fn:lower-case(xs:string(fn:hash("abc", "SHA-1")))
};

(: SHA-256 of "abc" :)
declare %test:assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
function t:hash-sha256-abc() {
    fn:lower-case(xs:string(fn:hash("abc", "SHA-256")))
};

(: Default (no algorithm) returns a non-empty result :)
declare %test:assertTrue function t:hash-default-not-empty() {
    fn:string-length(xs:string(fn:hash("anything"))) > 0
};

(: Unknown algorithm errors :)
declare %test:assertError function t:hash-unknown-algorithm-errors() {
    fn:hash("abc", "BOGUS-ALG-9000")
};
