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
import org.exist.collections.triggers.TriggerException;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.LockException;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.value.Sequence;
import org.openjdk.jmh.annotations.*;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for full XQuery compilation and evaluation.
 * Starts an embedded eXist-db instance and runs queries against stored data.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class XQueryEvalBenchmark {

    private ExistEmbeddedServer server;
    private BrokerPool pool;

    // Queries
    private static final String FLWOR_QUERY =
            "for $entry in collection('/db/bench')//entry\n" +
            "let $name := $entry/name/string()\n" +
            "let $value := xs:integer($entry/value)\n" +
            "where $value > 100 and $value < 900\n" +
            "order by $value descending\n" +
            "return <result name=\"{$name}\" value=\"{$value}\"/>";

    private static final String AGGREGATION_QUERY =
            "let $entries := collection('/db/bench')//entry\n" +
            "return map {\n" +
            "    'count': count($entries),\n" +
            "    'sum': sum($entries/value/xs:integer(.)),\n" +
            "    'avg': avg($entries/value/xs:integer(.))\n" +
            "}";

    private static final String CONSTRUCTION_QUERY =
            "<report>{\n" +
            "    for $i in 1 to 200\n" +
            "    return\n" +
            "        <record id=\"{$i}\">\n" +
            "            <field1>{ concat('value-', $i) }</field1>\n" +
            "            <field2>{ $i * $i }</field2>\n" +
            "            <nested><a>{ $i + 1 }</a><b>{ $i + 2 }</b></nested>\n" +
            "        </record>\n" +
            "}</report>";

    private static final String STRING_OPS_QUERY =
            "let $text := string-join(\n" +
            "    for $i in 1 to 500 return concat('word', $i),\n" +
            "    ' '\n" +
            ")\n" +
            "return (\n" +
            "    string-length($text),\n" +
            "    count(tokenize($text, '\\s+')),\n" +
            "    string-length(replace($text, 'word\\d+', 'X')),\n" +
            "    string-length(upper-case($text))\n" +
            ")";

    private static final String RECURSIVE_QUERY =
            "declare function local:fib($n as xs:integer) as xs:integer {\n" +
            "    if ($n le 1) then $n\n" +
            "    else local:fib($n - 1) + local:fib($n - 2)\n" +
            "};\n" +
            "for $i in 1 to 20\n" +
            "return local:fib($i)";

    private static final String MAP_ARRAY_QUERY =
            "let $data := map:merge(\n" +
            "    for $i in 1 to 100\n" +
            "    return map:entry('key-' || $i, array { $i, $i * 2, $i * 3 })\n" +
            ")\n" +
            "return (\n" +
            "    map:size($data),\n" +
            "    sum(for $key in map:keys($data) return array:size($data($key))),\n" +
            "    map:contains($data, 'key-50')\n" +
            ")";

    private static final String SERIALIZE_JSON_QUERY =
            "let $data :=\n" +
            "    array {\n" +
            "        for $entry in collection('/db/bench')//entry\n" +
            "        return map {\n" +
            "            'id': $entry/@id/string(),\n" +
            "            'name': $entry/name/string(),\n" +
            "            'value': xs:integer($entry/value)\n" +
            "        }\n" +
            "    }\n" +
            "return serialize($data, map { 'method': 'json', 'indent': false() })";

    private static final String TYPESWITCH_QUERY =
            "declare function local:process($items as item()*) as xs:string* {\n" +
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
            "local:process((1, 'hello', true(), 3.14, <test/>, 42, 'world', false(), 2.71, <foo/>))";

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        server = new ExistEmbeddedServer(true, true);
        server.startDb();
        pool = server.getBrokerPool();

        // Store test data
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn transaction = pool.getTransactionManager().beginTransaction()) {

            // Create /db/bench collection
            final Collection benchCol = broker.getOrCreateCollection(transaction, XmldbURI.create("/db/bench"));
            broker.saveCollection(transaction, benchCol);

            // Store 10 documents with 100 entries each
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

    @TearDown(Level.Trial)
    public void tearDown() {
        if (server != null) {
            server.stopDb();
        }
    }

    private Sequence evalQuery(final String query) throws Exception {
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final XQuery xqueryService = pool.getXQueryService();
            final XQueryContext context = new XQueryContext(pool);
            final CompiledXQuery compiled = xqueryService.compile(context, query);
            return xqueryService.execute(broker, compiled, null);
        }
    }

    @Benchmark
    public Sequence evalFlwor() throws Exception {
        return evalQuery(FLWOR_QUERY);
    }

    @Benchmark
    public Sequence evalAggregation() throws Exception {
        return evalQuery(AGGREGATION_QUERY);
    }

    @Benchmark
    public Sequence evalConstruction() throws Exception {
        return evalQuery(CONSTRUCTION_QUERY);
    }

    @Benchmark
    public Sequence evalStringOps() throws Exception {
        return evalQuery(STRING_OPS_QUERY);
    }

    @Benchmark
    public Sequence evalRecursive() throws Exception {
        return evalQuery(RECURSIVE_QUERY);
    }

    @Benchmark
    public Sequence evalMapArray() throws Exception {
        return evalQuery(MAP_ARRAY_QUERY);
    }

    @Benchmark
    public Sequence evalSerializeJson() throws Exception {
        return evalQuery(SERIALIZE_JSON_QUERY);
    }

    @Benchmark
    public Sequence evalTypeswitch() throws Exception {
        return evalQuery(TYPESWITCH_QUERY);
    }
}
