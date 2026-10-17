# Three-library unary RPC benchmark design

> Implementation update (2026-10-16): the approved unary comparison is implemented. Use [benchmark-running.md](benchmark-running.md) for commands/results and [nimrod-timeouts.md](nimrod-timeouts.md) for cancellation limits. Runtime checks selected the Kryo SPI bundled in Dubbo 2.7.23 with Kryo 4.0.2 / kryo-serializers 0.42, replacing the proposed external extension 1.0.1 because of duplicate classes and incompatible Kryo dependencies. Java 21 also requires the documented module-access flags. The full 180-run matrix is manual-only and has not been executed.

Revised 2026-10-16. Implemented; only build/integration/smoke checks have been run, with no performance conclusions. This design replaces the earlier broad transport/event suite and implements the scope in the [implementation plan](implementation-plan.md). Preserve and extend the working Nimrod/gRPC applications. Add only Apache Dubbo2 native RPC; capability and serializer evidence is in the [comparison matrix](comparison-matrix.md).

## Required implementations

| Target | Protocol and serializer |
| --- | --- |
| Nimrod IPC `4.3.1-SNAPSHOT` | RSocket over TCP, native Kryo; snapshot hash and resolved Kryo version recorded |
| Existing gRPC Java baseline | gRPC over HTTP/2 over TCP, Protocol Buffers; effective runtime/protoc/stub versions recorded |
| Apache Dubbo2 `2.7.23` | Native Dubbo binary RPC over TCP (`dubbo` / `dubbo://`), bundled Kryo SPI using Kryo 4.0.2, runtime-verified |

Dubbo must not use Triple, HTTP/2, gRPC compatibility or protobuf wrappers, nor gain an additional Triple comparison. Provider/consumer connect directly with registry `N/A`, no service discovery/config/metadata server and no unnecessary external process. Reuse existing application entry points and existing annotation/protobuf generation for Nimrod/gRPC; only Dubbo adds a new provider/consumer pair.

The active Dubbo serializer is bundled in `org.apache.dubbo:dubbo:2.7.23`, with `com.esotericsoftware:kryo:4.0.2` and `de.javakaffee:kryo-serializers:0.42` on both sides. `serialization=kryo` is explicit. The proposed external extension 1.0.1 was discarded after its duplicate classes/Kryo 5 dependency failed against the core's Kryo 4 API. No fallback serializer was used. [Runtime choices](benchmark-running.md#actual-dubbo-compatibility-choices) document the minimal module-access flags and proxy dependency required on the tested Java 21/Boot setup.

## Shared request and processing

Use neutral benchmark Java request/response POJOs in `benchmark-common`, preserving existing pricing DTO signatures. Include `runId`, `phaseId`, sequence, pricing inputs, body length/checksum and body bytes. Protobuf maps equivalent fields. Each target validates the request, computes the same fixed price and echoes the entire body and identifiers; the client validates every response. No database, simulated sleep, compression or server-dependent business logic.

Body sizes are exactly **128, 1,024, 10,240 and 102,400 bytes** in both request and response. Envelope, serializer and wire framing are additional bytes. Generate deterministic pseudorandom payloads outside measurement with a recorded seed and a bounded immutable pool. Count protobuf/POJO mapping and native serialization as application-stack cost, and report encoded sizes where available. Equal logical payloads do not imply equal wire sizes.

Kryo selection for Dubbo improves relevance to Nimrod but does not isolate transport cost: underlying Kryo versions, serializers, registrations, reference tracking and pools may differ. Record both configurations separately. Do not enforce wire compatibility between them; each library's client/server pair must agree internally.

## Matrix and lifecycle

Required matrix: **3 implementations × 4 sizes × concurrency 1/8/32 × 5 repetitions = 180 measured runs**. Each run uses one client JVM and one provider JVM, **30 seconds warmup and 120 seconds measurement** at the same size/concurrency. Minimum phase time is 7.5 hours, excluding startup/drain. Run the Nimrod/gRPC matrix first, then add Dubbo's 60 runs after compatibility checks.

1. Start fresh client/server processes for a repetition; capture resolved configuration/artifact hashes and confirm the actual protocol/serializer. Require a successful validated readiness RPC.
2. Start persistent workers behind a barrier; warm up for 30 seconds. Finish warmup calls before resetting phase IDs/recorders.
3. Measure for 120 seconds with exactly 1, 8 or 32 synchronous workers, each with at most one outstanding RPC. No per-call task queue or retained response list.
4. Stop new calls at the deadline. Drain outstanding calls for up to 30 seconds; report post-window completions separately.
5. Export counters, histograms/resources and validity state, close all channels/threads and terminate processes. Rotate target order between repetitions.

Concurrency is outstanding application RPCs, not sockets. Initial application server worker budget is eight on each target; configure/record bounded queues and rejection policy. Nimrod requester-pool size matches the client concurrency and is separate from generated server scheduling. Its pool initialization fix is already in the selected local snapshot; verify the installed artifact rather than assuming a version string proves contents.

For Dubbo, begin with one dedicated connection per service reference (`connections=1`), reused by its concurrent calls, eager setup (`lazy=false`) and actual connection verification; native protocol multiplexing allows concurrent RPCs on it. Record configured and observed counts for every target. Do not force extra Dubbo connections merely to match a client worker count or hide Nimrod pool waits outside the timing boundary.

Use the same Java 21 build, fixed 1 GiB min/max heap and G1 initial settings, equivalent CPU allocation and a quiet machine. Record JDK build, OS/hardware, heap/GC flags, effective dependencies, TCP options and topology. Start on loopback with separate JVMs; later two-host data is a separate result set. Plaintext and compression off are initial settings. Startup, handshake and readiness are not included in steady-state latency.

## Latency, completion and counting

Start the latency timer immediately before calling the configured proxy/stub; stop after return, response mapping and full validation. This includes client pool/transport/service waiting and serialization, but excludes prebuilt input generation and histogram recording. All elapsed time uses the same client JVM's `System.nanoTime()`. Server-local handler duration may be an additional metric; never subtract server monotonic timestamps from client timestamps.

Record **p50, p95, p99 and maximum**, sample count and recorder overflow for successful validated RPCs. Report failed-call durations separately with errors/timeouts; never insert a failed call as a zero-latency success. Use bounded per-worker HdrHistogram recorders with recorded precision/range and one-second interval snapshots. Preserve histogram files and each repetition's summary. Do not average percentile values; pooled histograms are permitted only for explicitly named compatible populations.

**Throughput = validated RPC completions timestamped within the measurement window / actual measurement-window seconds.** Send attempts or local write completions are not completed RPCs. Warmup and post-window drain work are separate. Account for attempted, validated, timed out, other RPC failures, invalid responses and unresolved operations so totals reconcile including drain; expose those counts both inside the window and at final reconciliation.

Use the same declared five-second call budget, retries/hedging disabled and no result caching. Verify each library's actual enforcement boundary. Nimrod server-execution timeout does not necessarily bound a client network wait, and cancelling a Java future does not prove remote work ended. Stop new calls upon timeout/failure that leaves remote completion uncertain rather than replacing stuck work indefinitely. A watchdog bounds process lifetime; watchdog-only enforcement must not be described as equivalent native deadlines. Unresolved work invalidates that performance run and is retained in failure diagnostics.

The main matrix is a **closed-loop capacity test**. It slows its offered rate during stalls; its quantiles are not an independent-arrival SLO measurement. To make fixed-rate latency claims, separately run an open-loop validation with intended-arrival timestamps, bounded admission and missed/rejected-arrival counts. Do not reset a late schedule or enqueue unlimited catch-up work. That optional validation is outside the 180-run matrix and adds no library target.

## CPU, memory and output

Measure both client and server each second, and separately summarize the 120-second phase:

- Process CPU-time delta / wall time as cores consumed; normalized CPU percentage only with its CPU allocation denominator.
- Heap used/committed, nonheap, RSS, direct buffers and thread count where available; baseline, mean/peak and final readings.
- GC collection count/time and optional pause/allocation diagnostics, with any profiler overhead identified.
- Completed RPC/s, latency distribution, errors/timeouts and queue/outstanding counts.

Use JDK MXBeans/GC logs and OS process sampling; record unavailable metrics as null with a reason. No per-message logging or unbounded latency lists. Export `manifest.json`, `summary.json`, `intervals.csv`, histogram files and bounded representative errors. Report all repetitions and throughput spread instead of selecting a best run.

## Dubbo protocol, serializer and connection manifest

The Dubbo manifest must contain explicit values, not defaults inferred from a requested configuration:

| Field group | Required entries |
| --- | --- |
| Runtime | `dubboVersion=2.7.23`, resolved Maven artifacts and hashes, core/config/remoting versions, JDK/Boot/Spring versions and JVM flags |
| Protocol | Requested and observed name `dubbo`, native binary TCP protocol, provider bind host/port, resolved `dubbo://` consumer URL, service interface/group/version and `injvm=false`/remote scope |
| Serializer | Requested `kryo`, observed SPI name/class and wire serializer ID, serializer extension GAV/hash, resolved Kryo and helper-library versions, DTO registration list/order and relevant options |
| Connections | Requested `connections=1` (reference-private, reused by concurrent calls), observed established TCP count, connection sharing, `lazy=false`, TCP no-delay/keepalive, connect timeout, heartbeat interval/timeout and reconnect interval/policy |
| Execution/policy | Client concurrency, provider worker count/threadpool/dispatcher, queue capacities and byte limits where configurable, payload limit, five-second RPC timeout, retries zero/fail-fast and caching disabled |
| Infrastructure | Registry `N/A`, no discovery/config/remote metadata center; QoS/listener settings and any other open ports |
| Validation | Protocol/serializer identification evidence, successful size/concurrency smoke tests, compatibility status, deviations and invalid-run reason |

Record effective heartbeat/reconnect and socket settings even when explicitly accepting a library default; do not invent an option for a setting the selected release cannot configure. Include actual observed connection count and peer addresses, not only `connections=1` in YAML. Apache describes [native connection configuration](https://dubbo.apache.org/en/docs/v2.7/user/examples/config-connections/). Identity inspection happens in readiness/validation, outside the hot path, using resolved provider/reference URLs plus codec/SPI diagnostics or a short native-frame inspection. Native Dubbo frames identify serializer selection; verify both directions and rule out an HTTP/2/Triple connection. Store the diagnostic method with the result.

A run configured for Kryo but observed using another serializer is **invalid**, not a silent fallback. On a future Kryo loading or round-trip failure, retain the exact dependency/runtime diagnostic and stop Dubbo benchmarking. A separately reviewed Hessian2 fallback retains the native Dubbo protocol and uses a new clearly labelled configuration/result identity; it is not an automatic additional serializer sweep. Never substitute Dubbo3/Triple to bypass a compatibility failure.

## Compatibility and correctness checks

Dubbo 2.7.23 is a legacy 2.x runtime. The implemented programmatic configuration runs in ordinary Boot beans with the Java 21 module-access settings described above, avoiding an old Boot starter's auto-configuration. Check Spring/Java APIs, reflection/proxy generation, lifecycle shutdown and Kryo helper compatibility on the common JDK. Do not downgrade the other implementations or silently change Dubbo runtime if this fails; report the constraint for review.

Validate all sizes, exact bytes/checksums/correlation, serializer registrations, concurrency 1 and 32, unavailable server, timeout behavior and finite drain before long runs. Invalid responses, recorder overflow, incomplete resources/configuration, unexpected protocol/serializer, unresolved work or unbounded backlog disqualify a run. Native client/server mismatch is a correctness failure, not a latency outlier.

## Separate capabilities

Existing Nimrod FNF and exact/wildcard pub/sub examples remain intact and outside this unary benchmark. Their local send-completion, transport receipt and remote application processing are different events. Empty unary replies, gRPC streaming, Dubbo callbacks/one-way options and void Java methods do not establish equivalent FNF or subject semantics. No new FNF/pubsub or additional library/protocol implementation is scheduled here.
