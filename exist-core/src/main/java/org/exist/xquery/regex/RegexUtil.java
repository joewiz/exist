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
package org.exist.xquery.regex;

import net.sf.saxon.Configuration;
import org.exist.thirdparty.net.sf.saxon.functions.regex.JDK15RegexTranslator;
import org.exist.thirdparty.net.sf.saxon.functions.regex.RegexSyntaxException;
import org.exist.thirdparty.net.sf.saxon.functions.regex.RegularExpression;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.Expression;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.StringValue;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * @author <a href="mailto:adam@evolvedbinary.com">Adam Retter</a>
 */
public class RegexUtil {

    /**
     * Parses the flags for an XQuery Regular Expression.
     *
     * @param context The calling expression
     * @param strFlags The XQuery Regular Expression flags.
     *
     * @return The flags for a Java Regular Expression.
     * @throws XPathException in case of invalid flag
     */
    public static int parseFlags(final Expression context, @Nullable final String strFlags) throws XPathException {
        int flags = 0;
        if(strFlags != null) {
            for (int i = 0; i < strFlags.length(); i++) {
                final char ch = strFlags.charAt(i);
                switch (ch) {
                    case 'm':
                        flags |= Pattern.MULTILINE;
                        break;

                    case 'i':
                        flags = flags | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                        break;

                    case 'x':
                        flags |= Pattern.COMMENTS;
                        break;

                    case 's':
                        flags |= Pattern.DOTALL;
                        break;

                    case 'q':
                        flags |= Pattern.LITERAL;
                        break;

                    default:
                        throw new XPathException(context, ErrorCodes.FORX0001, "Invalid regular expression flag: " + ch, new StringValue(String.valueOf(ch)));
                }
            }
        }
        return flags;
    }

    /**
     * Determines if the Java Regular Expression flags have the literal flag set.
     *
     * @param flags The Java Regular Expression flags
     *
     * @return true if the literal flag is set
     */
    public static boolean hasLiteral(final int flags) {
        return (flags & Pattern.LITERAL) != 0;
    }

    /**
     * Determines if the XQuery Expression flags have the literal flag set.
     *
     * @param flags The XQuery Expression flags
     *
     * @return true if the literal flag is set
     */
    public static boolean hasLiteral(final String flags) {
        return flags.contains("q");
    }

    /**
     * Determines if the Java Regular Expression flags have the case-insensitive flag set.
     *
     * @param flags The Java Regular Expression flags
     *
     * @return true if the case-insensitive flag is set
     */
    public static boolean hasCaseInsensitive(final int flags) {
        return (flags & Pattern.CASE_INSENSITIVE) != 0 || (flags & Pattern.UNICODE_CASE) != 0;
    }

    /**
     * Determines if the Java Regular Expression flags have the ignore-whitespace flag set.
     *
     * @param flags The Java Regular Expression flags
     *
     * @return true if the ignore-whitespace flag is set
     */
    public static boolean hasIgnoreWhitespace(final int flags) {
        return (flags & Pattern.COMMENTS) != 0;
    }

    /**
     * Translates the Regular Expression from XPath3 syntax to Java regex
     * syntax.
     *
     * @param context the context expression - used for error reporting
     * @param pattern a String containing a regular expression in the syntax of XPath Functions and Operators 3.0.
     * @param ignoreWhitespace true if whitespace is to be ignored ('x' flag)
     * @param caseBlind true if case is to be ignored ('i' flag)
     *
     * @return The Java Regular Expression
     *
     * @throws XPathException if the XQuery Regular Expression is invalid.
     */
    public static String translateRegexp(final Expression context, final String pattern, final boolean ignoreWhitespace, final boolean caseBlind) throws XPathException {
        return translateRegexp(context, pattern, ignoreWhitespace, caseBlind, null);
    }

    /**
     * Translates the Regular Expression from XPath3 syntax to Java regex
     * syntax, with an optional Saxon-based fallback for patterns that the
     * bundled JDK15RegexTranslator incorrectly rejects.
     *
     * @param context the context expression - used for error reporting
     * @param pattern a String containing a regular expression in the syntax of XPath Functions and Operators 3.0.
     * @param ignoreWhitespace true if whitespace is to be ignored ('x' flag)
     * @param caseBlind true if case is to be ignored ('i' flag)
     * @param saxonConfig optional Saxon Configuration for fallback validation; if null, no fallback is attempted
     *
     * @return The Java Regular Expression
     *
     * @throws XPathException if the XQuery Regular Expression is invalid.
     */
    public static String translateRegexp(final Expression context, final String pattern, final boolean ignoreWhitespace, final boolean caseBlind, @Nullable final Configuration saxonConfig) throws XPathException {
        // convert pattern to Java regex syntax
        try {
            final int options = RegularExpression.XML11 | RegularExpression.XPATH30;

            int flagbits = 0;
            if (ignoreWhitespace) {
                flagbits |= Pattern.COMMENTS;
            }
            if (caseBlind) {
                flagbits |= Pattern.CASE_INSENSITIVE;
            }

            final List<RegexSyntaxException> warnings = new ArrayList<>();
            return JDK15RegexTranslator.translate(pattern, options, flagbits, warnings);
        } catch (final RegexSyntaxException e) {
            if (saxonConfig != null) {
                return translateRegexpFallback(context, pattern, ignoreWhitespace, caseBlind, saxonConfig, e);
            }
            throw new XPathException(context, ErrorCodes.FORX0002, "Conversion from XPath F&O 3.0 regular expression syntax to Java regular expression syntax failed: " + e.getMessage(), new StringValue(pattern), e);
        }
    }

    /**
     * Fallback regex translation using Saxon's regex compiler. If Saxon accepts
     * the pattern and it is also valid Java regex, returns the raw pattern
     * (the bundled translator was overly strict). Otherwise, throws the
     * original error.
     */
    private static String translateRegexpFallback(final Expression context, final String pattern, final boolean ignoreWhitespace, final boolean caseBlind, final Configuration saxonConfig, final RegexSyntaxException originalError) throws XPathException {
        // Build the XPath flags string for Saxon
        final StringBuilder xpathFlags = new StringBuilder();
        if (ignoreWhitespace) {
            xpathFlags.append('x');
        }
        if (caseBlind) {
            xpathFlags.append('i');
        }

        try {
            final List<String> warnings = new ArrayList<>();
            saxonConfig.compileRegularExpression(pattern, xpathFlags.toString(), "XP30", warnings);
        } catch (final net.sf.saxon.trans.XPathException e) {
            // Saxon also rejects it — the pattern is truly invalid
            throw new XPathException(context, ErrorCodes.FORX0002, "Conversion from XPath F&O 3.0 regular expression syntax to Java regular expression syntax failed: " + originalError.getMessage(), new StringValue(pattern), originalError);
        }

        // Saxon accepted it; verify it's also valid as a Java regex
        try {
            int flagbits = 0;
            if (ignoreWhitespace) {
                flagbits |= Pattern.COMMENTS;
            }
            if (caseBlind) {
                flagbits |= Pattern.CASE_INSENSITIVE;
            }
            Pattern.compile(pattern, flagbits);
        } catch (final PatternSyntaxException e) {
            // Valid XPath regex but not valid Java regex — can't use raw pattern
            throw new XPathException(context, ErrorCodes.FORX0002, "Conversion from XPath F&O 3.0 regular expression syntax to Java regular expression syntax failed: " + originalError.getMessage(), new StringValue(pattern), originalError);
        }

        // Both Saxon and Java accept it — the bundled translator was overly strict
        return pattern;
    }
}
