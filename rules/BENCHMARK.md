# Rule engine — latency benchmark (Spec 05)

The headline acceptance criterion for the rule engine is **p99 of `RuleEngine.evaluate` in
single-digit milliseconds** under representative load. This file records how that is measured and the
numbers from a reference run. The measurement is **enforced**, not just documented: a JUnit gate runs
on every `verify` and fails the build if p99 ≥ 10 ms.

## Why the engine is fast

`evaluate` is pure and in-memory by construction:

- Rules are authored as data (`driftless.rules.*`) and **compiled once** — off the hot path, at
  startup and on each cache refresh — into immutable predicate records (`CompiledRule`), pre-sorted by
  priority (`CompiledRuleSet`).
- The hot path reads the live rule set via a single lock-free `AtomicReference` load
  (`RuleCache.current()`); evaluation is an in-order scan of integer minor-unit comparisons that exits
  at the first declining rule.
- There is **no datasource on the path** — `availableBalance` and the `VelocitySnapshot` are passed
  into the context by the saga, so the engine performs no I/O. (`NoDatabaseOnHotPathTest` asserts the
  engine's Spring context contains zero `DataSource` beans.)

## The enforced gate — `RuleEngineLatencyTest`

`src/test/java/io/driftless/rules/internal/RuleEngineLatencyTest.java` is a self-contained, recorded
load test that runs in `verify`:

- **Rule set:** 5 active rules — per-transaction limit, periodic spend cap, velocity count, velocity
  amount, and a 4-code MCC block list (a representative production mix).
- **Workload:** 1,024 pre-built contexts with a seeded random spread of amounts, MCCs, and velocity
  snapshots, so the measurement covers both the full-scan approve path and the early-exit decline
  paths.
- **Method:** 50,000 warm-up calls to reach steady state, then **500,000 measured calls**, each timed
  with `System.nanoTime()`. The full distribution is sorted and p50/p90/p99/p99.9/max are computed.
- **Assertion:** `p99 < 10 ms`. The percentiles are logged so the numbers below can be refreshed from
  any run.

## Reference numbers

Windows 11, JDK 21, 500,000 measured calls over 5 active rules. Two observed runs (the log line is
emitted by the test on every run, so these refresh automatically):

| Percentile | `test` run | `verify` run |
|-----------|-----------|--------------|
| p50 | 0.0053 ms (5.3 µs) | 0.0002 ms (0.2 µs) |
| p90 | 0.0068 ms (6.8 µs) | 0.0005 ms (0.5 µs) |
| **p99** | **0.0250 ms (25 µs)** | **0.0009 ms (0.9 µs)** |
| p99.9 | 0.1160 ms (116 µs) | 0.0031 ms (3.1 µs) |
| max | 92.25 ms | 0.69 ms |

**p99 is on the order of 1–25 microseconds — roughly 400–10,000× under the 10 ms budget.**

The two runs differ mostly in the tail: the `test` run's single-sample `max` (~92 ms) is a lone
JVM/OS outlier (a GC pause or scheduler preemption on one of half a million calls), while a warmer run
shows a sub-millisecond max. This run-to-run tail variance is exactly why the enforced gate targets
**p99**, not max. Absolute figures are hardware- and JVM-dependent, but the engine clears the
single-digit-millisecond p99 target by three to four orders of magnitude, so the gate has very large
headroom on any realistic CI machine.

## Deeper profiling — `RuleEngineBenchmark` (JMH, optional)

`src/test/java/io/driftless/rules/internal/RuleEngineBenchmark.java` is a JMH `SampleTime` benchmark
over the same rule set and workload, for on-demand profiling. It is **not** wired into the build (so it
does not slow `verify`). Run it directly via its `main`, e.g.:

```powershell
.\mvnw.cmd -pl rules -am -DskipTests test-compile
# then run io.driftless.rules.internal.RuleEngineBenchmark.main on the test classpath
```

It reports the same percentile family in microseconds for higher-resolution analysis.
