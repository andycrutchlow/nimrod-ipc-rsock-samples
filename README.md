# nimrod-ipc-rsock-samples

Sample projects demonstrating usage of the **Nimrod IPC RSocket** messaging library.

This repository contains a minimal, multi-module Maven setup showing how to define a shared RMI interface, run a Nimrod IPC server, and invoke it from a client.

---

## Project structure

```
nimrod-ipc-rsock-samples
├── pom.xml                (parent / aggregator)
├── common                 (shared RMI interfaces + DTOs)
├── server-basic           (basic server application)
└── client-basic           (basic client application)
```

---

## Common module (shared contract)

The `common` module contains the **shared RMI interface and data types** used by both server and client.

### Example RMI interface

```java
@NimrodRmiInterface(serviceName = "server1")
public interface PricingServiceRmiInterface {
    PriceResponse getPrice(PriceRequest request) throws Exception;
}
```

### Key points

- `@NimrodRmiInterface` defines a **logical service boundary**
- `serviceName` is used for routing and client configuration
- `concurrency` and `scheduler` apply **server-side only**
- The same interface is:
    - implemented by the server
    - proxied automatically on the client

### Annotation processing

The `common` module enables the Nimrod annotation processor so that server controllers and client proxies are generated at compile time.

```xml
<annotationProcessorPaths>
    <path>
        <groupId>com.nimrodtechs</groupId>
        <artifactId>nimrod-ipc-rsock-processor</artifactId>
        <version>${nimrod.ipc.version}</version>
    </path>
</annotationProcessorPaths>
```

No runtime reflection is required.

---

## ServerBasicApplication

The `server-basic` module runs a **Spring Boot server** that exposes the RMI interface over Nimrod IPC (RSocket).

### application.yaml

```yaml
server:
  port: 8080

spring:
  rsocket:
    server.port: 0   # disable Spring's built-in RSocket server

nimrod:
  rsock:
    server:
      name: server1
      port: 40280
```

### Configuration explanation

- `server.port: 8080`  
  Starts a standard Spring Boot web container (useful for demos or health checks).

- `spring.rsocket.server.port: 0`  
  Disables Spring’s own RSocket server.  
  Nimrod IPC runs its **own** RSocket server.

- `nimrod.rsock.server.name`  
  Logical server name.  
  Must match the client configuration and the `@NimrodRmiInterface(serviceName)` value.

- `nimrod.rsock.server.port`  
  TCP port used by Nimrod IPC for RSocket communication.

### server-basic/pom.xml (key dependencies)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<dependency>
    <groupId>com.nimrodtechs.samples</groupId>
    <artifactId>common</artifactId>
</dependency>

<dependency>
    <groupId>com.nimrodtechs</groupId>
    <artifactId>nimrod-ipc-rsock</artifactId>
</dependency>
```

### Running the server

From the repository root:

```bash
mvn -pl server-basic spring-boot:run
```

The server will:
- listen on HTTP port `8080`
- expose Nimrod IPC on port `40280`

---

## ClientBasicApplication

The `client-basic` module runs a **Spring Boot client** that invokes the RMI interface synchronously.

### application.yaml

```yaml
server:
  port: 8081

nimrod:
  rsock:
    server:
      servers:
        server1:
          host: localhost
          port: 40280
          maxConcurrentCalls: 4
```

### Configuration explanation

- `server.port: 8081`  
  Client’s own HTTP port (used for demo endpoints).

- `nimrod.rsock.server.servers.server1`  
  Defines a remote Nimrod IPC server.

- `server1`  
  Must match the `serviceName` defined on `@NimrodRmiInterface`.

- `host` / `port`  
  Address where the server is running.

- `maxConcurrentCalls`  
  Client-side connection pool size.  
  Controls how many concurrent request/response calls may be in flight.

### client-basic/pom.xml (key dependencies)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<dependency>
    <groupId>com.nimrodtechs.samples</groupId>
    <artifactId>common</artifactId>
</dependency>

<dependency>
    <groupId>com.nimrodtechs</groupId>
    <artifactId>nimrod-ipc-rsock</artifactId>
</dependency>
```

### Running the client

```bash
mvn -pl client-basic spring-boot:run
```

The client receives an auto-generated proxy for `PricingServiceRmiInterface` and performs blocking, RPC-style calls over RSocket.

---

## Parent pom.xml (module wiring)

The root `pom.xml` aggregates all modules and defines shared configuration:

```xml
<modules>
    <module>common</module>
    <module>server-basic</module>
    <module>client-basic</module>
</modules>

<properties>
    <nimrod.ipc.version>4.1-SNAPSHOT</nimrod.ipc.version>
</properties>

<dependencyManagement>
    <dependency>
        <groupId>com.nimrodtechs</groupId>
        <artifactId>nimrod-ipc-rsock-parent</artifactId>
        <version>${nimrod.ipc.version}</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
</dependencyManagement>
```

This ensures:
- consistent versions across modules
- clean separation of responsibilities
- predictable builds

---


---

## ClientBasicController test endpoints

The `client-basic` module exposes a simple REST controller that allows you to exercise
the Nimrod IPC request/response path from a browser or HTTP client. These endpoints
are intentionally simple and are designed to demonstrate both **single-call latency**
and **server-side concurrency behaviour**.

The controller delegates all pricing calls to the generated
`PricingServiceRmiInterface` client proxy.

### Single request/response test (`/price`)

```java
@GetMapping("/price")
public PriceResponse getPrice(
        @RequestParam String ccyPair,
        @RequestParam String tenor) throws Exception {
    ...
}
```

This endpoint performs:

1. **Warm-up**
    - Executes 5,000 RMI calls before measurement
    - Ensures JIT compilation and steady-state behaviour

2. **Measured call**
    - Sends a single `getPrice` request
    - Captures:
        - `timeSent` (on the client before the call)
        - `timeResponded` (on the server)
        - `timeReceived` (on the client after return)

This endpoint is useful for:
- verifying basic connectivity
- confirming request/response semantics
- observing baseline latency

#### Example invocation

```bash
curl "http://localhost:8081/price?ccyPair=EUR/USD&tenor=SPOT"
```

---

### Parallel request/response test (`/parallel-price`)

```java
@GetMapping("/parallel-price")
public ParallelPriceResponse getPriceParallel(
        @RequestParam String ccyPair,
        @RequestParam String tenor,
        @RequestParam(defaultValue = "1") int threads) throws Exception {
    ...
}
```

This endpoint demonstrates **concurrent client-side calls** and how they are handled
by the server.

Behaviour:

1. **Single warm-up phase**
    - 5,000 calls executed once before parallel execution

2. **Parallel execution**
    - A fixed thread pool of size `threads` is created
    - Each thread performs a synchronous RMI call
    - Calls are executed concurrently on the client

3. **Result aggregation**
    - All responses are collected and returned in a single payload

The response includes:
- requested currency pair and tenor
- requested parallelism level
- individual `PriceResponse` objects with timing data

This endpoint is useful for:
- validating server-side concurrency configuration
- observing head-of-line blocking vs parallel execution
- comparing `concurrency=1` vs higher values on the server

#### Example invocation

```bash
curl "http://localhost:8081/parallel-price?ccyPair=EUR/USD&tenor=SPOT&threads=4"
```


### Default vs tuned server-side concurrency

By default, `@NimrodRmiInterface` uses **single-threaded server execution**:

```java
@NimrodRmiInterface(serviceName = "server1")
```
- Requests are handled serially on the server
- Parallel client calls queue behind each other
- This is the safest and simplest execution model

For the parallel pricing test, the interface can be tuned explicitly:

```java
@NimrodRmiInterface(
    serviceName = "server1",
    concurrency = 4,
    scheduler = SchedulerType.PARALLEL
)
```
- Up to four requests may execute concurrently on the server
- Requests are scheduled on a bounded parallel scheduler
- Client code and wire protocol remain unchanged

This allows the `/parallel-price` endpoint to demonstrate the difference
between **serial execution** and **true server-side concurrency** using
the same client code.


When the server is configured with:

```java
@NimrodRmiInterface(
    serviceName = "server1",
    concurrency = 4,
    scheduler = SchedulerType.PARALLEL
)
```

the four requests will execute concurrently on the server. Reducing the server
concurrency to `1` will cause the same requests to be handled serially.


## Summary

This samples repository demonstrates:

- a shared RMI contract (`common`)
- a Nimrod IPC server (`server-basic`)
- a Nimrod IPC client (`client-basic`)
- annotation-driven code generation
- synchronous request/response semantics over RSocket
- server-side concurrency configuration without client impact

The examples are intentionally minimal and designed as a starting point for more advanced usage.
