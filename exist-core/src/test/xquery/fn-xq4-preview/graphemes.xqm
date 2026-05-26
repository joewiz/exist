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
 : Tests for fn:graphemes (XQuery 4.0 PR1068).
 :
 : Cases adapted from the QT4 conformance test set "fn-graphemes"
 : (https://github.com/qt4cg/qt4tests/blob/master/fn/graphemes.xml).
 : The full QT4 set is 1,189 cases auto-generated from the Unicode 15.1
 : GraphemeBreakTest.txt; this module ports a representative subset that
 : exercises the distinct grapheme-cluster boundary categories:
 : empty input, ASCII boundaries, combining marks, CR/LF/CRLF, control,
 : ZWJ emoji sequences, regional-indicator flags, Hangul jamo,
 : surrogate-pair (non-BMP) characters, Devanagari conjuncts.
 :)
module namespace t = "http://exist-db.org/xquery/test/fn-graphemes";
declare namespace test = "http://exist-db.org/xquery/xqsuite";

(: graphemes-001 -- empty sequence :)
declare
    %test:assertEmpty
function t:graphemes-empty-sequence() {
    fn:graphemes(())
};

(: graphemes-002 -- empty string :)
declare
    %test:assertEmpty
function t:graphemes-empty-string() {
    fn:graphemes("")
};

(: graphemes-003 -- two ASCII spaces -- two clusters :)
declare
    %test:assertEquals(" ", " ")
function t:graphemes-two-spaces() {
    fn:graphemes("  ")
};

(: graphemes-004 -- space + combining diaeresis + space -- two clusters :)
declare
    %test:assertEquals(" &#x0308;", " ")
function t:graphemes-space-combining-space() {
    fn:graphemes(fn:codepoints-to-string((32, 776, 32)))
};

(: graphemes-005 -- space + CR -- two clusters :)
declare
    %test:assertEquals(" ", "&#xD;")
function t:graphemes-space-cr() {
    fn:graphemes(fn:codepoints-to-string((32, 13)))
};

(: CRLF -- a single grapheme cluster :)
declare
    %test:assertEquals(1)
function t:graphemes-crlf-is-one() {
    fn:count(fn:graphemes(fn:codepoints-to-string((13, 10))))
};

(: LF alone -- one cluster :)
declare
    %test:assertEquals(1)
function t:graphemes-lf-is-one() {
    fn:count(fn:graphemes(fn:codepoints-to-string((10))))
};

(: Round trip via string-join :)
declare
    %test:assertEquals("Hello")
function t:graphemes-roundtrip-ascii() {
    fn:string-join(fn:graphemes("Hello"))
};

(: Simple ASCII string has one cluster per character :)
declare
    %test:assertEquals(5)
function t:graphemes-ascii-count() {
    fn:count(fn:graphemes("Hello"))
};

(: A precomposed character is one cluster :)
declare
    %test:assertEquals(1)
function t:graphemes-precomposed-acute() {
    (: U+00E9 LATIN SMALL LETTER E WITH ACUTE :)
    fn:count(fn:graphemes(fn:codepoints-to-string(233)))
};

(: A decomposed sequence (e + combining acute) is one cluster :)
declare
    %test:assertEquals(1)
function t:graphemes-decomposed-e-acute() {
    (: e (U+0065) + COMBINING ACUTE ACCENT (U+0301) :)
    fn:count(fn:graphemes(fn:codepoints-to-string((101, 769))))
};

(: A grinning-face emoji (non-BMP, surrogate pair) is one cluster :)
declare
    %test:assertEquals(1)
function t:graphemes-surrogate-emoji-one-cluster() {
    (: U+1F600 GRINNING FACE :)
    fn:count(fn:graphemes(fn:codepoints-to-string(128512)))
};

(: Two regional-indicator codepoints (U+1F1FA U+1F1F8 = "US" flag) form one cluster :)
declare
    %test:assertEquals(1)
function t:graphemes-flag-emoji-one-cluster() {
    fn:count(fn:graphemes(fn:codepoints-to-string((127482, 127480))))
};

(: ZWJ-joined emoji (family) is one cluster :)
declare
    %test:assertEquals(1)
function t:graphemes-family-emoji-one-cluster() {
    (: man + ZWJ + woman + ZWJ + girl + ZWJ + boy :)
    fn:count(fn:graphemes(fn:codepoints-to-string((128104, 8205, 128105, 8205, 128103, 8205, 128102))))
};

(: Hangul L+V is one cluster :)
declare
    %test:assertEquals(1)
function t:graphemes-hangul-lv() {
    (: U+1100 HANGUL CHOSEONG KIYEOK + U+1161 HANGUL JUNGSEONG A :)
    fn:count(fn:graphemes(fn:codepoints-to-string((4352, 4449))))
};

(: Hangul LVT (precomposed syllable) is one cluster :)
declare
    %test:assertEquals(1)
function t:graphemes-hangul-syllable-precomposed() {
    (: U+AC00 HANGUL SYLLABLE GA :)
    fn:count(fn:graphemes(fn:codepoints-to-string(44032)))
};

(: Devanagari conjunct: KA + VIRAMA + TA is one cluster (graphemes-1180) :)
declare
    %test:assertEquals(1)
function t:graphemes-devanagari-conjunct() {
    fn:count(fn:graphemes(fn:codepoints-to-string((2325, 2381, 2340))))
};

(: A trailing combining mark attaches to the prior base character :)
declare
    %test:assertEquals(3)
function t:graphemes-three-bases-and-marks() {
    (: a + acute, e + acute, o + acute => 3 clusters :)
    fn:count(fn:graphemes(fn:codepoints-to-string((97, 769, 101, 769, 111, 769))))
};

(: A skin-tone modifier joins the preceding emoji into one cluster :)
declare
    %test:assertEquals(1)
function t:graphemes-emoji-skin-tone() {
    (: U+1F44B WAVING HAND + U+1F3FD MEDIUM SKIN TONE :)
    fn:count(fn:graphemes(fn:codepoints-to-string((128075, 127997))))
};

(: Multi-cluster: ABC -- 3 clusters of length 1 :)
declare
    %test:assertEquals("A", "B", "C")
function t:graphemes-abc() {
    fn:graphemes("ABC")
};
