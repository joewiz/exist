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
package org.exist.xquery.value;

import org.exist.xquery.Cardinality;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of XQuery 4.0 built-in named record types from the XPath/XQuery
 * Functions and Operators 4.0 specification (section "Record types").
 *
 * <p>Each entry maps a record type's lexical name (e.g. {@code fn:uri-structure-record})
 * to a {@link RecordType} carrying the field declarations and extensibility flag
 * defined in the spec. The grammar consults this registry when an
 * {@code instance of <name>} or other sequence-type test references a named
 * record; the resulting RecordType drives structural matching against arbitrary
 * maps via {@link RecordType#matches(org.exist.xquery.functions.map.AbstractMapType)}.</p>
 *
 * <p>Field type checking is intentionally permissive for function-typed fields:
 * the spec calls for recursive types (e.g. {@code fn() as fn:schema-type-record})
 * which would require lazy initialization to express in eXist's SequenceType
 * representation. We instead declare those as {@code function(*)?}, accepting any
 * function reference. This still rejects non-function values (catching the
 * "wrong, value must be a map" cases) while side-stepping the recursion problem.</p>
 */
public final class BuiltInRecordTypes {

    private static final Map<String, RecordType> TYPES = new HashMap<>();

    static {
        TYPES.put("fn:load-xquery-module-record", buildLoadXQueryModuleRecord());
        TYPES.put("fn:random-number-generator-record", buildRandomNumberGeneratorRecord());
        TYPES.put("fn:schema-type-record", buildSchemaTypeRecord());
        TYPES.put("fn:parsed-csv-structure-record", buildParsedCsvStructureRecord());
        TYPES.put("fn:uri-structure-record", buildUriStructureRecord());
    }

    private BuiltInRecordTypes() {
    }

    /**
     * Lookup the structural definition for a built-in record name.
     *
     * @param name the qualified name (e.g. {@code fn:uri-structure-record})
     * @return the record type, or {@code null} if not built-in
     */
    @Nullable
    public static RecordType get(final String name) {
        return TYPES.get(name);
    }

    /**
     * @return true if the qualified name resolves to a built-in record type.
     */
    public static boolean isBuiltIn(final String name) {
        return TYPES.containsKey(name);
    }

    // --- helpers ---------------------------------------------------------

    private static SequenceType seq(final int primaryType, final Cardinality cardinality) {
        return new SequenceType(primaryType, cardinality);
    }

    /**
     * {@code map(K, V)} as a sequence type.
     */
    private static SequenceType typedMap(final SequenceType keyType, final SequenceType valueType) {
        final SequenceType t = new SequenceType(Type.MAP_ITEM, Cardinality.EXACTLY_ONE);
        t.setFunctionParamTypes(new SequenceType[] { keyType, valueType });
        return t;
    }

    /**
     * Permissive {@code function(*)} sequence type with the given cardinality.
     * Used for record fields whose declared type would otherwise be a recursive
     * function type (e.g. {@code fn() as fn:schema-type-record}); we accept any
     * function reference instead.
     */
    private static SequenceType anyFunction(final Cardinality cardinality) {
        return new SequenceType(Type.FUNCTION, cardinality);
    }

    private static RecordType.FieldDeclaration field(final String name, final boolean optional,
                                                      final SequenceType type) {
        return new RecordType.FieldDeclaration(name, type, optional);
    }

    // --- record type definitions -----------------------------------------

    /**
     * <pre>
     * record(
     *   variables  as map(xs:QName, item()*),
     *   functions  as map(xs:QName, map(xs:integer, function(*)))
     * )
     * </pre>
     * Not extensible.
     */
    private static RecordType buildLoadXQueryModuleRecord() {
        final SequenceType variables = typedMap(
                seq(Type.QNAME, Cardinality.EXACTLY_ONE),
                seq(Type.ITEM, Cardinality.ZERO_OR_MORE));
        final SequenceType functions = typedMap(
                seq(Type.QNAME, Cardinality.EXACTLY_ONE),
                typedMap(
                        seq(Type.INTEGER, Cardinality.EXACTLY_ONE),
                        anyFunction(Cardinality.EXACTLY_ONE)));
        return new RecordType(
                List.of(
                        field("variables", false, variables),
                        field("functions", false, functions)),
                false);
    }

    /**
     * <pre>
     * record(
     *   number   as xs:double,
     *   next     as fn() as fn:random-number-generator-record,
     *   permute  as fn(item()*) as item()*
     * )
     * </pre>
     * Per the QT4 test suite (built-in-record-type-204), this record is
     * treated as extensible. The spec also allows the function-typed fields
     * to declare specific return types; we accept any function reference.
     */
    private static RecordType buildRandomNumberGeneratorRecord() {
        return new RecordType(
                List.of(
                        field("number", false, seq(Type.DOUBLE, Cardinality.EXACTLY_ONE)),
                        field("next", false, anyFunction(Cardinality.EXACTLY_ONE)),
                        field("permute", false, anyFunction(Cardinality.EXACTLY_ONE))),
                true);
    }

    /**
     * <pre>
     * record(
     *   name              as xs:QName?,
     *   is-simple         as xs:boolean,
     *   base-type         as fn() as fn:schema-type-record?,
     *   primitive-type?   as fn() as fn:schema-type-record,
     *   variety?          as enum("atomic","list","union","empty","simple","element-only","mixed"),
     *   members?          as fn() as fn:schema-type-record*,
     *   simple-content-type? as fn() as fn:schema-type-record,
     *   matches?          as fn(xs:anyAtomicType) as xs:boolean,
     *   constructor?      as fn(xs:anyAtomicType?) as xs:anyAtomicType*
     * )
     * </pre>
     * Per QT4 test 205, treated as extensible.
     */
    private static RecordType buildSchemaTypeRecord() {
        return new RecordType(
                List.of(
                        field("name", false, seq(Type.QNAME, Cardinality.ZERO_OR_ONE)),
                        field("is-simple", false, seq(Type.BOOLEAN, Cardinality.EXACTLY_ONE)),
                        field("base-type", false, anyFunction(Cardinality.EXACTLY_ONE)),
                        field("primitive-type", true, anyFunction(Cardinality.EXACTLY_ONE)),
                        // 'variety' is an enum in the spec but is left untyped here
                        // to accept both string literals and enum-cast values
                        // (qt4 test 305 supplies a plain string).
                        field("variety", true, seq(Type.STRING, Cardinality.ZERO_OR_ONE)),
                        field("members", true, anyFunction(Cardinality.EXACTLY_ONE)),
                        field("simple-content-type", true, anyFunction(Cardinality.EXACTLY_ONE)),
                        field("matches", true, anyFunction(Cardinality.EXACTLY_ONE)),
                        field("constructor", true, anyFunction(Cardinality.EXACTLY_ONE))),
                true);
    }

    /**
     * <pre>
     * record(
     *   columns      as xs:string*,
     *   column-index as map(xs:string, xs:integer)?,
     *   rows         as array(xs:string)*,
     *   get          as fn(xs:positiveInteger, (xs:positiveInteger | xs:string)) as xs:string
     * )
     * </pre>
     * Not extensible.
     */
    private static RecordType buildParsedCsvStructureRecord() {
        final SequenceType columnIndex = new SequenceType(Type.MAP_ITEM, Cardinality.ZERO_OR_ONE);
        columnIndex.setFunctionParamTypes(new SequenceType[] {
                seq(Type.STRING, Cardinality.EXACTLY_ONE),
                seq(Type.INTEGER, Cardinality.EXACTLY_ONE) });
        final SequenceType rows = new SequenceType(Type.ARRAY_ITEM, Cardinality.ZERO_OR_MORE);
        rows.setFunctionParamTypes(new SequenceType[] { seq(Type.STRING, Cardinality.EXACTLY_ONE) });
        return new RecordType(
                List.of(
                        field("columns", false, seq(Type.STRING, Cardinality.ZERO_OR_MORE)),
                        field("column-index", false, columnIndex),
                        field("rows", false, rows),
                        field("get", false, anyFunction(Cardinality.EXACTLY_ONE))),
                false);
    }

    /**
     * All fields of {@code uri-structure-record} are optional in the spec.
     * Per QT4 test 206, treated as extensible.
     */
    private static RecordType buildUriStructureRecord() {
        return new RecordType(
                List.of(
                        field("uri", true, seq(Type.STRING, Cardinality.ZERO_OR_ONE)),
                        field("scheme", true, seq(Type.STRING, Cardinality.ZERO_OR_ONE)),
                        field("absolute", true, seq(Type.BOOLEAN, Cardinality.ZERO_OR_ONE)),
                        field("hierarchical", true, seq(Type.BOOLEAN, Cardinality.ZERO_OR_ONE)),
                        field("authority", true, seq(Type.STRING, Cardinality.ZERO_OR_ONE)),
                        field("userinfo", true, seq(Type.STRING, Cardinality.ZERO_OR_ONE)),
                        field("host", true, seq(Type.STRING, Cardinality.ZERO_OR_ONE)),
                        field("port", true, seq(Type.INTEGER, Cardinality.ZERO_OR_ONE)),
                        field("path", true, seq(Type.STRING, Cardinality.ZERO_OR_ONE)),
                        field("query", true, seq(Type.STRING, Cardinality.ZERO_OR_ONE)),
                        field("fragment", true, seq(Type.STRING, Cardinality.ZERO_OR_ONE)),
                        field("path-segments", true, seq(Type.STRING, Cardinality.ZERO_OR_MORE)),
                        field("query-parameters", true, typedMap(
                                seq(Type.STRING, Cardinality.EXACTLY_ONE),
                                seq(Type.STRING, Cardinality.ZERO_OR_MORE))),
                        field("filepath", true, seq(Type.STRING, Cardinality.ZERO_OR_ONE))),
                true);
    }
}
