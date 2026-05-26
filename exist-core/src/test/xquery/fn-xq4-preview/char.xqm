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
 : Tests for fn:char (XQuery 4.0 PR261).
 :
 : Cases adapted from the QT4 conformance test set "fn-char"
 : (https://github.com/qt4cg/qt4tests/blob/master/fn/char.xml). Selection covers
 : codepoint input, the most-used HTML5 named entities, multi-codepoint entities,
 : unknown-name and out-of-range errors.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-char";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

(: codepoint 65 = "A" :)
declare
    %test:assertEquals("A")
function t:char-by-codepoint() {
    fn:char(65)
};

(: codepoint 0x1F600 = 😀 (grinning face) - surrogate pair on UTF-16 :)
declare
    %test:assertTrue
function t:char-by-codepoint-supplementary() {
    fn:string-to-codepoints(fn:char(128512)) = 128512
};

(: HTML "NewLine" entity is U+000A :)
declare
    %test:assertEquals(10)
function t:char-named-newline() {
    fn:string-to-codepoints(fn:char("NewLine"))
};

(: HTML "nbsp" entity is U+00A0 :)
declare
    %test:assertEquals(160)
function t:char-named-nbsp() {
    fn:string-to-codepoints(fn:char("nbsp"))
};

(: HTML "Tab" entity is U+0009 :)
declare
    %test:assertEquals(9)
function t:char-named-tab() {
    fn:string-to-codepoints(fn:char("Tab"))
};

(: HTML "amp" entity is U+0026 :)
declare
    %test:assertEquals(38)
function t:char-named-amp() {
    fn:string-to-codepoints(fn:char("amp"))
};

(: HTML "NotEqualTilde" is a 2-codepoint entity (U+2242 U+0338) :)
declare
    %test:assertTrue
function t:char-named-multi-codepoint() {
    fn:deep-equal(fn:string-to-codepoints(fn:char("NotEqualTilde")), (8770, 824))
};

(: Unknown HTML entity raises FOCH0005 :)
declare
    %test:assertError("FOCH0005")
function t:char-unknown-name-errors() {
    fn:char("Unknown")
};

(: Names are case-sensitive (NBSP != nbsp) :)
declare
    %test:assertError("FOCH0005")
function t:char-wrong-case-errors() {
    fn:char("NBSP")
};

(: Disallowed leading '&' in name :)
declare
    %test:assertError("FOCH0005")
function t:char-name-with-ampersand-errors() {
    fn:char("&amp;nbsp")
};

(: Disallowed trailing ';' in name :)
declare
    %test:assertError("FOCH0005")
function t:char-name-with-semicolon-errors() {
    fn:char("nbsp;")
};

(: Negative codepoint :)
declare
    %test:assertError
function t:char-negative-codepoint-errors() {
    fn:char(-1)
};

(: Surrogate codepoint U+D800 is not a valid character :)
declare
    %test:assertError
function t:char-surrogate-errors() {
    fn:char(55296)
};
