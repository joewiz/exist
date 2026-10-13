/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.xquery;

import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.value.Sequence;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertTrue;

/**
 * Performance benchmark test for XQuery evaluation.
 * Measures parse + compile + execute times for representative queries.
 * Run with: mvn test -pl exist-core -Dtest=XQueryPerformanceTest -Ddependency-check.skip=true -Ddocker=false
 */
public class XQueryPerformanceTest {

    private static ExistEmbeddedServer server;
    private static BrokerPool pool;

    private static final int WARMUP = 5;
    private static final int ITERATIONS = 30;

    @BeforeClass
    public static void setUp() throws Exception {
        server = new ExistEmbeddedServer(true, true);
        server.startDb();
        pool = server.getBrokerPool();

        // Store test data
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn transaction = pool.getTransactionManager().beginTransaction()) {

            final Collection benchCol = broker.getOrCreateCollection(transaction, XmldbURI.create("/db/bench"));
            broker.saveCollection(transaction, benchCol);

            for (int docNum = 1; docNum <= 10; docNum++) {
                final StringBuilder sb = new StringBuilder();
                sb.append("<dataset id=\"dataset-").append(docNum).append("\">\n");
                for (int i = (docNum - 1) * 100 + 1; i <= docNum * 100; i++) {
                    sb.append("  <entry id=\"item-").append(i).append("\" value=\"").append(i).append("\">\n");
                    sb.append("    <name>Entry ").append(i).append("</name>\n");
                    sb.append("    <value>").append(i * (i % 13 + 1)).append("</value>\n");
                    sb.append("    <description>Description for entry ").append(i).append("</description>\n");
                    sb.append("  </entry>\n");
                }
                sb.append("</dataset>");

                broker.storeDocument(transaction, XmldbURI.create("data-" + docNum + ".xml"),
                        new StringInputSource(sb.toString()), MimeType.XML_TYPE, benchCol);
            }

            transaction.commit();
        }
    }

    @AfterClass
    public static void tearDown() {
        if (server != null) {
            server.stopDb();
        }
    }

    private static final Map<String, String> QUERIES = new LinkedHashMap<>() {{
        put("flwor", "for $entry in collection('/db/bench')//entry\n" +
                "let $name := $entry/name/string()\n" +
                "let $value := xs:integer($entry/value)\n" +
                "where $value > 100 and $value < 900\n" +
                "order by $value descending\n" +
                "return <result name=\"{$name}\" value=\"{$value}\"/>");

        put("aggregation", "let $entries := collection('/db/bench')//entry\n" +
                "return map {\n" +
                "    'count': count($entries),\n" +
                "    'sum': sum($entries/value/xs:integer(.)),\n" +
                "    'avg': avg($entries/value/xs:integer(.))\n" +
                "}");

        put("construction", "<report>{\n" +
                "    for $i in 1 to 200\n" +
                "    return\n" +
                "        <record id=\"{$i}\">\n" +
                "            <field1>{ concat('value-', $i) }</field1>\n" +
                "            <field2>{ $i * $i }</field2>\n" +
                "            <nested><a>{ $i + 1 }</a><b>{ $i + 2 }</b></nested>\n" +
                "        </record>\n" +
                "}</report>");

        put("string-ops", "let $text := string-join(\n" +
                "    for $i in 1 to 500 return concat('word', $i),\n" +
                "    ' '\n" +
                ")\n" +
                "return (\n" +
                "    string-length($text),\n" +
                "    count(tokenize($text, '\\s+')),\n" +
                "    string-length(replace($text, 'word\\d+', 'X')),\n" +
                "    string-length(upper-case($text))\n" +
                ")");

        put("recursive", "declare function local:fib($n as xs:integer) as xs:integer {\n" +
                "    if ($n le 1) then $n\n" +
                "    else local:fib($n - 1) + local:fib($n - 2)\n" +
                "};\n" +
                "for $i in 1 to 20\n" +
                "return local:fib($i)");

        put("map-array", "let $data := map:merge(\n" +
                "    for $i in 1 to 100\n" +
                "    return map:entry('key-' || $i, array { $i, $i * 2, $i * 3 })\n" +
                ")\n" +
                "return (\n" +
                "    map:size($data),\n" +
                "    sum(for $key in map:keys($data) return array:size($data($key))),\n" +
                "    map:contains($data, 'key-50')\n" +
                ")");

        put("serialize-json", "let $data :=\n" +
                "    array {\n" +
                "        for $entry in collection('/db/bench')//entry\n" +
                "        return map {\n" +
                "            'id': $entry/@id/string(),\n" +
                "            'name': $entry/name/string(),\n" +
                "            'value': xs:integer($entry/value)\n" +
                "        }\n" +
                "    }\n" +
                "return serialize($data, map { 'method': 'json', 'indent': false() })");

        put("typeswitch", "declare function local:process($items as item()*) as xs:string* {\n" +
                "    for $item in $items\n" +
                "    return\n" +
                "        typeswitch ($item)\n" +
                "            case xs:integer return 'int:' || $item\n" +
                "            case xs:string return 'str:' || $item\n" +
                "            case xs:boolean return 'bool:' || $item\n" +
                "            case xs:double return 'dbl:' || $item\n" +
                "            case element() return 'elem:' || local-name($item)\n" +
                "            default return 'other'\n" +
                "};\n" +
                "local:process((1, 'hello', true(), 3.14, <test/>, 42, 'world', false(), 2.71, <foo/>))");
    }};

    @Test
    public void runBenchmarks() throws Exception {
        final StringBuilder report = new StringBuilder();
        report.append("\n=== XQuery Evaluation Benchmark Results ===\n");
        report.append(String.format("%-20s %10s %10s %10s %10s %10s\n",
                "Query", "Mean(ms)", "Median(ms)", "P95(ms)", "Min(ms)", "Max(ms)"));
        report.append("=".repeat(75)).append("\n");

        for (final Map.Entry<String, String> entry : QUERIES.entrySet()) {
            final String name = entry.getKey();
            final String query = entry.getValue();

            // Warmup
            for (int w = 0; w < WARMUP; w++) {
                evalQuery(query);
            }

            // Measurement
            final double[] times = new double[ITERATIONS];
            for (int i = 0; i < ITERATIONS; i++) {
                final long start = System.nanoTime();
                evalQuery(query);
                final long end = System.nanoTime();
                times[i] = (end - start) / 1_000_000.0;
            }

            Arrays.sort(times);
            final double mean = Arrays.stream(times).average().orElse(0);
            final double median = times[times.length / 2];
            final double p95 = times[(int) Math.ceil(times.length * 0.95) - 1];
            final double min = times[0];
            final double max = times[times.length - 1];

            report.append(String.format("%-20s %10.3f %10.3f %10.3f %10.3f %10.3f\n",
                    name, mean, median, p95, min, max));
        }

        report.append("=".repeat(75)).append("\n");
        System.out.println(report);

        // Always pass — this is a benchmark, not a correctness test
        assertTrue("Benchmarks completed", true);
    }

    private Sequence evalQuery(final String query) throws Exception {
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final XQuery xqueryService = pool.getXQueryService();
            final XQueryContext context = new XQueryContext(pool);
            final CompiledXQuery compiled = xqueryService.compile(context, query);
            return xqueryService.execute(broker, compiled, null);
        }
    }
}
