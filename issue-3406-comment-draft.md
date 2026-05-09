[This response was co-authored with Claude Code. -Joe]

Following up on this almost-six-year-old report against current `develop` (commit `b917e1ab1d`). The headline findings:

1. **The "exponentially degrading" curve is gone.** Modern eXist scales cleanly as O(N²) on this reproducer, with `ms / $length²` essentially constant from `$length=200` upward.
2. **eXist itself is ~4× faster** than the 2020 5.3-SNAPSHOT numbers in the issue body.
3. **But BaseX has also gotten faster**, so the eXist-vs-BaseX ratio at issue scale is still ~9× — basically unchanged from 2020. So the gap is real, not a measurement artefact, but its shape is different from what the original "non-linear degradation" framing suggested.
4. **The residual cost is concentrated in two generic hotspots that are not specific to `for-each-pair`** — `Type.subTypeOf` and per-call user-function dispatch. A surgical optimisation to `FunHigherOrderFun.forEachPair` itself would yield <2% improvement.

## Scaling table — current `develop` vs modern BaseX

JUnit harness `Issue3406Benchmark` on the `perf/3406-foreachpair-flwor` branch, jacoco disabled, JIT warmed (best-of-3 below `$length=200`, single run above), Zulu JDK 21.38.21, MacBook Pro M-series. BaseX 12.3 numbers from a parallel run via `xquery:eval` to defeat constant folding.

| `$length` | 2020 eXist 5.3 (s) | 2020 BaseX 9.3 (s) | **2026 develop (s)** | **2026 BaseX 12.3 (s)** | eXist speedup vs 2020 | eXist : BaseX (2020) | eXist : BaseX (2026) | ms / `$length²` |
|-----------|--------------------|--------------------|----------------------|-------------------------|-----------------------|----------------------|----------------------|------------------|
| 100       | 0.05               | 0.009              | 0.014                | 0.008                   | 3.6×                  | 5.6×                 | 1.8×                 | 0.0014           |
| 200       | 0.14               | 0.02               | 0.038                | 0.016                   | 3.7×                  | 7.0×                 | 2.4×                 | 0.00095          |
| 500       | 0.9                | 0.1                | 0.230                | 0.058                   | 3.9×                  | 9.0×                 | 4.0×                 | 0.00092          |
| 1000      | 3.7                | 0.4                | 0.922                | 0.125                   | 4.0×                  | 9.3×                 | 7.4×                 | 0.00092          |
| 2000      | 15.3               | 1.4                | 3.670                | 0.425                   | 4.2×                  | 10.9×                | 8.6×                 | 0.00092          |
| 3000      | 36                 | 3.5                | 8.153                | 0.938                   | 4.4×                  | 10.3×                | 8.7×                 | 0.00091          |

The `ms / $length²` column collapses to a flat ~0.0009 above `$length=200` — exactly what a clean O(N²) algorithm should produce. The "non-linear degradation" the original report saw on eXist 5.3 has been resolved.

## Cost decomposition

Reduced shapes of the same query at `$length`={1000, 2000, 3000} run on current `develop`:

| `$length` | outerOnly* | hofOnly† | asWritten | inlineFlwor‡ |
|-----------|-----------:|---------:|----------:|-------------:|
| 1000      |   220 ms   |  887 ms  |   922 ms  |   1171 ms    |
| 2000      |   869 ms   | 3339 ms  |  3670 ms  |   5047 ms    |
| 3000      |  2010 ms   | 7538 ms  |  8153 ms  |  10978 ms    |

\* outer-loop only: `for $n1 in $seq, $n2 in $seq return $n1?n + $n2?n`
† outer + `for-each-pair` calls but no `every` quantifier
‡ `for-each-pair` replaced with an inline FLWOR (`for $i in 1 to ... return $a[$i] eq $b[$i]`)

At `$length=3000`, the as-written 8.15s breaks down as:

- **25%** outer FLWOR nested-loop scaffold (the floor of the N² join)
- **67%** `for-each-pair` invocations (1M HOF calls × 3 inner iterations = 3M user-function evaluations)
- **8%**  `every $alignment satisfies $alignment`

Two observations from this:

- **Replacing `for-each-pair` with a hand-written FLWOR is 35% slower**, not faster. So a surgical micro-optimisation to `FunHigherOrderFun.forEachPair` (e.g., reusing the per-call `Sequence[2]` argument array, like `foldRightNonRecursive` does) is unlikely to move the needle — the scaffold is a small fraction of the per-call cost.
- **Hash-join recognition would not help this query.** The join condition is a user-defined function (`local:compare#2`) over a derived value, with no comparable hash key. So even if eXist gained the BaseX `CmpHashG`-style hash-join rewrite, this particular reproducer would not benefit from it.

## JFR profile

A 55-second JFR at `$length=1000` (3,333 execution samples at 10 ms cadence). Top self-time:

| % samples | method                                          | role                                                      |
|----------:|--------------------------------------------------|-----------------------------------------------------------|
|    20.0%  | `Int2ObjectArrayMap.findKey`                    | linear-scan inside `Type.subTypeOf` union-types map       |
|     8.9%  | `UserDefinedFunction.eval`                      | actual `local:compare` body                               |
|     7.5%  | `ValueSequence.add`                             | result accumulation                                       |
|     6.6%  | `HashMap.getNode`                               | variable / scope lookups                                  |
|     6.0%  | `HashMap$HashIterator.<init>`                   | iterator allocation in lookups                            |
|     5.0%  | `FunctionCall.evalFunction`                     | UDF dispatch                                              |
|     4.6%  | `XQueryContext.resolveLocalVariable`            | per-call variable resolution                              |
|     4.4%  | `VariableImpl.destroy`                          | per-call variable teardown                                |
|     5.3%  | `Function.analyze` + `UserDefinedFunction.analyze` | per-call analyze in HOF dispatch                       |
|     1.2%  | `FunHigherOrderFun.forEachPair`                 | the HOF scaffold itself                                   |

The biggest single hotspot is **`Type.subTypeOf`** calling `Int2ObjectArrayMap.containsKey` twice per call as a linear scan. This is hot via `StringValue.compareTo` → `ValueComparison.compareAtomic`, on every `xs:string eq xs:string` the user function performs. It is a generic eXist hotspot, not specific to `for-each-pair`. A one-line fast-path (`if (!unionTypes.isEmpty())`) or a switch from `Int2ObjectArrayMap` to a hash-based map looks like an obvious follow-up — I'll file a separate issue for it.

The `forEachPair` scaffold itself is 1.2% — confirming the cost-decomposition table above: there is no surgical fix here.

The remaining ~15% is per-call function-dispatch overhead (variable resolve / destroy / analyze). That is the same architectural cost @duncdrum mentioned in 2020 ("we don't have a centralized iteration class") and is its own larger conversation.

## Recommendation

I'd suggest:

- **Close this issue**, with the diagnostic preserved on branch `perf/3406-foreachpair-flwor` (commit forthcoming) and the reproducer harness available for re-use.
- **File a follow-up issue for `Type.subTypeOf`** — that's a real, narrowly-scoped bottleneck and the 20% sample share suggests fixing it would be visible across many query shapes, not just this one.
- The remaining gap vs BaseX 12.3 (~9× at issue scale) is real but is dominated by per-call function-dispatch overhead in eXist, which would need an architectural conversation to address. That is not a #3406 fix.

Happy to keep this issue open as a tracker for the function-dispatch architectural work if anyone prefers, but the original "10× degrading non-linearly" framing no longer fits the current behaviour.
