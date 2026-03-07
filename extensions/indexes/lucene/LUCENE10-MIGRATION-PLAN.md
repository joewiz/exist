# Lucene 10 Migration Plan: TEI & Highlighting Features

## Overview

This document outlines the plan for migrating eXist-db's Lucene integration from
Lucene 4.x to Lucene 10.x, focusing on the TEI/KWIC highlighting pipeline and
full-text search features.

## Current Architecture Issues

### Highlighting Pipeline (`LuceneMatchListener`)
- `scanMatches()` manually reconstructs query matching logic using a `QueryVisitor`
  pattern, handling `TermQuery`, `PhraseQuery`, `BooleanQuery`, etc. case-by-case
- Proximity/span queries are not handled, causing missing `exist:match` markers (#833)
- Uses character-offset arithmetic to wrap matches in `exist:match` elements
- Fixed-character-count windowing with no passage ranking

### TEI Integration
- `TEIMatchListener` extends `LuceneMatchListener` with TEI namespace handling
- Same underlying match-detection bugs apply

### KWIC Module (`KWICModule`)
- `ft:highlight()` and `kwic:summarize()` use string manipulation on serialized XML
- No passage relevance scoring — returns first N characters, not best passage

## Migration Plan

### Phase 1: Core Lucene 10 API Migration

1. **Update Lucene dependencies** to 10.x in `pom.xml`
2. **Fix compilation breaks** from removed/changed APIs:
   - `IndexWriterConfig` changes
   - `Analyzer` API changes
   - `Query` subclass changes (e.g., `BooleanQuery` is immutable since Lucene 5)
   - `IndexReader` / `DirectoryReader` API changes
   - Codec/postings format changes
3. **Update `LuceneIndex`** — index creation, opening, segment merging
4. **Update `LuceneUtil`** — query parsing, field analysis

### Phase 2: UnifiedHighlighter Integration

This is the highest-value change. Replace the manual match-detection in
`LuceneMatchListener.scanMatches()` with Lucene 10's `UnifiedHighlighter`.

#### Key Design Decisions

**Offset Strategy:** Configure fields with
`IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS` to enable the fastest
postings-based offset source (~1.1 bytes/position overhead, sequential I/O).

**WEIGHT_MATCHES Mode (default in Lucene 9+):** Delegates match detection to
`Weight.matches(LeafReaderContext, int)` — the query itself reports where it
matches. This fixes:
- Missing `exist:match` for proximity/span queries (#833)
- Incorrect phrase highlighting
- All edge cases with complex boolean combinations

**Passage Breaking:** Use `BreakIterator.getSentenceInstance()` for sentence-aligned
passages instead of fixed character windows. This produces more readable KWIC output.

**Passage Scoring:** BM25-based scoring (`k1=1.2, b=0.75, pivot=87`) with position
normalization biasing toward earlier passages. Replaces the current "first N chars"
approach with "best N chars."

#### Implementation Steps

1. **Create `ExistPassageFormatter`** — custom `PassageFormatter` that produces
   `exist:match` elements instead of `<b>` tags
2. **Create `ExistHighlighter`** — wrapper around `UnifiedHighlighter.Builder` that:
   - Configures the formatter, scorer, and break iterator
   - Handles eXist's XML-aware field storage
   - Maps Lucene passages back to XML node positions
3. **Replace `LuceneMatchListener.scanMatches()`** with calls to `ExistHighlighter`
4. **Update `TEIMatchListener`** to use the new highlighter with TEI namespace mapping
5. **Rewrite `ft:highlight()`** to use `UnifiedHighlighter` directly, returning
   ranked passages with `exist:match` markers

### Phase 3: KWIC Module Modernization

1. **`kwic:summarize()`** — use UnifiedHighlighter's passage selection and scoring
   instead of string-based character counting
2. **`kwic:display()`** — format passages using the highlighter's output
3. **Add passage ranking** — return the highest-scoring passages, not just the first
4. **Support configurable context** — sentence-based or paragraph-based via
   `BreakIterator` selection

### Phase 4: `collection.xconf` Configuration

Update the Lucene index configuration schema to support:

```xml
<text qname="tei:p"
      highlight-offsets="postings|term-vectors|analysis"
      passage-break="sentence|paragraph|whole"
      passage-scorer-k1="1.2"
      passage-scorer-b="0.75">
```

Defaults should work well out of the box (postings offsets, sentence breaks, BM25
scoring with standard parameters).

### Phase 5: Testing & Compatibility

1. **Unit tests** for `ExistHighlighter` with various query types:
   - Simple term queries
   - Phrase queries
   - Proximity queries (the #833 fix)
   - Wildcard/regex queries
   - Boolean combinations
   - Fuzzy queries
2. **Integration tests** for `ft:highlight()` and `kwic:summarize()`
3. **TEI-specific tests** with TEI namespace handling
4. **Index migration** — document re-indexing requirements for existing databases
5. **Performance benchmarks** — compare highlighting speed old vs. new

## Key Files to Modify

| File | Changes |
|------|---------|
| `extensions/indexes/lucene/pom.xml` | Update Lucene version |
| `LuceneIndex.java` | Index creation/opening API changes |
| `LuceneMatchListener.java` | Replace `scanMatches()` with UnifiedHighlighter |
| `TEIMatchListener.java` | Update for new highlighter |
| `LuceneUtil.java` | Query parsing API changes |
| `KWICModule.java` | Passage-based KWIC with scoring |
| `Highlight.java` (`ft:highlight`) | UnifiedHighlighter integration |
| `collection.xconf` schema | New highlighting configuration options |

## References

- [UnifiedHighlighter API (Lucene 10.1.0)](https://lucene.apache.org/core/10_1_0/highlighter/org/apache/lucene/search/uhighlight/UnifiedHighlighter.html)
- [LUCENE-7438 — UnifiedHighlighter proposal](https://github.com/apache/lucene/issues/8490)
- [LUCENE-8286 — WEIGHT_MATCHES support](https://issues.apache.org/jira/browse/LUCENE-8286)
- [eXist-db Issue #833 — Missing exist:match for proximity queries](https://github.com/eXist-db/exist/issues/833)
- [PassageScorer API](https://lucene.apache.org/core/10_1_0/highlighter/org/apache/lucene/search/uhighlight/PassageScorer.html)
