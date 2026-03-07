# Lucene 10 Migration Plan: Highlighting Features

## Overview

This document tracks the plan for leveraging Lucene 10's highlighting
capabilities in eXist-db's full-text search infrastructure.

## Phase Status

| Phase | Description | Status |
|-------|-------------|--------|
| Phase 1 | Core Lucene 10 API Migration | **COMPLETE** (duncdrum) |
| Phase 2 | Match Highlighting via Matches API | **COMPLETE** |
| Phase 3 | New Highlighting Functions | **COMPLETE** |
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

## Phase 3: New Highlighting Functions (COMPLETE)

Two new XQuery functions in the `ft:` namespace:

### `ft:highlight($nodes)`

Creates in-memory copies of nodes with Lucene match terms wrapped in
`exist:match` elements. A convenience function equivalent to
`util:expand($nodes, "highlight-matches=elements expand-xincludes=no")`
but with a cleaner Lucene-specific API.

```xquery
let $hits := //p[ft:query(., "quick brown fox")]
for $hit in $hits
return ft:highlight($hit)
```

### `ft:get-passages($hits, $max-passages?, $options?)`

Extracts the best-scoring text passages from full-text query hits,
ranked by match density. Unlike `kwic:summarize()` which returns
context around the *first* match, this function scores all passages
and returns the top N by relevance.

```xquery
let $hits := //p[ft:query(., "quick brown fox")]
for $hit in $hits
return ft:get-passages($hit, 3, <options width="150"/>)
```

Returns `exist:passage` elements with a `score` attribute, containing
text with match terms wrapped in `exist:match`:

```xml
<exist:passage score="3.20">
  The <exist:match>quick brown fox</exist:match> jumps over the lazy dog.
</exist:passage>
```

**Options:**
- `width` — target passage width in characters (default: 150)
- `break` — `"sentence"` (default) or `"character"` passage breaking

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
- [x] ft:highlight() and ft:get-passages() XQuery tests
- [x] Performance benchmark (all query types, 10/50/200 paragraphs)

### Benchmark Results (Apple M1 Pro)

| Query Type | 10 paras | 50 paras | 200 paras | Per-paragraph |
|-----------|----------|----------|-----------|---------------|
| term | 0.29 ms | 0.79 ms | 3.30 ms | ~0.02 ms |
| phrase | 0.41 ms | 1.90 ms | 4.77 ms | ~0.02 ms |
| proximity | 0.53 ms | 1.71 ms | 5.61 ms | ~0.03 ms |
| wildcard | 0.58 ms | 1.13 ms | 5.10 ms | ~0.03 ms |
| boolean | 0.48 ms | 1.88 ms | 3.67 ms | ~0.02 ms |
| regex | 0.77 ms | 1.22 ms | 5.93 ms | ~0.03 ms |
| fuzzy | 7.84 ms | 24.97 ms | 81.41 ms | ~0.41 ms |

## Key Files Modified

| File | Changes |
|------|---------|
| `pom.xml` | Added `lucene-memory` dependency |
| `LuceneIndexWorker.java` | IndexOptions → include offsets; removed dead `getTerms()` |
| `LuceneMatchListener.java` | Replaced `scanMatches()` with MemoryIndex + Matches API; overlap merging; BoostQuery support |
| `Field.java` | Replaced `highlightMatches()` with MemoryIndex + Matches API |
| `PlainTextHighlighter.java` | Rewritten to use MemoryIndex + Matches API |
| `MarkableTokenFilter.java` | **Deleted** — dead code |
| `GetPassages.java` | **New** — `ft:get-passages()` ranked passage extraction |
| `Highlight.java` | **New** — `ft:highlight()` convenience function |
| `LuceneModule.java` | Registered new functions |
| `LuceneMatchListenerTest.java` | Added phrase, regex, prefix, boolean, proximity, fuzzy, overlap tests |
| `HighlightingBenchmark.java` | **New** — performance benchmark |
| `ft-match.xql` | Un-pended proximity test; added ft:highlight and ft:get-passages tests |

## References

- [Lucene Matches API](https://lucene.apache.org/core/10_1_0/core/org/apache/lucene/search/Matches.html)
- [MemoryIndex](https://lucene.apache.org/core/10_1_0/memory/org/apache/lucene/index/memory/MemoryIndex.html)
- [eXist-db Issue #833 — Missing exist:match for proximity queries](https://github.com/eXist-db/exist/issues/833)
