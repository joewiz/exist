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

import org.exist.xquery.AbstractExpression;
import org.exist.xquery.AnalyzeContextInfo;
import org.exist.xquery.Expression;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.util.ExpressionDumper;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.Type;

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
    public void analyze(final AnalyzeContextInfo contextInfo) throws XPathException {
        contextInfo.setParent(this);
        source.analyze(contextInfo);
        ftSelection.analyze(contextInfo);
        if (ignoreExpr != null) {
            ignoreExpr.analyze(contextInfo);
        }
    }

    @Override
    public Sequence eval(final Sequence contextSequence, final Item contextItem) throws XPathException {
        // TODO: implement FT evaluation (Phase 2/3)
        throw new XPathException(this, "XQFT contains text evaluation is not yet implemented");
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
