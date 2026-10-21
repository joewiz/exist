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
 : ft:passages returns the sentences of a stored field that best match the query a node was found by.
 :)
module namespace pas = "http://exist-db.org/xquery/test/passages";

declare namespace test = "http://exist-db.org/xquery/xqsuite";
declare namespace exist = "http://exist.sourceforge.net/NS/exist";

import module namespace ft = "http://exist-db.org/xquery/lucene";

declare variable $pas:COLLECTION := "/db/test-passages";

declare variable $pas:TEXT :=
    "The treaty was discussed at length. Mr. Dulles said the treaty and the treaty annex were final. " ||
    "Nothing else happened that day. The treaty was signed in Paris. Treaty ratification followed. " ||
    "Some treaties are older than this treaty, and the treaty of 1919 is one.";

declare
    %test:setUp
function pas:setup() {
    let $_ := (xmldb:create-collection("/db/system", "config"), xmldb:create-collection("/db/system/config", "db"))
    let $_ := xmldb:create-collection("/db/system/config/db", substring-after($pas:COLLECTION, "/db/"))
    let $_ := xmldb:store("/db/system/config" || $pas:COLLECTION, "collection.xconf",
        <collection xmlns="http://exist-db.org/collection-config/1.0">
            <index>
                <lucene>
                    <text qname="div">
                        <field name="text" expression="p"/>
                        <field name="title" expression="head"/>
                        <field name="unstored" expression="p" store="no"/>
                    </text>
                </lucene>
            </index>
        </collection>)
    let $_ := xmldb:create-collection("/db", substring-after($pas:COLLECTION, "/db/"))
    return xmldb:store($pas:COLLECTION, "doc.xml", <div><head>Report</head><p>{$pas:TEXT}</p></div>)
};

declare
    %test:tearDown
function pas:teardown() {
    xmldb:remove($pas:COLLECTION),
    xmldb:remove("/db/system/config" || $pas:COLLECTION)
};

declare function pas:hit($query as xs:string) as element(div)? {
    collection($pas:COLLECTION)//div[ft:query(., $query)]
};

(: five sentences mention the treaty; the three with the most matches are returned, in their order in the text :)
declare
    %test:assertEquals(
        "Mr. Dulles said the treaty and the treaty annex were final.",
        "Treaty ratification followed.",
        "Some treaties are older than this treaty, and the treaty of 1919 is one.")
function pas:best-three-in-order() {
    ft:passages(pas:hit("text:treaty"), "text", map { "as": "map" })?text
};

declare
    %test:assertEquals(5)
function pas:max() {
    count(ft:passages(pas:hit("text:treaty"), "text", map { "max": 10 }))
};

declare
    %test:assertTrue
function pas:offsets-select-the-passage-and-matches() {
    let $passages := ft:passages(pas:hit("text:treaty"), "text", map { "as": "map" })
    return every $p in $passages satisfies (
        substring($pas:TEXT, $p?start, $p?end - $p?start) = $p?text
        and (every $m in $p?matches?* satisfies lower-case(substring($pas:TEXT, $m?start, $m?end - $m?start)) = "treaty")
    )
};

declare
    %test:assertEquals("treaties", "treaty", "treaty")
function pas:wildcard() {
    let $p := ft:passages(pas:hit("text:treat*"), "text", map { "as": "map", "max": 1 })
    for $m in $p?matches?*
    return substring($pas:TEXT, $m?start, $m?end - $m?start)
};

declare
    %test:assertEquals("signed in Paris")
function pas:phrase() {
    let $p := ft:passages(pas:hit('text:"signed in paris"'), "text", map { "as": "map" })
    for $m in $p?matches?*
    return substring($pas:TEXT, $m?start, $m?end - $m?start)
};

declare
    %test:assertEmpty
function pas:no-match-in-the-field() {
    ft:passages(pas:hit("title:report"), "text")
};

declare
    %test:assertEmpty
function pas:unstored-field() {
    ft:passages(pas:hit("unstored:treaty"), "unstored")
};

declare
    %test:assertEquals("Treaty ratification followed.", "ratification")
function pas:element() {
    let $p := ft:passages(pas:hit("text:ratification"), "text", map { "as": "element" })
    return (string($p), $p/exist:match/string())
};

declare
    %test:assertTrue
function pas:length() {
    let $p := ft:passages(pas:hit("text:annex"), "text", map { "as": "map", "break": "length", "length": 120 })
    return count($p) = 1 and string-length($p?text) gt string-length("Mr. Dulles said the treaty and the treaty annex were final.")
};

declare
    %test:assertEquals(1)
function pas:whole() {
    count(ft:passages(pas:hit("text:treaty"), "text", map { "break": "whole" }))
};

declare
    %test:assertError("XPTY0004")
function pas:break-must-be-known() {
    ft:passages(pas:hit("text:treaty"), "text", map { "break": "paragraph" })
};

(: a phrase that runs from one sentence into the next: the passage takes in both sentences :)
declare
    %test:assertEquals("The treaty was signed in Paris. Treaty ratification followed.", "Paris. Treaty")
function pas:phrase-across-sentences() {
    let $p := ft:passages(pas:hit('text:"paris treaty"'), "text", map { "as": "map" })
    return ($p?text, for $m in $p?matches?* return substring($pas:TEXT, $m?start, $m?end - $m?start))
};

(: a query that also requires another field still finds its passages in this one :)
declare
    %test:assertEquals("Treaty ratification followed.")
function pas:other-field-required() {
    ft:passages(pas:hit("text:ratification AND title:report"), "text", map { "as": "map" })?text
};

(: a passage longer than max-length is cut around its match, at word boundaries, and its offsets and matches still select it :)
declare
    %test:assertEquals("the treaty annex were final.", "true")
function pas:max-length() {
    let $p := ft:passages(pas:hit("text:annex"), "text", map { "as": "map", "max-length": 30 })
    return ($p?text, string(
        string-length($p?text) le 30
        and substring($pas:TEXT, $p?start, $p?end - $p?start) = $p?text
        and (every $m in $p?matches?* satisfies substring($pas:TEXT, $m?start, $m?end - $m?start) = "annex")))
};

(: the matches a cut leaves out are not returned :)
declare
    %test:assertEquals("treaty")
function pas:max-length-drops-matches-outside() {
    let $p := ft:passages(pas:hit("text:treaty"), "text", map { "as": "map", "max": 1, "max-length": 20 })
    for $m in $p?matches?*
    return substring($pas:TEXT, $m?start, $m?end - $m?start)
};

declare
    %test:assertEquals("Treaty ratification followed.")
function pas:max-length-longer-than-the-passage() {
    ft:passages(pas:hit("text:ratification"), "text", map { "as": "map", "max-length": 200 })?text
};

declare
    %test:assertError("XPTY0004")
function pas:max-length-must-not-be-negative() {
    ft:passages(pas:hit("text:treaty"), "text", map { "max-length": -1 })
};

declare
    %test:assertTrue
function pas:elements-by-default() {
    ft:passages(pas:hit("text:ratification"), "text") instance of element(exist:passage)
};

(: the same best passages, best first :)
declare
    %test:assertTrue
function pas:order-by-score() {
    let $by-score := ft:passages(pas:hit("text:treaty"), "text", map { "as": "map", "order": "score" })
    let $in-document := ft:passages(pas:hit("text:treaty"), "text", map { "as": "map" })
    return (every $i in 2 to count($by-score) satisfies $by-score[$i - 1]?score ge $by-score[$i]?score)
        and deep-equal(sort($by-score?start), $in-document?start)
};

declare
    %test:assertError("XPTY0004")
function pas:order-must-be-known() {
    ft:passages(pas:hit("text:treaty"), "text", map { "order": "text" })
};

(: align 0 puts the match at the start of a cut passage, align 1 at its end; a match near either end of a sentence
 : moves the window back in, so the passage is as long as max-length allows :)
declare
    %test:assertEquals("older than this", "are older")
function pas:align() {
    ft:passages(pas:hit("text:older"), "text", map { "as": "map", "max-length": 15, "align": 0 })?text,
    ft:passages(pas:hit("text:older"), "text", map { "as": "map", "max-length": 15, "align": 1 })?text
};

declare
    %test:assertError("XPTY0004")
function pas:align-must-be-from-0-to-1() {
    ft:passages(pas:hit("text:annex"), "text", map { "align": 2 })
};

(: a match past the analyzed characters is not found :)
declare
    %test:assertEquals(1, 0)
function pas:max-analyzed() {
    count(ft:passages(pas:hit("text:1919"), "text")),
    count(ft:passages(pas:hit("text:1919"), "text", map { "max-analyzed": 100 }))
};

declare
    %test:assertEquals("Treaty ratification followed.", "ratification")
function pas:mark() {
    let $p := ft:passages(pas:hit("text:ratification"), "text", map { "mark": "mark" })
    return (string($p), $p/mark/string())
};

declare
    %test:assertEquals("Treaty RATIFICATION followed.", "hit")
function pas:highlight() {
    let $p := ft:passages(pas:hit("text:ratification"), "text",
        map { "highlight": function($match) { <b class="hit">{upper-case($match)}</b> } })
    return (string($p), string($p/b/@class))
};

declare
    %test:assertError("XPTY0004")
function pas:mark-is-for-elements() {
    ft:passages(pas:hit("text:ratification"), "text", map { "as": "map", "mark": "mark" })
};

declare
    %test:assertError("XPTY0004")
function pas:as-must-be-known() {
    ft:passages(pas:hit("text:ratification"), "text", map { "as": "html" })
};
