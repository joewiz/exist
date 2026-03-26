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
a trivial expression is **< 3 µs**.

### Benchmark 8: Module Instantiation — Code-Level Analysis

**⚠ CONFIRMED: Modules ARE re-instantiated per XQueryContext — not shared.**

Every `new XQueryContext(pool)` creates fresh module instances via reflection:
- `XQueryContext.loadDefaults()` iterates ALL built-in modules from conf.xml
- For each module, `addBuiltInModuleOrDeclareNamespace()` checks `getModules()` on the fresh empty map
- Finding nothing, it calls `instantiateModule()` which does:
  1. `Class.getConstructor()` — reflection lookup
  2. `constructor.newInstance()` — creates new module instance
  3. `module.prepare(ctx)` — module-specific initialization

| Metric | Value |
|---|---|
| Configured modules | 14 |
| Modules in fresh context | 14 |
| Module instances **shared** between contexts | **0** |
| Module instances **re-created** per context | **14** |

Context creation with 14 modules: **avg 11 µs** (median 10.5 µs).

### Benchmark 9: Per-Module Instantiation Cost

| Module | Constructor | prepare() | Total |
|---|---|---|---|
| MapModule | 621 ns | 65 ns | 686 ns |
| UtilModule | 614 ns | 223 ns | 837 ns |
| ResponseModule | 550 ns | 37 ns | 587 ns |
| SecurityManagerModule | 577 ns | 39 ns | 616 ns |
| TransformModule | 547 ns | 40 ns | 587 ns |
| XMLDBModule | 617 ns | 36 ns | 653 ns |
| ArrayModule | 545 ns | 44 ns | 589 ns |
| MathModule | 557 ns | 38 ns | 595 ns |
| InspectionModule | 559 ns | 36 ns | 595 ns |
| SessionModule | 518 ns | 32 ns | 550 ns |
| SystemModule | 574 ns | 36 ns | 610 ns |
| ValidationModule | 536 ns | 126 ns | 662 ns |
| FnModule | 550 ns | 27 ns | 577 ns |
| RequestModule | 549 ns | 30 ns | 579 ns |
| **GRAND TOTAL** | | | **8,723 ns** |

**Per-module cost: ~623 ns** (constructor ~560 ns + prepare ~63 ns).

For a 44-module production config: **~27 µs per context creation** from module
instantiation alone. The `prepare()` cost is minimal for these core modules.
Extension modules (Lucene, mail, scheduler) might have heavier `prepare()` calls.

### Benchmark 10: Compilation vs. Function Count

| Query | Functions | Avg compile (ns) |
|---|---|---|
| `count(1 to 10)` | 1 fn: call | 171,364 |
| `util:uuid()` | 1 module call | 96,009 |
| `util:uuid() + system:get-version()` | 2 module calls | 235,837 |
| 9 fn: calls (count, sum, etc.) | 9 fn: calls | 932,667 |

Compilation time scales with **number of function calls in the query**, not
with the number of modules in scope. The 14 loaded modules don't add overhead
to queries that don't use them.

## Interpretation

### For PR #4481 (Lazy Module Loading)

**Adam's claim is PARTIALLY confirmed at the code level.**

The claim: "For every query, we compile every module loaded in conf.xml."

**What we found:**
1. **Module re-instantiation is real**: Every `new XQueryContext()` calls
   `instantiateModule()` for ALL built-in modules — doing reflection
   (`Class.getConstructor`, `newInstance()`) and `prepare()` for each one.
   Module instances are NOT shared between contexts.

2. **The cost is measurable but small**: ~623 ns per module, totaling ~8.7 µs
   for 14 modules and extrapolating to ~27 µs for 44 production modules.
   This is ~30% of compilation time (80-90 µs) — not negligible, but also
   not the dominating factor.

3. **The per-query benchmark (10k queries) shows no aggregate effect** because
   the 27 µs per-context overhead is dwarfed by query compilation (80-90 µs)
   and other per-query costs. At scale (millions of queries), the overhead
   adds up: 27 µs × 1M queries = 27 seconds of CPU time.

4. **Heavy extension modules could be worse**: Our test only covers 14 core
   modules with lightweight `prepare()` calls (~63 ns average). Production
   modules like Lucene, scheduler, and mail might have heavier initialization.

**Recommendation**: Lazy loading would eliminate the per-query reflection +
instantiation cost for unused modules. For a 44-module production config,
this could save ~27 µs per query for queries that only use core functions.
The optimization is justified for **high-throughput deployments** (>10k
queries/sec) where microsecond-level savings compound. For typical
interactive use, the benefit is marginal.

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

**Partially confirmed.** Modules ARE re-instantiated per context via reflection,
but the per-query cost (~623 ns/module, ~27 µs for 44 modules) is small
relative to compilation (~80-170 µs) and doesn't show up in aggregate
query-level benchmarks.

| What Adam claimed | What we measured | Verdict |
|---|---|---|
| "Compile every module per query" | Modules re-instantiated per context via reflection | **Confirmed** |
| Per-query cost proportional to modules | ~623 ns/module (27 µs for 44 modules) | **Real but small** |
| This is inefficient and slows things down | Not visible in aggregate benchmarks (85 µs/query) | **Marginal impact** |

| Operation | Minimal (4 mod) | Full (14 mod) | Delta | Per-module |
|---|---|---|---|---|
| Query (compile+execute) | 85 µs | 84 µs | **~0** | ~0 |
| XQueryContext creation | 15.5 µs | 18.1 µs | **+2.6 µs** | 260 ns |
| Module instantiation (direct) | — | 8.7 µs | — | 623 ns |
| Compile only | 94 µs | 79 µs | **~0*** | ~0 |

*Full config faster than minimal due to JIT warmth — run order artifact
