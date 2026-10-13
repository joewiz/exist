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

import org.exist.dom.memtree.DocumentImpl;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.util.serializer.DOMSerializer;
import org.openjdk.jmh.annotations.*;
import org.w3c.dom.Node;

import javax.xml.transform.OutputKeys;
import java.io.StringWriter;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for XML serialization performance.
 * Builds in-memory documents and serializes them via DOMSerializer.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class SerializerBenchmark {

    private Node smallDoc;
    private Node mediumDoc;
    private Node largeDoc;
    private Properties xmlProperties;
    private Properties htmlProperties;

    @Setup(Level.Trial)
    public void setUp() {
        smallDoc = buildDocument(10);
        mediumDoc = buildDocument(100);
        largeDoc = buildDocument(1000);

        xmlProperties = new Properties();
        xmlProperties.setProperty(OutputKeys.METHOD, "xml");
        xmlProperties.setProperty(OutputKeys.INDENT, "no");
        xmlProperties.setProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

        htmlProperties = new Properties();
        htmlProperties.setProperty(OutputKeys.METHOD, "html");
        htmlProperties.setProperty(OutputKeys.INDENT, "no");
    }

    private Node buildDocument(final int numElements) {
        final MemTreeBuilder builder = new MemTreeBuilder();
        builder.startDocument();
        builder.startElement("", "root", "root", null);

        for (int i = 0; i < numElements; i++) {
            builder.startElement("", "entry", "entry", null);

            builder.startElement("", "id", "id", null);
            builder.characters(String.valueOf(i));
            builder.endElement();

            builder.startElement("", "title", "title", null);
            builder.characters("Entry number " + i + " with some longer text content for realistic sizing");
            builder.endElement();

            builder.startElement("", "value", "value", null);
            builder.characters(String.valueOf(i * 3.14159));
            builder.endElement();

            builder.startElement("", "description", "description", null);
            builder.characters("This is a description for entry " + i +
                    ". It contains multiple sentences to simulate real-world XML content. " +
                    "The quick brown fox jumps over the lazy dog. " +
                    "Lorem ipsum dolor sit amet, consectetur adipiscing elit.");
            builder.endElement();

            builder.endElement(); // entry
        }

        builder.endElement(); // root
        builder.endDocument();

        final DocumentImpl doc = builder.getDocument();
        return doc.getDocumentElement();
    }

    @Benchmark
    public String serializeSmallXml() throws Exception {
        final StringWriter writer = new StringWriter(4096);
        final DOMSerializer serializer = new DOMSerializer(writer, xmlProperties);
        serializer.serialize(smallDoc);
        return writer.toString();
    }

    @Benchmark
    public String serializeMediumXml() throws Exception {
        final StringWriter writer = new StringWriter(65536);
        final DOMSerializer serializer = new DOMSerializer(writer, xmlProperties);
        serializer.serialize(mediumDoc);
        return writer.toString();
    }

    @Benchmark
    public String serializeLargeXml() throws Exception {
        final StringWriter writer = new StringWriter(524288);
        final DOMSerializer serializer = new DOMSerializer(writer, xmlProperties);
        serializer.serialize(largeDoc);
        return writer.toString();
    }

    @Benchmark
    public String serializeWithIndent() throws Exception {
        final Properties indentProps = new Properties();
        indentProps.setProperty(OutputKeys.METHOD, "xml");
        indentProps.setProperty(OutputKeys.INDENT, "yes");
        indentProps.setProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

        final StringWriter writer = new StringWriter(65536);
        final DOMSerializer serializer = new DOMSerializer(writer, indentProps);
        serializer.serialize(mediumDoc);
        return writer.toString();
    }

    @Benchmark
    public Node buildDocument100() {
        return buildDocument(100);
    }

    @Benchmark
    public Node buildDocument1000() {
        return buildDocument(1000);
    }
}
