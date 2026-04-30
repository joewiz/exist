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

import org.exist.dom.QName;
import org.exist.dom.persistent.NodeProxy;
import org.w3c.dom.Node;

import javax.xml.stream.XMLStreamReader;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implements XQuery 4.0 union node tests (PR2200): a parenthesized list of
 * node tests separated by '|', e.g. {@code child::(title|author)} or
 * {@code child::(comment()|processing-instruction())}. Matches if any
 * branch matches.
 */
public class UnionNodeTest implements NodeTest {

    private final List<NodeTest> tests;
    private int nodeType;

    public UnionNodeTest(final List<NodeTest> tests) {
        this.tests = tests;
        this.nodeType = tests.isEmpty() ? 0 : tests.get(0).getType();
    }

    @Override
    public void setType(final int nodeType) {
        this.nodeType = nodeType;
        for (final NodeTest test : tests) {
            test.setType(nodeType);
        }
    }

    @Override
    public int getType() {
        return nodeType;
    }

    @Override
    public QName getName() {
        return null;
    }

    @Override
    public boolean matches(final NodeProxy proxy) {
        for (final NodeTest test : tests) {
            if (test.matches(proxy)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean matches(final Node node) {
        for (final NodeTest test : tests) {
            if (test.matches(node)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean matches(final XMLStreamReader reader) {
        for (final NodeTest test : tests) {
            if (test.matches(reader)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean matches(final QName name) {
        for (final NodeTest test : tests) {
            if (test.matches(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isWildcardTest() {
        for (final NodeTest test : tests) {
            if (test.isWildcardTest()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return tests.stream().map(Object::toString).collect(Collectors.joining("|", "(", ")"));
    }
}
