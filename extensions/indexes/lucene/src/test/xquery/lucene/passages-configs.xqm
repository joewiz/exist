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
 : ft:passages across the index configurations: the index's own text, with inline and ignored elements; fields
 : without an expression, stored or not; fields with an expression, with one value or several; a field with its
 : own analyzer; a named index; and a pretty-printed document.
 :)
module namespace pc = "http://exist-db.org/xquery/lucene/passages-configs/test";

declare namespace test = "http://exist-db.org/xquery/xqsuite";
declare namespace exist = "http://exist.sourceforge.net/NS/exist";

import module namespace ft = "http://exist-db.org/xquery/lucene";

declare variable $pc:COLLECTION := "/db/test-passages-configs";

declare variable $pc:COMPACT :=
    <div><head>Report</head><p>The treaty was signed in <hi>Par</hi>is.<anchor n="1"/> It was <note>a note on the treaty</note>ratified.<pb n="2"/></p><p>Nothing else.</p></div>;

declare variable $pc:PRETTY := '<div>
    <head>Memo</head>
    <p>The embassy
        reported on Kashmir.</p>
</div>';

declare
    %test:setUp
function pc:setup() {
    let $_ := (xmldb:create-collection("/db/system", "config"), xmldb:create-collection("/db/system/config", "db"))
    let $_ := xmldb:create-collection("/db/system/config/db", substring-after($pc:COLLECTION, "/db/"))
    let $_ := xmldb:store("/db/system/config" || $pc:COLLECTION, "collection.xconf",
        <collection xmlns="http://exist-db.org/collection-config/1.0">
            <index>
                <lucene>
                    <analyzer id="ws" class="org.apache.lucene.analysis.core.WhitespaceAnalyzer"/>
                    <text qname="div">
                        <inline qname="hi"/>
                        <ignore qname="note"/>
                        <field name="no-expr"/>
                        <field name="no-expr-unstored" store="no"/>
                        <field name="dot" expression="."/>
                        <field name="paras" expression="p"/>
                        <field name="unstored" expression="p" store="no"/>
                        <field name="ws" expression="p" analyzer="ws"/>
                    </text>
                    <text field="named" qname="p"/>
                </lucene>
            </index>
        </collection>)
    let $_ := xmldb:create-collection("/db", substring-after($pc:COLLECTION, "/db/"))
    return (
        xmldb:store($pc:COLLECTION, "compact.xml", $pc:COMPACT),
        xmldb:store($pc:COLLECTION, "pretty.xml", $pc:PRETTY)
    )
};

declare
    %test:tearDown
function pc:teardown() {
    xmldb:remove($pc:COLLECTION),
    xmldb:remove("/db/system/config" || $pc:COLLECTION)
};

declare function pc:hit($doc as xs:string, $query as xs:string) as element(div)? {
    doc($pc:COLLECTION || "/" || $doc)/div[ft:query(., $query)]
};

(: the index's own text: inline elements join their neighbors, ignored elements are left out, and the heading is a
 : block of its own, which the sentence after it doesn't run into :)
declare
    %test:assertEquals("The treaty was signed in Paris.", "It was ratified.")
function pc:index-text() {
    ft:passages(pc:hit("compact.xml", "treaty OR ratified"), (), map { "as": "map" })?text ! normalize-space()
};

declare
    %test:assertEquals("treaty", "Paris")
function pc:index-text-matches() {
    let $p := ft:passages(pc:hit("compact.xml", "treaty paris"), (), map { "as": "map", "max": 1 })
    for $m in $p?matches?*
    return substring($p?text, $m?start - $p?start + 1, $m?end - $m?start)
};

(: a field without an expression stores the index's own text :)
declare
    %test:assertEquals("The treaty was signed in Paris.")
function pc:field-without-expression() {
    ft:passages(pc:hit("compact.xml", "no-expr:paris"), "no-expr", map { "as": "map" })?text ! normalize-space()
};

(: a field without an expression that is not stored: its text is the node's, as indexed :)
declare
    %test:assertEquals("The treaty was signed in Paris.")
function pc:unstored-field-without-expression() {
    ft:passages(pc:hit("compact.xml", "no-expr-unstored:paris"), "no-expr-unstored", map { "as": "map" })?text ! normalize-space()
};

(: a field with an expression that is not stored has no text :)
declare
    %test:assertEmpty
function pc:unstored-field-with-expression() {
    ft:passages(pc:hit("compact.xml", "unstored:paris"), "unstored")
};

(: expression="." is the string value: nothing separates the elements, so "Report" and "The" are one word to the
 : index; the block starts stored beside the value still end the heading's sentence there :)
declare
    %test:assertEquals("The treaty was signed in Paris.", "0")
function pc:string-value-field() {
    ft:passages(pc:hit("compact.xml", "dot:paris"), "dot", map { "as": "map" })?text,
    string(count(pc:hit("compact.xml", "dot:report")))
};

(: a field with several values: each passage says which :)
declare
    %test:assertEquals("Nothing else.", 2)
function pc:several-values() {
    let $p := ft:passages(pc:hit("compact.xml", "paras:nothing"), "paras", map { "as": "map" })
    return ($p?text, $p?value)
};

(: a field with its own analyzer: the whitespace analyzer keeps "Paris." as one token :)
declare
    %test:assertEquals("Paris.")
function pc:field-analyzer() {
    let $p := ft:passages(pc:hit("compact.xml", "ws:Paris."), "ws", map { "as": "map" })
    for $m in $p?matches?*
    return substring($p?text, $m?start - $p?start + 1, $m?end - $m?start)
};

(: a named index, queried with ft:query-field (passed on directly: bound to a variable, ft:query-field's result
 : fails a node() parameter's type check, whichever function it is passed to) :)
declare
    %test:assertEquals("Nothing else.")
function pc:named-index() {
    ft:passages(ft:query-field("named", "nothing")[root(.) is doc($pc:COLLECTION || "/compact.xml")], (), map { "as": "map" })?text
};

(: a pretty-printed document: the line breaks and indentation are kept in the text, but don't end sentences :)
declare
    %test:assertEquals("The embassy reported on Kashmir.", "true")
function pc:pretty-printed() {
    let $p := ft:passages(pc:hit("pretty.xml", "kashmir"), (), map { "as": "map" })
    return (normalize-space($p?text), string(contains($p?text, "&#10;")))
};

(: the offsets select the passage in the text it was found in, whichever that is :)
declare
    %test:assertTrue
function pc:element-form-from-index-text() {
    let $p := ft:passages(pc:hit("compact.xml", "ratified"), (), map { "as": "element" })
    return $p/exist:match = "ratified" and normalize-space($p) = "It was ratified."
};

(: without blocks, the heading runs into the sentence after it, in the index's text and in a stored string value :)
declare
    %test:assertEquals("Report The treaty was signed in Paris.", "ReportThe treaty was signed in Paris.")
function pc:blocks-off() {
    ft:passages(pc:hit("compact.xml", "paris"), (), map { "as": "map", "blocks": false() })?text ! normalize-space(),
    ft:passages(pc:hit("compact.xml", "dot:paris"), "dot", map { "as": "map", "blocks": false() })?text
};

(: an element with text of its own has inline elements: a sentence runs across them :)
declare
    %test:assertEquals("The treaty was signed in Paris.")
function pc:mixed-content-is-inline() {
    ft:passages(pc:hit("compact.xml", "dot:signed"), "dot", map { "as": "map" })?text
};

declare
    %test:assertError("XPTY0004")
function pc:blocks-must-be-boolean() {
    ft:passages(pc:hit("compact.xml", "paris"), (), map { "blocks": "yes" })
};
