# Developer experience in the implemented comparison

Current samples use **Nimrod IPC 4.3.1 from Maven Central**, with Java 21 as the minimum/compiler target. See [release validation](release-validation.md) for Java 21/25 checks and [BENCHMARKS.md](../BENCHMARKS.md) for the archived performance binary.

All three applications execute the same `Workload` validation/pricing/full-body echo code in `benchmark-common`. Shared helpers provide payload generation, the synchronous load loop, histograms, failure handling and result output; those helpers are not counted as library-specific service boilerplate. These observations concern this implementation, not a general usability ranking.

| Concern | Nimrod IPC | gRPC Java | Native Dubbo2 |
| --- | --- | --- | --- |
| Contract | `BenchmarkRmi` Java interface and shared `BenchmarkMessage` POJO | `benchmark.proto` service/message | `DubboBenchmark` Java interface and shared POJO |
| Generation | Existing Maven Nimrod annotation processor produces client/controller Spring classes | Existing protobuf Maven plugin generates Java messages, blocking stub and service base | Runtime Java-interface proxy/reference; no application proto or annotation-processing step |
| Provider implementation | Profile configuration imports generated controller; service bean delegates to shared processor | Generated service base implemented with observer completion/error mapping, DTO mapping and a bounded worker executor | Boot-managed `ServiceConfig`, native `ProtocolConfig`, ordinary interface implementation |
| Client implementation | Import generated client, invoke Java method on injected interface | Reuse existing configured `pricing` channel/stub injection; per-call native deadline and wire/POJO mappings | Boot-managed direct `ReferenceConfig`, invoke ordinary Java method; no registry dependency |
| Serialization | Nimrod Kryo 5 path using its existing `application/x-kryo` strategies | Protobuf 3.25.3 generated types; explicit byte/POJO mapping | Bundled Dubbo2 Kryo SPI, Kryo 4.0.2 and kryo-serializers 0.42; simple Serializable POJO |
| Application configuration | Logical service `benchmark`, host/port, requester pool sized to concurrency; default scheduling selected by the benchmark contract (no generated scheduler offload) | Existing channel address/plaintext configuration and eight-worker application processing | Native TCP bind/direct `dubbo://` URL, registry `N/A`, explicit Kryo, private connection count, timeout/retry/heartbeat and provider worker settings |
| Errors and timeout | Proxy throws local/remote exceptions; no exposed native client network deadline; benchmark interrupt/abort required | gRPC status/deadline exceptions mapped to common result counts | Native Dubbo `RpcException`/timeout path mapped to common result counts |
| Java 21 integration | Reuses the existing working app and generated code | Reuses the existing working app/starter | Programmatic API avoids legacy starter; proxy/serializer module-access flags and matching Kryo dependencies are necessary |

Existing `ClientBasicApplication`, `ServerBasicApplication`, `GrpcClientApplication` and `GrpcServerApplication` remain the entry points. Three HTTP controller classes receive only a `!benchmark` profile annotation. Existing pricing DTOs/protobuf fields, pricing constants, endpoints and pub/sub applications are unchanged.

Nimrod's benchmark contract is deliberately outside the examples' broad `com.nimrodtechs.samples` component scan, in `com.nimrodtechs.benchmarks.nimrod`. Only benchmark-profile configurations import its generated client/controller. This avoids requiring the new service implementation in normal pricing mode. The original package scans still discover the original pricing proxy/controller.

gRPC adds one proto, a mapping helper, a benchmark responder/configuration and a client runner configuration. That mapping is part of its measured application path. Dubbo adds only the planned common/provider/consumer modules: native settings and a serializer observation decorator make its actual protocol/serializer inspectable. No registry, separate observability server or transport abstraction is introduced.

Changing a Nimrod contract entails rebuilding its shared interface/DTO artifact and annotation-generated code. Changing the gRPC contract entails editing the proto and regenerating messages/stubs; existing field numbers remain untouched. Changing Dubbo's contract entails rebuilding its shared interface/DTO artifact and keeping client/provider types compatible. Protobuf field compatibility and Kryo/Java class compatibility are different contracts; matching a Java method signature alone does not guarantee mixed-version serialization compatibility.

The original pricing endpoint checks, new full-body RPC checks and delayed-handler checks are executable scripts described in [benchmark-running.md](benchmark-running.md). The unit checks reject corrupted replies and inconsistent identities and verify bounded worker concurrency and both interruptible/uninterruptible stalls. No performance conclusions follow from those developer-facing checks.
