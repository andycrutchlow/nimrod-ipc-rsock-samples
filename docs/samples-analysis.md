# Samples project analysis

> Scope update (2026-10-16): the [revised implementation plan](implementation-plan.md) supersedes the implementation recommendations and benchmark scope below. Current scope is only Nimrod IPC, gRPC Java and Apache Dubbo, reusing the existing pricing applications. Other-library proposals below are historical and are not scheduled. Nimrod now uses 4.3.1-SNAPSHOT, including the requester-pool initialization fix.

Analysis date: 2026-10-13. Scope: all ten Maven modules, every sample Java source, protobuf contract, application YAML, root/module POMs and README. This is a static review; no application, build or benchmark was run, and no implementation was changed. Existing generated sources were inspected as supporting evidence, not regenerated.

Related documents: [comparison matrix](comparison-matrix.md), [benchmark design](benchmark-design.md), [implementation plan](implementation-plan.md).

## Evidence and version boundaries

The samples working tree is based on commit `1a7eb8f` and declares Nimrod `4.3.0`. Related source is available in `../nimrod-ipc-rsock-parent` (working tree declares `4.3.1-SNAPSHOT`, HEAD `350dec8`) and an older `../nimrod-ipc-rsock-21` project (`2.4-SNAPSHOT`). The parent project contains core, annotations and annotation-processor modules; it is the relevant maintained layout. The older project also has GUI/manual test applications, not a comparable benchmark suite.

The local Maven cache contains the actual `nimrod-ipc-rsock-4.3.0-sources.jar`. Its `RemoteServerService`, `RemoteServerInfo`, `PublisherSocketImpl`, `MessageProcessorEntry`, `SequentialExecutor` and `KryoCommon` sources match the corresponding sibling files. `SubscriberService` differs: the snapshot adds lifecycle locking, stream-attempt ownership and safer recovery installation/retirement. Snapshot recovery tests must not be presented as evidence that release 4.3.0 contains those fixes. Processor source was reviewed in the snapshot; existing sample-generated controller/client files corroborate parallel request scheduling and virtual-thread fire-and-forget dispatch, but their build provenance was not independently verified.

Two untracked IDE-template `Main.java` files already exist in `grpc-client-basic` and `grpc-common`. They print a greeting and loop; they are not IPC examples. They were left untouched. Ignored build/IDE output is not part of the maintained source inventory.

## Module inventory

| Module | Existing functionality | Assessment |
| --- | --- | --- |
| `nrpc-common` | Five DTOs, pricing RMI interface, annotation processor wiring | Reusable domain examples; couples consumers to Nimrod dependencies/code generation |
| `nrpc-server-basic` | Spring Boot pricing service, generated-controller component scan, FNF processing counters and HTTP metrics | Minimal real server; no benchmark run isolation |
| `nrpc-client-basic` | Generated proxy discovery; HTTP `/price`, `/parallel-price`, `/burst` | Connectivity/concurrency demos and a burst microbenchmark, not sustained comparative tests |
| `grpc-common` | `pricing.proto`, protobuf/gRPC code generation | Unary pricing contract only; no payload-size field or streaming RPC |
| `grpc-server-basic` | Boot application and unary `PricingGrpcService` | Existing direct RPC alternative |
| `grpc-client-basic` | Blocking stub, HTTP `/grpc/price` and `/grpc/parallel-price`, conversion to `PriceResponse` | Useful manual counterpart; imports `nrpc-common` just to reuse DTOs |
| `pubsub-publisher-basic` | Background `MarketData` publisher for `EURUSD.SPOT` and `EURUSD.1W`; subscription callbacks | Practical low-rate demo |
| `pubsub-subscriber-basic` | Console exact/wildcard subscriptions with `MarketData` callback | Shows subscribe and wildcard use, but not unsubscribe or assertions |
| `pubsub-loadtest-publisher` | CLI rate, subjects, payload, duration, fixed/round-robin subject selection | Existing scheduling and payload-generation starting point |
| `pubsub-loadtest-subscriber` | CLI subscriptions, per-second count/average/max/gap logs | Receiver throughput scaffold; incomplete correctness and latency accounting |

No maintained `src/test` tests are present in the samples. The test applications are executable demos; there is no JMH suite, repeatable integration suite, resource profiler or archived comparative result format.

## RMI and connection management

`PricingServiceRmiInterface` uses `serviceName="server1"`, `concurrency=8`, `SchedulerType.PARALLEL`. It declares blocking `getPrice(PriceRequest)` and `@NimrodFireAndForget publishPriceUpdate(PriceUpdateEvent)`. `nrpc-common/pom.xml` explicitly supplies the annotation processor. Generated Spring components are discovered through package scans, rather than a manually constructed client bean.

The user-highlighted `ClientBasicConfiguration.java` scans the Nimrod client, shared pricing and samples packages. Its imports include the generated client class and `@Bean`, but the configuration declares no bean. These imports are incidental, not an alternative proxy factory. `ServerBasicConfiguration` likewise scans server/shared components, supplies two unrelated greeting beans and prints `HERE`; those beans do not participate in IPC.

The generated client delegates to `RemoteServerService`: a Commons Pool requester is borrowed, the method-name route is invoked with an `Object[]` of parameters, and `retrieveMono(...).block()` or `send().block()` is used. The pool is returned in `finally`; retry-exhausted errors can trigger pool clearing. `maxConcurrentCalls` is a requester-pool ceiling and a blocking admission point, not server worker concurrency. The initialization loop repeatedly borrows/returns one object, so its log claiming the full connection count is initialized does not prove that many distinct physical connections are open. Requester construction may also be lazy; measure connections and first successful calls.

Client YAML configures `client-side.server1` at localhost:40280 with eight requesters, and unused `server2` at localhost:40284 with four. Server HTTP port is ephemeral (`0`), while Nimrod binds 40280. Defaults in the reviewed release include a 2-second keepalive interval, 7,200-second wait and zero reconnect attempts; no explicit client operation deadline is applied in the sample. Blocking pool waits and RPCs need explicit bounds for an unattended suite.

Request/response scheduling uses an eight-worker parallel scheduler. That worker count does not by itself bound all queued requests. The generated FNF controller starts a virtual thread per invocation and returns an empty Mono before application work finishes. Interface request concurrency therefore must not be assumed to cap FNF application concurrency. Server delay injection applies only to `publishPriceUpdate`, not pricing RPC.

## Pub/sub and Kryo

Basic publication alternates two market-data subjects about once per second. The console receiver offers `EURUSD.*`, `EURUSD.SPOT` and `EURUSD.1W`, with conflation disabled. Subscription-added/removed callbacks exist on the publisher, but the sample UI has no explicit unsubscribe path. The callback logs `nanoTime() - createdAt`, which is not a portable cross-JVM latency.

The load publisher has defaults of 1,000 messages/s, 128 payload bytes and 30 seconds; subjects are required. It increments a global sequence and allocates/fills a fresh payload for every message. It uses park/spin pacing and resets its schedule after falling more than one second behind without counting missed arrivals. The subscriber subscribes without conflation and logs metrics once a second. The default publisher name in the load CLI (`samplePublisher`) differs from the subscriber YAML alias (`publisher1`); the alias argument must match the configured connection alias, and embedded publisher identity must be recorded separately.

`PublisherSocketImpl` exposes request streams, exact subscriptions and wildcard-trie routing, multicast buffered sinks and connection cleanup. `publish()` returns void: it can return with no subscribers and is not a receiver acknowledgement. Publisher startup is asynchronous; the load CLI explicitly calls `start(port)` even though YAML can already trigger startup in `@PostConstruct`, creating a duplicate-bind risk. A returned `start()` does not establish transport readiness.

Non-conflating subscriber entries have a bounded queue of 5,096 messages and log/drop on overflow; conflating entries use a two-slot queue. Dispatch executors use fixed thread pools with unbounded executor task queues. Reactor prefetch, publisher sink buffering and transport buffers are additional queues. The publisher's default buffered sink has no sample-controlled capacity/overflow contract; do not describe every buffer as unbounded or every queue as safely controlled. At 100 KB, a single 5,096-entry payload queue can approach 500 MiB before overhead. Conflation intentionally replaces data and belongs in a separate latest-value scenario.

The reviewed release reconnects subscriber requesters indefinitely at fixed two-second intervals; resubscription is also scheduled after two seconds. Its long keepalive timeout makes a silent network interruption different from a clean server restart. Snapshot tests exercise recovery races, overlapping wildcard consolidation, unsubscribe promotion, stale errors and listener replacement using controlled timers/proxies. They are useful correctness references, not real-network recovery measurements.

Kryo is exercised implicitly in every Nimrod operation through `application/x-kryo` strategies. `KryoCommon` configures registration-optional serialization with reference tracking, built-in registrations, optional additional classes/customizers and a default 16-instance pool. `tryBorrow()` allocates a fallback instance when exhausted; this is not a strict cap on allocation. Reusable buffers start at 64 KiB, making the 100 KB case useful for expansion/allocation analysis. Samples do not demonstrate explicit DTO registration, custom serializers, round-trip assertions, compatibility across versions or separate serialization measurements.

## Existing measurement weaknesses

| Evidence | Consequence | Proposed treatment |
| --- | --- | --- |
| Both pricing controllers warm up 5,000 calls and then report one call | No distribution, steady-state throughput or repeatability | Keep as demos; add dedicated timed CLI runs |
| Parallel endpoints issue one call per task without a coordinated start barrier | Thread creation/start skew enters results; no sustained concurrency | Persistent bounded workers, start barrier and duration-based phases |
| Pricing implementations return different constants and use different mapping/allocation paths | Current examples are only approximate comparisons | Define identical shared processing and response payloads |
| `/burst` times FNF call return | Measures local send completion, not remote processing | Publish distinct producer and receiver rates; drain and reconcile |
| `/burst` uses requested messages as throughput numerator despite errors | Overstates success throughput | Report scheduled, attempted, accepted, processed and failed counts separately |
| Boxed synchronized latency list and server unbounded latency queue | Recorder contention and memory growth distort tests | Bounded histograms and interval aggregation |
| Server error counter is not incremented by the service; warmup FNF enters counters | Incomplete accounting and contaminated totals | Run IDs, separate warmup epoch, receiver error accounting |
| One global subscriber last-sequence value | Subject filtering, concurrent callbacks, reordering and duplicates look like loss | Sequence per publisher/subject; reconcile missing ranges after drain |
| Gap counter counts discontinuities, not missing messages | A gap of 1,000 counts as one; missing tail is invisible | Final sender manifest and unique receiver counts |
| `sumThenReset` counters and assumed one-second intervals | Concurrent snapshots can mismatch latency/count; timer delays distort rate | Consistent interval recorder and actual elapsed interval |
| Remote monotonic timestamps are subtracted | JVM clocks have no shared origin guarantee | Same-clock RTT and server-local processing times |
| REST entrypoints, per-call printing and indefinite threads/executors | Operational noise and unreliable process termination | New non-web CLI apps with deterministic shutdown |
| `invokeAll` futures in burst/load task submission are not inspected consistently | Unexpected worker exceptions can disappear | Collect futures/errors and enforce bounded completion |

Input validation is partial: parallel thread counts/payload allocations lack consistent bounds, subscriber CLI indexing can fail on missing values, extreme rates can produce a zero nanosecond interval, and nonpositive duration enables indefinite publication. There is no readiness barrier, warmup/measurement separation, drain deadline, message checksum, reset endpoint, multi-process client coordination, restart test, CPU/RSS/GC capture or machine-readable output.

## Maven and documentation assessment

The aggregator has ten modules under Spring Boot 3.5.4, Java release 21 and version `1.0-SNAPSHOT`. It imports Nimrod 4.3.0 and gRPC 1.58.0 BOMs. `grpc-common` pins protobuf runtime/protoc 3.25.3 and grpc-java generator 1.58.0, with os-maven 1.7.0 and protobuf-maven 0.6.1. Common modules duplicate compiler properties; internal dependencies mix `${project.version}` and literal snapshot versions.

The gRPC applications use `net.devh` starter 2.15.0.RELEASE. Its published compatibility table associates that release with Boot 2.7.16, so its use with Boot 3.5.4 needs a startup check rather than an assumption of compatibility. See [grpc-spring versions](https://grpc-ecosystem.github.io/grpc-spring/en/versions.html). No failure was reproduced in this review. New benchmarks can manage native gRPC builders as Boot beans and avoid depending on this starter.

The root imports multiple dependency-management sources whose effective Spring/Reactor/Netty alignment should be inspected during implementation. The sibling Nimrod parent additionally pins Reactor 2023.0.8 and RSocket 1.1.4; effective transitive versions must be recorded, not inferred solely from POM declarations. No Maven wrapper files were found. Boot executable repackaging is not explicitly configured in the sample module POMs; new CLI module packaging and main-class selection should be explicit.

README is primarily a three-module RMI guide with gRPC/pubsub TODO notes. It shows old Nimrod versions, the obsolete `nimrod.rsock.server.servers` client configuration, different concurrency/HTTP ports and claims port zero disables Spring's RSocket server. Port zero generally requests an ephemeral bind when that auto-configuration applies; remove/disable the relevant configuration explicitly when necessary. Claims about default global serial execution and no runtime reflection also need qualification: generated proxy dispatch avoids reflection for invocation, but does not establish that the entire stack/serialization is reflection-free.

## Reuse and missing examples

Preserve existing interfaces and DTOs. Reuse `PriceRequest`/`PriceResponse` for a practical pricing correctness example, `MarketData` for exact/wildcard subscription tutorials, and the sequence/payload ideas in `PriceUpdateEvent` and `LoadTestMessage` for the new contract. Do not make alternative benchmarks import `nrpc-common` and its processor merely to access DTOs.

Reuse the shape of `PublisherLoadEngine` pacing and `TestMessageReceiver` callbacks, while replacing schedule-reset accounting and metrics internals. FNF processed counters can inform receiver reports after adding run isolation and correctness tracking. Existing protobuf generation and unary server/client code provide a direct gRPC starting point.

Priority additions are deterministic payload echo RPC, bounded FNF with receiver reconciliation, exact-subject fanout and multi-client sustained CLI runs. Add practical tutorials for timeout/remote exception behavior, unavailable server/startup order, graceful shutdown, reconnect/resubscribe, unsubscribe and wildcard overlap, slow consumers/overflow and intentional conflation. Add serializer round trips, explicit registration/customization and documented version mismatch behavior. Multiple services/endpoints and Kubernetes DNS/restart examples are valuable after the local correctness suite works; no broker, registry or cluster automation is required initially.
