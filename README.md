# Nimrod IPC samples

[Nimrod IPC](https://github.com/andycrutchlow/nimrod-ipc-rsock-parent) is a Java messaging library combining annotation-driven synchronous RPC, fire-and-forget RPC and direct brokerless publish/subscribe. Use ordinary Java interfaces for remote calls and subjects for messaging, without a separate messaging broker. The library also provides wildcard subscriptions, message conflation, automatic reconnection and subscription recovery.

These samples demonstrate pricing RPC, fire-and-forget calls and exact/wildcard subscriptions. Conflation is available in the library but disabled in the runnable subscribers; publisher restart lets you try subscription recovery. The repository also includes comparative gRPC Java and Apache Dubbo2 implementations.

[Try RPC](#quick-start-nimrod-rpc) · [Try pub/sub](#quick-start-publishsubscribe) · [Explore examples](#explore-the-examples) · [Benchmark report](BENCHMARKS.md)

## Requirements and Maven Central

**Java compatibility:** Requires Java 21 or later. Tested with Java 21 and Java 25. The Maven compiler and bytecode target remain Java 21; see [release validation](docs/release-validation.md) for the tested JDK builds and evidence.

Use Maven and a POSIX-compatible shell for the commands below. The samples use Spring Boot 3.5.4 and **Nimrod IPC 4.3.1 from [Maven Central](https://central.sonatype.com/artifact/io.github.andycrutchlow/nimrod-ipc-rsock/4.3.1)**. You do not need to clone or install the library itself. For your own application:

```xml
<dependency>
    <groupId>io.github.andycrutchlow</groupId>
    <artifactId>nimrod-ipc-rsock</artifactId>
    <version>4.3.1</version>
</dependency>
```

The samples select the runtime, annotations, processor and parent POM through the shared `nimrod.ipc.version` property. For your own RPC contracts, the [sample guide](docs/sample-guide.md#rpc-integration-and-execution) explains how the shared interface module enables annotation processing and is used by both applications.

## Performance at a glance

In the published same-host RPC benchmark, Nimrod IPC achieved **up to approximately twice the throughput of gRPC Java** and exceeded its median throughput in all **12 tested configurations**.

![Median RPC throughput at concurrency 32](docs/benchmarks/throughput-c32.svg)

The chart shows medians of five runs at concurrency 32 on Java 21. Connection and execution models differ: Nimrod uses multiple requester connections at higher concurrency; gRPC and Dubbo2 each use one multiplexed connection. These results do not establish universal superiority; Dubbo2 leads some throughput comparisons and generally has lower p99 latency.

[BENCHMARKS.md](BENCHMARKS.md) contains all results, p50/p95/p99 latency, CPU, methodology, configuration and limitations. It preserves the distinction between the measured pre-release binary and the published 4.3.1 artifact.

<a id="build-once"></a>
<a id="basic-nimrod-rpc"></a>

## Quick start: Nimrod RPC

This example requests a price from another Java application. The [shared contract](nrpc-common/src/main/java/com/nimrodtechs/samples/rmiservices/pricing/PricingServiceRmiInterface.java) defines `getPrice(PriceRequest)`. The [server](nrpc-server-basic/src/main/java/com/nimrodtechs/samples/PricingService.java) implements that interface; the client receives a generated implementation and calls it like a normal Java service.

1. Clone the samples:

   ```bash
   git clone https://github.com/andycrutchlow/nimrod-ipc-rsock-samples.git
   cd nimrod-ipc-rsock-samples
   ```

2. Build all modules and their runnable classpaths:

   ```bash
   mvn verify dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt
   ```

3. Start the server:

   ```bash
   java -cp "nrpc-server-basic/target/classes:$(cat nrpc-server-basic/target/benchmark-classpath.txt)" \
     com.nimrodtechs.samples.ServerBasicApplication \
     --server.port=8080
   ```

4. Start the client in another terminal, from the samples repository root:

   ```bash
   java -cp "nrpc-client-basic/target/classes:$(cat nrpc-client-basic/target/benchmark-classpath.txt)" \
     com.nimrodtechs.samples.ClientBasicApplication
   ```

5. Invoke a pricing method:

   ```bash
   curl 'http://localhost:8081/price?ccyPair=EUR%2FUSD&tenor=SPOT'
   ```

Keep the server and client running in their respective terminals while invoking the endpoints. All launch and `curl` commands run from the samples repository root. Use Ctrl-C to stop the applications when finished.

Expect a JSON response containing `ccyPair: "EUR/USD"`, `tenor: "SPOT"` and `price: "1.1234"`. The endpoint performs warmup calls before returning its demonstration response; it is separate from the comparative benchmark.

The Nimrod server listens on **TCP port 40280**, using this existing configuration:

```yaml
nimrod:
  rsock:
    server:
      name: server1
      port: 40280
```

The client connects to logical service `server1` and invokes methods through the generated [Java interface proxy](nrpc-common/src/main/java/com/nimrodtechs/samples/rmiservices/pricing/PricingServiceRmiInterface.java). The sample applications expose HTTP endpoints solely to demonstrate and exercise those calls. **HTTP is not part of Nimrod IPC's underlying communication.**

**Spring Boot RSocket workaround:** The samples retain `spring.rsocket.server.port: 0` as a temporary configuration workaround. It is expected to become unnecessary once Nimrod's Spring Boot auto-configuration is refined in a future release. Port `0` permits an ephemeral port if Spring's listener starts; it does not disable that listener.

To make four concurrent pricing calls through the same client:

```bash
curl 'http://localhost:8081/parallel-price?ccyPair=EUR%2FUSD&tenor=SPOT&threads=4'
```

<a id="fire-and-forget"></a>

For a one-way call demonstration, send a short burst and inspect the server's processing count:

```bash
curl 'http://localhost:8081/burst?messages=100&threads=1&payloadBytes=128'
curl 'http://localhost:8080/fnf/server-metrics'
```

Send completion does not acknowledge remote application processing. See [fire-and-forget details](docs/sample-guide.md#fire-and-forget) for how the counters and the initial connectivity event are handled. [RPC configuration details](docs/sample-guide.md#rpc-integration-and-execution) cover interface generation, component scanning, execution and timeouts.

<a id="publishsubscribe-and-wildcards"></a>

## Quick start: Publish/Subscribe

After building above, start the basic publisher from the repository root:

```bash
java -cp "pubsub-publisher-basic/target/classes:$(cat pubsub-publisher-basic/target/benchmark-classpath.txt)" \
  com.nimrodtechs.samples.PublisherBasicApplication
```

Start its subscriber in an interactive terminal, also from the repository root:

```bash
java -cp "pubsub-subscriber-basic/target/classes:$(cat pubsub-subscriber-basic/target/benchmark-classpath.txt)" \
  com.nimrodtechs.samples.SubscriberBasicApplication \
  --spring.application.name=prices-subscriber
```

The publisher listens on **40281** and alternates updates on `EURUSD.SPOT` and `EURUSD.1W`. Its publishing call uses the message's subject:

```java
publisherSocket.publish(msg.ccyPair() + "." + msg.tenor(), msg);
```

Choose `1` for wildcard `EURUSD.*`, `2` for exact subject `EURUSD.SPOT`, or `3` for exact subject `EURUSD.1W`. Expect `RECEIVED -> EURUSD.SPOT` and/or `RECEIVED -> EURUSD.1W` logs matching your selection. Selections add subscriptions; `q` exits. Use Ctrl-C to stop the publisher.

The received records include the actual subject and market-data fields. The wildcard choice receives both spot and one-week prices; either exact choice filters the feed to that subject.

For wildcard semantics, unsubscribe, conflation, recovery and delivery limitations, see [messaging details](docs/sample-guide.md#subjects-subscriptions-and-recovery). The [load-test tools](docs/sample-guide.md#pubsub-load-test-tools) share port 40281, so stop the basic publisher before trying them.

<a id="sample-map"></a>

## Explore the examples

| Example | Modules |
| --- | --- |
| Basic Nimrod RPC | [`nrpc-server-basic`](nrpc-server-basic), [`nrpc-client-basic`](nrpc-client-basic) |
| Fire-and-forget RPC (`/burst`) | Same [server](nrpc-server-basic) and [client](nrpc-client-basic); [instructions](docs/sample-guide.md#fire-and-forget) |
| Basic publish/subscribe and wildcards | [`pubsub-publisher-basic`](pubsub-publisher-basic), [`pubsub-subscriber-basic`](pubsub-subscriber-basic) |
| Pub/sub load-test tools | [`pubsub-loadtest-publisher`](pubsub-loadtest-publisher), [`pubsub-loadtest-subscriber`](pubsub-loadtest-subscriber) |
| gRPC pricing and benchmark | [`grpc-server-basic`](grpc-server-basic), [`grpc-client-basic`](grpc-client-basic), [`grpc-common`](grpc-common); [pricing commands](docs/sample-guide.md#grpc-pricing-example) |
| Native Apache Dubbo2 benchmark | [`dubbo-server-basic`](dubbo-server-basic), [`dubbo-client-basic`](dubbo-client-basic), [`dubbo-common`](dubbo-common) |
| Shared contracts and benchmark support | [`nrpc-common`](nrpc-common), [`benchmark-common`](benchmark-common) |

Start only the pair you want to explore: the Nimrod examples do not require gRPC or Dubbo2 processes. Shared modules contain contracts and support code rather than standalone applications. The [sample guide](docs/sample-guide.md) provides further commands, configuration and troubleshooting. All examples use the shared Maven build above; the guide also explains how to build only a selected pair.

## Benchmarking

The comparative RPC benchmark exercises equivalent validated echo-style workloads in Nimrod IPC, gRPC Java and native Apache Dubbo2. It covers payloads of 128 B, 1 KB, 10 KB and 100 KB at concurrency 1, 8 and 32.

After building, a short correctness check for all three implementations is:

```bash
python3 scripts/benchmarks/run.py --smoke --offline --skip-build \
  --sizes 128 --concurrency 1
```

The runner starts and stops its own client/server processes. Smoke results check correctness and do not support performance conclusions. The separate pub/sub load-test tools demonstrate configurable publishing and subscriber diagnostics; they are not part of this RPC comparison.

See [BENCHMARKS.md](BENCHMARKS.md) for the complete 180-run baseline, reproduction commands and archive availability. The existing report and charts can be checked without executing benchmark cases using `python3 scripts/benchmarks/report.py --check`.

## Documentation and license

- [Sample configuration, execution and troubleshooting](docs/sample-guide.md)
- [Benchmark results, methodology and limitations](BENCHMARKS.md)
- [Maven Central and Java 21/25 validation](docs/release-validation.md)
- [Framework programming models](docs/developer-experience.md)
- [Nimrod timeout behavior and benchmark safeguards](docs/nimrod-timeouts.md)
- [Benchmark runner details](docs/benchmark-running.md)

Nimrod IPC's published artifact metadata identifies the [Apache License 2.0](https://central.sonatype.com/artifact/io.github.andycrutchlow/nimrod-ipc-rsock/4.3.1). This samples checkout has no root `LICENSE` file; consult artifact metadata and preserve source copyright/license notices when reusing code. Issues and pull requests belong in the corresponding GitHub repository.
