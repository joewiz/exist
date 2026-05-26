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
 : Tests for fn:characters (XQuery 4.0 PR).
 :
 : Cases adapted from the QT4 conformance test set "fn-characters"
 : (https://github.com/qt4cg/qt4tests/blob/master/fn/characters.xml).
 : Cases that depend on XQuery 4.0-only syntax are omitted.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-characters";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

(: characters-001 -- empty sequence input :)
declare
    %test:assertEmpty
function t:characters-empty-sequence() {
    fn:characters(())
};

(: characters-002 -- empty string input :)
declare
    %test:assertEmpty
function t:characters-empty-string() {
    fn:characters("")
};

(: characters-003 -- simple ASCII :)
declare
    %test:assertEquals("U", "P")
function t:characters-ascii() {
    fn:characters("UP")
};

(: characters-004 -- longer string preserves length :)
declare
    %test:assertEquals(10000)
function t:characters-long-string() {
    fn:count(fn:characters(fn:string-join((1 to 10000) ! "A")))
};

(: characters-005 -- non-ASCII :)
declare
    %test:assertEquals("ä", "ö", "ü", "ß", "Ä", "Ö", "Ü", "ẞ")
function t:characters-non-ascii() {
    fn:characters("äöüßÄÖÜẞ")
};

(: characters-010 -- positional subsequence over non-BMP characters :)
declare
    %test:assertEquals("௵")
function t:characters-non-ascii-subsequence() {
    fn:subsequence(fn:characters("இ௵"), 2)
};

(: arity-0 form requires context item; verify it returns characters of "." :)
declare
    %test:assertEquals("a", "b", "c")
function t:characters-context-item() {
    "abc" ! fn:characters(.)
};

(: surrogate-pair codepoint is one Java char-pair but XQ4 counts it as one item :)
declare
    %test:assertEquals(2)
function t:characters-surrogate-counts-once() {
    (: U+1F600 grinning face: surrogate pair on UTF-16 ; "A" + emoji = 2 chars :)
    fn:count(fn:characters(fn:concat("A", fn:char(128512))))
};

(: count check for a single ASCII char :)
declare
    %test:assertEquals(1)
function t:characters-single-char() {
    fn:count(fn:characters("A"))
};

(: round-trip identity through string-join :)
declare
    %test:assertEquals("Hello, World!")
function t:characters-roundtrip() {
    fn:string-join(fn:characters("Hello, World!"))
};
