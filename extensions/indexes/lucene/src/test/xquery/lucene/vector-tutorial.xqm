xquery version "3.1";

(:~
 : The examples of the "Working with vectors in eXist-db XQuery" tutorial, with the results the tutorial states.
 : Scratch check, not for a PR.
 :)
module namespace vt="http://exist-db.org/xquery/lucene/vector-tutorial/test";

declare namespace test="http://exist-db.org/xquery/xqsuite";

import module namespace vector="http://exist-db.org/xquery/vector";

declare variable $vt:C := "/db/vector-ops";

declare
    %test:setUp
function vt:setup() {
    let $config :=
        <collection xmlns="http://exist-db.org/collection-config/1.0">
            <index xmlns:xs="http://www.w3.org/2001/XMLSchema">
                <lucene>
                    <text qname="title"/>
                    <text qname="article">
                        <vector-field name="topic" expression="topic" dimension="4"
                            similarity="cosine" encoding="text"/>
                    </text>
                </lucene>
            </index>
        </collection>
    let $articles :=
        <articles>
            <article id="a1"><title>Treaty of friendship with Tonga</title><topic>0.9 0.3 0.1 0.0</topic></article>
            <article id="a2"><title>Trade agreement negotiations</title><topic>0.6 0.8 0.0 0.1</topic></article>
            <article id="a3"><title>Phosphate exports from Nauru</title><topic>0.1 0.9 0.0 0.3</topic></article>
            <article id="a4"><title>Access to the naval base</title><topic>0.4 0.1 0.9 0.0</topic></article>
            <article id="a5"><title>Nuclear testing in the Pacific</title><topic>0.2 0.0 0.7 0.7</topic></article>
            <article id="a6"><title>Fisheries research cooperation</title><topic>0.1 0.4 0.0 0.9</topic></article>
        </articles>
    return (
        xmldb:create-collection("/db/system/config/db", "vector-ops"),
        xmldb:store("/db/system/config/db/vector-ops", "collection.xconf", $config),
        xmldb:create-collection("/db", "vector-ops"),
        xmldb:store("/db/vector-ops", "articles.xml", $articles),
        xmldb:reindex("/db/vector-ops")
    )
};

declare
    %test:tearDown
function vt:teardown() {
    xmldb:remove($vt:C),
    xmldb:remove("/db/system/config/db/vector-ops")
};

declare
    %test:assertEquals("0.8136 0.78 0.9539 0.6 0.8")
function vt:task2() {
    let $treaty := [0.9, 0.3, 0.1, 0.0]
    let $trade := [0.6, 0.8, 0.0, 0.1]
    return string-join((
        round(vector:cosine($treaty, $trade), 4),
        round(vector:dot-product($treaty, $trade), 4),
        round(vector:magnitude($treaty), 4),
        vector:normalize([3, 4])?*), " ")
};

declare
    %test:assertEquals("a1: 0.9, 0.3, 0.1, 0", "a2: 0.6, 0.8, 0, 0.1", "a3: 0.1, 0.9, 0, 0.3", "a4: 0.4, 0.1, 0.9, 0", "a5: 0.2, 0, 0.7, 0.7", "a6: 0.1, 0.4, 0, 0.9")
function vt:task3() {
    for $article in collection($vt:C)//article
    return $article/@id || ": " || string-join(ft:vector($article, "topic")?* ! round(., 2), ", ")
};

declare
    %test:assertEquals("Phosphate exports from Nauru (0.8449)", "Treaty of friendship with Tonga (0.8136)", "Fisheries research cooperation (0.4724)")
function vt:task4() {
    let $articles := collection($vt:C)//article
    let $source := $articles[@id = "a2"]
    let $vector := ft:vector($source, "topic")
    for $hit in $articles[ft:query-vector(., $vector, 4)] except $source
    let $similarity := vector:cosine($vector, ft:vector($hit, "topic"))
    order by $similarity descending
    return $hit/title || " (" || round($similarity, 4) || ")"
};

declare
    %test:assertEquals("Treaty of friendship with Tonga 0.9845", "Trade agreement negotiations 0.89", "Phosphate exports from Nauru 0.5157", "Fisheries research cooperation 0.2711")
function vt:task5() {
    let $query := [1.0, 0.5, 0.0, 0.0]
    let $candidates := collection($vt:C)//title[ft:query(., "treaty OR trade OR exports OR fisheries")]/..
    for $article in $candidates
    let $score := vector:cosine($query, ft:vector($article, "topic"))
    order by $score descending
    return $article/title || " " || round($score, 4)
};

declare
    %test:assertEquals("Trade agreement negotiations 0.9625", "Phosphate exports from Nauru 0.9583", "Treaty of friendship with Tonga 0.6352", "Fisheries research cooperation 0.5959", "Access to the naval base 0.2416", "Nuclear testing in the Pacific 0.221")
function vt:task6-centroid() {
    let $articles := collection($vt:C)//article
    let $liked := $articles[@id = ("a2", "a3")]
    let $centroid := vector:mean($liked ! ft:vector(., "topic"))
    for $article in $articles
    let $similarity := vector:cosine($centroid, ft:vector($article, "topic"))
    order by $similarity descending
    return $article/title || " " || round($similarity, 4)
};

declare
    %test:assertEquals("Treaty of friendship with Tonga 0.9769", "Trade agreement negotiations 0.8397", "Access to the naval base 0.6522", "Phosphate exports from Nauru 0.4712", "Nuclear testing in the Pacific 0.3706", "Fisheries research cooperation 0.2506")
function vt:task6-refine() {
    let $articles := collection($vt:C)//article
    let $query := [1.0, 0.5, 0.0, 0.0]
    let $example := ft:vector($articles[@id = "a4"], "topic")
    let $refined := vector:mean(($query, $example), (0.7, 0.3))
    for $article in $articles
    let $similarity := vector:cosine($refined, ft:vector($article, "topic"))
    order by $similarity descending
    return $article/title || " " || round($similarity, 4)
};

declare
    %test:assertEquals("different dimensions: EXVECTOR0005")
function vt:errors() {
    try {
        vector:cosine([1, 2], [1, 2, 3])
    } catch vector:EXVECTOR0005 {
        "different dimensions: " || local-name-from-QName($err:code)
    }
};
