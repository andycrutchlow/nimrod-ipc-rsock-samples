# Sample configuration and execution guide

Configuration, execution and messaging details for the **Nimrod IPC 4.3.1** samples. Start with the [RPC quick start](../README.md#quick-start-nimrod-rpc) or [pub/sub quick start](../README.md#quick-start-publishsubscribe). Commands here run from the **samples repository root**, with Java 21 or later, Maven and a POSIX-compatible shell. Java 25 validation is recorded in [release validation](release-validation.md).

[Build](#building-and-launching) · [RPC integration](#rpc-integration-and-execution) · [Fire-and-forget](#fire-and-forget) · [Messaging](#subjects-subscriptions-and-recovery) · [Load tools](#pubsub-load-test-tools) · [gRPC](#grpc-pricing-example) · [Troubleshooting](#troubleshooting)

## Building and launching

The root `nimrod.ipc.version` property selects the published **4.3.1** runtime, annotations, processor and parent POM from Maven Central. No library checkout or local library installation is required. The samples' own `1.0-SNAPSHOT` reactor version is separate from the Nimrod dependency.

Build all modules and generate runnable classpaths:

```bash
mvn verify dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt
```

This compiles and tests the samples without launching benchmarks. Add `-o` after dependencies have been cached. The applications are ordinary jars without executable Boot jar repackaging, so the documented commands use `java -cp`. The `:` classpath separator and `$(cat ...)` syntax are for POSIX shells.

To build a selected pair and its dependencies:

```bash
mvn -pl nrpc-server-basic,nrpc-client-basic -am verify \
  dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt
```

Substitute the relevant modules from the [example table](../README.md#explore-the-examples), such as `pubsub-loadtest-publisher,pubsub-loadtest-subscriber` or `grpc-server-basic,grpc-client-basic`. Rebuild after changing a shared interface, DTO or annotation; an application-only build may otherwise reuse stale generated code or dependency jars.

## RPC integration and execution

The [shared contract](../nrpc-common/src/main/java/com/nimrodtechs/samples/rmiservices/pricing/PricingServiceRmiInterface.java) is:

```java
@NimrodRmiInterface(serviceName = "server1", concurrency = 8, scheduler = SchedulerType.PARALLEL)
public interface PricingServiceRmiInterface {
    PriceResponse getPrice(PriceRequest request) throws Exception;

    @NimrodFireAndForget
    void publishPriceUpdate(PriceUpdateEvent event) throws Exception;
}
```

The imports and DTOs are in the linked source. [`nrpc-common/pom.xml`](../nrpc-common/pom.xml) configures `io.github.andycrutchlow:nimrod-ipc-rsock-processor` on `annotationProcessorPaths`. It generates a Spring client and controller in the interface's package. The [server implementation](../nrpc-server-basic/src/main/java/com/nimrodtechs/samples/PricingService.java) implements the Java interface; the [client controller](../nrpc-client-basic/src/main/java/com/nimrodtechs/samples/ClientBasicController.java) injects it and makes ordinary synchronous calls.

For this contract, the generated classes are `PricingServiceRmiInterface__NimrodRmiClient` and `PricingServiceRmiInterface__NimrodRmiController`. Keep the generated classes and shared DTOs available to both applications through the contract module.

Server scanning includes `com.nimrodtechs.ipcrsock.server` and the contract package; client scanning includes `com.nimrodtechs.ipcrsock.client` and that package. Both applications scan Nimrod configuration properties. The actual client configuration key is `nimrod.rsock.client-side.server1`, with `host`, `port` and `maxConcurrentCalls: 8`. That setting limits borrowed requesters and configures pool capacity; it is separate from the interface's server scheduler configuration.

`PARALLEL` uses a generated Reactor scheduler with the specified thread count. Calls still block their client thread until a response arrives. The default `SINGLE` setting adds no scheduler offload; it should not be described as a global serial executor across all network connections. `timeoutMs` is server-side only, and the current retry-policy annotation is a placeholder. See the [library README](https://github.com/andycrutchlow/nimrod-ipc-rsock-parent#rpc-programming-model).

The server-side `timeoutMs` setting applies a Reactor response timeout. It does not bound client pool/network waits or guarantee that arbitrary server work stops or rolls back when the timeout fires. For reactive callers, schedule the blocking client invocation on an appropriate worker rather than a Netty event-loop thread.

### Endpoint configuration

The server's Nimrod endpoint is separate from the HTTP demonstration endpoints:

```yaml
nimrod:
  rsock:
    server:
      name: server1
      port: 40280
```

The client's matching endpoint entry is:

```yaml
nimrod:
  rsock:
    client-side:
      server1:
        host: localhost
        port: 40280
        maxConcurrentCalls: 8
```

The interface's `serviceName` chooses that remote entry. A requester is borrowed for a synchronous call and returned afterward; a caller waits when the pool is exhausted. That wait and the remote response wait are not bounded by a native client RPC deadline. See [timeout behavior](nimrod-timeouts.md) for the benchmark's separate interrupt/abort safeguards. Transport reconnect settings do not automatically retry failed application methods; choose any application retry policy according to the method's side effects.

The server command in the README explicitly uses `--server.port=8080`; the checked-in HTTP default is an ephemeral port. The client demonstration endpoint is on 8081. Its additional `server2` YAML entry is not used by the pricing calls. HTTP controllers provide convenient triggers and metrics; Nimrod RPC itself runs over RSocket/TCP.

**Spring Boot RSocket workaround:** Preserve the sample's existing `spring.rsocket.server.port: 0`. It is a temporary workaround associated with Spring Boot RSocket auto-configuration for Nimrod IPC 4.3.1, expected to become unnecessary after future auto-configuration refinement. Port `0` permits an available ephemeral port if Spring's RSocket listener starts; it does not disable that listener. Nimrod's intended endpoint remains `nimrod.rsock.server.port: 40280`.

## RPC demonstration endpoints

With the [RPC server and client](../README.md#quick-start-nimrod-rpc) running:

```bash
curl 'http://localhost:8081/price?ccyPair=EUR%2FUSD&tenor=SPOT'
curl 'http://localhost:8081/parallel-price?ccyPair=EUR%2FUSD&tenor=SPOT&threads=4'
```

The first returns a JSON `PriceResponse` with currency pair, tenor, price `"1.1234"` and timing fields. The second returns four response objects and the requested parallelism. Each endpoint performs 5,000 warmup calls before its demonstration calls. These convenience endpoints are distinct from the shared echo-style benchmark and do not supply its published measurements.

### Fire-and-forget

With the same server/client pair running:

```bash
curl 'http://localhost:8081/burst?messages=100&threads=1&payloadBytes=128'
curl 'http://localhost:8080/fnf/server-metrics'
```

`/burst` calls `publishPriceUpdate`, returning client send counts, detected errors and send-duration statistics. The initial connectivity call adds one extra event. Poll the server metrics to observe processing; they are cumulative until restart. A successful send does not acknowledge server execution. The client waits for RSocket's send operation, and server fire-and-forget handlers run on virtual threads. This sample is separate from the [request/response benchmark baseline](../BENCHMARKS.md).

## Subjects, subscriptions and recovery

The [basic publisher](../pubsub-publisher-basic/src/main/java/com/nimrodtechs/samples/PublisherBasicApplication.java) scans `com.nimrodtechs.ipcrsock.publisher` and injects `PublisherSocketImpl`. The [basic subscriber](../pubsub-subscriber-basic/src/main/java/com/nimrodtechs/samples/SubscriberBasicApplication.java) scans `com.nimrodtechs.ipcrsock.subscriber` and injects `SubscriberService`. Both exchange the shared [MarketData](../nrpc-common/src/main/java/com/nimrodtechs/samples/dto/MarketData.java) record.

The publisher is configured with `nimrod.rsock.publisher.port: 40281`. The subscriber YAML maps its alias to that direct endpoint:

```yaml
nimrod:
  rsock:
    subscriber:
      setup:
        - publisher1,localhost,40281
```

With a configured setup, the subscriber needs `spring.application.name`, supplied by the README's launch command. The basic publisher alternates spot and one-week updates one second apart and logs subscription-added/removed notifications.

The core calls in these applications are:

```java
publisherSocket.publish(msg.ccyPair() + "." + msg.tenor(), msg);
subscriberService.subscribe("publisher1", "EURUSD.*", receiver, MarketData.class, false);
```

The receiver implements `MessageReceiverInterface<MarketData>` and receives publisher alias, actual subject and payload. A `MarketData` object can be created as in the publisher:

```java
MarketData msg = new MarketData("EURUSD", "SPOT", "1.12340", "1.12350", System.nanoTime());
```

### Wildcards and subscription lifecycle

The menu's `EURUSD.SPOT` and `EURUSD.1W` choices are exact subscriptions. `EURUSD.*` uses a trailing `.*` prefix match and covers deeper descendants as well as one suffix segment; arbitrary glob patterns are not supported. Keep compatible payload types for subjects sharing a subscription.

Selections add subscriptions rather than replacing earlier choices. Repeating a selection with the same listener is ignored. The library shares overlapping upstream subscriptions and avoids notifying the same listener twice for overlapping exact/wildcard matches.

The library provides `unsubscribe(publisherName, subject, listener)`, but the console menu does not expose it. Keep and pass the same listener instance when removing a subscription programmatically:

```java
subscriberService.unsubscribe("publisher1", "EURUSD.*", receiver);
```

### Conflation and processing order

Both runnable subscriber applications use **`conflate=false`**. There is no standalone conflation demo or CLI switch. At subscription registration, the same API accepts `true`:

```java
subscriberService.subscribe("publisher1", "EURUSD.SPOT", receiver, MarketData.class, true);
```

Choose this policy before registering the listener. Conflation replaces older pending updates in a small bounded queue when a listener falls behind, useful when the latest price, telemetry value or state matters more than every intermediate change. It is not appropriate for events that must all be processed.

Conflation is per listener/subscription queue, not a retained latest-value map keyed by every subject. A wildcard subscription shares one queue across all its matched subjects; use separate exact subscriptions for independent queues. There is no retained update for a future subscriber.

With `conflate=false`, each listener/subscription entry uses FIFO processing; different entries can run concurrently, while one entry is handled serially. A wildcard's matched messages share that entry. Neither choice establishes lossless delivery: FIFO listener queues are bounded and transport backpressure handling can also discard updates under overload.

### Reconnection and subscription recovery

Leave the basic subscriber running, stop the publisher and restart it using the same command. After transport failure detection and supported stream recovery, newly published matching messages should resume. Keepalive settings can make failure detection slow after a silent outage.

The library reconnects and reinstalls active subscriptions for supported connection-failure signals. There is no separate automated fault-injection or connection-management sample. Recovery resumes live traffic; it does not replay messages missed during disconnection. Pub/sub is in-memory distribution without persistent queues, durable subscriptions or per-subscriber delivery acknowledgements.

### Diagnostic latency

The pub/sub tools subtract the publisher's `System.nanoTime()` from a subscriber JVM's reading. Those are diagnostic observations, not a portable cross-JVM/cross-host latency method. They differ from the comparative RPC benchmark, which measures round-trip invocation and validation on a single client monotonic clock.

## Pub/sub load-test tools

These are configurable demonstration tools, separate from the published RPC comparison. Build the two `pubsub-loadtest-*` modules with the [reactor build](#building-and-launching). Stop the basic publisher before starting this pair.

Start the load publisher:

```bash
java -Dnimrod.rsock.publisher.port=-1 \
  -cp "pubsub-loadtest-publisher/target/classes:$(cat pubsub-loadtest-publisher/target/benchmark-classpath.txt)" \
  com.nimrodtechs.samples.PublisherCliApp \
  --publisher publisher1 --port 40281 --subjects EURUSD.SPOT,EURUSD.1W \
  --rate 1000 --payload-size 128 --duration-seconds 60 --mode round-robin
```

The JVM property suppresses the library's automatic publisher bind because this CLI explicitly calls `start(port)` itself; otherwise its YAML and CLI both try to bind the same port. All `--option value` arguments shown are parsed by the application, so use JVM `-D` properties for Spring settings.

Start its subscriber:

```bash
java -Dspring.application.name=load-subscriber \
  -cp "pubsub-loadtest-subscriber/target/classes:$(cat pubsub-loadtest-subscriber/target/benchmark-classpath.txt)" \
  com.nimrodtechs.samples.SubscriberCliApp \
  --publisher publisher1 --subjects 'EURUSD.*'
```

Expect per-second logs of `rate`, `total`, `avgLatency`, `maxLatency` and `gaps`. The publisher stops producing after 60 seconds, but its JVM and the subscriber remain running; use Ctrl-C to stop each. Starting the subscriber later misses earlier updates. Gap counts track sequence discontinuities, which can also reflect subject filtering or dispatch order, so they are not a delivery guarantee. See [diagnostic latency](#diagnostic-latency) before interpreting timing values.

Publisher options: required `--subjects`; optional `--publisher`, `--port`, `--rate`, `--payload-size`, `--duration-seconds`, and `--mode fixed|round-robin`. Defaults are `samplePublisher`, 40281, 1000 messages/s, 128 B, 30 seconds and `fixed`. Fixed mode uses only the first subject. Subscriber options are required `--publisher` and `--subjects`. The subscriber publisher alias must match its YAML setup (`publisher1` here).

## gRPC pricing example

Build `grpc-server-basic,grpc-client-basic` and dependencies, or use the [complete build](#building-and-launching). Start its server:

```bash
java -cp "grpc-server-basic/target/classes:$(cat grpc-server-basic/target/benchmark-classpath.txt)" \
  com.nimrodtechs.samples.grpcserver.GrpcServerApplication \
  --server.port=8082
```

Start its client:

```bash
java -cp "grpc-client-basic/target/classes:$(cat grpc-client-basic/target/benchmark-classpath.txt)" \
  com.nimrodtechs.samples.grpcclient.GrpcClientApplication \
  --server.port=8083
```

The client connects to plaintext gRPC port 9090. Invoke:

```bash
curl 'http://localhost:8083/grpc/price?ccyPair=EUR%2FUSD&tenor=SPOT'
curl 'http://localhost:8083/grpc/parallel-price?ccyPair=EUR%2FUSD&tenor=SPOT&threads=4'
```

Expect price `"1.2345"` and timing fields, or four responses. This legacy pricing constant differs from Nimrod's demo constant. The comparative **benchmark profile** instead runs the identical common echo-style workload and price `"1.1234"` in all three frameworks.

To validate both original pricing examples automatically after building (starts/stops its own server/client processes on free ports):

```bash
python3 scripts/benchmarks/check_examples.py
```

## Comparative RPC implementations

The benchmark profile reuses the Nimrod and gRPC main applications and adds native Dubbo2 provider/consumer modules. The runner manages their server/client processes, application readiness, finite execution and shutdown. There is no standalone Dubbo2 pricing HTTP endpoint. See [benchmark commands](../BENCHMARKS.md#reproducing-the-benchmarks) and [programming model comparisons](developer-experience.md) for the shared echo workload, serializers, connection models and framework-specific behavior.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Missing `target/benchmark-classpath.txt` or an application class | Run the root Maven build; execute commands from the samples repository root |
| Dependency resolution fails in offline mode | Run the build without `-o` first to cache Maven Central dependencies |
| Port already in use | Stop the earlier sample: basic and load publishers both default to 40281; RPC uses 40280 |
| Subscriber startup requests an application name | Supply `--spring.application.name=prices-subscriber` to the basic subscriber, or the documented JVM property for the strict load CLI |
| Subscriber is running but no messages arrive | Select a subject in the basic menu and check publisher alias, subject and configured host/port |
| Strict load CLI rejects a Spring option | Pass Spring settings as JVM `-D` properties before the classpath and main class, as shown above |
| A call waits indefinitely during a failure | Review [RPC deadline limitations](nimrod-timeouts.md); server timeout and transport recovery settings are not native client call deadlines |
