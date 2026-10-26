# Security-filter Kryo RMI investigation

> Historical record: version references, commands and validation statements below describe the dated investigation/design, not current build requirements. Current samples use **Nimrod IPC 4.3.1 from Maven Central**; see [release validation](release-validation.md) and the [completed 180-run benchmark report](../BENCHMARKS.md).

Verified locally on 2026-10-24 with Java 21.0.8, Nimrod 4.3.1-SNAPSHOT,
Kryo 5.5.0 and the existing generated RMI proxies over real RSocket/TCP connections.

## Outcome and reproduction limits

A library defect was reproduced and fixed: a concrete `ArrayList` response could not
be decoded as the interface declared by an RMI client, `List.class`.

**The exact reported `Unable to resolve reference ... id: 97` /
`IndexOutOfBoundsException: Index 97 out of bounds for length 1` did not reproduce.**
The ordinary generated list call instead failed with:

```text
KryoException: Class cannot be created (missing no-arg constructor): java.util.List
```

An additional, intentionally incorrect real RMI call requesting that same list as
`Object[].class` failed before the fix with `Encountered unregistered class ID: 97`.
That demonstrates a type mismatch can produce misleading class/reference symptoms;
it does not establish the precise cause of the original application's exception.
The application's client response class, generated proxy, actual list implementation,
peer versions, additional registrations and customizers still need checking against
its failing deployment.

## Minimal reproduction

The existing applications and annotation processor are reused:

- `nrpc-common/.../dto/SecurityFilterRecord.java`: the same eight String components.
- `nrpc-common/.../rmiservices/security/SecurityFiltersRmi.java`: synchronous shared
  interface, following existing Nimrod conventions.
- `nrpc-server-basic/.../security/SecurityFiltersService.java`: constructs deterministic,
  populated records in an `ArrayList`; no custom serializers or application workaround.
- `nrpc-client-basic/.../security/SecurityFilterReproduction.java`: opt-in
  `security-filter-repro` profile, invokes the generated client, checks every record
  and all eight component values, writes JSON, then exits.
- `nrpc-server-basic/.../security/SecurityFilterPayloadDiagnostics.java`: opt-in
  responder interceptor observing the final response data length. The usual Nimrod
  encoder and decoder perform all serialization.

The processor generates the required server method:

```java
@MessageMapping("getSecurityFilters")
public Mono<List<SecurityFilterRecord>> getSecurityFilters(Object[] params) throws Exception {
    final Object[] args = (params == null ? new Object[0] : params);
    int p0 = (int) args[0];
    return Mono.fromCallable(() -> service.getSecurityFilters(p0));
}
```

The `count` parameter permits independent list sizes while preserving the established
`Object[]` request argument convention. The generated client passes `List.class` to
`RemoteServerService.executeRmiMethod`. Spring unwraps the `Mono`; the response value
is the list itself. It is not wrapped in an `Object[]` response envelope. Only request
arguments use that array representation.

## Results before modifying library production code

| Response | Baseline result |
|---|---|
| Single populated record | Pass |
| `SecurityFilterRecord[1]` | Pass |
| `List<SecurityFilterRecord>`, 1 record | Fail: cannot instantiate `java.util.List` |
| `List<SecurityFilterRecord>`, 100 records | Fail: cannot instantiate `java.util.List` |
| List deliberately requested as `Object[]` | Fail: unregistered class ID 97 |

Raw results and full stacks are in `reproduction-results/before-fix/`.
Separate codec tests reproduced the failure with independent sender and receiver
pools, including a fresh pool per test. This rules out prior RMI calls being necessary
to trigger the list failure.

## Confirmed causes and library changes

In the sibling `../nimrod-ipc-rsock-parent/nimrod-ipc-rsock` project:

1. `KryoEncoder.encodeValue` and `serialize` used
   `writeObjectOrNull(output, value, value.getClass())`, omitting root class metadata.
   `KryoDecoder` read using the client's declared class. An `ArrayList` written with
   its concrete serializer was read as `List.class`, which cannot be constructed.
   Other incompatible target classes can interpret those same bytes using an entirely
   different serializer. The reactive encoder also used a different type-selection
   rule from the single-value encoder.
2. All encoder entry points now use `writeClassAndObject`, and every decoder entry
   point uses the matching `readClassAndObject`. The decoded runtime type must be
   assignable to the requested class. A wrong `Object[]` target now yields an explicit
   `DecodingException` naming the actual and requested types. Public method signatures
   and generated RMI APIs are preserved. Reactive null decoding completes empty.
3. A separate regression test exposed a streaming buffer ownership defect: emitted
   buffers referenced a reusable pooled byte array. After subsequent encodes, a
   retained earlier buffer decoded as a later message. Reactive encoding now copies
   its bytes before pooled state can be reused, matching existing synchronous behavior.
   This defect was demonstrated by a failing test before applying the copy fix; it
   has not been established as the cause of the application's original failure.

`KryoCommon` already enables reference tracking, disables required registration and
resets Kryo, output stream and output on release. `KryoInfo` supplies the independent
input/output buffers for each borrowed pool entry. Neither class needed changing.
Tests verify repeated object references within a message, repeated messages with a
pool size of one, and successful reuse after a rejected target type. There is no
evidence that a missing reference reset caused the reproduced list failure.

The annotation processor, RMI response handling, library timeout behavior and benchmark
implementations were not changed. The pre-existing connection-pool prewarm changes in
`RemoteServerService` were preserved.

## Wire compatibility

**This changes the Kryo wire format. Upgrade both RMI peers and all communicating
publishers/subscribers together. Mixed old/new codec versions are not supported by
this change.** Java public API compatibility does not imply wire compatibility.
Both peers must also retain compatible DTO definitions and consistent configured
Kryo registrations/customizers. Runtime class metadata does not reconcile conflicting
explicit registration IDs.

No artifacts were installed into the local Maven repository, committed or published.
The integration scripts prepend the freshly built core JAR to both application
classpaths. Ordinary IDE/Maven launches still resolve the installed snapshot until
the developer updates their normal local dependency build. The recorded JARs were:

| Version label | SHA-256 |
|---|---|
| Installed baseline 4.3.1-SNAPSHOT | `6a29af1d3c0d3b9b734b5d2adc302554793c760d297912b94d2d60f68bb03a1f` |
| Locally built patched 4.3.1-SNAPSHOT | `46ea4bafd2c93e615e52b4abbd295a9175c6b90f7518ee00156523ea9a848ad7` |

## Verification after the fix

All six positive real-RMI cases passed. Every list element and all String values were
checked. Response data sizes below are actual serialized RSocket data bytes, excluding
RSocket frame headers, metadata and TCP overhead. The 50,000-record response crosses
the 64 KiB initial codec buffer size without changing the payload or adding pagination.

| Response | Validated records | Serialized data bytes | RPC round trip (ms) |
|---|---:|---:|---:|
| Single record | 1 | 132 | 433.816 |
| Record array | 1 | 137 | 9.756 |
| List | 1 | 136 | 7.759 |
| List | 100 | 8,975 | 18.181 |
| List | 1,000 | 97,175 | 29.068 |
| List | 50,000 | 5,611,176 | 443.527 |

Round trip surrounds the generated client call, including its pool borrow, transport,
server record creation, serialization and client decoding. Executor startup and the
subsequent field validation are excluded. These are single diagnostic observations,
including cold initialization on the first call; **no performance conclusion follows**.

Other checks:

- Library reactor `mvn -o -pl nimrod-ipc-rsock -am package`: pass.
- New `KryoCodecTest`: 9 invocations pass, including all requested list sizes, separate
  pools, concrete records/arrays, all codec entry points, shared references, nulls,
  nested pub/sub payloads, incompatible requested types and retained streaming buffers.
- Existing `SubscriberServiceRecoveryTest`: all 14 invocations pass.
- Samples reactor `mvn -o verify dependency:build-classpath ...`: all 15 projects pass.
  This build uses the installed snapshot for dependency resolution; the real transport
  checks below explicitly use the patched JAR.
- Existing Nimrod pricing `/price` and `/parallel-price?threads=8`: pass, validating
  1 and 8 responses respectively with the patched core on both peers.
- Existing gRPC pricing checks: pass unchanged.
- Existing basic publisher and subscriber: wildcard `EURUSD.*` receives both
  `EURUSD.SPOT` and `EURUSD.1W` as populated `MarketData` records with the patched core.
  The check supplies the application name required by the current subscriber library
  as a CLI property; existing sample YAML is unchanged.
- Intentional wrong-target real RMI check after the fix: fails as expected with
  `Kryo root type java.util.ArrayList is not assignable to requested type [Ljava.lang.Object;`.

IntelliJ debugger sessions reported `breakpointsMuted: true`; agent logpoints therefore
provided no codec-value evidence. Pause/stack inspection was attempted, but the tool's
selected thread prevented the required local-variable inspection. Conclusions above
come from reproducible transport failures, full exception stacks, generated code,
codec inspection and regression tests. All agent breakpoints and sessions were removed;
the 26 pre-existing user breakpoints and global mute setting were preserved.

## Repeat the checks

From the library project, build without installation or publication:

```bash
cd ../nimrod-ipc-rsock-parent
mvn -o -pl nimrod-ipc-rsock -am package
cd ../nimrod-ipc-rsock-samples
```

Prepare the existing applications and runtime classpaths:

```bash
mvn -o verify dependency:build-classpath \
  -Dmdep.outputFile=target/benchmark-classpath.txt
```

Run all required positive cases against the patched core:

```bash
python3 scripts/reproductions/security_filters.py \
  --library-jar ../nimrod-ipc-rsock-parent/nimrod-ipc-rsock/target/nimrod-ipc-rsock-4.3.1-SNAPSHOT.jar \
  --counts 1 100 1000 50000 \
  --output reproduction-results/after-fix
```

Run the existing pricing and wildcard pub/sub checks:

```bash
python3 scripts/reproductions/check_existing_examples.py \
  --library-jar ../nimrod-ipc-rsock-parent/nimrod-ipc-rsock/target/nimrod-ipc-rsock-4.3.1-SNAPSHOT.jar
```

To reproduce the original local baseline while that unchanged JAR remains installed,
omit `--library-jar` and use `--counts 1 100`. Add `--include-type-probe` to intentionally
request a list as an array. Baseline failures and this negative probe return exit code
2; successful positive checks return 0. A future installed snapshot may already contain
the fix, so compare `library.json` rather than relying on the SNAPSHOT version label.

Each RPC has a 30-second caller watchdog; its worker is a daemon. The launcher enforces
a 30-second server-start deadline and an outer client-process deadline, then terminates
its own process groups, escalating to kill if necessary. Cancellation is not claimed
to impose a native server-side timeout. Optional `--server-only` mode is bounded to ten
minutes. No benchmark workloads or long-duration benchmark suite are executed.

Generated results are ignored by Git:

- `reproduction-results/before-fix/`: baseline JSON, both logs, launch and JAR manifests.
- `reproduction-results/after-fix/`: positive results for all requested sizes, both logs
  and manifests. `roundTripNanos` is an integer in nanoseconds;
  `serializedPayloadBytes` is an integer in bytes.
- `reproduction-results/after-fix-type-probe/`: expected incompatible-target failure.
- `reproduction-results/existing-examples/`: pub/sub logs, launch commands and checks JSON.
- `benchmark-results/legacy-pricing-1792852379992803000/`: passing existing pricing checks.

The runners overwrite their selected output files; use a new `--output` directory to
retain separate diagnostic runs.
