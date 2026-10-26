# Three-library unary RPC implementation plan

> Historical record: version references, commands and validation statements below describe the dated investigation/design, not current build requirements. Current samples use **Nimrod IPC 4.3.1 from Maven Central**; see [release validation](release-validation.md) and the [completed 180-run benchmark report](../BENCHMARKS.md).

> Implementation update (2026-10-16): the approved unary comparison is implemented. Use [benchmark-running.md](benchmark-running.md) for commands/results and [nimrod-timeouts.md](nimrod-timeouts.md) for cancellation limits. Runtime checks selected the Kryo SPI bundled in Dubbo 2.7.23 with Kryo 4.0.2 / kryo-serializers 0.42, replacing the proposed external extension 1.0.1 because of duplicate classes and incompatible Kryo dependencies. Java 21 also requires the documented module-access flags. The full 180-run matrix is manual-only and has not been executed.

Revised 2026-10-16. **Approved and implemented; full matrix remains manual-only.** Compare only **Nimrod IPC, gRPC Java and Apache Dubbo2**. The wire stacks are RSocket/TCP with Kryo, gRPC/HTTP/2 with Protocol Buffers, and native Dubbo binary RPC/TCP with verified Kryo preferred, respectively. Dubbo Triple, gRPC-compatible Dubbo protocols and HTTP/2 for Dubbo are prohibited; there is no additional Triple comparison. This plan supersedes the earlier broader implementation proposals and benchmark scope. Spring RSocket, Aeron, Netty, Hazelcast and Ignite comparison implementations are excluded. Underlying transport dependencies used by the three selected libraries are not separate comparison targets.

Preserve the working Nimrod and gRPC pricing applications, Spring Boot main classes, existing endpoints and pub/sub examples. Extend those applications with an opt-in unary benchmark profile and reuse their transport wiring. Add Dubbo after the existing two-library benchmark is repeatable. The plan was approved before implementation; implementation/runtime details are in [benchmark-running.md](benchmark-running.md).

## 1. Existing support and reusable code

This revision rechecked the Java pricing interfaces/DTOs, controllers, service implementations, protobuf contract, application configuration and parent/module Maven configuration. Findings are static source observations, not new benchmark results. The existing applications are the working baseline supplied by the developer; no replacement of their main classes or gRPC integration is proposed.

| Existing code | Already supports | Reuse and required extension |
| --- | --- | --- |
| `nrpc-common/.../rmiservices/pricing/PricingServiceRmiInterface.java` | Generated blocking `getPrice` proxy and server controller; separate annotated FNF method; parallel server scheduler configured at eight | Retain pricing/FNF contract. Add a benchmark-only unary interface in the same module using existing annotation processing |
| `nrpc-common/.../dto/PriceRequest.java`, `PriceResponse.java` | Pricing fields and client/server timing fields | Preserve DTO signatures and pricing endpoint behavior. Their lack of body bytes prevents the requested payload sweep; add neutral benchmark DTOs rather than padding currency/tenor strings |
| `nrpc-client-basic/.../ClientBasicController.java` | `/price`: 5,000 warmup calls then one timed call; `/parallel-price`: caller-selected thread count, one blocking RPC per task | Reuse injected proxy/configuration pattern. Add profile-specific CLI load runner; do not use HTTP requests or copy the endpoint's thread-per-run measurement logic into the benchmark |
| `nrpc-server-basic/.../PricingService.java` | Immediate pricing response, preserving request fields | Reuse Boot server and service implementation pattern; delegate new benchmark method to common request processing |
| `ClientBasicConfiguration.java`, `ServerBasicConfiguration.java`, existing applications/YAML | Generated component discovery, server bind, requester pool and endpoint configuration | Preserve entry points; benchmark profile provides only required endpoint, explicit pool sizing and isolated benchmark component scans |
| `grpc-common/src/main/proto/pricing.proto`, `grpc-common/pom.xml` | Unary `GetPrice`, generated messages/stubs and working Maven protobuf generation | Keep existing messages/RPC intact; add a separate benchmark proto/service using the same generation configuration |
| `grpc-client-basic/.../grpcclient/ClientGrpcController.java` | Blocking stub, 5,000-call warmup, one timed call and one-shot parallel calls | Reuse configured channel/stub injection pattern; add benchmark stub and CLI runner inside the same application |
| `grpc-server-basic/.../grpcserver/PricingGrpcService.java` | Unary responder with generated service base and `StreamObserver` | Retain pricing service; add benchmark unary responder in the same Boot application |
| `GrpcClientApplication.java`, `GrpcServerApplication.java`, existing gRPC YAML | Working Boot applications and direct plaintext address `localhost:9090` | Preserve main classes and starter integration; benchmark profile disables HTTP controller beans/web startup while retaining gRPC beans |
| Nimrod `/burst`, `FnfBenchmarkState`, pub/sub load modules | FNF local-call latency percentiles, processed counters, configurable event bodies and publisher/subscriber logs | Leave as separate capability examples. Their results cannot supply unary RPC metrics |

Paths abbreviated with `...` retain their existing `src/main/java/com/nimrodtechs/samples` prefix. No new template `Main` class is needed.

Both unary examples already support basic same-client-clock RTT: client sent time and client receive time can be subtracted. Remote `timeResponded` cannot be subtracted from a client monotonic timestamp to infer one-way latency. The pricing services currently return different constants (`1.1234` versus `1.2345`); the benchmark will use identical new processing without changing those existing demonstrations.

### Measurements missing from both unary examples

- Body sizes 128/1,024/10,240/102,400 bytes and an equal-sized validated response.
- A sustained 120-second measurement phase after a timed 30-second warmup, repeated five times.
- Continuous bounded concurrency at 1, 8 and 32. A parallel endpoint making one call per thread is not a sustained-concurrency test.
- Completed-RPC throughput, p50/p95/p99/maximum latency and bounded histograms. Nimrod FNF percentiles are a different completion boundary.
- Consistent timeout/error/invalid-response counts, finite drain/termination and failure-path handling.
- Client and server CPU/memory capture, interval results, resolved configuration and repeatable result files.

Use Nimrod **4.3.1-SNAPSHOT**, as already selected in the root POM. The requester-pool initialization fix (hold borrows, return in `finally`, `maxIdle=maxTotal`) has been made in that library version. Verify the installed artifact includes it; it is not outstanding sample implementation work. Record the local snapshot artifact hash because the snapshot version alone does not identify its contents.

## 2. Small module change, maximum application reuse

Keep the existing ten modules. Proposed additions are only:

| Module or path | Responsibility |
| --- | --- |
| `benchmark-common` | Ordinary Java JAR: benchmark request/response POJOs, deterministic payloads, shared processing, small configuration/result model, bounded histogram/load helpers |
| `dubbo-common` | Ordinary JAR: shared Java benchmark interface, depending on `benchmark-common`; no Nimrod annotations or protobuf generation |
| `dubbo-server-basic` | Spring Boot provider exposing that interface and delegating to shared processing |
| `dubbo-client-basic` | Spring Boot consumer using the shared load/metrics helpers and a direct-address reference |
| `benchmark-configs/`, `scripts/benchmarks/` | Versioned scenario YAML plus simple process launch/stop/collect scripts; neither is a Maven module |

Add dependencies on `benchmark-common` where needed in the existing Nimrod/gRPC contract and application modules. The new benchmark code belongs in narrow `com.nimrodtechs.samples.benchmark` packages, with transport-specific subpackages and explicit imports/scans. Do not make Dubbo import `nrpc-common` simply to reuse a DTO, and do not move existing pricing DTOs in this phase.

Reuse the existing client/server Boot applications with an opt-in `benchmark` profile and `ApplicationRunner`/`CommandLineRunner`. Their default pricing mode remains available. The benchmark profile excludes REST controllers, sets non-web mode where compatible, and explicitly enables benchmark services/runners. Confirm the existing generated Nimrod benchmark controller is discoverable on the server while its generated client is isolated to client configuration. Verify no additional listener is accidentally started. Only the finite benchmark client exits automatically; providers run until the process script stops them.

Use a few ordinary helpers and explicit per-library call functions, not a transport SPI, abstract server hierarchy or plugin framework. There are no new parallel copies of Nimrod/gRPC client/server application modules. Retain their existing main classes and gRPC starter unless a reproduced issue requires a separately reviewed fix. Audit dependency versions during validation rather than treating a possible compatibility concern as a reason to rewrite working examples.

## 3. Equivalent unary workload

Add benchmark request/response types with `runId`, `phaseId`, `sequence`, pricing inputs, declared body length/checksum and `byte[] payload`. Neutral POJOs with simple constructors/accessors make the selected Kryo/Dubbo serializers easy to validate. Protobuf represents the same logical fields with new message types.

A benchmark request has exactly 128, 1,024, 10,240 or 102,400 **body bytes**, excluding envelope/serializer/protocol overhead. The server validates the body, produces the same constant price and pricing fields for every library, and echoes the entire body and correlation fields. Client validates the complete reply. Record body size in both directions and encoded size where observable; do not claim that equal body size implies equal wire size.

Generate deterministic pseudorandom payloads before measurement from a fixed seed; retain an immutable bounded payload pool. Disable compression. Use the same body checksum algorithm and same response processing. Each server calls the shared processing function, with necessary protobuf/POJO mapping counted as part of that application's cost. Do not compare a metadata-only reply against a full-body echo.

The benchmark is a unary application-stack comparison: Nimrod/RSocket/TCP/Kryo, gRPC/HTTP/2/protobuf and Dubbo2/native Dubbo binary RPC/TCP with explicitly verified Kryo preferred. Different serialization work remains part of the documented result; this does not isolate transport-only overhead. Pricing input validation, checksum and full-body echo give the service a small, repeatable task without introducing databases or simulated sleeps.

## 4. Repeatable run protocol and metrics

The required matrix is **3 libraries × 4 body sizes × 3 concurrency levels × 5 repetitions = 180 measured runs**. Every run has **30 seconds warmup followed by 120 seconds measurement** at the same size/concurrency. Timed phases alone require 7.5 hours; startup, readiness checks and bounded drain add time. The initial Nimrod/gRPC matrix is 120 runs before adding Dubbo's 60.

Start with one client JVM and one server JVM per run. Concurrency means **1, 8 or 32 outstanding synchronous RPCs**, not client process count or TCP connection count. Use that many long-lived workers with one blocking call each and a common start barrier. No per-request executor submission, pending work list or unbounded latency queue. Each worker issues a new request only after the previous one terminates and its response is checked.

Use a fixed declared server worker budget (initially eight), the same application work and bounded request admission. Record actual worker and physical-connection counts. For Nimrod, configure the benchmark interface's parallel execution budget separately from its client requester-pool ceiling; pool size matches client concurrency so an unintended eight-requester limit does not mask the 32-client case. Configure gRPC/Dubbo workers and queues explicitly. With no retries and at most 32 unfinished calls, unfinished benchmark work is bounded even where library queues are not configurable; confirm timeouts do not allow abandoned remote work to accumulate.

Keep the following boundaries and rules identical:

| Item | Planned measurement |
| --- | --- |
| Latency | Client `nanoTime` immediately before invoking its RPC proxy/stub through complete response mapping and validation; same client clock throughout; includes pool/transport/service wait and serialization, excludes prebuilt input generation and histogram recording |
| Percentiles | p50, p95, p99, maximum and sample count for successful validated RPCs; failures/timeouts reported separately, never silently omitted from totals |
| Throughput | Validated successful completions timestamped inside the exact 120-second window divided by its measured elapsed seconds; attempted/send counts are not successful RPC/s |
| Phase boundary | Warmup workers finish before recorders reset; no warmup call crosses into measurement. Requests issued before measurement end may drain, but post-window completions/latencies are reported separately |
| Timeout/error | Same declared five-second call budget, retries/hedging disabled, counts for attempted, successful, timeout, other RPC error, invalid response and unresolved operations; error samples bounded |
| CPU | Client and server process CPU-time delta divided by phase wall time, reported as cores consumed and interval samples; no combined unexplained CPU percentage |
| Memory | Client and server heap used/committed, nonheap, RSS and direct-buffer use where available, sampled each second; baseline, mean/peak and end-of-phase values |
| Resource controls | Same Java 21 build, initial fixed 1 GiB min/max heap, G1, machine placement and CPU allocation; same logging/TLS/compression policy; retain GC count/time as diagnostic metadata |
| Recording | Per-worker bounded HdrHistogram recorders, merged for compatible populations; histogram overflow invalidates a run; one-second summaries and final machine-readable output |

Use transport-supported deadlines where available. Validate what Nimrod's timeout configuration actually bounds: server execution timeout or cancelling a waiting Java future alone does not prove client/network cancellation. On a timeout, stop issuing new calls in that run rather than replace a still-running call with another worker; drain for at most 30 seconds and terminate/mark invalid if unfinished work remains. A process watchdog prevents an indefinitely blocked API from hanging the suite; a watchdog-only result must not be presented as a comparable five-second native timeout. Resolve any missing enforceable timeout before publishing timeout-behavior comparisons.

Fresh client/server JVMs per repetition avoid cumulative state. Require a successful validated readiness RPC before warmup; do not equate requester construction with connection readiness. Rotate library order between repetitions, keep competing load off the test hosts and record topology. Start with loopback in separate JVMs; any later two-host results are a distinct result set. No discovery cluster, containers or Kubernetes installation is required for the initial comparison.

Export resolved configuration/version hashes, per-run `summary.json`, `intervals.csv` and histogram files. Report each repetition plus median/range of throughput and per-run latency quantiles; never average percentile values into a synthetic global percentile. Archive errors and resource time series with the run. Five repetitions describe variability, not a guarantee of statistical significance.

### Coordinated omission and interpretation

The required concurrency matrix is explicitly a **closed-loop capacity benchmark**: its offered rate falls during stalls. Report service RTT under that workload, not latency under an independent fixed arrival rate. Do not claim it eliminates coordinated omission or apply an unlabelled histogram correction.

Before making fixed-rate latency/SLO claims, use a separate small open-loop validation mode with the same call function, bounded outstanding work and an independent arrival schedule. Measure from intended arrival through validated completion, record admission delay/missed arrivals/rejections, and never reset a late schedule or build an unbounded catch-up queue. This is outside the 180-run matrix and adds no transport implementation; until run, publish only the closed-loop interpretation.

## 5. Implemented Apache Dubbo2 native binary RPC

The selected runtime is **Dubbo 2.7.23** using native **`dubbo` binary RPC over TCP**, a plain Java interface and a direct `dubbo://<local-interface>:<port>/<interface>` reference. Registry is `N/A`; there is no discovery/config/remote metadata server, Triple, HTTP/2 Dubbo protocol or gRPC wrapper. Provider and consumer remain separate Boot processes.

Runtime investigation replaced the initially proposed external Kryo extension 1.0.1 with the **Kryo serializer bundled inside Dubbo 2.7.23**, plus the matching **Kryo 4.0.2** and **kryo-serializers 0.42** dependencies. The external extension duplicated core serializer classes and introduced Kryo 5 libraries missing the core serializer's Kryo 4 pool API. No Hessian fallback was used. Native SPI diagnostics verify actual encode/decode use of `org.apache.dubbo.common.serialize.kryo.KryoSerialization`, serializer wire ID **8** and native effective URLs, with artifact hashes in manifests.

Java 21 needs module-access flags for `java.lang`, `java.util` and `java.text`; Javassist is pinned to 3.30.2-GA. Programmatic `ServiceConfig`/`ReferenceConfig` beans avoid a legacy Boot starter. Dubbo rewrites loopback targets to a local interface, so the launcher uses that same detected interface for all targets and binds providers to it explicitly. These compatibility adjustments and the serializer observation overhead are disclosed in [benchmark-running.md](benchmark-running.md).

The native reference has one private connection reused by concurrent RPCs, eager setup, remote scope, retries zero/fail-fast, five-second RPC timeout, three-second connect timeout, heartbeat/reconnect settings and a bounded eight-worker provider. Complete requested/effective configuration and observed sockets are recorded as specified in [benchmark-design.md](benchmark-design.md#dubbo-protocol-serializer-and-connection-manifest). A future protocol/serializer mismatch invalidates the run; it cannot silently fall back.

The Java 21 compatibility gate and short network/timeout checks pass with the documented setup. This does not establish compatibility with every JDK or DTO or provide production support for the EOL 2.7 line. Nimrod remains on its independent Kryo 5 path; the comparison does not claim identical encoding or native timeout semantics.

## 6. Developer-experience comparison

Document the working code and resolved configuration after implementation, with separate counts for handwritten contract, service, client/configuration and generated code. Do not invent line-count winners in advance; exclude common benchmark helpers from library-specific code totals.

| Concern | Nimrod IPC | gRPC Java | Apache Dubbo2 2.7.23 |
| --- | --- | --- | --- |
| Contract | Shared Java interface with Nimrod annotations and DTOs | `.proto` service/messages; generated Java API | Shared ordinary Java interface and POJOs |
| Code generation | Maven annotation processor generates Spring client/controller components | protoc and grpc-java Maven generation creates messages, stub and service base | Framework builds runtime reference/proxy; no application IDL generation step required |
| Serialization | Native Kryo object serialization, registration/customization and shared class compatibility | Protobuf field schema and generated types; mapping to common processing DTOs | Bundled Kryo SPI with Kryo 4.0.2 / kryo-serializers 0.42; effective serializer/URL observed and recorded |
| Server code | Implements Java contract; generated controller and package discovery | Implements generated service base, adapts messages and completes observer | Implements interface and exports provider with Dubbo configuration/annotation |
| Client code | Injects generated interface proxy | Injects/builds generated blocking stub on existing configured channel | Obtains configured Java interface reference and invokes it |
| Configuration | Logical server name, host/port, requester pool and generated execution settings | Channel target/plaintext or TLS, deadlines and server executor | Provider protocol/port, direct reference URL, serializer, timeout/retry/worker policy; registry disabled |
| Review evidence | Processor output/build ergonomics, DTO sharing, error propagation | Proto editing/regeneration, mapping boilerplate, status/deadline handling | Interface export/reference setup, native-protocol identity, serializer/Java 21 compatibility and service policy defaults |

Compare a clean contract change/build, application startup, one valid invocation, unavailable provider and serialization mismatch. Describe required source/config files and error messages. Report convenience, type coupling and operational defaults separately from measured runtime performance.

## 7. FNF and pub/sub stay separate

Preserve Nimrod's FNF and exact/wildcard pub/sub applications and document those capabilities separately. Their counters and latency boundaries do not enter the unary table. A local send return does not acknowledge remote processing.

gRPC unary is request/response even with an empty response; streaming is not inherently topic/wildcard pub/sub. Dubbo2's selected native binary unary Java RPC configuration does not establish equivalent FNF or subject-subscription semantics. Do not infer parity from `void` Java return types, asynchronous calls or callbacks. No new cross-library FNF/pubsub implementation is scheduled by this plan; any future comparison requires a separately reviewed semantic definition.

## 8. Priorities, dependencies and review gates

Estimates are engineer-days for one familiar Java/Spring developer, including focused checks and documentation, excluding the full benchmark wall time.

| Priority | Work | Depends on | Complexity / estimate | Exit condition |
| --- | --- | --- | --- | --- |
| P0 | Confirm existing pricing startup, snapshot identity, baseline configuration | Plan review | Small, 0.5–1 day | Working examples retained; exact artifacts/config recorded |
| P0 | Shared payload/processor, bounded runner and metrics | Baseline | Medium, 2–3 days | Sizes, correlation/checksum, phase timing, bounded recording and failure handling verified |
| P0 | Extend existing Nimrod and gRPC applications/contracts with benchmark profile | Shared helpers | Medium, 2–3 days | Same unary behavior at all sizes/concurrency; existing pricing endpoints still work outside profile |
| P1 | Dubbo2 2.7.23/Kryo compatibility gate and direct-address provider/consumer | Working two-library harness | Medium–large, 3–5 days | Native Dubbo TCP and actual serializer verified on common JDK; full connection manifest; no registry or Triple |
| P1 | Repetition script, CPU/memory output, DX notes and matrix validation | All three implementations | Medium, 1–2 days | Complete valid 180-run result set and reproducible commands; invalid runs distinguished |

Estimated implementation effort: **8.5–14 engineer-days**, assuming the Dubbo2/Kryo compatibility gate passes without substantial adaptation, plus at least 7.5 hours of timed matrix execution and startup/drain overhead. No event-suite expansion or additional library comparison is hidden in this estimate.

Before long runs, validate all sizes at concurrency 1 and 32; check exact response bytes, timed phases, histogram merging, error arithmetic and bounded shutdown. Include unavailable-server/timeout tests and confirm pool setup at 32 requesters in the installed Nimrod snapshot. Recheck existing pricing demos after adding benchmark profiles. Compare server worker settings and physical connections rather than assuming equal logical APIs produce equal defaults.

Review gates are: approve this three-library scope and application reuse; then review the shared workload/timing output on Nimrod/gRPC; then verify Dubbo2/Kryo compatibility, add native Dubbo TCP and run the full matrix. See the [updated comparison matrix](comparison-matrix.md) for the exact three protocol identities. Implementation and validation details now live in [benchmark-running.md](benchmark-running.md); the full performance matrix has not been run.
