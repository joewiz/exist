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

## Interpretation

### For PR #4481 (Lazy Module Loading)

The startup savings from lazy loading would be at most **~40 ms** on a 44-module
production system (extrapolated from ~0.9 ms/module). Total BrokerPool startup
is typically 5-10 seconds including database recovery, so module loading is
**< 1% of startup cost**.

Memory overhead per module is negligible — modules share state through the
BrokerPool registry. Lazy loading would not meaningfully reduce memory usage.

The strongest argument for lazy loading is **reducing class loading and
initialization for rarely-used modules** (e.g., mail, jndi, image). This
matters more for applications that only use a subset of modules.

**Recommendation**: Lazy loading is a reasonable optimization for extension
modules, but the performance benefit is marginal. A per-module `load="lazy"`
attribute gives administrators the right granularity without imposing
cold-call penalties on commonly-used modules.

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
| XQueryContext creation | **~0 KB** | References shared module instances |
| Warm query execution | **347 µs** | Built-in fn:count |
| Module function call | **417 µs** | +70 µs vs warm |
| Module import + call | **448 µs** | +100 µs vs warm |
| registered-modules() | **599 µs** | 14 modules |
| registered-functions() | **1,193 µs** | 3,328 functions |
| registered-functions(uri) | **373 µs** | Single module, 78 functions |
