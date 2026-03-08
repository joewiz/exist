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

import org.exist.xquery.ErrorCodes;
import org.exist.xquery.Expression;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.Sequence;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Sequential (in-memory) evaluator for W3C XQFT 3.0 expressions.
 *
 * Implements the AllMatches model from the spec in simplified form:
 * each FT expression node returns a list of {@link Match} objects,
 * where each Match records which token positions were matched and whether
 * they are inclusions or exclusions (for mild-not / not-in).
 *
 * @see <a href="https://www.w3.org/TR/xpath-full-text-30/#ftcontains">XQFT 3.0 §2</a>
 */
public class FTEvaluator {

    /**
     * A single match result: a set of token positions that were matched.
     * Positions are 0-based indices into the token array.
     */
    public static class Match {
        private final SortedSet<Integer> includePositions;
        private final SortedSet<Integer> excludePositions;
        // Tracks positions per operand group for the 'ordered' filter.
        // Each element is the set of positions from one FTAnd operand.
        private final List<SortedSet<Integer>> operandGroups;

        public Match() {
            this.includePositions = new TreeSet<>();
            this.excludePositions = new TreeSet<>();
            this.operandGroups = new ArrayList<>();
        }

        public Match(final int pos) {
            this();
            includePositions.add(pos);
            final SortedSet<Integer> group = new TreeSet<>();
            group.add(pos);
            operandGroups.add(group);
        }

        public Match(final SortedSet<Integer> includes, final SortedSet<Integer> excludes) {
            this.includePositions = new TreeSet<>(includes);
            this.excludePositions = new TreeSet<>(excludes);
            this.operandGroups = new ArrayList<>();
            if (!includes.isEmpty()) {
                operandGroups.add(new TreeSet<>(includes));
            }
        }

        private Match(final SortedSet<Integer> includes, final SortedSet<Integer> excludes,
                       final List<SortedSet<Integer>> groups) {
            this.includePositions = new TreeSet<>(includes);
            this.excludePositions = new TreeSet<>(excludes);
            this.operandGroups = new ArrayList<>(groups);
        }

        public SortedSet<Integer> getIncludePositions() {
            return includePositions;
        }

        public SortedSet<Integer> getExcludePositions() {
            return excludePositions;
        }

        public List<SortedSet<Integer>> getOperandGroups() {
            return operandGroups;
        }

        public SortedSet<Integer> getAllPositions() {
            final SortedSet<Integer> all = new TreeSet<>(includePositions);
            all.addAll(excludePositions);
            return all;
        }

        /** Combine two matches (e.g. for ftand), preserving operand groups */
        public Match combine(final Match other) {
            final SortedSet<Integer> inc = new TreeSet<>(includePositions);
            inc.addAll(other.includePositions);
            final SortedSet<Integer> exc = new TreeSet<>(excludePositions);
            exc.addAll(other.excludePositions);
            final List<SortedSet<Integer>> groups = new ArrayList<>(operandGroups);
            groups.addAll(other.operandGroups);
            return new Match(inc, exc, groups);
        }
    }

    /** All possible matches for an FT expression */
    public static class AllMatches {
        private final List<Match> matches;

        public AllMatches() {
            this.matches = new ArrayList<>();
        }

        public AllMatches(final List<Match> matches) {
            this.matches = new ArrayList<>(matches);
        }

        public List<Match> getMatches() {
            return matches;
        }

        public void addMatch(final Match match) {
            matches.add(match);
        }

        public boolean hasMatches() {
            return !matches.isEmpty();
        }
    }

    private final List<String> tokens;
    private final int totalTokens;

    public FTEvaluator(final String text) {
        this.tokens = tokenize(text);
        this.totalTokens = tokens.size();
    }

    public List<String> getTokens() {
        return Collections.unmodifiableList(tokens);
    }

    /**
     * Tokenize text into words using Unicode word boundaries.
     */
    static List<String> tokenize(final String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> result = new ArrayList<>();
        final BreakIterator wb = BreakIterator.getWordInstance(Locale.ROOT);
        wb.setText(text);
        int start = wb.first();
        for (int end = wb.next(); end != BreakIterator.DONE; start = end, end = wb.next()) {
            final String word = text.substring(start, end);
            // Only include words that contain at least one letter or digit
            if (word.codePoints().anyMatch(Character::isLetterOrDigit)) {
                result.add(word);
            }
        }
        return result;
    }

    /**
     * Evaluate the full FTSelection and apply positional filters.
     */
    public boolean evaluate(final FTSelection selection, final FTMatchOptions inheritedOptions)
            throws XPathException {
        AllMatches result = evalExpression(selection.getFTOr(), inheritedOptions);
        // Apply positional filters
        for (final Expression filter : selection.getPosFilters()) {
            result = applyPosFilter(result, filter);
        }
        return result.hasMatches();
    }

    /**
     * Recursively evaluate an FT expression node.
     */
    AllMatches evalExpression(final Expression expr, final FTMatchOptions options)
            throws XPathException {
        if (expr instanceof FTWords) {
            return evalFTWords((FTWords) expr, options);
        } else if (expr instanceof FTPrimaryWithOptions) {
            return evalFTPrimaryWithOptions((FTPrimaryWithOptions) expr, options);
        } else if (expr instanceof FTOr) {
            return evalFTOr((FTOr) expr, options);
        } else if (expr instanceof FTAnd) {
            return evalFTAnd((FTAnd) expr, options);
        } else if (expr instanceof FTMildNot) {
            return evalFTMildNot((FTMildNot) expr, options);
        } else if (expr instanceof FTUnaryNot) {
            return evalFTUnaryNot((FTUnaryNot) expr, options);
        } else if (expr instanceof FTSelection) {
            // Nested parenthesized FTSelection
            final FTSelection sel = (FTSelection) expr;
            AllMatches result = evalExpression(sel.getFTOr(), options);
            for (final Expression filter : sel.getPosFilters()) {
                result = applyPosFilter(result, filter);
            }
            return result;
        }
        throw new XPathException(expr, "Unsupported FT expression type: " + expr.getClass().getSimpleName());
    }

    /**
     * FTWords: the terminal matching node.
     * Evaluates the words value, tokenizes it, and finds matches in the source tokens.
     */
    AllMatches evalFTWords(final FTWords ftWords, final FTMatchOptions options)
            throws XPathException {
        // Evaluate the words value expression to get the search string(s)
        final Sequence wordsSeq = ftWords.getWordsValue().eval(null, null);
        final List<String> searchStrings = new ArrayList<>();
        for (int i = 0; i < wordsSeq.getItemCount(); i++) {
            searchStrings.add(wordsSeq.itemAt(i).getStringValue());
        }

        if (searchStrings.isEmpty()) {
            // Empty search matches everything (spec: empty string matches)
            final AllMatches am = new AllMatches();
            am.addMatch(new Match());
            return am;
        }

        // XQFT 3.0 §4.1: default case mode is implementation-defined.
        // We default to case-insensitive, matching most XQFT test suite expectations.
        final boolean caseInsensitive = options == null ||
                options.getCaseMode() == null ||
                options.getCaseMode() == FTMatchOptions.CaseMode.INSENSITIVE ||
                options.getCaseMode() == FTMatchOptions.CaseMode.LOWERCASE ||
                options.getCaseMode() == FTMatchOptions.CaseMode.UPPERCASE;
        final boolean useWildcards = options != null &&
                Boolean.TRUE.equals(options.getWildcards());
        // XQFT 3.0 §4.3: diacritics mode. Default to insensitive.
        final boolean diacriticsInsensitive = options == null ||
                options.getDiacriticsMode() == null ||
                options.getDiacriticsMode() == FTMatchOptions.DiacriticsMode.INSENSITIVE;

        // Collect stop words from options (XQFT 3.0 §4.6)
        final Set<String> stopWords = collectStopWords(options, caseInsensitive);

        final FTWords.AnyallMode mode = ftWords.getMode();
        AllMatches result;
        switch (mode) {
            case ANY:
                result = evalAny(searchStrings, caseInsensitive, useWildcards, diacriticsInsensitive, stopWords); break;
            case ANY_WORD:
                result = evalAnyWord(searchStrings, caseInsensitive, useWildcards, diacriticsInsensitive, stopWords); break;
            case ALL:
                result = evalAll(searchStrings, caseInsensitive, useWildcards, diacriticsInsensitive, stopWords); break;
            case ALL_WORDS:
                result = evalAllWords(searchStrings, caseInsensitive, useWildcards, diacriticsInsensitive, stopWords); break;
            case PHRASE:
                result = evalPhrase(searchStrings, caseInsensitive, useWildcards, diacriticsInsensitive, stopWords); break;
            default:
                result = evalAny(searchStrings, caseInsensitive, useWildcards, diacriticsInsensitive, stopWords); break;
        }

        // Apply FTTimes constraint if present
        final FTTimes ftTimes = ftWords.getFTTimes();
        if (ftTimes != null) {
            result = applyTimes(result, ftTimes);
        }
        return result;
    }

    /**
     * "any" mode: any of the search strings can match (each as a phrase).
     */
    private AllMatches evalAny(final List<String> searchStrings, final boolean caseInsensitive,
                               final boolean useWildcards, final boolean diacriticsInsensitive,
                               final Set<String> stopWords) {
        final AllMatches result = new AllMatches();
        for (final String searchStr : searchStrings) {
            final List<String> searchTokens = useWildcards ? tokenizeWildcard(searchStr) : tokenize(searchStr);
            if (searchTokens.isEmpty()) {
                result.addMatch(new Match());
                continue;
            }
            if (searchTokens.size() == 1) {
                findWordMatches(searchTokens.get(0), caseInsensitive, useWildcards, diacriticsInsensitive, stopWords, result);
            } else {
                findPhraseMatches(searchTokens, caseInsensitive, useWildcards, diacriticsInsensitive, stopWords, result);
            }
        }
        return result;
    }

    /**
     * "any word" mode: tokenize all search strings into individual words,
     * any single word can match.
     */
    private AllMatches evalAnyWord(final List<String> searchStrings, final boolean caseInsensitive,
                                   final boolean useWildcards, final boolean diacriticsInsensitive,
                                   final Set<String> stopWords) {
        final AllMatches result = new AllMatches();
        for (final String searchStr : searchStrings) {
            final List<String> words = useWildcards ? tokenizeWildcard(searchStr) : tokenize(searchStr);
            for (final String word : words) {
                if (isStopWord(word, stopWords, caseInsensitive)) {
                    continue;
                }
                findWordMatches(word, caseInsensitive, useWildcards, diacriticsInsensitive, stopWords, result);
            }
        }
        return result;
    }

    /**
     * "all" mode: all search strings must match (each as a phrase).
     */
    private AllMatches evalAll(final List<String> searchStrings, final boolean caseInsensitive,
                               final boolean useWildcards, final boolean diacriticsInsensitive,
                               final Set<String> stopWords) {
        AllMatches combined = null;
        for (final String searchStr : searchStrings) {
            final List<String> searchTokens = useWildcards ? tokenizeWildcard(searchStr) : tokenize(searchStr);
            if (searchTokens.isEmpty()) {
                continue;
            }
            final AllMatches phraseMatches = new AllMatches();
            findPhraseMatches(searchTokens, caseInsensitive, useWildcards, diacriticsInsensitive, stopWords, phraseMatches);
            if (!phraseMatches.hasMatches()) {
                return new AllMatches(); // all must match — one failed
            }
            combined = (combined == null) ? phraseMatches : crossProduct(combined, phraseMatches);
        }
        return combined != null ? combined : singleEmptyMatch();
    }

    /**
     * "all words" mode: tokenize all search strings, every individual word must match.
     */
    private AllMatches evalAllWords(final List<String> searchStrings, final boolean caseInsensitive,
                                    final boolean useWildcards, final boolean diacriticsInsensitive,
                                    final Set<String> stopWords) {
        final List<String> allWords = new ArrayList<>();
        for (final String s : searchStrings) {
            allWords.addAll(useWildcards ? tokenizeWildcard(s) : tokenize(s));
        }
        if (allWords.isEmpty()) {
            return singleEmptyMatch();
        }
        AllMatches combined = null;
        for (final String word : allWords) {
            if (isStopWord(word, stopWords, caseInsensitive)) {
                continue;
            }
            final AllMatches wordMatches = new AllMatches();
            findWordMatches(word, caseInsensitive, useWildcards, diacriticsInsensitive, stopWords, wordMatches);
            if (!wordMatches.hasMatches()) {
                return new AllMatches(); // all must match
            }
            combined = (combined == null) ? wordMatches : crossProduct(combined, wordMatches);
        }
        return combined != null ? combined : singleEmptyMatch();
    }

    /**
     * "phrase" mode: all search strings concatenated form one phrase.
     */
    private AllMatches evalPhrase(final List<String> searchStrings, final boolean caseInsensitive,
                                  final boolean useWildcards, final boolean diacriticsInsensitive,
                                  final Set<String> stopWords) {
        final List<String> phraseTokens = new ArrayList<>();
        for (final String s : searchStrings) {
            phraseTokens.addAll(useWildcards ? tokenizeWildcard(s) : tokenize(s));
        }
        if (phraseTokens.isEmpty()) {
            return singleEmptyMatch();
        }
        final AllMatches result = new AllMatches();
        findPhraseMatches(phraseTokens, caseInsensitive, useWildcards, diacriticsInsensitive, stopWords, result);
        return result;
    }

    /**
     * Find all positions where a single word matches in the token list.
     */
    private void findWordMatches(final String word, final boolean caseInsensitive,
                                 final boolean useWildcards, final boolean diacriticsInsensitive,
                                 final Set<String> stopWords, final AllMatches result) {
        if (isStopWord(word, stopWords, caseInsensitive)) {
            // Stop words in search query are treated as automatically matching
            return;
        }
        for (int i = 0; i < totalTokens; i++) {
            if (wordMatches(tokens.get(i), word, caseInsensitive, useWildcards, diacriticsInsensitive)) {
                result.addMatch(new Match(i));
            }
        }
    }

    /**
     * Find all positions where a phrase (sequence of words) matches consecutively.
     * Stop words in the search phrase are treated as matching any source token.
     */
    private void findPhraseMatches(final List<String> phraseTokens, final boolean caseInsensitive,
                                   final boolean useWildcards, final boolean diacriticsInsensitive,
                                   final Set<String> stopWords, final AllMatches result) {
        final int phraseLen = phraseTokens.size();
        outer:
        for (int i = 0; i <= totalTokens - phraseLen; i++) {
            for (int j = 0; j < phraseLen; j++) {
                final String searchToken = phraseTokens.get(j);
                // Stop words in search phrases match any source token position
                if (isStopWord(searchToken, stopWords, caseInsensitive)) {
                    continue; // this position is OK
                }
                if (!wordMatches(tokens.get(i + j), searchToken, caseInsensitive, useWildcards, diacriticsInsensitive)) {
                    continue outer;
                }
            }
            // Found a phrase match at positions i..i+phraseLen-1
            final SortedSet<Integer> positions = new TreeSet<>();
            for (int j = 0; j < phraseLen; j++) {
                positions.add(i + j);
            }
            result.addMatch(new Match(positions, new TreeSet<>()));
        }
    }

    /**
     * Check if a source token matches a search word.
     */
    private boolean wordMatches(final String sourceToken, final String searchWord,
                                final boolean caseInsensitive, final boolean useWildcards,
                                final boolean diacriticsInsensitive) {
        String src = sourceToken;
        String search = searchWord;

        // Apply diacritics normalization if insensitive
        if (diacriticsInsensitive) {
            src = stripDiacritics(src);
            search = stripDiacritics(search);
        }

        if (useWildcards) {
            final String regex = wildcardToRegex(search, caseInsensitive);
            return Pattern.matches(regex, src);
        }
        if (caseInsensitive) {
            return src.equalsIgnoreCase(search);
        }
        return src.equals(search);
    }

    /**
     * Tokenize a wildcard search pattern into tokens.
     * Unlike the normal tokenizer, this preserves wildcard characters (., *, +, ?, \, {, })
     * within tokens. Splits on whitespace boundaries.
     */
    static List<String> tokenizeWildcard(final String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> result = new ArrayList<>();
        for (final String part : pattern.split("\\s+")) {
            if (!part.isEmpty()) {
                result.add(part);
            }
        }
        return result;
    }

    /**
     * Strip diacritical marks from a string using Unicode normalization.
     * NFD decomposes characters, then we remove combining diacritical marks.
     */
    private static String stripDiacritics(final String text) {
        final String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        // Remove combining diacritical marks (Unicode block 0300-036F)
        return normalized.replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
    }

    /**
     * Check if a word is in the stop word set.
     */
    private static boolean isStopWord(final String word, final Set<String> stopWords,
                                       final boolean caseInsensitive) {
        if (stopWords.isEmpty()) {
            return false;
        }
        return caseInsensitive ? stopWords.contains(word.toLowerCase(Locale.ROOT)) : stopWords.contains(word);
    }

    /**
     * Collect stop words from FTMatchOptions.
     * XQFT 3.0 §4.6: inline stop words and stop word URIs.
     */
    private static Set<String> collectStopWords(final FTMatchOptions options, final boolean caseInsensitive) {
        if (options == null) {
            return Collections.emptySet();
        }
        if (Boolean.TRUE.equals(options.getNoStopWords())) {
            return Collections.emptySet();
        }
        final List<String> inlineWords = options.getInlineStopWords();
        if (inlineWords == null || inlineWords.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<String> result = new HashSet<>();
        for (final String sw : inlineWords) {
            result.add(caseInsensitive ? sw.toLowerCase(Locale.ROOT) : sw);
        }
        return result;
    }

    /**
     * Convert XQFT wildcard pattern to Java regex.
     * XQFT wildcards: "." matches any single char, ".+" matches one or more,
     * ".*" matches zero or more, ".{n,m}" etc.
     */
    static String wildcardToRegex(final String pattern, final boolean caseInsensitive) {
        final StringBuilder sb = new StringBuilder();
        if (caseInsensitive) {
            sb.append("(?i)");
        }
        // XQFT wildcard grammar (§4.7):
        // "." matches any single char
        // ".?" zero or one, ".+" one or more, ".*" zero or more
        // ".{n-m}" n to m of any char (note: dash, not comma)
        // ".{a-z}" character range (single char in range)
        int i = 0;
        while (i < pattern.length()) {
            final char c = pattern.charAt(i);
            if (c == '.') {
                i++;
                if (i < pattern.length()) {
                    final char next = pattern.charAt(i);
                    if (next == '*' || next == '+' || next == '?') {
                        sb.append('.');
                        sb.append(next);
                        i++;
                    } else if (next == '{') {
                        // Extract content between { and }
                        i++; // skip {
                        final StringBuilder rangeContent = new StringBuilder();
                        while (i < pattern.length() && pattern.charAt(i) != '}') {
                            rangeContent.append(pattern.charAt(i));
                            i++;
                        }
                        if (i < pattern.length()) {
                            i++; // skip }
                        }
                        final String range = rangeContent.toString();
                        final int dashIdx = range.indexOf('-');
                        if (dashIdx > 0 && dashIdx < range.length() - 1) {
                            final String left = range.substring(0, dashIdx);
                            final String right = range.substring(dashIdx + 1);
                            if (left.chars().allMatch(Character::isDigit) && right.chars().allMatch(Character::isDigit)) {
                                // Numeric range: .{n-m} → .{n,m}
                                sb.append(".{").append(left).append(',').append(right).append('}');
                            } else {
                                // Character range: .{a-z} → [a-z]
                                sb.append('[').append(left).append('-').append(right).append(']');
                            }
                        } else {
                            // Single number: .{n} → .{n}
                            sb.append('.').append('{').append(range).append('}');
                        }
                    } else {
                        // Just "." — match any single char
                        sb.append('.');
                    }
                } else {
                    sb.append('.');
                }
            } else if (c == '\\') {
                // Escaped char — treat next char as literal
                i++;
                if (i < pattern.length()) {
                    sb.append(Pattern.quote(String.valueOf(pattern.charAt(i))));
                    i++;
                }
            } else {
                // Literal character — escape for regex
                sb.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return sb.toString();
    }

    // === Boolean operators ===

    AllMatches evalFTOr(final FTOr ftOr, final FTMatchOptions options)
            throws XPathException {
        final AllMatches result = new AllMatches();
        for (final Expression operand : ftOr.getOperands()) {
            final AllMatches sub = evalExpression(operand, options);
            result.getMatches().addAll(sub.getMatches());
        }
        return result;
    }

    AllMatches evalFTAnd(final FTAnd ftAnd, final FTMatchOptions options)
            throws XPathException {
        AllMatches combined = null;
        for (final Expression operand : ftAnd.getOperands()) {
            final AllMatches sub = evalExpression(operand, options);
            if (!sub.hasMatches()) {
                return new AllMatches(); // short-circuit: one operand has no matches
            }
            combined = (combined == null) ? sub : crossProduct(combined, sub);
        }
        return combined != null ? combined : singleEmptyMatch();
    }

    AllMatches evalFTMildNot(final FTMildNot ftMildNot, final FTMatchOptions options)
            throws XPathException {
        final List<Expression> operands = ftMildNot.getOperands();
        if (operands.isEmpty()) {
            return new AllMatches();
        }
        AllMatches result = evalExpression(operands.get(0), options);
        for (int i = 1; i < operands.size(); i++) {
            final AllMatches exclude = evalExpression(operands.get(i), options);
            result = applyMildNot(result, exclude);
        }
        return result;
    }

    /**
     * Mild not: remove matches from left where a right match covers ALL
     * include positions of the left match (XQFT 3.0 §4.5.3).
     *
     * A left match is removed only when there exists a right match whose
     * include positions are a superset of the left match's include positions.
     */
    private AllMatches applyMildNot(final AllMatches left, final AllMatches right) {
        if (!right.hasMatches()) {
            return left;
        }
        final AllMatches result = new AllMatches();
        for (final Match lm : left.getMatches()) {
            boolean covered = false;
            for (final Match rm : right.getMatches()) {
                if (rm.getIncludePositions().containsAll(lm.getIncludePositions())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                result.addMatch(lm);
            }
        }
        return result;
    }

    AllMatches evalFTUnaryNot(final FTUnaryNot ftNot, final FTMatchOptions options)
            throws XPathException {
        final AllMatches inner = evalExpression(ftNot.getOperand(), options);
        if (inner.hasMatches()) {
            return new AllMatches(); // negation: inner matched → overall doesn't match
        }
        return singleEmptyMatch(); // inner didn't match → overall matches
    }

    AllMatches evalFTPrimaryWithOptions(final FTPrimaryWithOptions pwo, final FTMatchOptions inheritedOptions)
            throws XPathException {
        // Merge match options: local options override inherited ones
        final FTMatchOptions effective = mergeOptions(inheritedOptions, pwo.getMatchOptions());

        // XQFT 3.0 §4.6: raise FTST0006 if stop word URIs are specified but not supported
        if (effective != null && !effective.getStopWordURIs().isEmpty()) {
            throw new XPathException(pwo, ErrorCodes.FTST0006,
                    "External stop word lists are not supported: " + effective.getStopWordURIs());
        }

        // XQFT 3.0 §4.8: raise FTST0009 for unsupported languages
        if (effective != null && effective.getLanguage() != null) {
            final String lang = effective.getLanguage().toLowerCase(Locale.ROOT);
            if (!lang.isEmpty() && !lang.equals("en") && !lang.startsWith("en-")) {
                throw new XPathException(pwo, ErrorCodes.FTST0009,
                        "Language not supported: " + effective.getLanguage());
            }
        }

        return evalExpression(pwo.getPrimary(), effective);
    }

    // === Positional filters ===

    AllMatches applyPosFilter(final AllMatches input, final Expression filter)
            throws XPathException {
        if (filter instanceof FTOrder) {
            return applyOrdered(input);
        } else if (filter instanceof FTWindow) {
            final FTWindow ftWindow = (FTWindow) filter;
            if (ftWindow.getUnit() != FTUnit.WORDS) {
                throw new XPathException(filter, ErrorCodes.FTST0003,
                        "Sentence/paragraph tokenization is not supported");
            }
            return applyWindow(input, ftWindow);
        } else if (filter instanceof FTDistance) {
            final FTDistance ftDistance = (FTDistance) filter;
            if (ftDistance.getUnit() != FTUnit.WORDS) {
                throw new XPathException(filter, ErrorCodes.FTST0003,
                        "Sentence/paragraph tokenization is not supported");
            }
            return applyDistance(input, ftDistance);
        } else if (filter instanceof FTContent) {
            return applyContent(input, (FTContent) filter);
        } else if (filter instanceof FTScope) {
            // Scope (same/different sentence/paragraph) always requires sentence/paragraph
            // boundaries which aren't supported by this tokenizer.
            throw new XPathException(filter, ErrorCodes.FTST0003,
                    "Sentence/paragraph tokenization is not supported");
        }
        return input;
    }

    /**
     * "ordered": keep matches where operand groups appear in ascending
     * position order — i.e., max position of group i < min position of group i+1.
     */
    private AllMatches applyOrdered(final AllMatches input) {
        final AllMatches result = new AllMatches();
        for (final Match m : input.getMatches()) {
            if (isOrdered(m)) {
                result.addMatch(m);
            }
        }
        return result;
    }

    private boolean isOrdered(final Match match) {
        final List<SortedSet<Integer>> groups = match.getOperandGroups();
        if (groups.size() <= 1) {
            return true;
        }
        int prevMax = Integer.MIN_VALUE;
        for (final SortedSet<Integer> group : groups) {
            if (group.isEmpty()) {
                continue;
            }
            if (group.first() <= prevMax) {
                return false;
            }
            prevMax = group.last();
        }
        return true;
    }

    /**
     * "window N words": all matched positions must fit within N consecutive token positions.
     */
    private AllMatches applyWindow(final AllMatches input, final FTWindow ftWindow)
            throws XPathException {
        final int windowSize = evalIntExpr(ftWindow.getWindowExpr());
        final AllMatches result = new AllMatches();
        for (final Match m : input.getMatches()) {
            final SortedSet<Integer> positions = m.getAllPositions();
            if (positions.isEmpty()) {
                result.addMatch(m);
            } else {
                final int span = positions.last() - positions.first() + 1;
                if (span <= windowSize) {
                    result.addMatch(m);
                }
            }
        }
        return result;
    }

    /**
     * "distance range unit": the distance between consecutive match positions
     * must satisfy the range constraint.
     */
    private AllMatches applyDistance(final AllMatches input, final FTDistance ftDistance)
            throws XPathException {
        final FTRange range = ftDistance.getRange();
        final int[] bounds = evalRange(range);
        final int min = bounds[0];
        final int max = bounds[1];

        final AllMatches result = new AllMatches();
        for (final Match m : input.getMatches()) {
            final List<Integer> posList = new ArrayList<>(m.getIncludePositions());
            if (posList.size() <= 1) {
                result.addMatch(m);
                continue;
            }
            boolean satisfies = true;
            for (int i = 1; i < posList.size(); i++) {
                final int dist = posList.get(i) - posList.get(i - 1) - 1; // gap between positions
                if (dist < min || dist > max) {
                    satisfies = false;
                    break;
                }
            }
            if (satisfies) {
                result.addMatch(m);
            }
        }
        return result;
    }

    /**
     * "at start" / "at end" / "entire content": content-based positional filter.
     */
    private AllMatches applyContent(final AllMatches input, final FTContent ftContent) {
        final AllMatches result = new AllMatches();
        for (final Match m : input.getMatches()) {
            final SortedSet<Integer> positions = m.getIncludePositions();
            if (positions.isEmpty()) {
                continue;
            }
            switch (ftContent.getContentType()) {
                case AT_START:
                    if (positions.first() == 0) {
                        result.addMatch(m);
                    }
                    break;
                case AT_END:
                    if (positions.last() == totalTokens - 1) {
                        result.addMatch(m);
                    }
                    break;
                case ENTIRE_CONTENT:
                    if (positions.first() == 0 && positions.last() == totalTokens - 1) {
                        result.addMatch(m);
                    }
                    break;
            }
        }
        return result;
    }

    /**
     * Apply FTTimes constraint: the number of matches must satisfy the range.
     * "occurs exactly N times" means exactly N distinct matches.
     */
    private AllMatches applyTimes(final AllMatches input, final FTTimes ftTimes)
            throws XPathException {
        final FTRange range = ftTimes.getRange();
        final int[] bounds = evalRange(range);
        final int min = bounds[0];
        final int max = bounds[1];
        final int matchCount = input.getMatches().size();

        if (matchCount >= min && matchCount <= max) {
            return input;
        }
        return new AllMatches(); // constraint not satisfied
    }

    // === Helpers ===

    /**
     * Cross product of two AllMatches: combine each match from left with
     * each match from right.
     */
    private AllMatches crossProduct(final AllMatches left, final AllMatches right) {
        final AllMatches result = new AllMatches();
        for (final Match lm : left.getMatches()) {
            for (final Match rm : right.getMatches()) {
                result.addMatch(lm.combine(rm));
            }
        }
        return result;
    }

    private AllMatches singleEmptyMatch() {
        final AllMatches am = new AllMatches();
        am.addMatch(new Match());
        return am;
    }

    private int evalIntExpr(final Expression expr) throws XPathException {
        final Sequence seq = expr.eval(null, null);
        return seq.itemAt(0).toJavaObject(int.class);
    }

    private int[] evalRange(final FTRange range) throws XPathException {
        switch (range.getMode()) {
            case EXACTLY: {
                final int n = evalIntExpr(range.getExpr1());
                return new int[]{n, n};
            }
            case AT_LEAST: {
                final int n = evalIntExpr(range.getExpr1());
                return new int[]{n, Integer.MAX_VALUE};
            }
            case AT_MOST: {
                final int n = evalIntExpr(range.getExpr1());
                return new int[]{0, n};
            }
            case FROM_TO: {
                final int from = evalIntExpr(range.getExpr1());
                final int to = evalIntExpr(range.getExpr2());
                return new int[]{from, to};
            }
            default:
                return new int[]{0, Integer.MAX_VALUE};
        }
    }

    /**
     * Merge inherited options with local overrides.
     */
    static FTMatchOptions mergeOptions(final FTMatchOptions inherited, final FTMatchOptions local) {
        if (local == null) {
            return inherited;
        }
        if (inherited == null) {
            return local;
        }
        // Local overrides inherited
        final FTMatchOptions merged = new FTMatchOptions();
        merged.setCaseMode(local.getCaseMode() != null ? local.getCaseMode() : inherited.getCaseMode());
        merged.setDiacriticsMode(local.getDiacriticsMode() != null ? local.getDiacriticsMode() : inherited.getDiacriticsMode());
        merged.setStemming(local.getStemming() != null ? local.getStemming() : inherited.getStemming());
        merged.setWildcards(local.getWildcards() != null ? local.getWildcards() : inherited.getWildcards());
        merged.setLanguage(local.getLanguage() != null ? local.getLanguage() : inherited.getLanguage());
        merged.setNoThesaurus(local.getNoThesaurus() != null ? local.getNoThesaurus() : inherited.getNoThesaurus());
        merged.setNoStopWords(local.getNoStopWords() != null ? local.getNoStopWords() : inherited.getNoStopWords());
        // Merge stop word lists (local overrides if non-empty)
        if (!local.getInlineStopWords().isEmpty()) {
            merged.getInlineStopWords().addAll(local.getInlineStopWords());
        } else if (inherited.getInlineStopWords() != null) {
            merged.getInlineStopWords().addAll(inherited.getInlineStopWords());
        }
        if (!local.getStopWordURIs().isEmpty()) {
            merged.getStopWordURIs().addAll(local.getStopWordURIs());
        } else if (inherited.getStopWordURIs() != null) {
            merged.getStopWordURIs().addAll(inherited.getStopWordURIs());
        }
        return merged;
    }
}
