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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.xquery.util.ExpressionDumper;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

import java.util.Optional;

public class ContextItemDeclaration extends AbstractExpression implements RewritableExpression {

    private static final Logger LOG = LogManager.getLogger(ContextItemDeclaration.class);

    private final Optional<SequenceType> itemType;
    private final boolean external;
    private Optional<Expression> value;

    public ContextItemDeclaration(final XQueryContext context, final SequenceType itemType, final boolean external, final Expression value) throws XPathException {
        super(context);
        // The context item type is an ItemType; an occurrence indicator on the
        // declaration is a static syntax error per XQ31/XQ40 spec
        // (https://www.w3.org/TR/xquery-40/#prod-ContextItemDecl). The grammar
        // accepts an arbitrary SequenceType, so enforce the constraint here so
        // it fires regardless of whether anyone calls analyze() — this node is
        // attached to the context, not embedded in the expression tree.
        if (itemType != null && itemType.getCardinality() != Cardinality.EXACTLY_ONE) {
            throw new XPathException((Expression) null, ErrorCodes.XPST0003,
                    "Occurrence indicator not allowed on context item type: " + itemType);
        }
        this.itemType = Optional.ofNullable(itemType);
        this.external = external;
        this.value = Optional.ofNullable(value);
    }

    @Override
    public void analyze(final AnalyzeContextInfo contextInfo) throws XPathException {
        contextInfo.setParent(this);

        if (value.isPresent()) {
            value.get().analyze(contextInfo);
        }
    }

    @Override
    public Sequence eval(final Sequence contextSequence, final Item contextItem) throws XPathException {
        if (context.getProfiler().isEnabled()) {
            context.getProfiler().start(this);
            context.getProfiler().message(this, Profiler.DEPENDENCIES, "DEPENDENCIES", Dependency.getDependenciesName(this.getDependencies()));
            if (contextSequence != null) {
                context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT SEQUENCE", contextSequence);
            }
        }

        final Sequence raw;
        if (external) {

            //TODO(AR): how to set the context item externally? doesn't eXist-db do this by default anyway?

            // is there a default value
            if (value.isPresent()) {
                raw = value.get().eval(null, null);
            } else {
                return null;
            }
        } else {
            raw = value.get().eval(null, null);
        }

        return enforceType(raw);
    }

    /**
     * Validate the evaluated context item against the declared type.
     *
     * <p>The XQuery spec requires the context item to be a single item; an empty
     * sequence or a sequence of more than one item raises XPTY0004. When a
     * required type is declared (the {@code as <type>} clause), the value must
     * match it. In XQuery 4.0 mode, function-conversion rules apply (atomization
     * plus xs:untypedAtomic / numeric / xs:anyURI promotion); in XQuery 3.x mode
     * the match must be exact.</p>
     *
     * @param raw the value produced by evaluating the declaration's expression
     * @return the (possibly coerced) value that should become the context item
     * @throws XPathException XPTY0004 on cardinality or type mismatch
     */
    private Sequence enforceType(final Sequence raw) throws XPathException {
        if (raw == null) {
            return null;
        }
        // The context item must be a single item per XQ 3.1/4.0 spec.
        if (raw.isEmpty()) {
            throw new XPathException(this, ErrorCodes.XPTY0004,
                    "Context item is empty; declaration requires exactly one item.");
        }
        if (raw.hasMany()) {
            throw new XPathException(this, ErrorCodes.XPTY0004,
                    "Context item bound to a sequence of " + raw.getItemCount()
                            + " items; declaration requires exactly one item.");
        }

        if (itemType.isEmpty()) {
            return raw;
        }
        final SequenceType declared = itemType.get();
        if (declared.getPrimaryType() == Type.ITEM) {
            return raw;
        }

        // Try the strict subtype path first — it's a fast-path for XQ3.x and
        // for XQ4 cases where conversion would be a no-op.
        if (declared.checkType(raw)) {
            return raw;
        }

        // XQ4 PR254: function-conversion rules apply to context item assignment.
        // Atomize node values, then cast atomic values to the declared atomic
        // type when needed (numeric promotion, xs:string -> xs:anyURI,
        // xs:untypedAtomic -> any atomic).
        if (context.getXQueryVersion() >= 40
                && Type.subTypeOf(declared.getPrimaryType(), Type.ANY_ATOMIC_TYPE)) {
            final Sequence converted = applyAtomicFunctionConversion(raw, declared.getPrimaryType());
            if (converted != null) {
                return converted;
            }
        }

        throw new XPathException(this, ErrorCodes.XPTY0004,
                "Context item value does not match declared type "
                        + Type.getTypeName(declared.getPrimaryType())
                        + "; got " + Type.getTypeName(raw.getItemType()) + ".");
    }

    /**
     * Apply XQuery 4.0 function-conversion rules to a sequence against an
     * atomic target type: atomize each item, then promote/cast per
     * {@link DynamicTypeCheck#coerceAtomicItem}. Returns {@code null} if any
     * item resists conversion, letting the caller fall through to XPTY0004.
     */
    private Sequence applyAtomicFunctionConversion(final Sequence raw, final int targetType) {
        try {
            final ValueSequence out = new ValueSequence(raw.getItemCount());
            for (final SequenceIterator it = raw.iterate(); it.hasNext(); ) {
                final Item item = it.nextItem();
                final Item atomized = item.atomize();
                final Item coerced = DynamicTypeCheck.coerceAtomicItem(context, this, atomized, targetType);
                out.add(coerced);
            }
            return out;
        } catch (final XPathException e) {
            return null;
        }
    }

    @Override
    public int returnsType() {
        return itemType.map(SequenceType::getPrimaryType)
                .orElseGet(() -> value.map(Expression::returnsType).orElse(Type.ITEM));
    }

    @Override
    public void dump(final ExpressionDumper dumper) {
        dumper.nl().display("declare context item", line);
        itemType.map(it -> dumper.display(" as ").display(it.toString()));
        if(external) {
            dumper.display(" external ");
        }
        if (value.isPresent()) {
            dumper.display(" := ");
            value.get().dump(dumper);
        }
        dumper.nl();
    }

    @Override
    public String toString() {
        final StringBuilder result = new StringBuilder();
        result.append("declare context item");
        itemType.map(it -> result.append(" as ").append(it));
        if(external) {
            result.append(" external ");
        }
        value.map(v -> result.append(" := ").append(v));
        return result.toString();
    }

    @Override
    public void replace(final Expression oldExpr, final Expression newExpr) {
        if (value.isPresent() && value.get() == oldExpr) {
            this.value = Optional.ofNullable(newExpr);
        }
    }

    @Override
    public void remove(final Expression oldExpr) throws XPathException {
    }

    @Override
    public Expression getPrevious(final Expression current) {
        return null;
    }

    @Override
    public Expression getFirst() {
        return null;
    }
}
