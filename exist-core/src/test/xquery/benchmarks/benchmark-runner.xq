xquery version "3.1";

(:~
 : Performance benchmark runner for eXist-db.
 : Runs each benchmark query N times and reports timing statistics.
 :
 : Deploy test data first, then execute via the REST API:
 :   curl -u admin: http://localhost:8080/exist/rest/db/bench/benchmark-runner.xq
 :)

declare namespace bench = "http://exist-db.org/xquery/benchmarks";

declare variable $bench:ITERATIONS := 100;
declare variable $bench:WARMUP := 10;

(:~ Run a benchmark: warmup, then measure $iterations runs :)
declare function bench:run($name as xs:string, $query as xs:string, $iterations as xs:integer) as map(*) {
    (: Warmup phase - discard results :)
    for $w in 1 to $bench:WARMUP
    return util:eval($query),

    (: Measurement phase :)
    let $times :=
        for $i in 1 to $iterations
        let $start := util:system-time()
        let $result := util:eval($query)
        let $end := util:system-time()
        return ($end - $start) div xs:dayTimeDuration("PT0.001S")
    let $sorted := sort($times)
    let $count := count($sorted)
    return map {
        "name": $name,
        "mean_ms": round(avg($times) * 1000) div 1000,
        "median_ms": round($sorted[ceiling($count div 2)] * 1000) div 1000,
        "p95_ms": round($sorted[ceiling($count * 0.95)] * 1000) div 1000,
        "min_ms": round(min($times) * 1000) div 1000,
        "max_ms": round(max($times) * 1000) div 1000,
        "iterations": $iterations
    }
};

(:~ Format a single benchmark result as text :)
declare function bench:format($result as map(*)) as xs:string {
    $result?name || ": mean=" || $result?mean_ms || "ms, median=" || $result?median_ms ||
    "ms, p95=" || $result?p95_ms || "ms, min=" || $result?min_ms || "ms, max=" || $result?max_ms ||
    "ms (" || $result?iterations || " iterations)"
};

(: ===== Benchmark Definitions ===== :)

let $benchmarks := (

    (: 1. Simple XPath lookup :)
    bench:run("xpath-lookup", '
        collection("/db/bench")//entry[@id = "item-42"]
    ', $bench:ITERATIONS),

    (: 2. Range query :)
    bench:run("range-query", '
        count(collection("/db/bench")//entry[@value > 500])
    ', $bench:ITERATIONS),

    (: 3. FLWOR expression :)
    bench:run("flwor-complex", '
        for $entry in collection("/db/bench")//entry
        let $name := $entry/name/string()
        let $value := xs:integer($entry/value)
        where $value > 100 and $value < 900
        order by $value descending
        return <result name="{$name}" value="{$value}"/>
    ', $bench:ITERATIONS),

    (: 4. Aggregation :)
    bench:run("aggregation", '
        let $entries := collection("/db/bench")//entry
        return map {
            "count": count($entries),
            "sum": sum($entries/value/xs:integer(.)),
            "avg": avg($entries/value/xs:integer(.)),
            "min": min($entries/value/xs:integer(.)),
            "max": max($entries/value/xs:integer(.))
        }
    ', $bench:ITERATIONS),

    (: 5. Serialization to JSON :)
    bench:run("serialize-json", '
        let $data :=
            array {
                for $entry in collection("/db/bench")//entry
                return map {
                    "id": $entry/@id/string(),
                    "name": $entry/name/string(),
                    "value": xs:integer($entry/value)
                }
            }
        return serialize($data, map { "method": "json", "indent": false() })
    ', $bench:ITERATIONS),

    (: 6. Node construction :)
    bench:run("node-construction", '
        <report generated="{current-dateTime()}">{
            for $i in 1 to 200
            return
                <record id="{$i}">
                    <field1>{ concat("value-", $i) }</field1>
                    <field2>{ $i * $i }</field2>
                    <field3>{ $i mod 7 = 0 }</field3>
                    <nested>
                        <a>{ $i + 1 }</a>
                        <b>{ $i + 2 }</b>
                    </nested>
                </record>
        }</report>
    ', $bench:ITERATIONS),

    (: 7. String operations :)
    bench:run("string-ops", '
        let $text := string-join(
            for $i in 1 to 500 return concat("word", $i),
            " "
        )
        return (
            string-length($text),
            count(tokenize($text, "\s+")),
            string-length(replace($text, "word\d+", "X")),
            string-length(upper-case($text))
        )
    ', $bench:ITERATIONS),

    (: 8. Recursive function :)
    bench:run("recursive-fn", '
        declare function local:fib($n as xs:integer) as xs:integer {
            if ($n le 1) then $n
            else local:fib($n - 1) + local:fib($n - 2)
        };
        for $i in 1 to 20
        return local:fib($i)
    ', $bench:ITERATIONS),

    (: 9. Map/array operations :)
    bench:run("map-array-ops", '
        let $data := map:merge(
            for $i in 1 to 100
            return map:entry("key-" || $i, array { $i, $i * 2, $i * 3 })
        )
        return (
            map:size($data),
            sum(for $key in map:keys($data) return array:size($data($key))),
            map:contains($data, "key-50")
        )
    ', $bench:ITERATIONS),

    (: 10. Typeswitch :)
    bench:run("typeswitch", '
        declare function local:process($items as item()*) as xs:string* {
            for $item in $items
            return
                typeswitch ($item)
                    case xs:integer return "int:" || $item
                    case xs:string return "str:" || $item
                    case xs:boolean return "bool:" || $item
                    case xs:double return "dbl:" || $item
                    case element() return "elem:" || local-name($item)
                    default return "other"
        };
        local:process((1, "hello", true(), 3.14, <test/>, 42, "world", false(), 2.71, <foo/>))
    ', $bench:ITERATIONS)
)

(: ===== Output ===== :)

return
    <benchmark-results timestamp="{current-dateTime()}"
                       iterations="{$bench:ITERATIONS}"
                       warmup="{$bench:WARMUP}"
                       exist-version="{system:get-version()}"
                       build="{system:get-build()}">
    {
        for $result in $benchmarks
        return
            <benchmark name="{$result?name}"
                       mean_ms="{$result?mean_ms}"
                       median_ms="{$result?median_ms}"
                       p95_ms="{$result?p95_ms}"
                       min_ms="{$result?min_ms}"
                       max_ms="{$result?max_ms}"/>
    }
    </benchmark-results>
