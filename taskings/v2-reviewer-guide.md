# Reviewer Guide: eXist-db 7.0 PRs

## Overview

This is a guide to the 13 `v2/` PRs (plus one closely related PR, [#6145](https://github.com/eXist-db/exist/pull/6145)) prepared for eXist-db 7.0. Each PR has been individually tested (build, exist-core tests, Codacy), and all branches merge cleanly together in the `next-v2` integration branch.

The PRs are organized into 5 waves by dependency and review complexity. **You can review in any order**, but merging should follow the wave order to avoid conflicts.

### Testing methodology

Every branch was tested individually: build (`mvn install -pl exist-core -am`), full exist-core unit tests (~6,500 tests), and Codacy static analysis. Branches with grammar changes also ran XQTS compliance suites. All 14 branches were then merged together in the `next-v2` integration branch and tested again — final result: **6,240 / 6,357 (98.2%)**, with all 15 remaining failures pre-existing on develop.

For full context on the consolidation process, see the [consolidation report](v2-consolidation-report.md).

## CI Health Note

All 13 `v2/` PRs were rebased on 2026-04-13 after [#6224](https://github.com/eXist-db/exist/pull/6224) (CI/Maven fixes) merged. The CI runs from that rebase are the current baseline.

**Known noise in CI results** — do not treat these as blockers:

- **Integration failures (ubuntu/windows/macOS)**: All PRs show 1–3 integration job failures. These are pre-existing test hangs (the surefire fork timeout fires; the CI job still reports FAILURE). Not caused by any v2 change.
- **"Test and Publish Container Images" failure**: Caused by a transient HTTP 502 from `exist-db.org`'s public XAR repo during the build. Not a code issue.
- **XQTS runner crash on #6212 (Saxon 12)**: The CI XQTS job uses the Saxon 9.9 runner against the Saxon 12 classpath and crashes with `NoSuchMethodError: AnyURIValue.<init>(CharSequence)`. This is expected; [exist-xqts-runner #49](https://github.com/eXist-db/exist-xqts-runner/pull/49) adds Saxon 12 compatibility and should merge alongside #6212.
- **`replace.empty-match` unit tests (#6212 and #6218)**: These two PRs have complementary unit test failures. #6212 has `replace.empty-match-fails` failing (Saxon 12 permits empty matches; the XQ 3.1 gating is in #6218). #6218 has `replace.empty-match-allowed` failing (XQ4 mode expects empty matches; requires Saxon 12 from #6212). When both are merged together, both tests pass. Reviewers can ignore these failures when reviewing either PR individually.

## How to Test

Pull the Docker image with all branches pre-merged:

```bash
docker pull joewiz/existdb:next-v2
docker run -d --name existdb-next -p 8080:8080 -p 8443:8443 joewiz/existdb:next-v2
```

Or review individual branches: each `v2/` branch is on `joewiz/exist` and targets `develop`.

---

## Wave 1: Small, Independent PRs (review in any order)

These touch no shared infrastructure. Each can be reviewed and merged independently.

### `v2/xq31-compliance-fixes` ([#6207](https://github.com/eXist-db/exist/pull/6207)) — XQuery 3.1 Compliance Bugfixes

**Priority**: High — fixes real bugs users have reported
**Reviewer effort**: Medium (23 commits, 36 files — but each commit is a focused bugfix)
**Risk**: Low — all changes are bugfixes with tests

23 bugfixes for XQ 3.1 spec compliance: casting error codes, fn:not() crashes, path expression dedup, format-date, fn:parse-ietf-date, and more. Consolidates approved work from @duncdrum (#6165, #6080) and fixes from #6083, #6085.

**What to look for**: Each commit fixes a specific XQTS failure or reported bug. The casting/error-code changes are the most subtle — verify the error code choices match the spec.

---

### `v2/query-profiling` ([#6208](https://github.com/eXist-db/exist/pull/6208)) — Query Profiling Functions

**Priority**: Medium — useful developer tooling, no risk to core
**Reviewer effort**: Low (5 commits, 11 files — all new code, no existing code modified)
**Risk**: Very low — additive only

Adds `util:time()`, `util:memory()`, `util:track()`, `util:explain()`, `util:profile()`. All new functions in a new module, no changes to existing code.

**What to look for**: Function signatures, return types, whether the profiling data is accurate and useful.

---

### `v2/xq4-axes` ([#6209](https://github.com/eXist-db/exist/pull/6209)) — XQuery 4.0 Axes

**Priority**: Low — XQ4 feature, not urgent
**Reviewer effort**: Low (3 commits, 10 files)
**Risk**: Low

Adds XQ4 axes (`following-or-self`, `preceding-or-self`, etc.) and fixes a pre-existing bug where preceding/following axes miss nodes when predicates add context tracking (from approved #6076 by @duncdrum). Also creates `XQuery4Tests.java` runner for the `xquery4/` test directory.

**What to look for**: Axis semantics match the XQ4 spec. The `XQuery4Tests.java` runner is reusable infrastructure.

---

### `v2/xq4-record-types` ([#6210](https://github.com/eXist-db/exist/pull/6210)) — XQuery 4.0 Record Types

**Priority**: Low — XQ4 feature
**Reviewer effort**: Low (3 commits, 9 files)
**Risk**: Low

Record type declarations and pattern matching per XQ4 spec.

**What to look for**: Type system integration, record type syntax.

---

### `v2/xq4-filter-expr-am` ([#6211](https://github.com/eXist-db/exist/pull/6211)) — XQuery 4.0 Array/Map Filter

**Priority**: Low — XQ4 feature
**Reviewer effort**: Very low (2 commits, 4 files — smallest PR)
**Risk**: Low

Adds `?[expr]` filter syntax for arrays and maps. Includes grammar changes and expression class.

**What to look for**: Grammar production correctness, interaction with existing `?` lookup syntax.

---

## Wave 2: Infrastructure Upgrades (review independently, merge before Wave 3)

These are major dependency upgrades. They don't conflict with each other but should merge before the grammar PRs in Wave 3.

### `v2/saxon-12-upgrade` ([#6212](https://github.com/eXist-db/exist/pull/6212)) — Saxon 9.9 → 12.5

**Priority**: High — eliminates the unmaintained exist-saxon-regex fork
**Reviewer effort**: Medium (7 commits, 143 files — but most changes are mechanical API migration)
**Risk**: Medium — regex behavior may differ between Saxon versions

Eliminates the `exist-saxon-regex` fork module entirely. The main work is migrating fn:replace/fn:matches/fn:analyze-string from Saxon 9's `CharSequence` API to Saxon 12's `StringView`/`UnicodeString` API.

**What to look for**: The regex API migration in FunReplace.java, FunMatches.java, FunAnalyzeString.java. The function-replacement callback changes are the most complex part. The `exist-saxon-regex` module removal.

---

### `feature/websocket-core` ([#6145](https://github.com/eXist-db/exist/pull/6145)) — Jetty 11 → 12 (Jakarta Servlet 6.0) + WebSocket

**Priority**: High — Jetty 11 is EOL
**Reviewer effort**: Medium (~41 files for Jetty migration + WebSocket module)
**Risk**: Medium — servlet API migration affects all HTTP handling
**Approvals**: @dizzzz ✓

`javax.servlet` → `jakarta.servlet` across all modules. Addresses review feedback from @reinhapa and @dizzzz on the original PR #6144: `printStackTrace` replaced with logging, unhelpful comments removed, `sendError` evaluated but not used for RESTXQ (would commit response prematurely). Also adds a WebSocket module with streaming XQuery evaluation.

> Note: The original `v2/jetty-12-upgrade` PR ([#6213](https://github.com/eXist-db/exist/pull/6213)) has been superseded by this broader PR.

**What to look for**: The `setStatus` reason parameter decision (dropped, not replaced with `sendError` — see comment in HttpServletResponseAdapter.java). Servlet filter chain changes. WebSocket API.

---

## Wave 3: Grammar Changes (merge in order — these touch XQuery.g/XQueryTree.g)

These PRs modify the ANTLR 2 grammar. They use **labeled sections** to minimize conflicts, but should be merged in the order listed. The grammar conflicts between them are trivial (keyword list sections).

### `v2/w3c-xquery-update-3.0` ([#6214](https://github.com/eXist-db/exist/pull/6214)) — W3C XQuery Update Facility 3.0

**Priority**: High — long-requested feature, 100% non-schema XQTS compliance
**Reviewer effort**: High (6 commits, 47 files — new subsystem)
**Risk**: Medium — new expression types, in-memory DOM mutations

Implements copy-modify-return, insert, delete, replace, rename per the W3C XQUF 3.0 spec. Coexists with eXist's legacy update syntax. New package `org.exist.xquery.xquf` with PUL (Pending Update List) architecture.

**What to look for**: The PUL implementation (how updates are collected and applied). In-memory DOM mutation methods. The `isUpdating()` / `isVacuous()` static analysis for XUST0001/XUST0002. Interaction with legacy update syntax. NamePool approach for namespace conflict detection (credited to BaseX).

XQTS: 656/666 non-schema, non-fn-put (98.5%).

---

### `v2/xqft-phase2` ([#6215](https://github.com/eXist-db/exist/pull/6215)) — W3C Full Text 3.0

**Priority**: Medium — niche but powerful feature
**Reviewer effort**: High (4 commits, 32 files — new subsystem)
**Risk**: Low-medium — integrates with existing Lucene index

`contains text` expressions with stemming, thesaurus, wildcards, proximity, and scoring. New package `org.exist.xquery.ft`.

**What to look for**: FT expression evaluation, integration with Lucene index, score variable handling in FLWOR clauses.

XQTS FTTS: 1,320/1,334 (99.0%).

---

### `v2/xquery-4.0-parser` ([#6216](https://github.com/eXist-db/exist/pull/6216)) — XQuery 4.0 Grammar + Version Gating

**Priority**: High — enables all XQ4 features
**Reviewer effort**: High (8 commits, 63 files — grammar is complex)
**Risk**: Medium — grammar changes affect all query parsing

Adds XQ4 syntax to ANTLR 2: pipeline (`->`), mapping arrow (`=>!`), otherwise, ternary (`?? !!`), focus functions, keyword arguments, string templates, braced if, while clause, default parameters, for-member, method calls.

**Key review point — version gating**: Per @line-o's [review](https://github.com/eXist-db/exist/pull/6139#pullrequestreview-3994434688) and the [community meeting decision](https://github.com/eXist-db/exist/pull/6139#issuecomment-4113087188), all 12 XQ4 constructs throw XPST0003 when the query declares `xquery version "3.1"`. Feature flag `exist.xquery4.enabled` (default true) gates `xquery version "4.0"` declarations entirely.

**What to look for**: The version gating in XQueryTree.g (search for `getXQueryVersion() < 40`). The feature flag implementation. Grammar production correctness — the expression precedence chain must be preserved.

XQTS QT4: 89.3% (parser-only, without function implementations).

Also includes reserved-keywords-as-NCNames fix from #6103.

---

### `v2/declare-decimal-format` ([#6217](https://github.com/eXist-db/exist/pull/6217)) — XQuery 3.1 Decimal Format

**Priority**: Medium — spec compliance
**Reviewer effort**: Low (2 commits, 6 files)
**Risk**: Low
**Dependency**: Must merge after the 3 grammar PRs above (uses the merged grammar as base)

`declare decimal-format` and `declare default decimal-format` prolog declarations. Addresses @line-o's review on #6077.

**What to look for**: Grammar integration with the other three grammar PRs. Error codes XQST0097 (duplicate format) and FODF1310 (invalid zero-digit).

---

## Wave 4: Functions (merge after parser)

### `v2/xq4-core-functions` ([#6218](https://github.com/eXist-db/exist/pull/6218)) — 82 XQuery 4.0 Functions

**Priority**: High — the bulk of XQ4 user-facing features
**Reviewer effort**: High (5 commits, 130 files — largest PR by file count)
**Risk**: Low-medium — all new functions, minimal changes to existing code

82 new or updated functions across fn:, array:, map:, and math:. Includes fn:replace/fn:tokenize empty-match version gating (XQ4 behavior only in `xquery version "4.0"` mode).

**What to look for**: Function signatures match the XQ4 spec. Return types and error codes. The fn:replace empty-match gating (XQ4 allows empty matches, XQ 3.1 doesn't). The fn:doc multi-arity fix.

XQTS QT4: 435/599 non-skipped (72.6%) — lower total because many tests require parser features.

---

## Wave 5: Serialization and Parser (independent, merge last)

### `v2/serialization-compliance` ([#6219](https://github.com/eXist-db/exist/pull/6219)) — W3C Serialization Compliance

**Priority**: Medium — important for interop, fixes the eXide regression
**Reviewer effort**: Medium (14 commits, 26 files)
**Risk**: Low-medium — serialization affects all output

Fixes across XML, HTML, XHTML, JSON, text, adaptive, and CSV serialization. The critical fix: self-closing meta tags in XHTML mode that broke the URL rewrite view pipeline (eXide regression).

**What to look for**: The XMLWriter namespace undeclaration fix (empty `xmlns=""` handling). The XHTML meta tag self-closing fix. JSON escape-solidus default. CSV serializer implementation.

---

### `v2/new-parser` ([#6220](https://github.com/eXist-db/exist/pull/6220)) — Recursive Descent Parser

**Priority**: Low for merge (opt-in only), high for long-term strategy
**Reviewer effort**: Medium (12 files, ~5,700 lines — but self-contained)
**Risk**: Very low — opt-in via `-Dexist.parser=rd`, default remains ANTLR 2
**Dependency**: Must merge after XQUF, XQFT, and parser PRs

A complete recursive descent XQuery parser supporting XQ 3.1, 4.0, XQUF 3.0, and XQFT 3.0. Benchmarks show it's 15-82x faster than ANTLR 2 and matches BaseX speed. Zero impact on existing behavior — the feature flag defaults to ANTLR 2.

**What to look for**: Parser architecture (single-pass, builds Expression tree directly). Keyword handling without ANTLR 2's testLiterals trap. Error reporting quality.

---

## Merge Order Summary

```
Wave 1 (any order):     v2/xq31-compliance-fixes
                        v2/query-profiling
                        v2/xq4-axes
                        v2/xq4-record-types
                        v2/xq4-filter-expr-am

Wave 2 (any order):     v2/saxon-12-upgrade
                        feature/websocket-core  (#6145)

Wave 3 (in order):      v2/w3c-xquery-update-3.0
                        v2/xqft-phase2
                        v2/xquery-4.0-parser
                        v2/declare-decimal-format

Wave 4:                 v2/xq4-core-functions

Wave 5 (any order):     v2/serialization-compliance
                        v2/new-parser
```

## Supporting Infrastructure

### XQTS Runner (exist-xqts-runner repo)

[PR #49](https://github.com/eXist-db/exist-xqts-runner/pull/49) — "Extend XQTS runner: QT4/FTTS/Update suites, assertion fixes, batch runner, Saxon 12"

This consolidated PR supersedes the previously approved [#45](https://github.com/eXist-db/exist-xqts-runner/pull/45) (now closed). It enables all the XQTS compliance scores cited in this guide and adds Saxon 12 compatibility. Without it, only XQ 3.1 tests can be run.

**Should be merged alongside the v2/ PRs.** Needs first review.

### W3C XInclude Test Suite

[PR #6206](https://github.com/eXist-db/exist/pull/6206) — adds the W3C XInclude 1.0 conformance suite (148 tests) plus XInclude 1.1 and XProc 3.0 test cases. Includes XPointer fixes and `parse="text"` support. Improves XInclude conformance from 38/148 (25.7%) to **72/126 (57.1%)** with fixes for XPointer element() scheme and `parse="text"` support. Remaining gaps: xml:base handling, namespace comparison, and advanced xpointer schemes.

## Cross-Repo PRs

These PRs on other repositories are part of the 7.0 work:

| Repo | PR | Title | Status |
|------|----|-------|--------|
| exist-xqts-runner | [#49](https://github.com/eXist-db/exist-xqts-runner/pull/49) | QT4/FTTS/XQUF suites + Saxon 12 | Needs review; merge alongside v2/ PRs |
| exist-xqts-runner | [~~#45~~](https://github.com/eXist-db/exist-xqts-runner/pull/45) | ~~QT4/FTTS/XQUF test suite support~~ | Closed; superseded by #49 |
| eXist-db/exist | [#6206](https://github.com/eXist-db/exist/pull/6206) | XInclude test suite + conformance | Review needed |
| eXide | [#778](https://github.com/eXist-db/eXide/pull/778) | Modernize: CM6 editor, REx parser, LSP | Review needed |
| exist-markdown | [#69](https://github.com/eXist-db/exist-markdown/pull/69) | CommonMark/GFM (flexmark-java) | Approved |
| jinks | [#2](https://github.com/eeditiones/jinks/pull/2) | exist-site profile | Review needed |

### Jackrabbit WebDAV (`joewiz/feature/jackrabbit-webdav`)

Replaces Milton with Apache Jackrabbit. Litmus compliance: 96/98 (98.0%) vs Milton's 47/67 (70.1%). Includes shared locks (Level 2), dead properties, lock persistence, and litmus CI workflow.

**Build prerequisite**: Clone and `mvn install` [joewiz/jackrabbit-webdav-jakarta](https://github.com/joewiz/jackrabbit-webdav-jakarta) (Jakarta EE 10 transform of Jackrabbit WebDAV).

## New Repos (joewiz — to transfer to eXist-db org)

These repos were created for 7.0 apps and should be transferred to the eXist-db GitHub org:

| Repo | Description |
|------|-------------|
| [joewiz/exist-api](https://github.com/joewiz/exist-api) | Unified platform REST API |
| [joewiz/dashboard-next](https://github.com/joewiz/dashboard-next) | New dashboard (replaces dashboard + monex) |
| [joewiz/documentation-next](https://github.com/joewiz/documentation-next) | New documentation (replaces doc + fundocs) |
| [joewiz/wiki-next](https://github.com/joewiz/wiki-next) | Blog/wiki (replaces AtomicWiki) |
| [joewiz/notebook](https://github.com/joewiz/notebook) | XQuery sandbox (replaces sandbox) |
| [joewiz/website-next](https://github.com/joewiz/website-next) | Site shell (nav, search, chrome) |
| [joewiz/exist-file](https://github.com/joewiz/exist-file) | EXPath File module XAR |
| [joewiz/exist-binary](https://github.com/joewiz/exist-binary) | EXPath Binary module XAR |
| [joewiz/exist-crypto](https://github.com/joewiz/exist-crypto) | EXPath Crypto module XAR |
| [joewiz/exist-http-client](https://github.com/joewiz/exist-http-client) | EXPath HTTP Client module XAR |

## Also Ready to Merge (Independent eXist-db/exist PRs)

These are not part of the v2/ consolidation but are approved and ready:

| PR | Title | Status |
|----|-------|--------|
| [#6087](https://github.com/eXist-db/exist/pull/6087) | Fix XInclude relative path resolution | 1 approval (@duncdrum) |
| [#6092](https://github.com/eXist-db/exist/pull/6092) | [ZN] timezone name modifier for format-dateTime | 1 approval (@duncdrum) |
| [~~#6142~~](https://github.com/eXist-db/exist/pull/6142) | ~~Add weekly Prethink context refresh workflow~~ | Merged 2026-04-13 |
| [~~#6146~~](https://github.com/eXist-db/exist/pull/6146) | ~~Lucene 10 upgrade~~ | Merged 2026-04-13 |
| [~~#6153~~](https://github.com/eXist-db/exist/pull/6153) | ~~Re-enable 9 skipped tests that now pass~~ | Merged |
| [#6162](https://github.com/eXist-db/exist/pull/6162) | Fix negative double/float in value index | 2 approvals (@duncdrum, @reinhapa) — ready to merge |
| [#6163](https://github.com/eXist-db/exist/pull/6163) | Validate json-to-xml escape option (FOJS0005) | 2 approvals (@duncdrum, @reinhapa) — ready to merge |
| [#6182](https://github.com/eXist-db/exist/pull/6182) | Unify module discovery | 1 approval (@duncdrum); @adamretter has raised a concern about benchmark methodology |
| [#6184](https://github.com/eXist-db/exist/pull/6184) | Add repo:resource-available() | 1 approval (@duncdrum); changes requested by @line-o |
| [~~#6186~~](https://github.com/eXist-db/exist/pull/6186) | ~~Add surefire fork timeouts~~ | Closed; superseded by [#6224](https://github.com/eXist-db/exist/pull/6224) (merged 2026-04-13) |
| [#6191](https://github.com/eXist-db/exist/pull/6191) | Fix FLWOR sort race condition | 1 approval (@duncdrum) — ready to merge |

These can be merged at any time — they don't conflict with the v2/ branches.
