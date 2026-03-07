# XQuery Full Text (XQFT) Implementation Plan for eXist-db with Lucene 10

## Executive Summary

This document analyzes the feasibility and approach for implementing the W3C XQuery and XPath Full Text 3.0 (XQFT) specification in eXist-db's next major version, which will upgrade from Lucene 4.10.4 to Lucene 10. It examines which Lucene 10 features facilitate XQFT conformance, identifies gaps where Lucene cannot directly support XQFT semantics, evaluates the feasibility of non-indexed (in-memory) full-text search, and uses BaseX as a reference implementation.

---

## Table of Contents

1. [Current State of eXist-db Full-Text Search](#1-current-state-of-exist-db-full-text-search)
2. [XQFT Specification Overview](#2-xqft-specification-overview)
3. [Lucene 10: Features Facilitating XQFT](#3-lucene-10-features-facilitating-xqft)
4. [Gap Analysis: What Lucene Cannot Support](#4-gap-analysis-what-lucene-cannot-support)
5. [BaseX as Reference Implementation](#5-basex-as-reference-implementation)
6. [Non-Indexed (In-Memory) Full-Text Search](#6-non-indexed-in-memory-full-text-search)
7. [Implementation Architecture](#7-implementation-architecture)
8. [Phased Implementation Plan](#8-phased-implementation-plan)
9. [Migration from ft:query to XQFT](#9-migration-from-ftquery-to-xqft)
10. [Risk Analysis and Open Questions](#10-risk-analysis-and-open-questions)

---

## 1. Current State of eXist-db Full-Text Search

### 1.1 Lucene Integration (Lucene 4.10.4)

eXist-db currently uses **Lucene 4.10.4** (defined in `exist-parent/pom.xml`), integrated as an optional index module under `extensions/indexes/lucene/`. The integration is mature but proprietary — it does not implement the W3C XQFT standard.

**Key components:**

| Component | Location | Role |
|-----------|----------|------|
| `LuceneIndex` | `indexing/lucene/LuceneIndex.java` | Main index lifecycle, manages IndexWriter, ReaderManager, TaxonomyWriter |
| `LuceneIndexWorker` | `indexing/lucene/LuceneIndexWorker.java` | Indexing pipeline, SAX-driven text extraction, search execution |
| `LuceneIndexConfig` | `indexing/lucene/LuceneIndexConfig.java` | Per-element configuration from collection.xconf |
| `LuceneMatchListener` | `indexing/lucene/LuceneMatchListener.java` | Match highlighting and offset extraction |
| `XMLToQuery` | `indexing/lucene/XMLToQuery.java` | Translates XML query descriptions to Lucene Query objects |
| `LuceneModule` | `xquery/modules/lucene/LuceneModule.java` | XQuery function module (`ft:query`, `ft:score`, etc.) |
| `AnalyzerConfig` | `indexing/lucene/AnalyzerConfig.java` | Reflection-based analyzer instantiation |

**Current API** — eXist-db uses `ft:query()` (not `ft:contains`):
```xquery
(: eXist-db proprietary full-text search :)
//speech[ft:query(., 'love AND "star crossed"')]

(: vs. XQFT standard syntax :)
//speech[. contains text 'love' ftand 'star crossed' all words]
```

### 1.2 Current Index Configuration

Indexes are configured via `collection.xconf`:
```xml
<collection xmlns="http://exist-db.org/collection-config/1.0">
  <index>
    <lucene>
      <analyzer class="org.apache.lucene.analysis.standard.StandardAnalyzer"/>
      <text qname="SPEECH">
        <ignore qname="SPEAKER"/>
      </text>
      <text qname="TITLE"/>
    </lucene>
  </index>
</collection>
```

### 1.3 What Must Change

The jump from Lucene 4.10.4 to Lucene 10 is massive (~6 major versions). Key breaking changes include:
- Span queries moved from `o.a.l.search.spans` to `o.a.l.queries.spans` (separate module)
- `Version.LUCENE_4_10_4` constant eliminated; no more version-parameterized analyzers
- `Analyzer` API changes (no more `ReusableAnalyzerBase`)
- `Filter` class removed, replaced by query-based filtering
- Codec and directory API overhauls
- Taxonomy/faceting API modernized
- `IndexWriter` configuration changes

---

## 2. XQFT Specification Overview

### 2.1 Core Language Constructs

The W3C XQuery and XPath Full Text 3.0 Recommendation (W3C, 2015) defines a declarative full-text search language embedded in XQuery/XPath expressions.

**FTContains Expression:**
```
Expr FTContainsExpr ::= RangeExpr ( "contains" "text" FTSelection FTIgnoreOption? )?
```

**FTSelection** — the query tree:

| Construct | Syntax | Semantics |
|-----------|--------|-----------|
| **FTWords** | `'word'`, `'phrase' all words`, `'a' any word` | Terminal: match words/phrases |
| **FTOr** | `A ftor B` | Union of matches |
| **FTAnd** | `A ftand B` | Intersection of matches |
| **FTMildNot** | `A not in B` | A's matches minus those containing B |
| **FTUnaryNot** | `ftnot A` | Negation (only in FTAnd) |
| **FTOrder** | `ordered` | Matches must appear in query order |
| **FTWindow** | `window N words` | All matches within N tokens |
| **FTDistance** | `distance N words` | Between-match distance constraint |
| **FTScope** | `same sentence` / `same paragraph` | Structural scope constraint |
| **FTContent** | `at start` / `at end` / `entire content` | Anchoring constraints |
| **FTTimes** | `occurs N times` | Cardinality constraint |

### 2.2 Match Options

| Option | Syntax | Default | Description |
|--------|--------|---------|-------------|
| Case | `case sensitive/insensitive` | implementation-defined | Case folding |
| Diacritics | `diacritics sensitive/insensitive` | implementation-defined | Accent folding |
| Stemming | `with stemming` | no stemming | Morphological normalization |
| Thesaurus | `with thesaurus at URI` | no thesaurus | Synonym expansion |
| Stop Words | `with stop words at URI` | no stop words | Stop word filtering |
| Language | `language "en"` | implementation-defined | Language for analysis |
| Wildcards | `with wildcards` | no wildcards | `.` and `.*` in search terms |
| Extension | `with FTExtensionOption` | — | Implementation-defined extensions |

### 2.3 FTIgnoreOption

```xquery
(: Ignore footnotes when searching :)
book contains text "important concept"
  without content fn:footnote
```

This filters out content from specified nodes before matching — a feature deeply tied to XML awareness.

### 2.4 Scoring

XQFT defines a `score` clause:
```xquery
for $doc score $s in //article
where $doc contains text "xml database"
order by $s descending
return $doc
```

Scoring semantics are implementation-defined; the spec only requires that scores be `xs:double` values between 0 and 1.

### 2.5 Data Model: AllMatches

The XQFT formal semantics define a complex match model:
- **AllMatches**: set of possible Match alternatives
- **Match**: a set of StringInclude and StringExclude pairs
- **StringInclude/StringExclude**: token position ranges (queryPos, startPos, endPos)

This positional model is central to how positional filters (FTOrder, FTWindow, FTDistance, FTScope, FTContent) operate. Every XQFT operation must track and propagate position information.

---

## 3. Lucene 10: Features Facilitating XQFT

### 3.1 Span Queries → Direct XQFT Positional Filter Support

Lucene's span query framework provides the strongest mapping to XQFT's positional model. In Lucene 10, spans have been moved to `org.apache.lucene.queries.spans` (in the `lucene-queries` module):

| Lucene 10 Span Query | XQFT Feature | Mapping Quality |
|-----------------------|--------------|-----------------|
| `SpanTermQuery` | FTWords (single word) | Direct |
| `SpanNearQuery(ordered=false)` | FTWindow | Direct |
| `SpanNearQuery(ordered=true)` | FTOrder + FTWindow | Direct |
| `SpanOrQuery` | FTOr | Direct |
| `SpanNotQuery` | FTMildNot | Close (needs tuning) |
| `SpanFirstQuery` | FTContent `at start` | Direct |
| `SpanContainingQuery` | Nested positional constraints | Partial |
| `SpanWithinQuery` | FTWindow (containment variant) | Partial |

**Key advantage:** SpanNearQuery accepts a `slop` parameter and an `inOrder` flag, directly modeling XQFT's `window` and `ordered` constraints. SpanQueries can be nested arbitrarily, matching XQFT's composable filter model.

### 3.2 Improved Analysis Pipeline

Lucene 10's analyzer framework supports all XQFT match options:

| XQFT Match Option | Lucene 10 Support | Mechanism |
|--------------------|--------------------|-----------|
| Case insensitive | `LowerCaseFilter` | Built-in |
| Diacritics insensitive | `ASCIIFoldingFilter`, `ICUFoldingFilter` | Built-in |
| Stemming | `SnowballFilter`, `KStemFilter`, language-specific stemmers | Built-in for 20+ languages |
| Stop words | `StopFilter` | Built-in |
| Language | Language-specific analyzer chains | Per-language analyzers |
| Wildcards | `WildcardQuery`, `SpanMultiTermQueryWrapper` | Direct (wraps wildcards in spans) |

**Critical feature:** `SpanMultiTermQueryWrapper` allows wrapping wildcard, fuzzy, prefix, and regex queries as SpanQueries, enabling positional filters on wildcard matches. This directly supports XQFT wildcard option combined with positional filters.

### 3.3 Position and Offset Tracking

Lucene 10 provides robust positional indexing:
- **Term positions**: stored by default when using `TextField` (indexed + tokenized)
- **Term offsets**: optionally stored via `FieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS)`
- **Payloads**: per-position payload data via `PayloadAttribute`

Position data enables all XQFT positional filters. Offsets enable precise highlighting for match location reporting.

### 3.4 Payloads for Structural Information

Lucene 10 payloads can encode structural metadata per token:
- **Sentence/paragraph boundaries**: encode sentence/paragraph IDs as payloads to support `FTScope` (same sentence / same paragraph)
- **Node identity**: encode XML node IDs to support `FTIgnoreOption` and node-level match tracking
- **SpanPayloadCheckQuery**: filter spans by payload values

This is particularly relevant for `FTScope`, which requires sentence/paragraph awareness — something not natively provided by Lucene but achievable through custom token filters that inject boundary payloads.

### 3.5 Modern Scoring (BM25)

Lucene 10 defaults to BM25 scoring (replacing TF-IDF in older versions), which provides better relevance ranking. XQFT scoring is implementation-defined, so BM25 is an excellent default. Lucene also supports:
- `FunctionScoreQuery` for custom scoring
- Boosting via `BoostQuery`
- Score normalization capabilities

### 3.6 Concurrent Search and NRT

Lucene 10's near-real-time (NRT) search, `SearcherManager`, and `IndexReaderContext` improvements provide better concurrent access — important for eXist-db's multi-user environment.

### 3.7 Summary: Lucene 10 XQFT Coverage

| XQFT Feature | Lucene 10 Support | Notes |
|---------------|-------------------|-------|
| FTWords (word/phrase) | **Full** | TermQuery, PhraseQuery, SpanNearQuery |
| FTOr | **Full** | BooleanQuery(SHOULD), SpanOrQuery |
| FTAnd | **Full** | BooleanQuery(MUST) at document level; SpanNearQuery(unordered, large slop) for positional |
| FTMildNot | **Full** | SpanNotQuery |
| FTUnaryNot | **Full** | BooleanQuery(MUST_NOT) |
| FTOrder | **Full** | SpanNearQuery(inOrder=true) |
| FTWindow | **Full** | SpanNearQuery(slop=N) |
| FTDistance | **Partial** | Requires custom SpanQuery or post-filtering |
| FTScope (sentence) | **Partial** | Via payload-based sentence boundaries |
| FTScope (paragraph) | **Partial** | Via payload-based paragraph boundaries |
| FTContent (at start) | **Full** | SpanFirstQuery |
| FTContent (at end) | **Partial** | Requires custom query or post-filter |
| FTContent (entire content) | **Partial** | Requires field-length check |
| FTTimes | **Partial** | Post-filtering on span count |
| Case option | **Full** | Analyzer chain |
| Diacritics option | **Full** | ICUFoldingFilter |
| Stemming option | **Full** | Snowball/language stemmers |
| Thesaurus option | **Partial** | SynonymGraphFilter (but not URI-based thesaurus loading) |
| Stop words option | **Full** | StopFilter |
| Language option | **Full** | Language-specific analyzers |
| Wildcards option | **Full** | SpanMultiTermQueryWrapper(WildcardQuery) |
| FTIgnoreOption | **None** | Must be handled at indexing or pre-query time |
| Scoring | **Full** | BM25, normalized to 0–1 |

---

## 4. Gap Analysis: What Lucene Cannot Support

### 4.1 FTDistance (Precise Inter-Match Distance)

XQFT's `distance` constraint specifies exact ranges between matched terms (e.g., `distance at least 2 at most 5 words`). Lucene's `SpanNearQuery` only provides a maximum slop (window), not a minimum distance.

**Solution:** Custom `SpanQuery` subclass (`SpanDistanceQuery`) that wraps `SpanNearQuery` and post-filters on positional distance. Alternatively, use Lucene's `Spans` iterator to filter matches by exact distance.

### 4.2 FTScope (Same Sentence / Same Paragraph)

Lucene has no native concept of sentences or paragraphs within a field.

**Solution options:**
1. **Payload-based approach** (recommended): Custom `TokenFilter` that detects sentence/paragraph boundaries during indexing and writes boundary markers as payloads. At query time, use `SpanPayloadCheckQuery` to constrain matches to the same structural unit.
2. **Multi-value field approach**: Index each sentence/paragraph as a separate value in a multi-valued field, using position gaps to prevent cross-boundary phrase matches.
3. **Separate fields approach**: Index sentence/paragraph content in parallel fields with structural metadata.

### 4.3 FTContent (`at end`, `entire content`)

- `at start`: Directly supported via `SpanFirstQuery`
- `at end`: No direct Lucene equivalent. **Solution:** Custom `SpanQuery` that checks if a match's end position equals the field's total term count.
- `entire content`: Requires the match to span the entire field content. **Solution:** Combine `at start` + `at end` + single match, or verify match positions cover [0, fieldLength).

### 4.4 FTTimes (Occurrence Count)

XQFT allows constraining how many times a term appears (`occurs at least 3 times`). Lucene doesn't natively support this.

**Solution:** Post-query filtering using `Spans` iteration to count occurrences within each document, then filtering by the required cardinality.

### 4.5 FTIgnoreOption

The `without content` clause removes specified XML nodes from consideration during matching. This is fundamentally an XML-aware feature that Lucene (a text-oriented engine) cannot support natively.

**Solution options:**
1. **Index-time approach**: Create secondary indexes that exclude specific element content (e.g., index `<speech>` both with and without `<footnote>` children). This is already partially supported by eXist's `<ignore>` configuration.
2. **Query-time approach**: For dynamic ignore patterns, pre-process the XML to strip ignored nodes, then search the stripped text. This requires a non-indexed path.
3. **Hybrid approach**: Maintain positional maps between original and filtered text, adjusting match positions accordingly.

### 4.6 Thesaurus (URI-Based)

XQFT specifies thesaurus support via URI references and relationship types:
```xquery
"happy" with thesaurus at "http://example.org/thesaurus.xml"
  relationship "NT" levels 2
```

Lucene's `SynonymGraphFilter` provides query-time synonym expansion but doesn't support:
- URI-based thesaurus loading
- Relationship types (BT, NT, RT, etc.)
- Level-limited traversal

**Solution:** Custom thesaurus implementation that:
1. Loads and caches ISO 25964 / SKOS thesaurus files
2. Expands terms based on relationship type and level constraints
3. Feeds expanded terms into Lucene queries via `SpanOrQuery`

### 4.7 Dynamic Match Options

XQFT allows match options to be specified per-expression, mixing different options within a single query:
```xquery
"Straße" with stemming using language "de"
  ftand "café" using diacritics insensitive
```

Lucene analyzers are typically configured per-field at index time. Supporting per-expression match options requires:
- **Query-time analysis**: Using different analyzers to process query terms (already partially supported)
- **Multi-analyzer indexing**: Indexing the same content with multiple analysis chains to support different match option combinations
- **Fallback to non-indexed path**: For combinations not covered by pre-built indexes

---

## 5. BaseX as Reference Implementation

### 5.1 BaseX XQFT Architecture

BaseX implements XQFT using a **custom full-text index** — it does **not** use Lucene. Key architectural decisions:

1. **Custom token-based index**: BaseX builds its own inverted index storing token positions, with support for:
   - Fuzzy matching (Levenshtein distance)
   - Wildcard matching
   - Stemming (via Snowball)
   - Case/diacritics sensitivity
   - Positional information for all tokens

2. **Dual-mode execution**:
   - **Indexed mode**: Uses the full-text index for stored database documents
   - **Sequential (non-indexed) mode**: Tokenizes and evaluates in-memory, supporting XQFT on any XDM value including constructed/in-memory nodes

3. **FTTokenizer**: BaseX implements its own tokenizer (`FTLexer`/`FTTokenizer`) that handles:
   - Unicode-aware word boundary detection
   - Sentence and paragraph boundary detection (enabling `FTScope`)
   - Configurable case/diacritics/stemming options per tokenization pass

### 5.2 BaseX Conformance

BaseX provides one of the most complete XQFT implementations:

| Feature | BaseX Support | Notes |
|---------|---------------|-------|
| FTContains | **Full** | Standard syntax |
| FTWords (all modes) | **Full** | any, all, phrase, any word, all words |
| FTOr/FTAnd/FTMildNot/FTUnaryNot | **Full** | |
| FTOrder | **Full** | |
| FTWindow | **Full** | |
| FTDistance | **Full** | All comparison modes |
| FTScope (sentence/paragraph) | **Full** | Custom sentence/paragraph detection |
| FTContent (start/end/entire) | **Full** | |
| FTTimes | **Full** | |
| Case option | **Full** | |
| Diacritics option | **Full** | |
| Stemming option | **Full** | Via Snowball, 20+ languages |
| Thesaurus option | **Partial** | Supports loading thesaurus files, but limited relationship traversal |
| Stop words option | **Full** | |
| Language option | **Full** | |
| Wildcards option | **Full** | `.` and `.*` patterns |
| FTIgnoreOption | **Full** | Sequential evaluation strips ignored nodes |
| Scoring | **Full** | Custom scoring model |
| In-memory FT search | **Full** | Sequential tokenization-based evaluation |

### 5.3 Lessons from BaseX for eXist-db

**What eXist-db can learn:**

1. **Non-indexed fallback is essential**: BaseX's ability to evaluate XQFT on any XDM value (not just indexed data) is critical for spec conformance. Users expect `contains text` to work on constructed nodes, function results, etc.

2. **Sentence/paragraph detection**: BaseX uses Unicode-aware heuristics (period/question mark/exclamation + whitespace for sentences, double newline for paragraphs). eXist-db should implement similar detection.

3. **Tokenizer abstraction**: BaseX separates tokenization from indexing, allowing the same tokenizer to drive both indexed and sequential search. eXist-db should adopt a similar layered design.

4. **Optimizer integration**: BaseX's compiler detects `contains text` expressions and rewrites them to use the full-text index when available, falling back to sequential evaluation otherwise. The optimizer checks index availability, match option compatibility, and structural constraints.

**Where eXist-db can improve upon BaseX:**

1. **Lucene's mature ranking**: BaseX uses a simple custom scoring model. Lucene's BM25 and extensible scoring framework can provide more sophisticated relevance ranking.

2. **Advanced analysis**: Lucene's rich analyzer ecosystem (ICU, language-specific analyzers, phonetic, etc.) exceeds BaseX's built-in analysis capabilities.

3. **Faceted search integration**: eXist-db can combine XQFT with Lucene faceted search — something BaseX doesn't offer.

4. **Scalability**: Lucene's battle-tested concurrent indexing and searching infrastructure may offer better performance on large collections.

---

## 6. Non-Indexed (In-Memory) Full-Text Search

### 6.1 Why It's Necessary

XQFT conformance requires `contains text` to work on any string or node value, including:
- Constructed/in-memory elements
- Function return values
- Variables bound to string values
- Results of `fn:doc()` on non-indexed documents

A Lucene-only implementation would limit XQFT to pre-indexed, stored documents — breaking the spec.

### 6.2 Proposed Architecture: Sequential FT Evaluator

Implement a standalone `SequentialFTEvaluator` that operates independently of Lucene:

```
┌───────────────────────────────────────────────────┐
│                 XQFT Expression                    │
│           (contains text ... )                     │
└───────────┬───────────────────────────┬───────────┘
            │                           │
    ┌───────▼───────┐          ┌───────▼────────┐
    │  Index-backed │          │   Sequential   │
    │   Evaluator   │          │   Evaluator    │
    │  (Lucene 10)  │          │  (in-memory)   │
    └───────┬───────┘          └───────┬────────┘
            │                           │
    ┌───────▼───────┐          ┌───────▼────────┐
    │ Lucene Index  │          │  FTTokenizer   │
    │ SpanQueries   │          │  (standalone)  │
    │ Analyzers     │          │  Token+Position│
    └───────────────┘          └────────────────┘
```

### 6.3 Sequential Evaluator Design

The sequential evaluator would:

1. **Tokenize** the source text using a shared `FTTokenizer` abstraction (same tokenization rules as the Lucene analyzer chain, but operating in-memory without building an index)
2. **Build a token list** with positions, sentence/paragraph boundaries
3. **Evaluate the FTSelection** bottom-up against the token list:
   - FTWords: scan tokens for matches (applying case/diacritics/stemming options)
   - FTAnd/FTOr/FTMildNot: combine match sets
   - Positional filters: check position constraints against match positions
   - FTScope: check sentence/paragraph IDs
   - FTContent: check positions against token list boundaries
4. **Return AllMatches** result

### 6.4 Performance Considerations

Sequential evaluation is O(n*m) where n is document size and m is query complexity. For large documents, this is significantly slower than indexed search. Mitigation strategies:
- **Optimizer hint**: warn/log when sequential evaluation is used on large data
- **Temporary index**: for repeated searches on the same in-memory data, consider building a temporary RAM-based Lucene index
- **Lazy evaluation**: integrate with XQuery's lazy evaluation to avoid tokenizing content that won't be examined

---

## 7. Implementation Architecture

### 7.1 Parser and AST

Extend eXist-db's XQuery parser to recognize XQFT syntax. The parser must handle:
- `contains text` expression
- All FTSelection constructs (FTWords, FTOr, FTAnd, FTMildNot, FTUnaryNot)
- Positional filters (FTOrder, FTWindow, FTDistance, FTScope, FTContent)
- Match options (case, diacritics, stemming, thesaurus, stop words, language, wildcards)
- FTIgnoreOption
- `score` variable binding in FLWOR expressions

**New AST node classes** (under `org.exist.xquery.ft`):

```
FTContainsExpr          — top-level "contains text" expression
FTSelection             — abstract base for all FT selections
  ├── FTWords           — terminal: word/phrase matching
  ├── FTOr              — ftor
  ├── FTAnd             — ftand
  ├── FTMildNot         — not in
  └── FTUnaryNot        — ftnot
FTPositionalFilter      — abstract base for positional filters
  ├── FTOrder           — ordered
  ├── FTWindow          — window N words/sentences/paragraphs
  ├── FTDistance         — distance N words/sentences/paragraphs
  ├── FTScope           — same sentence/paragraph/different sentence/paragraph
  └── FTContent         — at start/at end/entire content
FTMatchOption           — abstract base for match options
  ├── FTCaseOption
  ├── FTDiacriticsOption
  ├── FTStemOption
  ├── FTThesaurusOption
  ├── FTStopWordOption
  ├── FTLanguageOption
  ├── FTWildCardOption
  └── FTExtensionOption
FTIgnoreOption          — without content
FTTimes                 — occurs N times
```

### 7.2 FT Compilation: AST → Execution Plan

The compiler translates XQFT AST nodes into an execution plan:

```
FTContainsExpr.compile()
  ├── Check if source nodes are indexed (consult LuceneIndexConfig)
  ├── If indexed AND all match options compatible with index:
  │     → Compile to IndexedFTEval (wrapping Lucene SpanQueries)
  ├── If not indexed OR incompatible options:
  │     → Compile to SequentialFTEval
  └── Handle FTIgnoreOption:
        → Wrap source in node-filtering layer
```

### 7.3 Indexed Evaluator: XQFT → Lucene Query Translation

**Translation rules:**

```java
class XQFTToLuceneCompiler {

    // FTWords "hello world" all words
    //   → SpanNearQuery([SpanTermQuery("hello"), SpanTermQuery("world")],
    //                    slop=0, inOrder=true)

    // FTWords "hello" any word "world"
    //   → SpanOrQuery([SpanTermQuery("hello"), SpanTermQuery("world")])

    // A ftand B
    //   → BooleanQuery(MUST(A), MUST(B))  // document-level
    //   (positional AND requires SpanNearQuery with large slop)

    // A ftor B
    //   → SpanOrQuery(A, B)  // preserves positional info

    // A not in B
    //   → SpanNotQuery(A, B)

    // A ordered
    //   → set inOrder=true on enclosing SpanNearQuery

    // A window 5 words
    //   → SpanNearQuery(subqueries, slop=5, inOrder=false)

    // A at start
    //   → SpanFirstQuery(A, maxEnd=A.termCount)

    // Wildcards: "hel.*"
    //   → SpanMultiTermQueryWrapper(WildcardQuery("hel.*"))
    //     (translating XQFT .=any char, .*=any string
    //      to Lucene ?=any char, *=any string)

    // Stemming:
    //   → apply stemmer to query terms, use analyzed form

    // Thesaurus:
    //   → expand terms via thesaurus, wrap in SpanOrQuery
}
```

### 7.4 Custom Lucene Components Needed

| Component | Purpose |
|-----------|---------|
| `SentenceParagraphTokenFilter` | Detects sentence/paragraph boundaries, writes boundary IDs as payloads |
| `SpanDistanceQuery` | Extends SpanNearQuery to enforce minimum distance between matches |
| `SpanAtEndQuery` | Matches spans at the end of a field |
| `SpanEntireContentQuery` | Matches spans covering the entire field |
| `FTTimesQuery` | Wraps a SpanQuery and filters by occurrence count |
| `XQFTAnalyzer` | Configurable analyzer chain supporting all XQFT match options |
| `ThesaurusExpander` | Loads and queries thesaurus files, returns expanded term sets |

### 7.5 Index Configuration for XQFT

Extend `collection.xconf` to support XQFT-specific configuration:

```xml
<collection xmlns="http://exist-db.org/collection-config/1.0">
  <index>
    <lucene>
      <!-- Existing config continues to work -->
      <text qname="speech"/>

      <!-- New: XQFT-aware configuration -->
      <fulltext qname="speech"
                case="insensitive"
                diacritics="insensitive"
                stemming="yes"
                language="en"
                sentence-detection="yes"
                paragraph-detection="yes">
        <thesaurus uri="xmldb:exist:///db/thesaurus/wordnet.xml"/>
        <stop-words uri="xmldb:exist:///db/config/stopwords-en.txt"/>
        <ignore qname="footnote"/>
      </fulltext>
    </lucene>
  </index>
</collection>
```

### 7.6 Integration with the XQuery Engine

**Optimizer integration** — extend `org.exist.xquery.Optimizer`:
1. Detect `FTContainsExpr` in the expression tree
2. Check if the context nodes have a Lucene full-text index
3. If indexed: rewrite to use index-backed evaluation with pre-selection
4. If not indexed: leave as sequential evaluation
5. Handle `score` variable binding by extracting Lucene scores

**FLWOR integration** — support `score` in `for` clauses:
```xquery
for $hit score $score in collection("/db/docs")//article
where $hit contains text "xml" ftand "database"
order by $score descending
return $hit/title
```

---

## 8. Phased Implementation Plan

### Phase 1: Foundation (Lucene 10 Migration + Parser)

**Objective**: Upgrade to Lucene 10 and add XQFT parsing without breaking existing `ft:query` functionality.

**Tasks:**
1. Upgrade Lucene dependency from 4.10.4 to 10.x
2. Migrate all Lucene API usages (spans package relocation, Filter removal, analyzer changes, codec changes)
3. Ensure all existing `ft:query` tests pass with Lucene 10
4. Extend the XQuery parser (ANTLR/JavaCC grammar) to recognize `contains text` expressions
5. Implement XQFT AST node classes
6. Add basic type checking and static analysis for FT expressions

**Deliverable**: eXist-db running on Lucene 10 with `ft:query` working and `contains text` parsing (but not yet evaluating).

### Phase 2: Core XQFT Evaluation (Sequential)

**Objective**: Implement sequential (non-indexed) XQFT evaluation supporting all features.

**Tasks:**
1. Implement `FTTokenizer` with Unicode-aware word/sentence/paragraph boundary detection
2. Implement `SequentialFTEvaluator` with the AllMatches data model
3. Implement all FTSelection evaluations (FTWords, FTOr, FTAnd, FTMildNot, FTUnaryNot)
4. Implement positional filters (FTOrder, FTWindow, FTDistance, FTScope, FTContent)
5. Implement FTTimes
6. Implement match options (case, diacritics, stemming, stop words, language, wildcards)
7. Implement FTIgnoreOption
8. Implement scoring (simple TF-based scoring for sequential mode)
9. Run W3C XQFT test suite (XQFTTS)

**Deliverable**: Full XQFT support on non-indexed/in-memory data, passing XQFTTS conformance tests.

### Phase 3: Indexed XQFT Evaluation (Lucene-Backed)

**Objective**: Implement Lucene-backed XQFT evaluation for indexed data with automatic optimization.

**Tasks:**
1. Implement `XQFTToLuceneCompiler` — XQFT AST to Lucene SpanQuery translation
2. Implement custom Lucene components:
   - `SentenceParagraphTokenFilter` (payload-based boundary detection)
   - `SpanDistanceQuery` (minimum distance enforcement)
   - `SpanAtEndQuery` and `SpanEntireContentQuery`
   - `FTTimesQuery` (occurrence count filtering)
3. Implement `XQFTAnalyzer` — configurable analyzer chain
4. Extend `collection.xconf` schema for XQFT configuration
5. Implement optimizer integration — automatic index selection
6. Implement score extraction and normalization (Lucene BM25 → [0,1])
7. Handle fallback: when index doesn't support requested match options, fall back to sequential
8. Performance testing and optimization

**Deliverable**: XQFT queries automatically use Lucene indexes when available, with transparent fallback.

### Phase 4: Advanced Features and Polish

**Objective**: Complete thesaurus support, extension options, and production hardening.

**Tasks:**
1. Implement thesaurus loading (ISO 25964, SKOS formats) and query expansion
2. Implement `FTExtensionOption` framework for eXist-specific extensions (e.g., fuzzy matching, regex)
3. Implement FLWOR `score` variable binding
4. Migration tooling: document differences between `ft:query` and `contains text`
5. Backward compatibility: ensure `ft:query` continues to work alongside XQFT
6. Comprehensive documentation
7. Performance benchmarking against BaseX
8. Edge case handling and robustness testing

**Deliverable**: Production-ready XQFT implementation with documentation and migration guide.

---

## 9. Migration from ft:query to XQFT

### 9.1 Equivalence Examples

| eXist-db `ft:query` | XQFT Standard |
|----------------------|---------------|
| `$node[ft:query(., 'word')]` | `$node[. contains text 'word']` |
| `$node[ft:query(., '"exact phrase"')]` | `$node[. contains text 'exact phrase' phrase]` |
| `$node[ft:query(., 'word1 AND word2')]` | `$node[. contains text 'word1' ftand 'word2']` |
| `$node[ft:query(., 'word1 OR word2')]` | `$node[. contains text 'word1' ftor 'word2']` |
| `$node[ft:query(., 'word*')]` | `$node[. contains text 'word.*' with wildcards]` |
| `$node[ft:query(., 'word~')]` | `$node[. contains text 'word' with thesaurus ...]` (no direct fuzzy equivalent) |
| `ft:score($node)` | `for $n score $s in ... return $s` |

### 9.2 Coexistence Strategy

Both APIs should coexist:
- `ft:query()` remains as the eXist-specific Lucene API (full access to Lucene features like fuzzy search, complex boolean queries, facets)
- `contains text` provides standards-compliant XQFT (portable across implementations)
- Both can use the same underlying Lucene indexes
- `ft:query` features not in XQFT (facets, field queries, binary indexing) remain exclusive to `ft:query`
- XQFT features not in `ft:query` (sequential evaluation, FTScope, FTIgnoreOption) are exclusive to `contains text`

---

## 10. Risk Analysis and Open Questions

### 10.1 Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Lucene 4→10 migration breaks existing functionality | High | Comprehensive test suite; phased migration; maintain backward compatibility |
| Sequential evaluator performance on large documents | Medium | Optimizer warnings; temporary RAM index option; lazy evaluation |
| FTScope (sentence/paragraph) accuracy varies by language | Medium | Use ICU BreakIterator for sentence detection; configurable heuristics |
| Thesaurus format complexity (ISO 25964, SKOS) | Low | Start with simple XML thesaurus format; add SKOS later |
| XQFT test suite (XQFTTS) coverage gaps | Low | Supplement with custom tests based on BaseX's test suite |
| SpanQuery API marked "internal" in Lucene 10 | Medium | Monitor Lucene roadmap; spans API has been stable across versions despite labeling |

### 10.2 Open Questions

1. **Parser technology**: eXist-db currently uses a custom recursive descent parser (ANTLR-generated). Is the grammar extensible enough for XQFT syntax, or does it need refactoring?

2. **Index compatibility**: Should XQFT-configured indexes be a new index type alongside the existing `<lucene>` config, or should the existing config be extended? (Recommendation: extend existing config with new `<fulltext>` element, keeping `<text>` for backward compatibility.)

3. **Conformance level target**: Should eXist-db aim for full XQFT conformance or a pragmatic subset? (Recommendation: full conformance for core features; thesaurus support can be partial initially.)

4. **FTScope boundary detection**: How should sentence/paragraph boundaries be detected in XML content where markup interrupts text flow? (Recommendation: follow BaseX's approach — detect boundaries in the serialized text content of each indexed element.)

5. **Match option interactions with existing analyzers**: How to handle conflicts between XQFT match options and analyzers configured in `collection.xconf`? (Recommendation: XQFT match options override index-time analyzers at query time when possible; fall back to sequential evaluation when override is impossible.)

6. **Lucene SpanQuery stability**: The spans API is marked "internal" in Lucene 10. What's the long-term stability risk? (Assessment: Despite the "internal" label, spans have been a stable Lucene feature for 15+ years. The package relocation from `search.spans` to `queries.spans` was a one-time reorganization.)

---

## Appendix A: Lucene 4.10.4 → 10 Migration Checklist

Key API changes affecting eXist-db's current Lucene integration:

- [ ] `org.apache.lucene.search.spans.*` → `org.apache.lucene.queries.spans.*`
- [ ] Add `lucene-queries` module dependency (for spans)
- [ ] Remove `Version` parameter from analyzer constructors
- [ ] Replace `Filter` usage with equivalent `Query` + `BooleanQuery`
- [ ] Update `IndexWriterConfig` API (no more `setWriteLockTimeout`, changed merge scheduler)
- [ ] Update `DirectoryReader.open()` API
- [ ] Replace `TopDocs.totalHits` (int) with `TopDocs.totalHits` (TotalHits object)
- [ ] Update `SortField` API
- [ ] Replace deprecated `NumericRangeQuery` with `PointRangeQuery`
- [ ] Update `Codec` and `PostingsFormat` APIs
- [ ] Migrate `TaxonomyFacetCounts` API changes
- [ ] Update `QueryParser` package structure
- [ ] Handle `IOException` changes in `IndexReader` lifecycle
- [ ] Update `Analyzer.TokenStreamComponents` API
- [ ] Replace `DocValues` API changes (sorted, numeric, binary)

## Appendix B: XQFT Feature → Implementation Strategy Matrix

| XQFT Feature | Indexed Strategy | Sequential Strategy | Complexity |
|---------------|-----------------|---------------------|------------|
| FTWords (single) | SpanTermQuery | Token scan | Low |
| FTWords (phrase) | SpanNearQuery(slop=0, ordered) | Consecutive token match | Low |
| FTWords (all words) | SpanNearQuery(large slop, unordered) | All tokens present | Low |
| FTWords (any word) | SpanOrQuery | Any token present | Low |
| FTOr | SpanOrQuery | Union match sets | Low |
| FTAnd | BooleanQuery(MUST) + spans | Intersect match sets | Medium |
| FTMildNot | SpanNotQuery | Subtract overlapping matches | Medium |
| FTUnaryNot | BooleanQuery(MUST_NOT) | Complement | Low |
| FTOrder | SpanNearQuery(inOrder=true) | Check match positions | Medium |
| FTWindow | SpanNearQuery(slop=N) | Check position range | Medium |
| FTDistance | Custom SpanDistanceQuery | Check pairwise distances | High |
| FTScope (sentence) | Payload-based SpanQuery | Tokenizer sentence IDs | High |
| FTScope (paragraph) | Payload-based SpanQuery | Tokenizer paragraph IDs | Medium |
| FTContent (at start) | SpanFirstQuery | Check pos=0 | Low |
| FTContent (at end) | Custom SpanAtEndQuery | Check pos=last | Medium |
| FTContent (entire) | Custom SpanEntireContentQuery | Check pos coverage | Medium |
| FTTimes | Custom FTTimesQuery | Count matches | Medium |
| Case option | Analyzer chain | toLowerCase() | Low |
| Diacritics option | ICUFoldingFilter | Unicode normalization | Low |
| Stemming option | SnowballFilter | Snowball library | Low |
| Thesaurus option | SynonymOrQuery expansion | Same expansion | High |
| Stop words option | StopFilter | Token filtering | Low |
| Language option | Language-specific analyzer | Language-specific tokenizer | Medium |
| Wildcards option | SpanMultiTermQueryWrapper | Regex matching on tokens | Medium |
| FTIgnoreOption | Secondary index / pre-filter | Strip nodes before tokenizing | High |
| Scoring | BM25 normalized | TF-based | Medium |

## Appendix C: Comparison Summary — eXist-db vs BaseX

| Aspect | BaseX | eXist-db (Current) | eXist-db (Proposed) |
|--------|-------|---------------------|---------------------|
| XQFT support | Yes (native) | No | Yes |
| Index engine | Custom inverted index | Lucene 4.10.4 | Lucene 10 |
| Non-indexed FT | Yes (sequential) | No | Yes (SequentialFTEvaluator) |
| Sentence/paragraph scope | Yes (custom tokenizer) | No | Yes (payload-based + tokenizer) |
| Scoring model | Custom (simple) | Lucene TF-IDF | Lucene BM25 |
| Thesaurus support | Partial | No | Partial → Full |
| Faceted search | No | Yes | Yes |
| Fuzzy search | Yes (built-in) | Yes (Lucene) | Yes (Lucene + extension option) |
| Proprietary FT API | No | ft:query() | ft:query() (preserved) |
| Standards compliance | High | None (proprietary) | High (target) |
| Analysis ecosystem | Limited (Snowball) | Rich (Lucene analyzers) | Rich (Lucene 10 analyzers) |
