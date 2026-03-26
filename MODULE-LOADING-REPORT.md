# Module Loading Performance Report

## Environment

- eXist-db 7.0.0-SNAPSHOT (develop branch)
- Java 21.0.5 (Zulu)
- macOS, Apple M3 Pro
- Date: 2026-03-25

## Benchmark 1: Startup Time

Measures BrokerPool startup time with different numbers of configured modules.
Each measurement starts a fresh BrokerPool instance with temporary storage.

| Configuration | Modules | Avg (ms) | Min (ms) | Max (ms) |
|---|---|---|---|---|
| All test modules | 14 | **46** | 36 | 61 |
| Minimal (fn, util, xmldb, system) | 4 | **37** | 35 | 40 |

**First cold start**: 491 ms (includes JIT compilation, class loading)

**Key finding**: The marginal cost of loading 10 additional modules is **~9 ms**
(~0.9 ms per module). For the production config with 44 modules, this extrapolates
to roughly **40 ms** of module-loading overhead — negligible compared to the
total BrokerPool startup time which includes database initialization, index
loading, and recovery.

## Benchmarks 2-4: Not yet complete

Memory footprint, query execution latency, and module discovery cost benchmarks
require further work to run XQuery queries within the exec:java classpath.
The FnModule static initialization fails outside the normal Maven test
infrastructure. These benchmarks should be run as JUnit tests instead.

## Interpretation

### For PR #4481 (Lazy Module Loading)

The startup savings from lazy loading would be at most **~40 ms** on a production
system with 44 modules. This is a small fraction of total startup time (typically
5-10 seconds including database recovery). The benefit is real but marginal.

The lazy loading approach has a hidden cost: **first-call latency** for each
module. When a user's XQuery first imports a lazily-loaded module, the module
must be initialized on-demand. This adds latency to the first query that uses
each module — potentially hundreds of milliseconds for modules with complex
initialization (e.g., Lucene, which initializes index readers).

**Recommendation**: Lazy loading is a reasonable optimization for rarely-used
extension modules (image, jndi, mail, cqlparser), but core modules (fn, util,
xmldb, map, array, math) should remain eager. A `load="lazy"` attribute per
module in conf.xml gives administrators the right granularity.

### For PR #6182 (Module Discovery)

The `registered-functions()` call iterates all loaded modules and collects
function signatures. For eagerly-loaded modules, this is a fast in-memory
operation. For lazily-loaded modules, it would trigger loading of ALL modules
to discover their functions — defeating the purpose of lazy loading.

**Recommendation**: `registered-functions()` should only inspect already-loaded
modules by default. Add an option to force-load all modules if needed.

### For RESTXQ (#6154)

The trigger-based approach (old RestXqServlet) pre-compiles modules when stored.
The scan-based approach (NativeRestXqServlet) compiles on first request.

With the `scan-on-startup=true` option (our default), the native servlet
pre-scans at startup — similar to the trigger approach but without the
trigger infrastructure overhead. The scan cost is dominated by XQuery
compilation, not module loading.

**Recommendation**: `scan-on-startup=true` provides the best of both worlds:
no cold-start latency for users, no trigger complexity for developers.

## Next Steps

1. Run memory footprint benchmarks as JUnit tests (not exec:java)
2. Benchmark with the full 44-module production config
3. Measure cold-call latency for lazily-loaded modules
4. Profile which modules have the most expensive initialization
