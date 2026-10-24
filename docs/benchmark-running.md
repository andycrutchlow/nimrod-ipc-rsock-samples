# Running the three-library unary benchmarks

The implementation extends the existing Nimrod and gRPC applications under the `benchmark` Spring profile and adds a native Dubbo2 provider/consumer. Original pricing interfaces, DTOs, main classes, endpoints and pub/sub examples remain available outside that profile. No alternative IPC target or event benchmark has been added.

## Commands

Requirements: Java 21, Maven, Python 3, locally installed Nimrod `4.3.1-SNAPSHOT`, and Maven Central dependencies. `lsof` is recommended for observed socket counts; `ps` supplies RSS. All scripts use the standard Python library. Execute from the repository root.

Build and run unit checks, and generate runnable classpaths:

```sh
mvn verify dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt
```

After dependencies are cached, `mvn -o` works. Applications run with their existing Spring Boot main classes and generated classpaths; they are not replaced with duplicate main classes or executable-JAR repackaging.

Run short all-size/all-concurrency checks and intentional timeout checks:

```sh
python3 scripts/benchmarks/run.py --integration --offline
```

The command builds first. Use `--skip-build` after a successful build. Default invocation or `--smoke` runs only the 36 short RPC cases. `--integration` adds three delayed-handler timeout cases. Smoke phases are 0.3-second warmup / 0.7-second measurement with one repetition. These exercise data and lifecycle correctness, not stable performance. For a quick subset:

```sh
python3 scripts/benchmarks/run.py --smoke --skip-build --sizes 128 102400 --concurrency 1 32
```

Check the original pricing endpoints with the benchmark profile disabled:

```sh
python3 scripts/benchmarks/check_examples.py
```

Run the **full suite manually**, when ready:

```sh
python3 scripts/benchmarks/run.py --full --offline
```

Without filters, `--full` selects the 180-run matrix: three implementations, four body sizes, concurrency 1/8/32, five repetitions, 30-second warmup and 120-second measurement. It requires at least 7.5 hours of timed phases plus readiness/drain/startup. Neither Maven builds nor integration/smoke commands execute it. The script rotates library order between repetitions and runs cases sequentially.

Targeted full-duration runs accept the same implementation, size and concurrency filters as smoke checks:

```sh
python3 scripts/benchmarks/run.py \
  --full \
  --implementations nimrod \
  --sizes 128 1024 10240 102400 \
  --concurrency 8 32 \
  --offline \
  --output benchmark-results/nimrod-single
```

This selects 40 runs (one implementation × four sizes × two concurrency levels × five repetitions). Every case retains the full 30-second warmup, 120-second measurement, validation, metrics, resource sampling and process supervision. Omitted filters retain their default dimensions. Build each desired Nimrod server scheduler configuration before launching and use a distinct output directory for each configuration; the existing source manifest records the contract source hash.

The selected implementations, body sizes, concurrency levels, durations, repetition count and planned run counts are recorded as `selectedMatrix` in `suite.json`, `source-manifest.json`, each `launch.json` and each run result (including `suite-results.json`). Integration metadata counts the additional fixed delayed-handler checks separately.

Test matrix selection and command acceptance without launching Maven, JVMs or benchmarks:

```sh
python3 -m unittest discover -s scripts/benchmarks/tests -v
```

`benchmark-configs/unary.json` records the shared matrix, durations, timeout, drain and payload seed. The launcher records a copy and hashes of tracked/untracked source files in `source-manifest.json`. Each repetition starts fresh client/server JVMs with `-Xms1g -Xmx1g -XX:+UseG1GC`. No per-message logging is enabled. Keep other machine workload controlled before interpreting full results.

By default all targets use the same automatically detected local interface, with separate JVMs. Dubbo2 rewrites loopback URLs to its local interface, so the launcher obtains that address using its own `NetUtils` and applies it to all three stacks. Providers bind that specific interface, not a wildcard address. `--host <local-interface-IP>` overrides the selection; do not pass a loopback address when including Dubbo2. This is a same-host interface benchmark, not a cross-node test. Fresh free ports avoid collisions.

## Workload and measurement

Every library validates the same deterministic body, produces the same price and echoes the entire body and correlation fields. Request and response each contain 128, 1,024, 10,240 or 102,400 body bytes; envelope and protocol bytes are additional. Existing pricing methods continue to return their original values.

Client latency begins immediately before calling the transport function and ends after response mapping/full validation. It includes pool waiting, serialization, network and server processing; input generation and recording are outside the timer. Quantiles use bounded HdrHistogram recorders with three significant digits; the final maximum is the exact largest measured latency. Interval histogram maxima are quantized upper bounds. Failures/timeouts are separate from successful latency populations.

Throughput counts validated completions inside the measurement window. Warmup is fully drained before resetting. Post-window completions and their latencies have separate fields. Concurrency is fixed persistent synchronous workers, each with one unfinished call, not TCP connection count. Application processing uses eight workers; no per-call client task queue exists. The gRPC handler offloads to an eight-worker executor with a 32-entry queue; Nimrod uses its generated eight-worker scheduler; Dubbo uses a fixed eight-worker/32-entry provider queue.

This is a closed-loop capacity workload. Offered arrivals slow during stalls; these quantiles do not support independent-arrival SLO claims or demonstrate the elimination of coordinated omission. Open-loop scheduling is not implemented in this iteration. No smoke throughput/latency values are presented as performance conclusions.

## Result location and format

Default directory: `benchmark-results/<UTC timestamp>/`. Use `--output <new-directory>` for a fixed location; existing case directories are not overwritten. Generated data is ignored by Git.

Suite files:

- `suite.json`, `topology.json`, `source-manifest.json`: mode, environment/topology and exact source/configuration identity.
- `build.log`: Maven output when the script builds.
- `suite-results.json`: per-case pass/failure and validity. An intentional timeout is a passed integration check with an invalid performance result.

Each `<library>-<bytes>B-c<concurrency>-r<repetition>/` contains:

- `launch.json`, `server.log`, `client.log`, `run-result.json`: exact commands, process diagnostics and outcome.
- `client/manifest.json`, `server/manifest.json`: Java/JVM environment, configuration, transport identity and SHA-256 of classpath JARs, including local snapshot artifacts.
- `client/summary.json`: readiness/warmup/measurement attempted/completed/drain/error/timeout/invalid/unresolved counters; successful latency p50/p95/p99/exact maximum in nanoseconds; measurement completed RPC/s; validity.
- `client/intervals.csv` and `*.hlog`: interval distributions/rates and mergeable phase histograms. CSV units are in column names.
- `client/resources.csv`, `server/resources.csv`: streamed one-second process CPU, heap, nonheap, direct-buffer, thread and GC readings.
- `os-resources.csv`: external timestamped RSS (KiB) and diagnostic `ps` CPU percentage. Use JDK CPU time for comparisons, not the lifetime percentage from `ps`.
- `resource-summary.json`: common measurement-window CPU cores and sampled memory summaries per process. Server CPU is interpolated at client window boundaries; short windows may lack samples, recorded as null with a reason.
- `client-connections.*`, `server-connections.*`: established socket evidence observed during measurement when `lsof` is available.
- Dubbo `*/transport-observation.json`: serializer actually invoked on encode/decode, native effective URL, code-source JAR and wire serializer ID. Both directions must observe Kryo ID 8 for the run to pass.
- `server/server-summary.json`: cumulative handler processed/error totals after provider shutdown.

Resource samples use wall time only to align processes; RPC latency uses one client monotonic clock. CPU is cores consumed, not an unexplained percentage. All repetitions remain available; the script does not average quantiles or select a fastest run. Missing metrics, timeout aborts and serializer mismatch are visible rather than converted to successes.

## Actual Dubbo compatibility choices

The implementation retains **Dubbo 2.7.23**, **native `dubbo` binary TCP**, direct reference, registry `N/A`, no discovery/config/metadata process and no Triple. One reference-private connection is reused by concurrent calls (`connections=1`, `lazy=false`). Retries are zero, cluster fail-fast, RPC timeout five seconds, connect timeout three seconds, heartbeat 60 seconds / heartbeat timeout 180 seconds and reconnect two seconds. Requested/effective URLs and observed sockets are retained.

The successful serializer is the **Kryo SPI bundled in Dubbo 2.7.23**, using **`com.esotericsoftware:kryo:4.0.2`** and **`de.javakaffee:kryo-serializers:0.42`**, matching the release BOM. The previously proposed external 1.0.1 extension was not retained: it duplicates the bundled serializer classes and introduces Kryo 5 dependencies incompatible with the bundled Kryo 4 pool API. No Hessian fallback was used. Nimrod independently uses its Kryo 5 artifact; equal serializer family does not imply equal versions/bytes.

Java 21 requires Dubbo JVM flags opening `java.lang`, `java.util` and `java.text` to unnamed modules for its proxy generator and registered Kryo serializers. The launcher sets these only for Dubbo and records the command/flags. Javassist is pinned to 3.30.2-GA in Dubbo modules rather than its old transitive version. Boot 3.5.4 manages ordinary programmatic `ServiceConfig`/`ReferenceConfig` beans; no legacy Dubbo Boot starter is needed. Do not remove these adjustments or claim stock dependency compatibility without retesting. Dubbo 2.7 is a legacy/EOL comparison target.

A small Dubbo SPI decorator delegates unchanged native encoding while counting actual serialize/deserialize invocations and recording the effective URL. Its counters add two `LongAdder` operations per RPC per process; that instrumentation overhead is included and disclosed in results. Mismatched protocol or serializer makes the run fail. This is not a transport-only benchmark.

## Timeout and process limits

See [Nimrod timeout investigation](nimrod-timeouts.md). gRPC uses a per-call native deadline and Dubbo its configured timeout. Nimrod's generated blocking proxy does not expose an equivalent native client deadline. The shared deadline monitor checks in-flight calls approximately every five milliseconds, interrupts an over-budget worker, stops new calls and marks the run invalid. It does not retry, create replacement workers or pretend that cancellation stops remote work.

Warmup/readiness/measurement all use the bounded supervisor. A stuck worker remains daemonized and is reported unresolved after the drain bound; finite benchmark clients explicitly exit. The external Python supervisor separately bounds server startup, client lifetime and shutdown, escalating from TERM to KILL on its own process group if necessary. Never run the matrix by repeatedly invoking an unbounded raw proxy yourself. There is no claim of equivalent native timeout semantics, and no Nimrod library changes were made.
