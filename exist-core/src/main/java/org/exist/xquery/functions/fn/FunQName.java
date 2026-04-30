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

import org.exist.Namespaces;
import org.exist.dom.QName;
import org.exist.util.XMLNames;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.Dependency;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.Function;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.Profiler;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.QNameValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

/**
 * @author wolf
 *
 */
public class FunQName extends BasicFunction {

	public final static FunctionSignature signature =
		new FunctionSignature(
			new QName("QName", Function.BUILTIN_FUNCTION_NS),
			"Returns an xs:QName with the namespace URI given in $uri. If $uri is " +
			"the zero-length string or the empty sequence, it represents \"no namespace\"; in " +
			"this case, if the value of $qname contains a colon (:), an error is " +
			"raised [err:FOCA0002]. The prefix (or absence of a prefix) in $qname is " +
			"retained in the returned xs:QName value. The local name in the result is " +
			"taken from the local part of $qname.\n\nIf $qname does not have " +
			"the correct lexical form for xs:QName an error is raised [err:FOCA0002].\n\n" +
			"Note that unlike xs:QName this function does not require a xs:string literal as the argument.",
			new SequenceType[] {
				new FunctionParameterSequenceType("uri", Type.STRING, Cardinality.ZERO_OR_ONE, "The namespace URI"),
				new FunctionParameterSequenceType("qname", Type.STRING, Cardinality.EXACTLY_ONE, "The prefix")
			},
			new FunctionReturnSequenceType(Type.QNAME, Cardinality.EXACTLY_ONE, "the xs:QName with the namespace URI given in $uri"));

	public FunQName(XQueryContext context) {
		super(context, signature);
	}

	/* (non-Javadoc)
	 * @see org.exist.xquery.BasicFunction#eval(org.exist.xquery.value.Sequence[], org.exist.xquery.value.Sequence)
	 */
	public Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException {
        if (context.getProfiler().isEnabled()) {
            context.getProfiler().start(this);       
            context.getProfiler().message(this, Profiler.DEPENDENCIES, "DEPENDENCIES", Dependency.getDependenciesName(this.getDependencies()));
            if (contextSequence != null)
                {context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT SEQUENCE", contextSequence);}
        }  

        //TODO : currently useless (but for empty sequences) since the type is forced :-(
        if (!args[0].isEmpty() && args[0].getItemType() != Type.STRING)
        	{throw new XPathException(this, ErrorCodes.XPTY0004, "Namespace URI is of type '" +
        			Type.getTypeName(args[0].getItemType()) + "', 'xs:string' expected", args[0]);}
        String namespace;
		if (args[0].isEmpty())
			{namespace = "";}
		else
			{namespace = args[0].getStringValue();}
		
		final String param = args[1].getStringValue();
		
		String prefix = null;
		String localName = null;		
		try {
			prefix = QName.extractPrefix(param);
			localName = QName.extractLocalName(param);
        } catch (final QName.IllegalQNameException e) {
                final ValueSequence argsSeq = new ValueSequence(args[0]);
                argsSeq.addAll(args[1]);
        	throw new XPathException(this, ErrorCodes.FOCA0002, "Invalid lexical form of either prefix or local name.", argsSeq);
        }

		if ((prefix != null && !prefix.isEmpty()) && (namespace == null || namespace.isEmpty())){
                final ValueSequence argsSeq = new ValueSequence(args[0]);
                argsSeq.addAll(args[1]);
                throw new XPathException(this, ErrorCodes.FOCA0002, "Non-empty namespace prefix with empty namespace URI", argsSeq);
        }
		
		// fn:QName creates the QName from the supplied URI and lexical QName per QT4
		// spec; reserved-namespace validation (XML_NS / XMLNS_NS) is the responsibility
		// of the caller (typically a constructor) where the dynamic XQDY0044 / XQST0070
		// rules apply. Allowing fn:QName to silently produce QNames in those namespaces
		// matches Saxon and BaseX. The xml namespace is special-cased only to require
		// the 'xml' prefix when one is supplied.
		if (prefix != null && !prefix.isEmpty()) {
			if ("xml".equalsIgnoreCase(prefix) && (namespace == null || !namespace.equalsIgnoreCase(Namespaces.XML_NS))) {
				throw new XPathException(this, ErrorCodes.XQDY0044, "prefix 'xml' can be used only with '" + Namespaces.XML_NS + "'");
			}
			if (namespace != null && namespace.equalsIgnoreCase(Namespaces.XML_NS) && !"xml".equalsIgnoreCase(prefix)) {
				throw new XPathException(this, ErrorCodes.XQDY0044, "'" + Namespaces.XML_NS + "' can't be use with prefix '" + prefix + "'");
			}
		}

		final QName qname = new QName(localName, namespace, prefix);
        // Note: fn:QName does not modify the static namespace context — auto-declaring
        // the prefix would clobber legitimate XQST0070 checks for reserved prefixes
        // such as xmlns and would also leak into later expressions.

        if(!XMLNames.isName(qname.getLocalPart()))
            {throw new XPathException(this, ErrorCodes.FOCA0002, "'" + qname.getLocalPart() + "' is not a valid local name.");}

        final Sequence result = new QNameValue(this, context, qname);

        if (context.getProfiler().isEnabled()) 
            {context.getProfiler().end(this, "", result);} 
        
        return result;
	}
}
