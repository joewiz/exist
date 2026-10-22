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
package org.exist.xquery.functions.util;

import com.evolvedbinary.j8fu.tuple.Tuple2;
import org.exist.dom.QName;
import org.exist.util.SentenceBreaker;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.Cardinality;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.Expression;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.map.AbstractMapType;
import org.exist.xquery.functions.map.MapType;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.FunctionParameterSequenceType;
import org.exist.xquery.value.FunctionReturnSequenceType;
import org.exist.xquery.value.IntegerValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.StringValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static com.evolvedbinary.j8fu.tuple.Tuple.Tuple;

/**
 * Splits a text into sentences, see {@link SentenceBreaker}.
 */
public class Sentences extends BasicFunction {

    private static final QName NAME = new QName("sentences", UtilModule.NAMESPACE_URI, UtilModule.PREFIX);

    private static final String DESCRIPTION = """
            Splits $text into sentences, with the sentence rules of ICU for a language and the abbreviations of CLDR \
            for it, which don't end a sentence. Each sentence is returned as it appears in $text, without the \
            whitespace around it.""";

    /** The options util:sentences and ft:passages share, as their descriptions give them. */
    public static final String SENTENCE_OPTIONS = """
            "language", a BCP 47 language tag (default "en"); \
            "abbreviations", strings that never end a sentence, such as ("Amb.", "No.", "U.S."); \
            "cldr-abbreviations", whether CLDR's abbreviations for the language never end a sentence either \
            (default true); \
            "merge-initials", whether initials followed by a capital don't end a sentence, as in "J. F. Dulles" \
            (default false); \
            "max-initials", the most capital letters, each with a period and with no space between them, that \
            merge-initials takes as one name's initials, such as 2 for "R.N. Ginsburgh" (default 1, at most 10); \
            "merge-quoted-continuation", whether a question or exclamation mark followed by a lowercase word \
            doesn't end a sentence, as in '"Is this final?" he asked.' (default false); \
            "dash-as-space", whether an em or en dash directly before a letter separates words, as in \
            "Negotiations—Mr. Bell", so that the abbreviations and initials after it are recognized (default false); \
            "break-at-newlines", whether a line break ends a sentence, as in ICU's rules, rather than being \
            whitespace, as in the text of XML documents (default false); \
            "rules", break rules in the syntax of ICU's RuleBasedBreakIterator, which replace ICU's sentence rules \
            for the language; the other options still apply (default none)""";

    private static final FunctionParameterSequenceType TEXT =
            new FunctionParameterSequenceType("text", Type.STRING, Cardinality.ZERO_OR_ONE, "The text to split");

    public static final FunctionSignature[] signatures = {
            new FunctionSignature(NAME, DESCRIPTION, new SequenceType[] {TEXT},
                    new FunctionReturnSequenceType(Type.STRING, Cardinality.ZERO_OR_MORE, "the sentences, in order")),
            new FunctionSignature(NAME, DESCRIPTION + " $options is a map with the keys: " + SENTENCE_OPTIONS + """
                    ; and "offsets", whether each sentence is returned as a map with the keys "text", "start" and \
                    "end", where start is the position of its first character in $text, from 1, and end the position \
                    after its last, so that substring($text, $start, $end - $start) is the sentence (default false). \
                    An option of the wrong type raises XPTY0004, a max-initials out of range or rules that are not \
                    valid ICU rules EXXQDY0008.""",
                    new SequenceType[] {TEXT,
                            new FunctionParameterSequenceType("options", Type.MAP_ITEM, Cardinality.EXACTLY_ONE, "The options")},
                    new FunctionReturnSequenceType(Type.ITEM, Cardinality.ZERO_OR_MORE,
                            "the sentences, in order, as strings or, with the offsets option, as maps"))
    };

    private static final StringValue LANGUAGE = new StringValue("language");
    private static final StringValue ABBREVIATIONS = new StringValue("abbreviations");
    private static final StringValue CLDR_ABBREVIATIONS = new StringValue("cldr-abbreviations");
    private static final StringValue MERGE_INITIALS = new StringValue("merge-initials");
    private static final StringValue MAX_INITIALS = new StringValue("max-initials");
    private static final StringValue MERGE_QUOTED_CONTINUATION = new StringValue("merge-quoted-continuation");
    private static final StringValue DASH_AS_SPACE = new StringValue("dash-as-space");
    private static final StringValue BREAK_AT_NEWLINES = new StringValue("break-at-newlines");
    private static final StringValue RULES = new StringValue("rules");
    private static final StringValue OFFSETS = new StringValue("offsets");

    public Sentences(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        if (args[0].isEmpty()) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final String text = args[0].getStringValue();
        final AbstractMapType options = args.length > 1 ? (AbstractMapType) args[1].itemAt(0) : null;
        final SentenceBreaker breaker = sentenceBreaker(this, options);
        final boolean offsets = options != null && booleanOption(this, options, OFFSETS, false);

        final List<SentenceBreaker.Span> spans = breaker.sentences(text);
        final ValueSequence result = new ValueSequence(spans.size());
        for (final SentenceBreaker.Span span : spans) {
            final String sentence = text.substring(span.start(), span.end());
            if (offsets) {
                // positions in characters, from 1, as substring counts them
                final int start = text.codePointCount(0, span.start()) + 1;
                final int end = start + sentence.codePointCount(0, sentence.length());
                final List<Tuple2<AtomicValue, Sequence>> entries = List.of(
                        Tuple(new StringValue(this, "text"), new StringValue(this, sentence)),
                        Tuple(new StringValue(this, "start"), new IntegerValue(this, start)),
                        Tuple(new StringValue(this, "end"), new IntegerValue(this, end)));
                result.add(new MapType(this, context, null, entries));
            } else {
                result.add(new StringValue(this, sentence));
            }
        }
        return result;
    }

    /**
     * A sentence breaker with the sentence options of a map, which util:sentences and ft:passages share.
     *
     * @param expression the expression the options are given to, for errors
     * @param options the options, or null for the defaults
     * @return the breaker
     * @throws XPathException XPTY0004 if an option has the wrong type, EXXQDY0008 if max-initials is out of range or
     *     the rules are not valid ICU rules
     */
    public static SentenceBreaker sentenceBreaker(final Expression expression, @Nullable final AbstractMapType options)
            throws XPathException {
        if (options == null) {
            return new SentenceBreaker(SentenceBreaker.Options.DEFAULT);
        }
        final SentenceBreaker.Options defaults = SentenceBreaker.Options.DEFAULT;
        final List<String> abbreviations = new ArrayList<>();
        for (final SequenceIterator i = options.get(ABBREVIATIONS).iterate(); i.hasNext(); ) {
            abbreviations.add(i.nextItem().getStringValue());
        }
        final long maxInitials = integerOption(expression, options, MAX_INITIALS, defaults.maxInitials());
        if (maxInitials < 1 || maxInitials > SentenceBreaker.MAX_INITIALS) {
            throw new XPathException(expression, ErrorCodes.EXXQDY0008,
                    "Option 'max-initials' must be from 1 to " + SentenceBreaker.MAX_INITIALS + ": " + maxInitials);
        }
        final SentenceBreaker.Options parsed = defaults.toBuilder()
                .language(stringOption(expression, options, LANGUAGE, defaults.language()))
                .abbreviations(abbreviations)
                .cldrAbbreviations(booleanOption(expression, options, CLDR_ABBREVIATIONS, defaults.cldrAbbreviations()))
                .mergeInitials(booleanOption(expression, options, MERGE_INITIALS, defaults.mergeInitials()))
                .maxInitials((int) maxInitials)
                .mergeQuotedContinuation(booleanOption(expression, options, MERGE_QUOTED_CONTINUATION,
                        defaults.mergeQuotedContinuation()))
                .dashAsSpace(booleanOption(expression, options, DASH_AS_SPACE, defaults.dashAsSpace()))
                .breakAtNewlines(booleanOption(expression, options, BREAK_AT_NEWLINES, defaults.breakAtNewlines()))
                .rules(stringOption(expression, options, RULES, defaults.rules()))
                .build();
        try {
            return new SentenceBreaker(parsed);
        } catch (final IllegalArgumentException e) {
            throw new XPathException(expression, ErrorCodes.EXXQDY0008,
                    "Option 'rules' is not valid ICU break rules: " + e.getMessage(), e);
        }
    }

    private static boolean booleanOption(final Expression expression, final AbstractMapType options,
            final StringValue key, final boolean defaultValue) throws XPathException {
        final Sequence value = options.get(key);
        if (value.isEmpty()) {
            return defaultValue;
        }
        if (!value.hasOne() || value.itemAt(0).getType() != Type.BOOLEAN) {
            throw new XPathException(expression, ErrorCodes.XPTY0004, "Option '" + key.getStringValue() + "' must be a boolean");
        }
        return value.effectiveBooleanValue();
    }

    private static long integerOption(final Expression expression, final AbstractMapType options,
            final StringValue key, final long defaultValue) throws XPathException {
        final Sequence value = options.get(key);
        if (value.isEmpty()) {
            return defaultValue;
        }
        if (!value.hasOne() || !Type.subTypeOf(value.itemAt(0).getType(), Type.INTEGER)) {
            throw new XPathException(expression, ErrorCodes.XPTY0004, "Option '" + key.getStringValue() + "' must be an integer");
        }
        return ((IntegerValue) value.itemAt(0)).getLong();
    }

    @Nullable
    private static String stringOption(final Expression expression, final AbstractMapType options,
            final StringValue key, @Nullable final String defaultValue) throws XPathException {
        final Sequence value = options.get(key);
        if (value.isEmpty()) {
            return defaultValue;
        }
        final int type = value.hasOne() ? value.itemAt(0).getType() : Type.ITEM;
        if (!Type.subTypeOf(type, Type.STRING) && type != Type.UNTYPED_ATOMIC) {
            throw new XPathException(expression, ErrorCodes.XPTY0004, "Option '" + key.getStringValue() + "' must be a string");
        }
        return value.getStringValue();
    }
}
