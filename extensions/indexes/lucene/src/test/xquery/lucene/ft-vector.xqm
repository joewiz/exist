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
 : ft:vector($node, $field) reads back the vector a vector field stored for a node. The vectors here are given in
 : the documents (encoding="text"), so no embedding model is needed, and every value is exactly representable as a
 : float, so the vectors read back must equal them exactly.
 :)
module namespace fv="http://exist-db.org/xquery/lucene/ft-vector/test";

declare namespace test="http://exist-db.org/xquery/xqsuite";

declare variable $fv:COLL := "/db/test-ft-vector";
declare variable $fv:CONF_COLL := "/db/system/config/db/test-ft-vector";

declare variable $fv:XCONF :=
    <collection xmlns="http://exist-db.org/collection-config/1.0">
        <index xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <lucene>
                <text qname="article">
                    <vector-field name="embedding" expression="embedding" dimension="4" similarity="cosine" encoding="text"/>
                </text>
            </lucene>
        </index>
    </collection>;

declare variable $fv:DATA :=
    <articles>
        <article id="a"><embedding>0.25 -0.5 0.75 1.0</embedding></article>
        <article id="b"><embedding>1.0 0.0 0.0 0.0</embedding></article>
        <article id="c"><embedding>0.5 0.5 0.0 0.0</embedding></article>
        <article id="d"><embedding>0.0 0.0 1.0 0.0</embedding></article>
        <article id="none"><title>No embedding</title></article>
    </articles>;

declare
    %test:setUp
function fv:setup() {
    let $_ := xmldb:create-collection("/db/system", "config")
    let $_ := xmldb:create-collection("/db/system/config", "db")
    let $_ := xmldb:create-collection("/db/system/config/db", "test-ft-vector")
    let $_ := xmldb:store($fv:CONF_COLL, "collection.xconf", $fv:XCONF)
    let $_ := xmldb:create-collection("/db", "test-ft-vector")
    return xmldb:store($fv:COLL, "articles.xml", $fv:DATA)
};

declare
    %test:tearDown
function fv:teardown() {
    xmldb:remove($fv:COLL),
    xmldb:remove($fv:CONF_COLL)
};

declare %private function fv:article($id as xs:string) as element(article) {
    collection($fv:COLL)//article[@id = $id]
};

(:~ Cosine similarity, written out so that these tests need nothing beyond ft:vector. :)
declare %private function fv:cosine($a as array(*), $b as array(*)) as xs:double {
    let $dot := sum(for $i in 1 to array:size($a) return $a($i) * $b($i))
    let $norms := math:sqrt(sum($a?* ! (. * .))) * math:sqrt(sum($b?* ! (. * .)))
    return $dot div $norms
};

(:~ The vector read back equals the one the document gave, member for member. :)
declare
    %test:assertEquals("0.25", "-0.5", "0.75", "1")
function fv:returns-the-stored-vector() {
    ft:vector(fv:article("a"), "embedding")?* ! string(.)
};

(:~ It is an array of xs:double of the field's dimension, like the query vectors ft:query-vector takes. :)
declare
    %test:assertTrue
function fv:is-an-array-of-doubles-of-the-dimension() {
    let $v := ft:vector(fv:article("a"), "embedding")
    return array:size($v) eq 4 and (every $d in $v?* satisfies $d instance of xs:double)
};

(:~ Every indexed node gets its own vector back, the one in its own text. :)
declare
    %test:assertTrue
function fv:each-node-gets-its-own-vector() {
    every $article in collection($fv:COLL)//article[embedding]
    satisfies deep-equal(
        ft:vector($article, "embedding")?*,
        tokenize($article/embedding) ! xs:double(xs:float(.))
    )
};

(:~ A node the field did not index has no vector. :)
declare
    %test:assertEmpty
function fv:node-without-vector-is-empty() {
    ft:vector(fv:article("none"), "embedding")
};

(:~ A field the configuration does not define has no vector. :)
declare
    %test:assertEmpty
function fv:unknown-field-is-empty() {
    ft:vector(fv:article("a"), "no-such-field")
};

(:~ An in-memory node is not indexed, so it has no vector. :)
declare
    %test:assertEmpty
function fv:in-memory-node-is-empty() {
    ft:vector(<article><embedding>1.0 0.0 0.0 0.0</embedding></article>, "embedding")
};

declare
    %test:assertEmpty
function fv:empty-node-is-empty() {
    ft:vector((), "embedding")
};

(:~ Vectors read back can be compared: [1,0,0,0] and [0.5,0.5,0,0] are 45 degrees apart. :)
declare
    %test:assertEquals("0.707107")
function fv:similarity-of-stored-vectors() {
    round(fv:cosine(ft:vector(fv:article("b"), "embedding"), ft:vector(fv:article("c"), "embedding")), 6)
};

(:~ Ranking the read-back vectors by similarity to a query gives the order ft:query-vector returns. :)
declare
    %test:assertTrue
function fv:similarity-ranks-as-query-vector-does() {
    let $query := [0.9, 0.3, 0.1, 0.0]
    let $searched :=
        for $hit in ft:query-vector(collection($fv:COLL)//article, $query, 4)
        order by ft:score($hit) descending
        return string($hit/@id)
    let $ranked :=
        for $article in collection($fv:COLL)//article[embedding]
        order by fv:cosine($query, ft:vector($article, "embedding")) descending
        return string($article/@id)
    return deep-equal($searched, $ranked)
};

(:~ After a reindex the vectors are still there, unchanged. :)
declare
    %test:assertEquals("0.25", "-0.5", "0.75", "1")
function fv:survives-reindex() {
    let $_ := xmldb:reindex($fv:COLL)
    return ft:vector(fv:article("a"), "embedding")?* ! string(.)
};
