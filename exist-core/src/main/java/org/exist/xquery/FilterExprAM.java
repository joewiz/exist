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

import io.lacuna.bifurcan.IEntry;
import org.exist.xquery.functions.array.ArrayType;
import org.exist.xquery.functions.map.AbstractMapType;
import org.exist.xquery.functions.map.MapType;
import org.exist.xquery.util.ExpressionDumper;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.NumericValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

/**
 * XQuery 4.0 FilterExprAM -- the array/map filter expression {@code ?[expr]}.
 *
 * <p>For arrays, the predicate is evaluated once per member with the member
 * sequence as the context item; the kept members form the result array.</p>
 *
 * <p>For maps, the predicate is evaluated once per entry with the singleton
 * map {@code map { 'key': K, 'value': V }} as the context item; the kept
 * entries form the result map.</p>
 *
 * <p>Predicate result handling:
 * <ul>
 *   <li>empty sequence -- false (drop)</li>
 *   <li>all-numeric sequence -- positional: a member/entry is kept when its
 *       1-based position equals any integer in the predicate result; non-integer
 *       and zero values are ignored</li>
 *   <li>otherwise -- effective boolean value (single boolean/string/URI/etc.;
 *       multi-item non-numeric results raise FORG0006)</li>
 * </ul>
 *
 * @see <a href="https://qt4cg.org/specifications/xquery-40/xpath-40-xquery-40.html#id-filter-am">
 *      QT4 spec: FilterExprAM</a>
 */
public class FilterExprAM extends AbstractExpression {

    private static final String KEY = "key";
    private static final String VALUE = "value";

    private final Expression target;
    private final Expression predicate;

    public FilterExprAM(final XQueryContext context, final Expression target, final Expression predicate) {
        super(context);
        this.target = target;
        // Deeply unwrap single-step PathExpr wrappers nested inside BinaryOp operands
        // (OpOr, OpAnd, ...). Without this, count(.) ge 3 or position() le 2 evaluates
        // as PathExpr.eval iterating over multi-item array members, producing
        // (false, false) and triggering FORG0006 from EBV.
        this.predicate = deepUnwrapPathExprs(predicate);
    }

    private static Expression deepUnwrapPathExprs(final Expression expr) {
        if (!(expr instanceof PathExpr pe)) {
            return expr;
        }
        for (int i = 0; i < pe.getSubExpressionCount(); i++) {
            final Expression child = pe.getSubExpression(i);
            final Expression simplified = deepUnwrapPathExprs(child);
            if (simplified != child) {
                pe.replace(child, simplified);
            }
        }
        // Only unwrap plain PathExpr containers, never BinaryOp/Predicate subclasses
        // which use steps for their own (left/right operand) purposes.
        if (pe.getClass() == PathExpr.class && pe.getSubExpressionCount() == 1) {
            return pe.getSubExpression(0);
        }
        return expr;
    }

    @Override
    public void analyze(final AnalyzeContextInfo contextInfo) throws XPathException {
        target.analyze(contextInfo);

        // Predicate runs with each member/entry as the context item; mark it
        // so context-item-dependent expressions are not const-folded over the outer scope.
        final AnalyzeContextInfo predInfo = new AnalyzeContextInfo(contextInfo);
        predInfo.setParent(this);
        predInfo.addFlag(IN_PREDICATE);
        predInfo.setStaticType(Type.ITEM);
        predicate.analyze(predInfo);
    }

    @Override
    public Sequence eval(final Sequence contextSequence, final Item contextItem) throws XPathException {
        final Sequence targetSeq = target.eval(contextSequence, contextItem);
        if (targetSeq.isEmpty()) {
            return Sequence.EMPTY_SEQUENCE;
        }

        final ValueSequence result = new ValueSequence();
        for (final SequenceIterator iter = targetSeq.iterate(); iter.hasNext(); ) {
            final Item item = iter.nextItem();
            if (item.getType() == Type.ARRAY_ITEM) {
                result.add(filterArray((ArrayType) item));
            } else if (Type.subTypeOf(item.getType(), Type.MAP_ITEM)) {
                result.add(filterMap((AbstractMapType) item));
            } else {
                throw new XPathException(this, ErrorCodes.XPTY0004,
                        "FilterExprAM (?[]) requires an array or map, got " +
                                Type.getTypeName(item.getType()));
            }
        }

        return result;
    }

    private ArrayType filterArray(final ArrayType array) throws XPathException {
        final int size = array.getSize();
        final ArrayType filtered = new ArrayType(context, Sequence.EMPTY_SEQUENCE);
        if (size == 0) {
            return filtered;
        }

        final Sequence positionalContext = makePositionalContext(size);
        final Sequence savedContextSeq = context.getContextSequence();
        final int savedPos = context.getContextPosition();
        try {
            for (int i = 0; i < size; i++) {
                final Sequence member = array.get(i);
                context.setContextSequencePosition(i, positionalContext);
                final Sequence predResult = predicate.eval(member, null);
                if (matchesPredicate(predResult, i + 1)) {
                    filtered.add(member);
                }
            }
        } finally {
            context.setContextSequencePosition(savedPos, savedContextSeq);
        }
        return filtered;
    }

    private AbstractMapType filterMap(final AbstractMapType map) throws XPathException {
        final int size = map.size();
        final MapType filtered = new MapType(this, context);
        if (size == 0) {
            return filtered;
        }

        final Sequence positionalContext = makePositionalContext(size);
        final AtomicValue keyName = new StringValue(this, KEY);
        final AtomicValue valueName = new StringValue(this, VALUE);
        final Sequence savedContextSeq = context.getContextSequence();
        final int savedPos = context.getContextPosition();
        try {
            int idx = 0;
            for (final IEntry<AtomicValue, Sequence> entry : map) {
                // Predicate context for maps is a singleton { 'key': K, 'value': V } map,
                // so ?key / ?value lookups resolve to the entry's key and value.
                final MapType ctxMap = new MapType(this, context);
                ctxMap.add(keyName, entry.key().toSequence());
                ctxMap.add(valueName, entry.value());

                context.setContextSequencePosition(idx, positionalContext);
                final Sequence predResult = predicate.eval(ctxMap, null);
                if (matchesPredicate(predResult, idx + 1)) {
                    filtered.add(entry.key(), entry.value());
                }
                idx++;
            }
        } finally {
            context.setContextSequencePosition(savedPos, savedContextSeq);
        }
        return filtered;
    }

    /**
     * Build a synthetic context sequence whose item count equals {@code size},
     * so that fn:position() and fn:last() resolve correctly inside the predicate.
     */
    private Sequence makePositionalContext(final int size) throws XPathException {
        final ValueSequence seq = new ValueSequence(size);
        for (int i = 1; i <= size; i++) {
            seq.add(new IntegerValue(this, i));
        }
        return seq;
    }

    private boolean matchesPredicate(final Sequence predResult, final int position) throws XPathException {
        if (predResult.isEmpty()) {
            return false;
        }

        // If every item is numeric, treat the predicate as positional.
        boolean allNumeric = true;
        for (final SequenceIterator iter = predResult.iterate(); iter.hasNext(); ) {
            final Item item = iter.nextItem();
            if (!Type.subTypeOfUnion(item.getType(), Type.NUMERIC)) {
                allNumeric = false;
                break;
            }
        }

        if (allNumeric) {
            for (final SequenceIterator iter = predResult.iterate(); iter.hasNext(); ) {
                final NumericValue nv = (NumericValue) iter.nextItem();
                if (!nv.hasFractionalPart() && !nv.isZero() && nv.getInt() == position) {
                    return true;
                }
            }
            return false;
        }

        // Mixed or non-numeric: fall through to EBV. Multi-item non-node sequences
        // raise FORG0006 here, matching test expectations for ?[1, true()] etc.
        return predResult.effectiveBooleanValue();
    }

    @Override
    public int returnsType() {
        return target.returnsType();
    }

    @Override
    public Cardinality getCardinality() {
        return target.getCardinality();
    }

    @Override
    public void dump(final ExpressionDumper dumper) {
        target.dump(dumper);
        dumper.display("?[");
        predicate.dump(dumper);
        dumper.display("]");
    }

    @Override
    public String toString() {
        return target.toString() + "?[" + predicate.toString() + "]";
    }

    @Override
    public void resetState(final boolean postOptimization) {
        super.resetState(postOptimization);
        target.resetState(postOptimization);
        predicate.resetState(postOptimization);
    }
}
