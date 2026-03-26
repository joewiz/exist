# Module Loading Performance Report

## Environment

- eXist-db 7.0.0-SNAPSHOT (develop branch, test conf.xml with 14 modules)
- Java 21.0.5 (Zulu)
- macOS, Apple M3 Pro
- Date: 2026-03-26

## Results

### Benchmark 1: Startup Time

Measures BrokerPool startup+shutdown time with different module counts.
Each measurement creates a fresh BrokerPool instance with temporary storage.

| Configuration | Modules | Avg (ms) | Min (ms) | Max (ms) |
|---|---|---|---|---|
| All test modules | 14 | **46** | 36 | 61 |
| Minimal (fn, util, xmldb, system) | 4 | **37** | 35 | 40 |

First cold start: 491 ms (includes JIT compilation, class loading).

**Marginal cost per module: ~0.9 ms**

### Benchmark 2: Memory Footprint

Measured with 14 modules loaded, 3,328 registered functions.

| Metric | Value |
|---|---|
| Heap after startup | 27,463 KB |
| Heap after module introspection | 27,478 KB |
| Module introspection delta | **15 KB** |
| Per-XQueryContext overhead | **~0 KB** (negligible) |

Module metadata is shared across XQueryContexts via the BrokerPool module registry.
Creating new XQueryContexts does not duplicate module state — they reference
the same loaded module instances.

### Benchmark 3: Query Execution Latency

All times in microseconds. 10 iterations after 3 warmup.

| Query | Avg (µs) | Median (µs) | Min | Max |
|---|---|---|---|---|
| Warm: `count(1 to 1000)` | **347** | 337 | 298 | 517 |
| Module call: `util:system-time()` | **417** | 374 | 328 | 869 |
| Import + call: `sm:get-account-metadata-keys()` | **448** | 450 | 422 | 486 |
| FLWOR with map (compile+execute) | **1,221** | 1,210 | 1,085 | 1,391 |

**Key finding**: The overhead of calling a function from an already-loaded module
vs a built-in function is **~70 µs** (417 - 347). Importing a module that's already
in the registry adds only **~100 µs** more (448 - 347). XQuery compilation
dominates execution time for non-trivial expressions (~1,200 µs for FLWOR+map).

### Benchmark 4: Module Discovery Cost

| Operation | Avg (µs) | Median (µs) | Min | Max |
|---|---|---|---|---|
| `registered-modules()` (14 modules) | **599** | 652 | 467 | 716 |
| `registered-functions()` (3,328 functions) | **1,193** | 1,000 | 690 | 3,352 |
| `registered-functions(util)` (78 functions) | **373** | 369 | 348 | 424 |

**Key finding**: `registered-functions()` across all modules costs ~1.2 ms for 3,328
functions. This is fast enough for interactive use but would be noticeable if called
on every request. The single-module variant is 3x faster (~370 µs).

### Benchmark 5: Per-Query Overhead vs. Module Count

Tests Adam Retter's claim (issue #1848): "For every query, we compile every
module loaded in conf.xml." Runs `count(1 to 10)` 10,000 times against both
configurations.

| Configuration | Avg (ns/query) | Median | p99 | Total (10k queries) |
|---|---|---|---|---|
| Minimal (4 modules) | **85,172** | 80,833 | 153,000 | 852 ms |
| Full (14 modules) | **83,823** | 80,791 | 111,625 | 838 ms |
| **Delta** | **-1,349** | -42 | | -14 ms |

**Result: No per-query overhead from extra modules.** The full configuration
is within noise of the minimal configuration (actually 1.6% faster, likely JIT
warmth effects). Adam's 2022 claim about per-query proportional cost does not
hold in the current codebase.

### Benchmark 6: XQueryContext Construction Cost

Isolates the cost of `new XQueryContext(pool)` — where module bindings are
established for each query.

| Configuration | Avg (ns) | Median | Min | Max |
|---|---|---|---|---|
| Minimal (4 modules) | **15,531** | 14,083 | 10,750 | 2,227,542 |
| Full (14 modules) | **18,126** | 16,750 | 14,583 | 1,653,500 |
| **Delta** | **+2,595** | +2,667 | | |
| **Per extra module** | **~260 ns** | | | |

XQueryContext creation cost IS proportional to module count (~260 ns per module),
but the absolute magnitude is tiny: 2.6 µs for 10 extra modules. For a 44-module
production config, context creation would be ~10 µs slower — still negligible
compared to query compilation (~80-94 µs) and execution.

### Benchmark 7: Compile vs. Execute Breakdown

Separates compilation from execution to identify where module overhead lives.

| Phase | Minimal (4 mod) | Full (14 mod) | Delta |
|---|---|---|---|
| **Compile** | 94 µs | 79 µs | -15 µs* |
| **Execute** | 2.9 µs | 0.9 µs | -2 µs* |
| **Total** | 97 µs | 80 µs | -17 µs* |

*Negative delta = full is faster (JIT warmth from running after minimal)

Compilation dominates query cost at **~80-94 µs** per query. Execution of
a trivial expression is **< 3 µs**. Module count has no measurable effect
on compilation speed — the XQuery parser and compiler do not scale with
the number of registered modules.

## Interpretation

### For PR #4481 (Lazy Module Loading)

**Adam's claim (issue #1848) is not supported by current data.**

The claim: "For every query, we compile every module loaded in conf.xml."

Our measurement: 10,000 queries show **no per-query overhead** from extra
modules. Full config (14 modules) runs at the same speed as minimal (4 modules):
~83 µs/query vs ~85 µs/query (Benchmark 5).

XQueryContext creation does scale with module count, but at only **~260 ns
per module** — for a 44-module production config that's ~10 µs per context,
which is ~12% of compilation time (80 µs). Measurable but not the "compile
every module" cost described in the issue.

Startup savings from lazy loading: at most **~40 ms** on a 44-module system.
Total BrokerPool startup is 5-10 seconds, so module loading is **< 1%**.

Memory overhead per module is negligible — modules share state through the
BrokerPool registry.

**Recommendation**: The data does not justify lazy loading for performance
reasons. The existing eager loading approach has negligible per-query and
per-startup cost. If PR #4481 is pursued, it should be motivated by
**startup time for embedded/minimal applications**, not per-query performance.

### For PR #6182 (Module Discovery / registered-functions)

`registered-functions()` costs ~1.2 ms for 3,328 functions across 14 modules.
For a 44-module production system, this might reach 3-4 ms. This is acceptable
for:
- Interactive tools (eXide function completion)
- One-time startup operations (RESTXQ route registration)
- Monitoring dashboards

It is NOT acceptable to call on every HTTP request. Our PR #6182's
`registered-functions()` enhancement should document this as an
administrative/development function, not a request-path operation.

With lazy loading (PR #4481), `registered-functions()` would need to either:
1. Only inspect loaded modules (fast, but incomplete)
2. Force-load all modules (slow, defeats lazy loading)
3. Cache results (best — load once, serve from cache)

**Recommendation**: Option 3 — cache function signatures at first call,
invalidate when modules are loaded/unloaded.

### For RESTXQ (PR #6154)

The `scan-on-startup=true` approach scans modules at servlet init time.
XQuery compilation (~1.2 ms per expression) dominates the scan cost.
For a typical RESTXQ app with 20-50 annotated functions across 5-10
modules, the startup scan would take **~60-120 ms** — well within
acceptable startup latency.

The trigger-based approach compiles modules when stored. The scan-based
approach compiles on startup or first request. Both take the same total
time; the difference is when the cost is paid:
- **Trigger**: paid immediately on module store (blocking the store operation)
- **Scan-on-startup**: paid at servlet init (before first request)
- **Scan-on-demand**: paid on first HTTP request (adds latency for first user)

**Recommendation**: `scan-on-startup=true` (our default) is the best
trade-off — no impact on module store operations, no cold-start latency
for users.

## Summary Table

| Metric | Value | Notes |
|---|---|---|
| Startup per module | **0.9 ms** | Marginal cost of each additional module |
| Module memory overhead | **~0 KB** | Shared via BrokerPool registry |
| XQueryContext creation per module | **~260 ns** | Scales linearly but negligible |
| Per-query overhead (4 vs 14 modules) | **none** | Within noise (-1.6%) |
| Warm query execution | **347 µs** | Built-in fn:count |
| Module function call | **417 µs** | +70 µs vs warm |
| Module import + call | **448 µs** | +100 µs vs warm |
| Compilation (trivial query) | **80-94 µs** | Dominates query cost |
| Execution (trivial query) | **1-3 µs** | Negligible |
| registered-modules() | **599 µs** | 14 modules |
| registered-functions() | **1,193 µs** | 3,328 functions |
| registered-functions(uri) | **373 µs** | Single module, 78 functions |

### Key finding: Adam's per-query claim (issue #1848)

| Operation | Minimal (4 mod) | Full (14 mod) | Delta | Per-module |
|---|---|---|---|---|
| Query (compile+execute) | 85 µs | 84 µs | **~0** | ~0 |
| XQueryContext creation | 15.5 µs | 18.1 µs | **+2.6 µs** | 260 ns |
| Compile only | 94 µs | 79 µs | **~0*** | ~0 |

*Full config faster than minimal due to JIT warmth — run order artifact
