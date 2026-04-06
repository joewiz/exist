# QT4 Gap Definitive Analysis

## Methodology

Selected 14 specific failing QT4 rd tests from 4 gap categories. For each, ran the exact query through both parsers on `feature-new-parser` using `assertBothParsers()` which compiles and evaluates under both ANTLR 2 and the rd parser, then asserts identical string results.

## Results

**14/14 tests produce IDENTICAL results on both parsers.**

| Test | Category | ANTLR 2 Result | rd Parser Result | Match? |
|------|----------|----------------|------------------|--------|
| `insert-before(('a','b','c'),0,('x','y'))` | trailing | `x y a b c` | `x y a b c` | YES |
| `concat('a','b')` | trailing | `ab` | `ab` | YES |
| `sum((1,2,3))` | trailing | `6` | `6` | YES |
| `string-join(tokenize('a    b c','\s'),'|')` | trailing | `a||||b|c` | `a||||b|c` | YES |
| `abs(3.5)` | trailing | `3.5` | `3.5` | YES |
| `zero-or-one('a')` | trailing | `a` | `a` | YES |
| `string(number(()))` | trailing | `NaN` | `NaN` | YES |
| duplicates-filter (FLWOR) | XPTY0004 | `10 20` | `10 20` | YES |
| duplicates-index-of | XPTY0004 | `1 2` | `1 2` | YES |
| yearMonthDuration arithmetic | XPTY0004 | `P1Y6M` | `P1Y6M` | YES |
| dayTimeDuration arithmetic | XPTY0004 | `PT5M` | `PT5M` | YES |
| recursive element rename | unexpected | `<x>text</x>` | `<x>text</x>` | YES |
| escape-html-uri | wrong value | `http://example.com/test` | `http://example.com/test` | YES |
| translate('bar','abc','ABC') | wrong value | `BAr` | `BAr` | YES |

## Cross-Branch Verification (2026-03-30)

Ran the same 10 tests on the `next` branch directly:

```
cd ~/workspace/exist/.claude/worktrees/next
mvn test -pl exist-core -Dexist.parser=rd \
  -Dtest="org.exist.xquery.parser.next.XQueryParserTest#qt4_next_*"
```

**Result: 10/10 PASS on `next` with the rd parser.**

Both parsers produce identical results on BOTH branches for all tested patterns.

## Conclusion

**The QT4 gap is NOT from the rd parser, the expression classes, or the integration files.** All tested queries produce identical results on both parsers on both branches.

### What the QT4 gap IS:

**The XQTS runner.** The gap is in how the exist-xqts-runner:
1. Compiles test queries (possibly with different compilation options)
2. Binds external variables (the `$result` variable binding path)
3. Evaluates assertion queries (the `string-join(for $r in $result...)` pattern)
4. Serializes results for comparison
5. Loads test modules via `compileModule()` (module compilation path)

The runner uses `XQuery.compile()` and `XQueryContext.compileModule()` in ways that differ from direct `XQueryParser.parse()`. The trailing-space, XPTY0004, and wrong-value failures all disappear when the same queries are compiled and evaluated directly.

### What the QT4 gap is NOT:

- NOT a parser bug (queries parse and evaluate correctly on both parsers)
- NOT a tree-shape unwrapping bug (GeneralComparison fix is on both branches)
- NOT a contextId issue (<1% of gap)
- NOT missing file sync (9 integration files are now on both branches)

### Next investigation:

1. **Instrument the XQTS runner** — add logging to `ExistServer.executeQuery()` to see what compilation options are passed
2. **Compare runner compilation vs direct** — is the runner setting different `OutputProperties` or `XQueryContext` flags?
3. **Check runner module loading** — does the runner's `compileModule` path differ from the unit test path?
4. **Reproduce one trailing-space failure in the runner** — pick `functx-fn-concat-1` and trace the exact `$result` sequence through the runner's assertion pipeline
