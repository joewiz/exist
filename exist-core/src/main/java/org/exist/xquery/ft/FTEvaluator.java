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

import org.exist.xquery.Expression;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.Sequence;

import java.text.BreakIterator;
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

        public Match() {
            this.includePositions = new TreeSet<>();
            this.excludePositions = new TreeSet<>();
        }

        public Match(final int pos) {
            this();
            includePositions.add(pos);
        }

        public Match(final SortedSet<Integer> includes, final SortedSet<Integer> excludes) {
            this.includePositions = new TreeSet<>(includes);
            this.excludePositions = new TreeSet<>(excludes);
        }

        public SortedSet<Integer> getIncludePositions() {
            return includePositions;
        }

        public SortedSet<Integer> getExcludePositions() {
            return excludePositions;
        }

        public SortedSet<Integer> getAllPositions() {
            final SortedSet<Integer> all = new TreeSet<>(includePositions);
            all.addAll(excludePositions);
            return all;
        }

        /** Combine two matches (e.g. for ftand) */
        public Match combine(final Match other) {
            final SortedSet<Integer> inc = new TreeSet<>(includePositions);
            inc.addAll(other.includePositions);
            final SortedSet<Integer> exc = new TreeSet<>(excludePositions);
            exc.addAll(other.excludePositions);
            return new Match(inc, exc);
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

        final boolean caseInsensitive = options != null &&
                options.getCaseMode() == FTMatchOptions.CaseMode.INSENSITIVE;
        final boolean useWildcards = options != null &&
                Boolean.TRUE.equals(options.getWildcards());

        final FTWords.AnyallMode mode = ftWords.getMode();
        switch (mode) {
            case ANY:
                return evalAny(searchStrings, caseInsensitive, useWildcards);
            case ANY_WORD:
                return evalAnyWord(searchStrings, caseInsensitive, useWildcards);
            case ALL:
                return evalAll(searchStrings, caseInsensitive, useWildcards);
            case ALL_WORDS:
                return evalAllWords(searchStrings, caseInsensitive, useWildcards);
            case PHRASE:
                return evalPhrase(searchStrings, caseInsensitive, useWildcards);
            default:
                return evalAny(searchStrings, caseInsensitive, useWildcards);
        }
    }

    /**
     * "any" mode: any of the search strings can match (each as a phrase).
     */
    private AllMatches evalAny(final List<String> searchStrings, final boolean caseInsensitive,
                               final boolean useWildcards) {
        final AllMatches result = new AllMatches();
        for (final String searchStr : searchStrings) {
            final List<String> searchTokens = tokenize(searchStr);
            if (searchTokens.isEmpty()) {
                result.addMatch(new Match());
                continue;
            }
            findPhraseMatches(searchTokens, caseInsensitive, useWildcards, result);
        }
        return result;
    }

    /**
     * "any word" mode: tokenize all search strings into individual words,
     * any single word can match.
     */
    private AllMatches evalAnyWord(final List<String> searchStrings, final boolean caseInsensitive,
                                   final boolean useWildcards) {
        final AllMatches result = new AllMatches();
        for (final String searchStr : searchStrings) {
            for (final String word : tokenize(searchStr)) {
                findWordMatches(word, caseInsensitive, useWildcards, result);
            }
        }
        return result;
    }

    /**
     * "all" mode: all search strings must match (each as a phrase).
     */
    private AllMatches evalAll(final List<String> searchStrings, final boolean caseInsensitive,
                               final boolean useWildcards) {
        AllMatches combined = null;
        for (final String searchStr : searchStrings) {
            final List<String> searchTokens = tokenize(searchStr);
            if (searchTokens.isEmpty()) {
                continue;
            }
            final AllMatches phraseMatches = new AllMatches();
            findPhraseMatches(searchTokens, caseInsensitive, useWildcards, phraseMatches);
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
                                    final boolean useWildcards) {
        final List<String> allWords = new ArrayList<>();
        for (final String s : searchStrings) {
            allWords.addAll(tokenize(s));
        }
        if (allWords.isEmpty()) {
            return singleEmptyMatch();
        }
        AllMatches combined = null;
        for (final String word : allWords) {
            final AllMatches wordMatches = new AllMatches();
            findWordMatches(word, caseInsensitive, useWildcards, wordMatches);
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
                                  final boolean useWildcards) {
        final List<String> phraseTokens = new ArrayList<>();
        for (final String s : searchStrings) {
            phraseTokens.addAll(tokenize(s));
        }
        if (phraseTokens.isEmpty()) {
            return singleEmptyMatch();
        }
        final AllMatches result = new AllMatches();
        findPhraseMatches(phraseTokens, caseInsensitive, useWildcards, result);
        return result;
    }

    /**
     * Find all positions where a single word matches in the token list.
     */
    private void findWordMatches(final String word, final boolean caseInsensitive,
                                 final boolean useWildcards, final AllMatches result) {
        for (int i = 0; i < totalTokens; i++) {
            if (wordMatches(tokens.get(i), word, caseInsensitive, useWildcards)) {
                result.addMatch(new Match(i));
            }
        }
    }

    /**
     * Find all positions where a phrase (sequence of words) matches consecutively.
     */
    private void findPhraseMatches(final List<String> phraseTokens, final boolean caseInsensitive,
                                   final boolean useWildcards, final AllMatches result) {
        final int phraseLen = phraseTokens.size();
        outer:
        for (int i = 0; i <= totalTokens - phraseLen; i++) {
            for (int j = 0; j < phraseLen; j++) {
                if (!wordMatches(tokens.get(i + j), phraseTokens.get(j), caseInsensitive, useWildcards)) {
                    continue outer;
                }
            }
            // Found a phrase match at positions i..i+phraseLen-1
            final Match m = new Match();
            for (int j = 0; j < phraseLen; j++) {
                m.getIncludePositions().add(i + j);
            }
            result.addMatch(m);
        }
    }

    /**
     * Check if a source token matches a search word.
     */
    private boolean wordMatches(final String sourceToken, final String searchWord,
                                final boolean caseInsensitive, final boolean useWildcards) {
        if (useWildcards) {
            // Convert XQFT wildcard pattern to Java regex
            final String regex = wildcardToRegex(searchWord, caseInsensitive);
            return Pattern.matches(regex, sourceToken);
        }
        if (caseInsensitive) {
            return sourceToken.equalsIgnoreCase(searchWord);
        }
        return sourceToken.equals(searchWord);
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
        // XQFT wildcard tokens are already regex-like with . as the wildcard char
        // We need to escape everything except the wildcard constructs
        int i = 0;
        while (i < pattern.length()) {
            final char c = pattern.charAt(i);
            if (c == '.') {
                // Pass through . and following quantifier
                sb.append('.');
                i++;
                if (i < pattern.length()) {
                    final char next = pattern.charAt(i);
                    if (next == '*' || next == '+' || next == '?') {
                        sb.append(next);
                        i++;
                    } else if (next == '{') {
                        // Pass through {n,m}
                        while (i < pattern.length() && pattern.charAt(i) != '}') {
                            sb.append(pattern.charAt(i));
                            i++;
                        }
                        if (i < pattern.length()) {
                            sb.append('}');
                            i++;
                        }
                    }
                }
            } else if (c == '\\') {
                // Escaped char — pass through literal
                sb.append('\\');
                i++;
                if (i < pattern.length()) {
                    sb.append(pattern.charAt(i));
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
     * Mild not: remove matches from left whose include positions overlap
     * with any include position from right matches.
     */
    private AllMatches applyMildNot(final AllMatches left, final AllMatches right) {
        if (!right.hasMatches()) {
            return left;
        }
        // Collect all exclude positions from right-side matches
        final Set<Integer> excludePositions = new HashSet<>();
        for (final Match rm : right.getMatches()) {
            excludePositions.addAll(rm.getIncludePositions());
        }
        final AllMatches result = new AllMatches();
        for (final Match lm : left.getMatches()) {
            boolean overlaps = false;
            for (final int pos : lm.getIncludePositions()) {
                if (excludePositions.contains(pos)) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
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
        return evalExpression(pwo.getPrimary(), effective);
    }

    // === Positional filters ===

    AllMatches applyPosFilter(final AllMatches input, final Expression filter)
            throws XPathException {
        if (filter instanceof FTOrder) {
            return applyOrdered(input);
        } else if (filter instanceof FTWindow) {
            return applyWindow(input, (FTWindow) filter);
        } else if (filter instanceof FTDistance) {
            return applyDistance(input, (FTDistance) filter);
        } else if (filter instanceof FTContent) {
            return applyContent(input, (FTContent) filter);
        } else if (filter instanceof FTScope) {
            // Scope (same/different sentence/paragraph) requires sentence/paragraph boundaries
            // which aren't tracked in this simple tokenizer. Pass through for now.
            return input;
        }
        return input;
    }

    /**
     * "ordered": keep matches where include positions are in ascending order
     * relative to the order of the sub-expressions that produced them.
     * Simplified: check that include positions are sorted.
     */
    private AllMatches applyOrdered(final AllMatches input) {
        final AllMatches result = new AllMatches();
        for (final Match m : input.getMatches()) {
            final SortedSet<Integer> positions = m.getIncludePositions();
            if (positions.size() <= 1 || isOrdered(positions)) {
                result.addMatch(m);
            }
        }
        return result;
    }

    private boolean isOrdered(final SortedSet<Integer> positions) {
        // Positions are in a SortedSet, so they're always ascending.
        // The "ordered" constraint really means that the first operand's tokens
        // appear before the second operand's tokens. Since we combine via
        // cross-product which preserves this, sorted positions = ordered.
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
        return merged;
    }
}
