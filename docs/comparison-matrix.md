# Three-library RPC comparison matrix

> Historical record: version references, commands and validation statements below describe the dated investigation/design, not current build requirements. Current samples use **Nimrod IPC 4.3.1 from Maven Central**; see [release validation](release-validation.md) and the [completed 180-run benchmark report](../BENCHMARKS.md).

> Implementation update (2026-10-16): the approved unary comparison is implemented. Use [benchmark-running.md](benchmark-running.md) for commands/results and [nimrod-timeouts.md](nimrod-timeouts.md) for cancellation limits. Runtime checks selected the Kryo SPI bundled in Dubbo 2.7.23 with Kryo 4.0.2 / kryo-serializers 0.42, replacing the proposed external extension 1.0.1 because of duplicate classes and incompatible Kryo dependencies. Java 21 also requires the documented module-access flags. The full 180-run matrix is manual-only and has not been executed.

Revised 2026-10-16. Implemented configuration; smoke checks establish correctness, not performance conclusions. The only comparison targets are **Nimrod IPC, gRPC Java and Apache Dubbo2**. This document replaces the earlier broad alternatives assessment. Reuse the existing Nimrod/gRPC pricing applications as described in the [implementation plan](implementation-plan.md); measurement rules are in the [benchmark design](benchmark-design.md).

## Protocol and serialization identity

| Implementation | Version baseline | Required wire protocol | Serializer | Contract and infrastructure |
| --- | --- | --- | --- | --- |
| Nimrod IPC | Local `4.3.1-SNAPSHOT`; record exact artifact hash | RSocket over TCP | Native Kryo; record resolved Kryo artifact/version and configuration | Annotated shared Java interface, generated client/controller; direct configured host/port |
| gRPC Java | Existing gRPC `1.58.0` baseline; record effective runtime/generator versions | gRPC over HTTP/2 over TCP | Protocol Buffers; current proto/runtime baseline `3.25.3` | `.proto` service/messages, generated Java stubs; existing direct channel target |
| Apache Dubbo2 | **`2.7.23`**, runtime-checked on the recorded Java 21/Boot 3.5.4 environment | Native **Dubbo binary RPC protocol over TCP**, protocol name `dubbo`, URL `dubbo://...` | **Kryo 4.0.2**, bundled `dubbo-serialization-kryo` SPI from Dubbo 2.7.23, with kryo-serializers 0.42 | Ordinary shared Java interface/POJOs; direct provider URL, registry `N/A`; no discovery server |

For Dubbo, **no Triple, HTTP/2, gRPC-compatible protocol, protobuf wrapper or additional Triple comparison** is permitted. HTTP/2 is used only by the gRPC target. The Dubbo2 runtime candidate is from the 2.x line, not a Dubbo3 substitution. The upstream [2.7.23 release POM](https://github.com/apache/dubbo/blob/dubbo-2.7.23/pom.xml) identifies that version. The upstream [support table](https://github.com/apache/dubbo#supported-versions) marks 2.7.23 EOL; it is a requested legacy-protocol benchmark target, not a production-version recommendation.

## Verified serializer and compatibility boundary

Apache's [Dubbo 2.7 serialization guide](https://dubbo.apache.org/en/docs/v2.7/user/examples/serialization/) documents native-protocol Kryo support. Runtime investigation confirmed the serializer bundled inside the 2.7.23 core JAR requires Kryo **4.0.2** and kryo-serializers **0.42**, matching that release's dependency BOM. The initially proposed external extension 1.0.1 was removed: duplicate classes and its Kryo 5 dependency caused a missing Kryo 4 pool API at runtime. No Hessian fallback or runtime/protocol substitution was needed.

Both processes explicitly select `serialization=kryo`. A transparent native SPI decorator observes encode/decode execution, serializer class, native effective URL and serializer wire ID **8**; both directions must match for a check to pass. Classpath JAR hashes identify the actual bundled implementation. POJO full-body/identity/checksum checks exercise the real network path; static configuration alone is not treated as evidence of serializer use.

The launcher supplies Java 21 module access for `java.lang`, `java.util` and `java.text`; Dubbo modules pin Javassist 3.30.2-GA. Programmatic configuration runs under the existing Boot 3.5.4 environment without a legacy starter. Compatibility is established only for the tested setup, not arbitrary JDKs or DTOs. See [runtime details](benchmark-running.md#actual-dubbo-compatibility-choices).

Nimrod uses its Kryo 5 artifact, while Dubbo uses Kryo 4. Different versions, registrations, reference handling, pooling and encoding remain application-stack costs. Matching the serializer family does not prove identical serialization cost or wire compatibility. Any future serializer or runtime change needs fresh compatibility checks and a distinct result identity.

## Unary comparability and capability boundaries

| Capability | Nimrod IPC | gRPC Java | Apache Dubbo2, native protocol |
| --- | --- | --- | --- |
| Unary request/response | Existing generated pricing proxy and service | Existing generated pricing stub/service | Implemented Java interface provider/reference |
| Repeated size-controlled unary benchmark | Implemented shared workload/runner in existing applications | Same logical workload/runner, protobuf representation | Same workload/runner and Java DTOs |
| FNF or one-way | Existing annotated FNF; send return is not processing acknowledgement | Empty unary reply is still acknowledged RPC; streaming has different semantics | One-way configuration is a separate capability; a `void` method alone does not establish FNF semantics |
| Subject/wildcard pub/sub | Existing native sample examples | No equivalent subject/wildcard layer in the pricing unary example | Selected configuration has no equivalent subject/wildcard pub/sub layer |
| Comparison scope | Unary performance plus separately documented capabilities | Unary performance plus separately documented capabilities | Unary performance plus separately documented capabilities |

All three unary methods must validate identical request content, execute identical small pricing/echo work and return the full same-sized body. Use matching bounded client concurrency, server work budgets, timeout policy and response-validation boundaries. Successful local submission, FNF return, stream write or transport acknowledgement is not a completed unary RPC.

Existing FNF/pubsub samples remain separate. No new cross-library event implementation is scheduled. Streaming, callbacks and one-way invocations must not be described as equivalent subject messaging or folded into the unary throughput table.

## Direct connection policy

Dubbo provider and consumer run in separate JVMs on a fixed host/port. Configure a single `dubbo://host:20880/<service-interface>` target, registry `N/A`, remote scope/`injvm=false`, no config/metadata center and no discovery dependency. Disable unnecessary QoS listeners. Explicitly configure retries zero, timeout, connection sharing/count, lazy connection, heartbeat/reconnect, worker threads and queues; validate their effective values. Apache documents [direct references](https://dubbo.apache.org/en/docs/v2.7/user/examples/explicit-target/) and [connection settings](https://dubbo.apache.org/en/docs/v2.7/user/examples/config-connections/).

The first comparison uses one dedicated Dubbo connection per service reference (`connections=1`), reused by concurrent calls on that reference, with eager initialization and a validated readiness RPC. Positive `connections` requests reference-private connections; zero uses the remote-server shared-connection mode. Concurrency 1/8/32 is outstanding RPC count, not connection count. Nimrod's requester pool and gRPC's channel may use different physical connection counts; record them and avoid claiming socket-level equivalence. A later matched-connection experiment would be a separately labelled configuration, not another protocol target.

## Developer experience and result disclosure

Compare handwritten interface/schema, Maven generation steps, service implementation, client invocation, serializer setup and endpoint/timeout/concurrency configuration. Nimrod supplies generated Java-interface proxies/controllers; gRPC supplies generated protobuf messages/stubs/service bases; Dubbo2 supplies runtime Java-interface references/proxies without an application IDL generation step. Document required handwritten code separately from generated output and shared benchmark helpers.

Every result must include exact implementation and serializer artifacts/hashes, required versus observed protocol/serializer, resolved URL, configured/observed connections and sharing, timeouts/retries, heartbeat/reconnect, worker/queue settings and Java/Boot/runtime environment. For Dubbo, mismatched protocol or serializer is an invalid run. No performance ranking is supported until identical workloads and these identities have been verified.
