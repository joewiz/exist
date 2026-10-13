xquery version "3.1";

(:~
 : Setup benchmark test data in /db/bench.
 : Run via REST API:
 :   curl -u admin: http://localhost:8080/exist/rest/db/setup-test-data.xq
 :)

(: Create the benchmark collection :)
let $_ := xmldb:create-collection("/db", "bench")

(: Store test documents with varied content :)
let $docs :=
    for $doc-num in 1 to 10
    let $content :=
        <dataset id="dataset-{$doc-num}" generated="{current-dateTime()}">
        {
            for $i in (($doc-num - 1) * 100 + 1) to ($doc-num * 100)
            return
                <entry id="item-{$i}" type="{('article','book','report')[$i mod 3 + 1]}"
                       date="{xs:date('2020-01-01') + xs:dayTimeDuration('P' || $i || 'D')}"
                       value="{$i}">
                    <name>Entry {$i}</name>
                    <author>{('Smith','Jones','Williams','Brown','Davis')[$i mod 5 + 1]}</author>
                    <title>Title for entry number {$i} in dataset {$doc-num}</title>
                    <value>{$i * ($i mod 13 + 1)}</value>
                    <description>This is the description for entry {$i}. It contains
                    some text content that is used for full-text and string benchmarks.
                    The quick brown fox jumps over the lazy dog. Pack my box with
                    five dozen liquor jugs.</description>
                    <tags>
                    {
                        for $t in 1 to ($i mod 5 + 1)
                        return <tag>tag-{$t}</tag>
                    }
                    </tags>
                </entry>
        }
        </dataset>
    return xmldb:store("/db/bench", "data-" || $doc-num || ".xml", $content)

(: Store the benchmark runner :)
let $runner := util:binary-doc(xs:anyURI("benchmark-runner.xq"))

return
    <setup-complete>
        <collection>/db/bench</collection>
        <documents-stored>{count($docs)}</documents-stored>
        <total-entries>{count(collection("/db/bench")//entry)}</total-entries>
    </setup-complete>
