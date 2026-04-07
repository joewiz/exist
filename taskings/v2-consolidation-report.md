# PR Consolidation Report: eXist-db 7.0

## Background

After the March 30 community call, I wrote in the core devs Slack channel:

> Thanks, everyone, for your feedback yesterday on the various tracks I've been working on! For my big PRs that I sense interest in, I'm thinking of pulling them back into draft, and reorganizing the commits to make them cleaner and hopefully easier to review and maintain, and then force-pushing the updated branch. In looking over my PR branches, I see some commits that are fixes to missteps earlier in the branch. Probably better to force push a cleaned up branch without the back-and-forth. I'll aim to have my PRs ready for final review by our next community call, if not before.

Here's what we did.

## What We Did

We rebuilt **14 open PRs** into **15 clean v2/ branches**, each independently tested, Codacy-scanned, and integration-tested. The original PRs had grown organically — some had 50+ commits with back-and-forth fixes, missteps, and tangled dependencies. The v2/ branches are clean, focused, and reviewable.

### The numbers

- **14 original PRs** rebuilt into **14 clean v2/ branches** (the mega XQ4 PR was split into 6 focused branches)
- **6 additional approved PRs** folded in (preserving reviewer approvals)
- **119 test failures** found during integration testing → **16 remaining** (all pre-existing on develop)
- **3 reviewer change requests** addressed
- Every branch: build passes, exist-core tests pass, Codacy clean, XQTS scores verified

### How we addressed reviewer feedback

#### @line-o on PR #6139 (XQuery 4.0 parser)

**Request** ([review](https://github.com/eXist-db/exist/pull/6139#pullrequestreview-3994434688)): "Modules with `xquery version "3.1";` should not allow to use 4.0 features"

**Community meeting follow-up** ([comment](https://github.com/eXist-db/exist/pull/6139#issuecomment-4113087188)): "We would like to keep this under an experimental-features flag until the XQuery 4.0 specification reaches stable state."

**What we did** (in `v2/xquery-4.0-parser`):
- Added **version gating** for 12 XQ4 constructs in XQueryTree.g — `otherwise`, pipeline (`->`), mapping arrow (`=>!`), ternary (`?? !!`), keyword arguments, focus functions, string templates, `while` clause, default parameters, `for-member`, and method calls all throw XPST0003 when the query declares `xquery version "3.1"`.
- Added **feature flag** `exist.xquery4.enabled` (system property, default `true`). When set to `false`, declaring `xquery version "4.0"` throws XPST0003.

#### @reinhapa on PR #6144 (Jetty 12)

**Request** ([review](https://github.com/eXist-db/exist/pull/6144#pullrequestreview-3980232426)): Replace `e.printStackTrace()` with proper logging; remove backwards-compatibility comments that add no value; address the dropped `reason` parameter in `setStatus(int, String)`.

**What we did** (in `v2/jetty-12-upgrade`):
- Replaced all `e.printStackTrace()` calls with `logger.fatal()` / `logger.error()` in JettyStart.java
- Removed unhelpful backwards-compatibility comments flagged in XQueryURLRewrite.java, HttpServletRequestWrapper.java, and HttpServletResponseAdapter.java
- Evaluated `sendError(int, String)` as replacement for the dropped reason parameter — determined it's **not safe** for RESTXQ because `sendError()` commits the response and triggers container error page handling, preventing RESTXQ from serializing its custom response body. Added a comment explaining the decision.

#### @dizzzz on PR #6144 (Jetty 12)

**Request** ([review](https://github.com/eXist-db/exist/pull/6144#pullrequestreview-4017557509)): Confirmed @reinhapa's comment removals; noted the PR looks similar to a branch he started earlier.

**What we did**: Incorporated all comment removal suggestions. The v2/ branch is a clean implementation that both reviewers can evaluate from scratch.

#### @line-o on PR #6077 (declare decimal-format)

**Review comments** addressed: Added `df` prefix to decimal-format helper methods, added Javadoc (already in the original commits by @line-o's request).

**What we did** (in `v2/declare-decimal-format`): Cherry-picked both commits, resolved grammar conflicts with XQUF/XQFT/parser branches. The branch is based on the merged grammar so it integrates cleanly.

### Approved work preserved

Three PRs with existing approvals were consolidated into v2/ branches, with attribution noted in each PR description:

| Original PR | Approved By | Consolidated Into |
|-------------|-------------|-------------------|
| [#6165](https://github.com/eXist-db/exist/pull/6165) Fix XQ 3.1 casting and numeric comparison | @reinhapa, @duncdrum | `v2/xq31-compliance-fixes` |
| [#6076](https://github.com/eXist-db/exist/pull/6076) Fix preceding/following axes with predicates | @duncdrum | `v2/xq4-axes` |
| [#6080](https://github.com/eXist-db/exist/pull/6080) Fix // + reverse axis | @duncdrum | `v2/xq31-compliance-fixes` |

Additional approved PRs remain as independent PRs (not consolidated):
- [#6092](https://github.com/eXist-db/exist/pull/6092) [ZN] timezone modifier (approved by @duncdrum) — merged into next-v2 for testing
- [#6146](https://github.com/eXist-db/exist/pull/6146) Lucene 10 (approved by @windauer, @line-o) — merged into next-v2

## The v2/ Branches

Each branch was tested individually: full exist-core unit tests (~6,500 tests), Codacy static analysis, and XQTS compliance where applicable. All branches pass with 0 failures.

| Branch | What It Does | Size | XQTS | Supersedes |
|--------|-------------|------|------|------------|
| [#6207](https://github.com/eXist-db/exist/pull/6207) `v2/xq31-compliance-fixes` | 23 XQ 3.1 bugfixes (casting, fn:not, path dedup, format-date, etc.) | 23 commits, 36 files | — | #6165, #6083, #6085, #6080 |
| [#6208](https://github.com/eXist-db/exist/pull/6208) `v2/query-profiling` | util:time, memory, track, explain, profile | 5 commits, 11 files | — | #6194 |
| [#6209](https://github.com/eXist-db/exist/pull/6209) `v2/xq4-axes` | XQ4 axes + preceding/following predicate fix | 3 commits, 10 files | — | #6076 |
| [#6210](https://github.com/eXist-db/exist/pull/6210) `v2/xq4-record-types` | XQ4 record type declarations | 3 commits, 9 files | — | — |
| [#6211](https://github.com/eXist-db/exist/pull/6211) `v2/xq4-filter-expr-am` | XQ4 `?[expr]` filter for arrays/maps | 2 commits, 4 files | — | — |
| [#6212](https://github.com/eXist-db/exist/pull/6212) `v2/saxon-12-upgrade` | Saxon 9.9 → 12.5, eliminate regex fork | 7 commits, 143 files | — | #6143 |
| [#6213](https://github.com/eXist-db/exist/pull/6213) `v2/jetty-12-upgrade` | Jetty 11 → 12, Jakarta Servlet 6.0 | 2 commits, 41 files | — | #6144 |
| [#6214](https://github.com/eXist-db/exist/pull/6214) `v2/w3c-xquery-update-3.0` | W3C XQUF 3.0 (copy-modify, insert/delete/replace/rename) | 6 commits, 47 files | XQUF 98.5% | #6111 |
| [#6215](https://github.com/eXist-db/exist/pull/6215) `v2/xqft-phase2` | W3C Full Text 3.0 (contains text, stemming, proximity) | 4 commits, 32 files | FTTS 99.0% | #6133 |
| [#6216](https://github.com/eXist-db/exist/pull/6216) `v2/xquery-4.0-parser` | XQ4 grammar + version gating + feature flag | 8 commits, 63 files | QT4 89.3% | #6139, #6103 |
| [#6217](https://github.com/eXist-db/exist/pull/6217) `v2/declare-decimal-format` | `declare decimal-format` (XQ 3.1) | 2 commits, 6 files | — | #6077 |
| [#6218](https://github.com/eXist-db/exist/pull/6218) `v2/xq4-core-functions` | 82 XQ4 functions (fn:, array:, map:, math:) | 5 commits, 130 files | QT4 72.6% | (part of #6139) |
| [#6219](https://github.com/eXist-db/exist/pull/6219) `v2/serialization-compliance` | XML/HTML/XHTML/JSON/text/adaptive/CSV serialization | 14 commits, 26 files | — | #6138 |
| [#6220](https://github.com/eXist-db/exist/pull/6220) `v2/new-parser` | Recursive descent (rd) parser (opt-in: `-Dexist.parser=rd`) | 12 files | — | — |

## Integration Testing (next-v2)

All 14 branches merged together + Lucene 10 (#6146) + Jackrabbit WebDAV:

| Suite | next-v2 | develop baseline | Improvement |
|-------|---------|-----------------|-------------|
| **QT4** | 71,412 / 83,228 (85.8%) | 31,674 / 36,965 (85.7%) | +39,738 pass, +0.1% rate (test pool doubled) |
| **XQ 3.1** | 48,598 / 52,374 (92.8%) | 24,025 / 26,773 (89.7%) | +24,573 pass, +3.1% rate |
| **FTTS** | 1,320 / 1,334 (99.0%) | 661 / 667 (99.1%) | +659 pass (test pool doubled) |

exist-core unit tests: 6,240 / 6,357 (98.2%) — 15 failures, all pre-existing on develop. Zero new integration regressions.

## Parser Benchmark Results

The rd parser (`v2/new-parser`) was benchmarked against 5 other parsers:

| Parser | Speed vs ANTLR 2 | Compliance (QT4 prod-*) |
|--------|:-:|:-:|
| eXist rd | 15-82x faster | 80.6% |
| BaseX | 15-85x faster | 83.0% |
| REx (generated) | 50-300x faster | — |
| Saxon | 2-8x faster | 62.3% (includes static analysis) |
| eXist ANTLR 2 | baseline | 84.9% |

The rd parser matches BaseX speed (within 0.5-1.1x) and is only 2.6-3.5x slower than a pure recognizer (REx) — remarkable given it builds full Expression trees while REx does zero AST construction.

## Docker Image

A Docker image is available with all features and 19 pre-installed app packages, including the new dashboard, documentation, blog, notebook, and eXide. Build instructions are in the [testing apps guide](testing-apps-guide.md), or pull from `joewiz/existdb:next-v2`.

## Related Work

### XQTS Runner (exist-xqts-runner PR #45)

The [exist-xqts-runner](https://github.com/eXist-db/exist-xqts-runner) PR [#45](https://github.com/eXist-db/exist-xqts-runner/pull/45) (approved by @duncdrum, comments from @adamretter and @line-o) adds QT4, FTTS, and XQuery Update support to the compliance test runner. This is the infrastructure that validates all the XQTS scores cited in this report. It should be merged alongside the v2/ PRs.

### W3C XInclude Test Suite

[PR #6206](https://github.com/eXist-db/exist/pull/6206) adds the W3C XInclude 1.0 conformance test suite (148 tests) plus XInclude 1.1 and XProc 3.0 XInclude tests. Includes XPointer fixes and `parse="text"` support. Current baseline: **38/148 (25.6%)** on the W3C 1.0 suite. Identified gaps include xml:base handling, namespace comparison, and xpointer-scheme support.

### XInclude Compliance Scores

| Suite | Score | Notes |
|-------|-------|-------|
| **W3C XInclude 1.0** (develop) | 38/148 (25.7%) | Before PR #6206 |
| **W3C XInclude 1.0** (next-v2) | 72/126 (57.1%) | After PR #6206 (+34 pass, 22 reclassified as skipped) |

## What's Next

- Open the v2/ PRs for review (descriptions drafted, test plans checked off)
- Close the 14 superseded original PRs with pointers to the new ones
- Merge XQTS runner PR #45 (approved, enables QT4/FTTS/XQUF compliance testing)
- Merge XQTS runner PR #49 (Saxon 12 compat, after Saxon merge)
- Review eXide PR [#778](https://github.com/eXist-db/eXide/pull/778) (CM6 + REx + LSP)
- Merge exist-markdown PR [#69](https://github.com/eXist-db/exist-markdown/pull/69) (CommonMark/GFM, approved)
- Review jinks PR [#2](https://github.com/eeditiones/jinks/pull/2) (exist-site profile)
- Review XInclude PR [#6206](https://github.com/eXist-db/exist/pull/6206) (test suite + conformance fixes)
- Open Jackrabbit WebDAV PR (branch: `joewiz/feature/jackrabbit-webdav`, litmus 96/98)
- Publish [jackrabbit-webdav-jakarta](https://github.com/joewiz/jackrabbit-webdav-jakarta) to Maven Central (build prerequisite)
- The 10 independently approved PRs (#6087, #6092, #6142, #6162, #6163, #6182, #6184, #6186, #6191 + #6146) can be merged on their own schedule
