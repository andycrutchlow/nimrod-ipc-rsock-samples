# Implementation validation

> Historical record: version references, commands and validation statements below describe the dated investigation/design, not current build requirements. Current samples use **Nimrod IPC 4.3.1 from Maven Central**; see [release validation](release-validation.md) and the [completed 180-run benchmark report](../BENCHMARKS.md).

Validated 2026-10-16 on Oracle GraalVM Java 21.0.8, Maven 3.8.1, macOS 14.5 x86_64 and Spring Boot 3.5.4.

- Clean offline Maven `verify` and dependency-classpath generation: all 15 reactor projects passed.
- Shared workload/runner unit tests: 7 passed, zero failures/errors/skips. Tests cover body/correlation corruption, concurrency/accounting, timeout interruption and uninterruptible-worker termination.
- Short full coverage: 36 healthy size/concurrency RPC checks and 3 intentional timeout checks passed (39/39). Each healthy case uses 0.3-second warmup and 0.7-second measurement, one repetition.
- Final-artifact check after the exact-max/quantile-bound adjustment: 100 KB, concurrency 32, all three libraries passed (3/3). Successful counters/histograms reconcile and failures/timeouts/unresolved counts are zero.
- Original pricing examples with benchmark profiles disabled: single and eight-way parallel endpoints passed for both Nimrod and gRPC (4 endpoint checks, 18 validated responses).
- Dubbo remains 2.7.23 native binary TCP with actual Kryo serializer ID 8 on both processes; no Triple or Hessian fallback.
- Observed client socket counts at concurrency 32 were 32 Nimrod / 1 gRPC / 1 Dubbo2. These are recorded topology differences, not performance conclusions.
- Full 180-run benchmark matrix: **not executed**.

Generated local artifacts (ignored by Git):

- Build log: `benchmark-results/final-build.log`.
- All-size/concurrency and timeout checks: `benchmark-results/validation-20261016/`.
- Final-artifact smoke: `benchmark-results/final-artifact-smoke-20261016/`.
- Existing pricing checks: `benchmark-results/legacy-pricing-1792182294570806000/`.
- Unit XML/text reports: `benchmark-common/target/surefire-reports/`.

Known limits: Nimrod has an interrupt/abort benchmark deadline rather than a proven native client deadline/remote cancellation; Dubbo requires the documented Java 21 flags and matching Kryo 4 dependencies. CPU/memory sampling in sub-second smoke windows can produce missing values, explicitly null with reasons. No throughput/latency performance conclusions are drawn from these checks. See [running instructions](benchmark-running.md), [timeout details](nimrod-timeouts.md) and [developer experience](developer-experience.md).

Manual full-suite command from the repository root:

```sh
python3 scripts/benchmarks/run.py --full --offline
```

Results are JSON summaries/manifests, CSV interval/resource readings and HdrHistogram logs under `benchmark-results/<UTC timestamp>/` by default.
