# Lucene 10 Migration Plan: Highlighting Features

## Overview

This document tracks the plan for leveraging Lucene 10's highlighting
capabilities in eXist-db's full-text search infrastructure.

## Phase Status

| Phase | Description | Status |
|-------|-------------|--------|
| Phase 1 | Core Lucene 10 API Migration | **COMPLETE** (duncdrum) |
| Phase 2 | Match Highlighting via Matches API | **COMPLETE** |
| Phase 3 | KWIC Module Modernization | Planned |
| Phase 4 | `collection.xconf` Configuration | Planned |
| Phase 5 | Testing & Compatibility | **COMPLETE** |

## Phase 1: Core Lucene 10 API Migration (COMPLETE)

Completed by duncdrum in 96 commits on the `lucene-update` branch.
Lucene upgraded from 4.x to **10.3.0**. Key changes:
- `BooleanQuery.Builder` pattern (immutable queries)
- `SearcherTaxonomyManager` / `ReaderManager` for index access
- `NumericUtils.intToSortableBytes()` for document ID encoding
- Analyzer API changes (no Version parameter)
- Bug fixes: #4584 (spans across inline elements), #4835 (multiple match highlighting)
- 30+ new regression tests

## Phase 2: Match Highlighting via Matches API (COMPLETE)

Replaced the manual token-by-token match detection with Lucene 10's
`Matches` API via `MemoryIndex`. This was the highest-value change.

### What Changed

**`LuceneMatchListener.scanMatches()`** — The old approach tokenized the
concatenated text content and checked each token against extracted query
terms, only handling `TermQuery` and `PhraseQuery`. The new approach:

1. Extracts text content from XML nodes (unchanged)
2. Creates a `MemoryIndex` with the text and the correct field name/analyzer
3. Uses `Weight.matches()` to get character-level match offsets
4. Maps offsets back to XML text nodes via `OffsetList` (unchanged)

This correctly handles **all** query types:
- Term queries
- Phrase queries (highlighted as single spans)
- Proximity/span queries (fixes #833)
- Wildcard queries
- Fuzzy queries
- Regex queries
- Prefix queries
- Complex boolean combinations
- Boosted queries

**`Field.highlightMatches()`** — Same MemoryIndex approach applied to
`ft:highlight-field-matches` for consistent highlighting across APIs.

**`PlainTextHighlighter`** — Rewritten for `ft:search` path.

**`extractContentQuery()`** — New utility that strips non-content-field
clauses from queries (e.g. `_idx` FILTER, `pub-year` range queries,
`BoostQuery` wrappers) so the query can be used against a single-field
MemoryIndex.

**Overlap merging** — Match spans are inserted in sorted order and
merged during emission, so overlapping spans from different query clauses
(e.g. term + phrase containing that term) produce clean, non-nested
`exist:match` output.

**Dead code removal** — `MarkableTokenFilter` (entire class) and
`LuceneIndexWorker.getTerms()` removed — no longer needed after migration.

**Index Options** — Changed from `DOCS_AND_FREQS_AND_POSITIONS` to
`DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS` to enable future optimization
via postings-based offset retrieval on the persistent index.

### Dependencies Added

- `lucene-memory` 10.3.0 — provides `MemoryIndex`

### Test Results

655 tests pass, 0 failures.

## Phase 3: KWIC Module Modernization (Planned)

The `kwic.xql` module uses character-counting to extract context around
`exist:match` elements. This could be improved with passage scoring:

1. **`kwic:summarize()`** — Consider using UnifiedHighlighter's BM25-based
   passage scoring to return the *best* passage, not just the *first*
2. **Passage ranking** — Return highest-scoring passages
3. **Configurable context** — Sentence-based or paragraph-based via
   `BreakIterator` selection

This phase requires adding `lucene-highlighter` dependency for
`UnifiedHighlighter`, `PassageFormatter`, and `PassageScorer`.

## Phase 4: `collection.xconf` Configuration (Planned)

Update the Lucene index configuration schema to support:

```xml
<text qname="p"
      passage-break="sentence|paragraph|whole"
      passage-scorer-k1="1.2"
      passage-scorer-b="0.75">
```

Defaults should work well out of the box.

## Phase 5: Testing & Compatibility (COMPLETE)

- [x] Unit tests for proximity, wildcard, fuzzy query highlighting
- [x] Phrase query highlighting (single-span assertion)
- [x] Regex, prefix query highlighting
- [x] Boolean combination highlighting
- [x] Overlapping match span merging
- [x] Regression tests for #4584, #4835
- [x] Field-highlight tests with mixed content/field queries
- [x] XQuery proximity test un-pended (was pending for #833)
- [x] BoostQuery handling in extractContentQuery
- [ ] Performance benchmarks — compare highlighting speed old vs. new
- [ ] Index migration documentation — reindexing requirements

## Key Files Modified

| File | Changes |
|------|---------|
| `pom.xml` | Added `lucene-memory` dependency |
| `LuceneIndexWorker.java` | IndexOptions → include offsets; removed dead `getTerms()` |
| `LuceneMatchListener.java` | Replaced `scanMatches()` with MemoryIndex + Matches API; overlap merging; BoostQuery support |
| `Field.java` | Replaced `highlightMatches()` with MemoryIndex + Matches API |
| `PlainTextHighlighter.java` | Rewritten to use MemoryIndex + Matches API |
| `MarkableTokenFilter.java` | **Deleted** — dead code |
| `LuceneMatchListenerTest.java` | Added phrase, regex, prefix, boolean, proximity, fuzzy, overlap tests |
| `ft-match.xql` | Un-pended proximity/slop test |

## References

- [Lucene Matches API](https://lucene.apache.org/core/10_1_0/core/org/apache/lucene/search/Matches.html)
- [MemoryIndex](https://lucene.apache.org/core/10_1_0/memory/org/apache/lucene/index/memory/MemoryIndex.html)
- [UnifiedHighlighter API](https://lucene.apache.org/core/10_1_0/highlighter/org/apache/lucene/search/uhighlight/UnifiedHighlighter.html)
- [eXist-db Issue #833 — Missing exist:match for proximity queries](https://github.com/eXist-db/exist/issues/833)
