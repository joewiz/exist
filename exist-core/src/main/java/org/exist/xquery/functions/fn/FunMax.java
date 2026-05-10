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
package org.exist.xquery.functions.fn;

import com.ibm.icu.text.Collator;
import org.exist.dom.QName;
import org.exist.xquery.Cardinality;
import org.exist.xquery.Dependency;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.Function;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.Profiler;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.DurationValue;
import org.exist.xquery.value.FloatValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.NumericValue;
import org.exist.xquery.value.QNameValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.Type;

/**
 * Implementation of fn:max with XQuery 4.0 semantics.
 * Uses fn:compare-based mutual comparability (XQ4 numeric total order,
 * duration total order, date/time total order).
 *
 * @author <a href="mailto:wolfgang@exist-db.org">Wolfgang Meier</a>
 */
public class FunMax extends CollatingFunction {

    public final static FunctionSignature[] signatures = {
        new FunctionSignature(
            new QName("max", Function.BUILTIN_FUNCTION_NS),
            "Returns the maximum value from the input sequence, using XQ4 comparison semantics.",
            new SequenceType[] {
                new FunctionParameterSequenceType("values", Type.ANY_ATOMIC_TYPE,
                    Cardinality.ZERO_OR_MORE, "The input sequence")
            },
            new FunctionReturnSequenceType(Type.ANY_ATOMIC_TYPE, Cardinality.ZERO_OR_ONE,
                "the maximum value")
        ),
        new FunctionSignature(
            new QName("max", Function.BUILTIN_FUNCTION_NS),
            "Returns the maximum value from the input sequence, using the specified collation.",
            new SequenceType[] {
                new FunctionParameterSequenceType("values", Type.ANY_ATOMIC_TYPE,
                    Cardinality.ZERO_OR_MORE, "The input sequence"),
                new FunctionParameterSequenceType("collation", Type.STRING,
                    Cardinality.ZERO_OR_ONE, "The collation URI")
            },
            new FunctionReturnSequenceType(Type.ANY_ATOMIC_TYPE, Cardinality.ZERO_OR_ONE,
                "the maximum value")
        )
    };

    public FunMax(XQueryContext context, FunctionSignature signature) {
        super(context, signature);
    }

    public Sequence eval(Sequence contextSequence, Item contextItem) throws XPathException {
        if (context.getProfiler().isEnabled()) {
            context.getProfiler().start(this);
            context.getProfiler().message(this, Profiler.DEPENDENCIES, "DEPENDENCIES",
                Dependency.getDependenciesName(this.getDependencies()));
            if (contextSequence != null)
                {context.getProfiler().message(this, Profiler.START_SEQUENCES,
                    "CONTEXT SEQUENCE", contextSequence);}
            if (contextItem != null)
                {context.getProfiler().message(this, Profiler.START_SEQUENCES,
                    "CONTEXT ITEM", contextItem.toSequence());}
        }

        Sequence result;
        final Sequence arg = getArgument(0).eval(contextSequence, contextItem);
        if (arg.isEmpty()) {
            result = Sequence.EMPTY_SEQUENCE;
        } else {
            final Collator collator = getOptionalCollator(contextSequence, contextItem);
            result = findMax(arg, collator);
        }

        if (context.getProfiler().isEnabled())
            {context.getProfiler().end(this, "", result);}

        return result;
    }

    /**
     * Get collator, handling empty sequence for XQ4 optional collation parameter.
     */
    private Collator getOptionalCollator(Sequence contextSequence, Item contextItem)
            throws XPathException {
        if (getArgumentCount() == 2) {
            final Sequence collationSeq = getArgument(1).eval(contextSequence, contextItem);
            if (!collationSeq.isEmpty()) {
                final String collationURI = collationSeq.getStringValue();
                return context.getCollator(collationURI, ErrorCodes.FOCH0002);
            }
        }
        return context.getDefaultCollator();
    }

    private Sequence findMax(Sequence arg, Collator collator) throws XPathException {
        final SequenceIterator iter = arg.unorderedIterator();
        AtomicValue max = null;

        while (iter.hasNext()) {
            final Item item = iter.nextItem();
            AtomicValue value = item.atomize();

            // XQ 3.1: xs:QName has no order
            if (value instanceof QNameValue) {
                throw new XPathException(this, ErrorCodes.FORG0006,
                    "Cannot compare " + Type.getTypeName(value.getType()), value);
            }

            // Cast untypedAtomic to double
            if (value.getType() == Type.UNTYPED_ATOMIC) {
                value = value.convertTo(Type.DOUBLE);
            }

            // Validate and wrap duration subtypes
            if (Type.subTypeOf(value.getType(), Type.DURATION)) {
                value = validateAndWrapDuration((DurationValue) value, max);
            }

            // XQ 3.1 numeric type promotion: ensure both operands share the
            // least common numeric type that supports comparison, so that
            // the returned value carries the promoted type (e.g. max((1, xs:float(2)))
            // is xs:float, not xs:integer).
            if (value instanceof NumericValue && max instanceof NumericValue) {
                max = max.promote(value);
                value = value.promote(max);
            }

            // NaN propagation: any NaN in the input forces the result to be NaN,
            // typed at the highest numeric type seen so far.
            if (value instanceof NumericValue && ((NumericValue) value).isNaN()) {
                max = promoteNaN(value, max);
                continue;
            }

            if (max == null) {
                max = value;
            } else if (max instanceof NumericValue && ((NumericValue) max).isNaN()) {
                // max is already NaN and won't be displaced; keep it but
                // upgrade its type if value introduced a wider numeric type.
                max = promoteNaN(max, value);
            } else {
                try {
                    final int cmp = FunCompare.compare(value, max, collator);
                    if (cmp > 0) {
                        max = value;
                    }
                } catch (final XPathException e) {
                    throw new XPathException(this, ErrorCodes.FORG0006,
                        "Cannot compare " + Type.getTypeName(max.getType()) +
                        " and " + Type.getTypeName(value.getType()), value);
                }
            }
        }

        return max;
    }

    /**
     * Validate and wrap a duration value per XQ 3.1 fn:min/fn:max rules:
     * only xs:yearMonthDuration or xs:dayTimeDuration are accepted, and all
     * durations in the sequence must share the same subtype.
     */
    private AtomicValue validateAndWrapDuration(final DurationValue value, final AtomicValue accumulator)
            throws XPathException {
        final DurationValue wrapped = value.wrap();
        final int wrappedType = wrapped.getType();
        if (wrappedType != Type.YEAR_MONTH_DURATION && wrappedType != Type.DAY_TIME_DURATION) {
            throw new XPathException(this, ErrorCodes.FORG0006,
                "Cannot compare " + Type.getTypeName(wrappedType), wrapped);
        }
        if (accumulator != null
                && Type.subTypeOf(accumulator.getType(), Type.DURATION)
                && accumulator.getType() != wrappedType) {
            throw new XPathException(this, ErrorCodes.FORG0006,
                "Cannot compare " + Type.getTypeName(accumulator.getType())
                + " and " + Type.getTypeName(wrappedType), wrapped);
        }
        return wrapped;
    }

    /**
     * Return a NaN typed at the widest numeric type between the incoming NaN
     * value and the current accumulator. Used so that
     * max((xs:float("NaN"), xs:double(2))) returns xs:double NaN.
     */
    private static AtomicValue promoteNaN(final AtomicValue nan, final AtomicValue other) {
        if (other == null) {
            return nan;
        }
        if (nan.getType() == Type.DOUBLE || other.getType() == Type.DOUBLE) {
            return DoubleValue.NaN;
        }
        if (nan.getType() == Type.FLOAT || other.getType() == Type.FLOAT) {
            return FloatValue.NaN;
        }
        return nan;
    }
}
