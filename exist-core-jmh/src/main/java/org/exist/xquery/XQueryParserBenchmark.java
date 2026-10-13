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

import antlr.collections.AST;
import org.exist.xquery.parser.XQueryLexer;
import org.exist.xquery.parser.XQueryParser;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.StringReader;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for XQuery parser performance.
 * Tests lexer + parser (AST generation) without requiring BrokerPool.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class XQueryParserBenchmark {

    private static final String SIMPLE_FLWOR =
            "for $i in 1 to 100 let $x := $i * 2 where $x > 50 return $x";

    private static final String COMPLEX_QUERY =
            "xquery version \"3.1\";\n" +
            "declare namespace tei = \"http://www.tei-c.org/ns/1.0\";\n" +
            "declare function local:process($nodes as node()*) as node()* {\n" +
            "    for $node in $nodes\n" +
            "    return\n" +
            "        typeswitch ($node)\n" +
            "            case element(tei:p) return\n" +
            "                <p class=\"tei-p\">{ local:process($node/node()) }</p>\n" +
            "            case element(tei:hi) return\n" +
            "                if ($node/@rend = 'bold') then\n" +
            "                    <strong>{ local:process($node/node()) }</strong>\n" +
            "                else if ($node/@rend = 'italic') then\n" +
            "                    <em>{ local:process($node/node()) }</em>\n" +
            "                else\n" +
            "                    <span>{ local:process($node/node()) }</span>\n" +
            "            case element() return\n" +
            "                element { node-name($node) } {\n" +
            "                    $node/@*,\n" +
            "                    local:process($node/node())\n" +
            "                }\n" +
            "            case text() return $node\n" +
            "            default return ()\n" +
            "};\n" +
            "let $doc := <root>\n" +
            "    <items>\n" +
            "        { for $i in 1 to 50\n" +
            "          let $label := concat('item-', $i)\n" +
            "          let $value := $i * $i\n" +
            "          order by $value descending\n" +
            "          return\n" +
            "              <item id=\"{$label}\" value=\"{$value}\">\n" +
            "                  <name>{ $label }</name>\n" +
            "                  <data>{ string-join(for $j in 1 to $i return string($j), ', ') }</data>\n" +
            "              </item>\n" +
            "        }\n" +
            "    </items>\n" +
            "</root>\n" +
            "let $filtered := $doc//item[@value > 100]\n" +
            "let $grouped := for $item in $filtered\n" +
            "    group by $decade := ($item/@value idiv 1000)\n" +
            "    order by $decade\n" +
            "    return <group decade=\"{$decade}\" count=\"{count($item)}\"/>\n" +
            "return\n" +
            "    <result total=\"{count($filtered)}\">\n" +
            "        { $grouped }\n" +
            "    </result>";

    private static final String XPATH_EXPRESSION =
            "//entry[@type='article'][author[contains(., 'Smith')]]" +
            "/preceding-sibling::entry[position() <= 3]" +
            "/(title | subtitle)" +
            "[string-length(.) > 10]" +
            "/normalize-space(.)";

    private static final String FUNCTION_DECLARATIONS =
            "declare function local:fibonacci($n as xs:integer) as xs:integer {\n" +
            "    if ($n le 1) then $n\n" +
            "    else local:fibonacci($n - 1) + local:fibonacci($n - 2)\n" +
            "};\n" +
            "declare function local:factorial($n as xs:integer) as xs:integer {\n" +
            "    if ($n le 1) then 1\n" +
            "    else $n * local:factorial($n - 1)\n" +
            "};\n" +
            "declare function local:gcd($a as xs:integer, $b as xs:integer) as xs:integer {\n" +
            "    if ($b = 0) then $a\n" +
            "    else local:gcd($b, $a mod $b)\n" +
            "};\n" +
            "declare function local:is-prime($n as xs:integer) as xs:boolean {\n" +
            "    if ($n lt 2) then false()\n" +
            "    else if ($n = 2) then true()\n" +
            "    else if ($n mod 2 = 0) then false()\n" +
            "    else\n" +
            "        let $limit := xs:integer(math:sqrt($n))\n" +
            "        return not(some $i in (3 to $limit) satisfies $n mod $i = 0)\n" +
            "};\n" +
            "for $n in 1 to 20\n" +
            "return map {\n" +
            "    'n': $n,\n" +
            "    'fib': local:fibonacci($n),\n" +
            "    'fact': local:factorial($n),\n" +
            "    'prime': local:is-prime($n)\n" +
            "}";

    @Benchmark
    public AST parseSimpleFlwor() throws Exception {
        final XQueryLexer lexer = new XQueryLexer(new StringReader(SIMPLE_FLWOR));
        final XQueryParser parser = new XQueryParser(lexer);
        parser.xpath();
        return parser.getAST();
    }

    @Benchmark
    public AST parseComplexQuery() throws Exception {
        final XQueryLexer lexer = new XQueryLexer(new StringReader(COMPLEX_QUERY));
        final XQueryParser parser = new XQueryParser(lexer);
        parser.xpath();
        return parser.getAST();
    }

    @Benchmark
    public AST parseXPathExpression() throws Exception {
        final XQueryLexer lexer = new XQueryLexer(new StringReader(XPATH_EXPRESSION));
        final XQueryParser parser = new XQueryParser(lexer);
        parser.xpath();
        return parser.getAST();
    }

    @Benchmark
    public AST parseFunctionDeclarations() throws Exception {
        final XQueryLexer lexer = new XQueryLexer(new StringReader(FUNCTION_DECLARATIONS));
        final XQueryParser parser = new XQueryParser(lexer);
        parser.xpath();
        return parser.getAST();
    }
}
