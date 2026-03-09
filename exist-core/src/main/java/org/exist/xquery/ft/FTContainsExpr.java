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
package org.exist.xquery.ft;

import org.exist.dom.memtree.NodeImpl;
import org.exist.xquery.AbstractExpression;
import org.exist.xquery.AnalyzeContextInfo;
import org.exist.xquery.Dependency;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.Expression;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.util.ExpressionDumper;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.Type;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.HashSet;
import java.util.Set;

/**
 * W3C XQuery and XPath Full Text 3.0 — FTContainsExpr.
 *
 * <pre>FTContainsExpr ::= StringConcatExpr ( "contains" "text" FTSelection FTIgnoreOption? )?</pre>
 *
 * Evaluates whether the string value of the left-hand expression, after
 * tokenization, matches the FTSelection. Returns xs:boolean.
 *
 * @see <a href="https://www.w3.org/TR/xpath-full-text-30/#ftcontains">XQFT 3.0 §2.1</a>
 */
public class FTContainsExpr extends AbstractExpression {

    private Expression source;
    private FTSelection ftSelection;
    private Expression ignoreExpr;

    public FTContainsExpr(final XQueryContext context) {
        super(context);
    }

    public void setSearchSource(final Expression source) {
        this.source = source;
    }

    public Expression getSearchSource() {
        return source;
    }

    public void setFTSelection(final FTSelection ftSelection) {
        this.ftSelection = ftSelection;
    }

    public FTSelection getFTSelection() {
        return ftSelection;
    }

    public void setIgnoreExpr(final Expression ignoreExpr) {
        this.ignoreExpr = ignoreExpr;
    }

    public Expression getIgnoreExpr() {
        return ignoreExpr;
    }

    @Override
    public int getDependencies() {
        // The source expression (left-hand side of "contains text") is always
        // evaluated against the context item, so we must report CONTEXT_ITEM
        // dependency. Without this, Predicate.evalPredicate may pass null
        // as the context sequence, causing XPDY0002 errors on step expressions.
        return source.getDependencies() | Dependency.CONTEXT_ITEM;
    }

    @Override
    public void analyze(final AnalyzeContextInfo contextInfo) throws XPathException {
        contextInfo.setParent(this);
        source.analyze(contextInfo);
        ftSelection.analyze(contextInfo);
        if (ignoreExpr != null) {
            ignoreExpr.analyze(contextInfo);
        }
    }

    @Override
    public Sequence eval(Sequence contextSequence, final Item contextItem) throws XPathException {
        if (contextItem != null) {
            contextSequence = contextItem.toSequence();
        }

        // Evaluate source expression to get the search context
        final Sequence sourceSeq = source.eval(contextSequence, null);

        // Per XQFT 3.0 §2.1: if the source evaluates to an empty sequence,
        // there is no text to search — return false immediately.
        if (sourceSeq.isEmpty()) {
            return BooleanValue.FALSE;
        }

        // Collect ignored text if FTIgnoreOption is present
        Set<String> ignoredTexts = null;
        if (ignoreExpr != null) {
            final Sequence ignoredNodes = ignoreExpr.eval(contextSequence, null);
            if (!ignoredNodes.isEmpty()) {
                // XQFT 3.0 §3.7: FTIgnoreOption must evaluate to a node sequence.
                // Non-node values raise XPTY0004.
                for (int i = 0; i < ignoredNodes.getItemCount(); i++) {
                    if (!Type.subTypeOf(ignoredNodes.itemAt(i).getType(), Type.NODE)) {
                        throw new XPathException(this, ErrorCodes.XPTY0004,
                                "FTIgnoreOption 'without content' expression must evaluate to nodes, got: "
                                        + Type.getTypeName(ignoredNodes.itemAt(i).getType()));
                    }
                }
                ignoredTexts = new HashSet<>();
                for (int i = 0; i < ignoredNodes.getItemCount(); i++) {
                    final String ignoredText = ignoredNodes.itemAt(i).getStringValue();
                    if (ignoredText != null && !ignoredText.isEmpty()) {
                        ignoredTexts.add(ignoredText);
                    }
                }
            }
        }

        // Per XQFT 3.0 §2.1: if the source is a sequence of items,
        // evaluate each item independently and return true if ANY matches.
        for (int i = 0; i < sourceSeq.getItemCount(); i++) {
            String sourceText = sourceSeq.itemAt(i).getStringValue();

            // Apply FTIgnoreOption: "without content" removes text of ignored nodes
            if (ignoredTexts != null) {
                for (final String ignored : ignoredTexts) {
                    sourceText = sourceText.replace(ignored, " ");
                }
            }

            final FTEvaluator evaluator = new FTEvaluator(sourceText);
            // Pass default FT match options from static context (declare ft-option)
            final FTMatchOptions defaultOpts = context.getDefaultFTMatchOptions();
            if (evaluator.evaluate(ftSelection, defaultOpts)) {
                return BooleanValue.TRUE;
            }
        }

        return BooleanValue.FALSE;
    }

    @Override
    public int returnsType() {
        return Type.BOOLEAN;
    }

    @Override
    public void dump(final ExpressionDumper dumper) {
        source.dump(dumper);
        dumper.display(" contains text ");
        ftSelection.dump(dumper);
        if (ignoreExpr != null) {
            dumper.display(" without content ");
            ignoreExpr.dump(dumper);
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append(source.toString());
        sb.append(" contains text ");
        sb.append(ftSelection.toString());
        if (ignoreExpr != null) {
            sb.append(" without content ");
            sb.append(ignoreExpr.toString());
        }
        return sb.toString();
    }

    @Override
    public void resetState(final boolean postOptimization) {
        super.resetState(postOptimization);
        source.resetState(postOptimization);
        ftSelection.resetState(postOptimization);
        if (ignoreExpr != null) {
            ignoreExpr.resetState(postOptimization);
        }
    }
}
