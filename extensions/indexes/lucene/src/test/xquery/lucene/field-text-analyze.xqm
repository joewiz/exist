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
 : ft:field-text and ft:analyze: the text the index has for a node or a field, and the tokens an analyzer makes
 : of a text. The fields' names are this test's own, as all collections share one index.
 :)
module namespace fta="http://exist-db.org/xquery/lucene/field-text-analyze/test";

declare namespace test="http://exist-db.org/xquery/xqsuite";

declare variable $fta:COLLECTION_NAME := "lucene-test-field-text-analyze";
declare variable $fta:COLLECTION := "/db/" || $fta:COLLECTION_NAME;

declare variable $fta:XCONF as element(collection) :=
    <collection xmlns="http://exist-db.org/collection-config/1.0">
        <index xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <lucene>
                <analyzer class="org.apache.lucene.analysis.standard.StandardAnalyzer"/>
                <analyzer id="stemmed" class="org.apache.lucene.analysis.en.EnglishAnalyzer">
                    <param name="stopwords" type="org.apache.lucene.analysis.CharArraySet"/>
                </analyzer>
                <analyzer id="ws" class="org.apache.lucene.analysis.core.WhitespaceAnalyzer"/>
                <text qname="div">
                    <ignore qname="note"/>
                    <inline qname="hi"/>
                    <field name="fta-text"/>
                    <field name="fta-unstored" store="no"/>
                    <field name="fta-dot" expression="."/>
                    <field name="fta-heads" expression="head"/>
                    <field name="fta-stemmed" analyzer="stemmed" store="no"/>
                </text>
            </lucene>
        </index>
    </collection>;

declare variable $fta:XML as document-node() :=
    document {
        <volume>
            <div id="d1"><head>Report</head><p>The <hi>tre</hi>aty was signed<note>A note</note> in Paris.</p></div>
        </volume>
    };

declare
    %test:setUp
function fta:setUp() {
    xmldb:create-collection("/db/system/config", "db"),
    xmldb:create-collection("/db/system/config/db", $fta:COLLECTION_NAME),
    xmldb:store("/db/system/config/db/" || $fta:COLLECTION_NAME, "collection.xconf", $fta:XCONF),
    xmldb:create-collection("/db", $fta:COLLECTION_NAME),
    xmldb:store($fta:COLLECTION, "volume.xml", $fta:XML)
};

declare
    %test:tearDown
function fta:tearDown() {
    xmldb:remove($fta:COLLECTION),
    xmldb:remove("/db/system/config/db/" || $fta:COLLECTION_NAME)
};

declare %private function fta:div() as element(div) {
    collection($fta:COLLECTION)//div
};

(: the index's text: the note is ignored, hi is part of its word, and head and p are separated :)
declare
    %test:assertEquals("Report The treaty was signed in Paris.")
function fta:index-text() {
    ft:field-text(fta:div())
};

(: a field without an expression has the index's text, stored or not :)
declare
    %test:args("fta-text")
    %test:assertEquals("Report The treaty was signed in Paris.")
    %test:args("fta-unstored")
    %test:assertEquals("Report The treaty was signed in Paris.")
function fta:field-without-expression($field as xs:string) {
    ft:field-text(fta:div(), $field)
};

(: a field with the expression "." has the node's string value: the note's text is in it, and nothing separates
 : the head from the paragraph :)
declare
    %test:assertEquals("ReportThe treaty was signedA note in Paris.")
function fta:field-with-expression-dot() {
    ft:field-text(fta:div(), "fta-dot")
};

declare
    %test:assertEquals("Report")
function fta:field-with-expression() {
    ft:field-text(fta:div(), "fta-heads")
};

declare
    %test:assertEmpty
function fta:in-memory-node() {
    ft:field-text(<div>Report</div>)
};

declare
    %test:assertEmpty
function fta:node-without-index() {
    ft:field-text(collection($fta:COLLECTION)/volume)
};

declare
    %test:assertEquals("agre/1/7/1", "treati/8/16/2")
function fta:analyze-field() {
    ft:analyze(fta:div(), "fta-stemmed", "Agreed treaties") ! string-join((?term, ?start, ?end, ?position), "/")
};

(: the positions are characters of the text, as substring() counts them, also after a character outside the BMP :)
declare
    %test:assertEquals("treaties")
function fta:analyze-positions() {
    let $text := "𝔸 treaties"
    let $token := ft:analyze(fta:div(), "fta-text", $text)[last()]
    return substring($text, $token?start, $token?end - $token?start)
};

declare
    %test:assertEquals("report", "the", "treaty")
function fta:analyze-index() {
    ft:analyze(fta:div(), (), "Report the Treaty") ! ?term
};

declare
    %test:assertEquals("Report", "the", "Treaty")
function fta:analyze-with-analyzer() {
    ft:analyze(fta:div(), (), "Report the Treaty", map { "analyzer": "ws" }) ! ?term
};

declare
    %test:assertEquals("agre")
function fta:analyze-for-query() {
    ft:analyze(fta:div(), "fta-stemmed", "agreed", map { "for": "query" }) ! ?term
};

declare
    %test:assertError("XPTY0004")
function fta:analyze-for-must-be-known() {
    ft:analyze(fta:div(), "fta-stemmed", "agreed", map { "for": "both" })
};

declare
    %test:assertError("XPTY0004")
function fta:analyze-analyzer-must-exist() {
    ft:analyze(fta:div(), (), "agreed", map { "analyzer": "no-such-analyzer" })
};
