# Nimrod IPC 4.3.1 release validation

Validated on **26 October 2026** using Maven 3.8.1 and macOS 14.5 x86_64. The samples use the published **4.3.1** artifacts under `io.github.andycrutchlow`. Java 21 is the minimum and compiler target; Java 25 was additionally tested as described below.

## Maven Central resolution

An initially empty temporary Maven local repository was used for the complete build. A temporary settings file mirrored all repositories to `https://repo.maven.apache.org/maven2`, so neither a locally installed Nimrod JAR nor another remote repository could satisfy resolution. The developer's `~/.m2/repository` was not modified and the library source was not built or installed.

All four release coordinates resolved from Central:

| Artifact | Packaging | Version |
| --- | --- | --- |
| `nimrod-ipc-rsock` | JAR | 4.3.1 |
| `nimrod-ipc-rsock-annotations` | JAR | 4.3.1 |
| `nimrod-ipc-rsock-processor` | JAR, annotation processor | 4.3.1 |
| `nimrod-ipc-rsock-parent` | Imported POM | 4.3.1 |

The resolved JARs and POMs matched independent HTTPS downloads from Central by SHA-256. Maven's `_remote.repositories` markers identify the Central-only mirror for every artifact. No Nimrod snapshot version exists in the isolated cache or generated application classpaths. The samples' own `1.0-SNAPSHOT` reactor version remains unchanged; it is not a Nimrod dependency.

To repeat resolution/build checks from the samples repository root, using a fresh cache:

```bash
validation_dir="$(mktemp -d)"
cat > "$validation_dir/settings.xml" <<'XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors>
    <mirror>
      <id>release-central</id>
      <url>https://repo.maven.apache.org/maven2</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>
</settings>
XML

mvn -B -s "$validation_dir/settings.xml" \
  -Dmaven.repo.local="$validation_dir/repository" \
  clean verify dependency:build-classpath \
  -Dmdep.outputFile=target/benchmark-classpath.txt

mvn -B -o -s "$validation_dir/settings.xml" \
  -Dmaven.repo.local="$validation_dir/repository" \
  dependency:tree '-Dincludes=io.github.andycrutchlow:*'
```

Normal users only need the [README build command](../README.md#build-once); the isolated-cache procedure is a release verification step.

## Java compatibility evidence

| Check | Java 21 | Java 25 |
| --- | --- | --- |
| Runtime | Oracle GraalVM `21.0.8+12-LTS-jvmci-23.1-b72` | OpenJDK `25+36-3489` |
| Clean build | All 15 reactor projects passed | All 15 reactor projects passed |
| Java tests | 7 passed; no failures/errors/skips | 7 passed; no failures/errors/skips |
| Produced application bytecode | All 114 production classes: major version 65 (Java 21) | All 114 production classes: major version 65 (Java 21) |
| Original Nimrod and gRPC pricing | Single and eight-way parallel endpoints passed; 18 validated responses | Single and eight-way parallel endpoints passed; 18 validated responses |

The Java 25 Surefire reports identify the forked test runtime as `25+36-3489`; this was an actual build/test run, not a claim inferred from source compatibility. Both builds used the same isolated Central-resolved dependency cache and retained compiler release **21**. Java 25 ran offline after the Java 21 build populated that cache.

To repeat with installed JDKs, set `JAVA_HOME` and put that JDK's `bin` first on `PATH`, then run the isolated build above. To run the pricing applications with the chosen JDK after building:

```bash
JAVA="$JAVA_HOME/bin/java" python3 scripts/benchmarks/check_examples.py
```

The existing wildcard pub/sub regression was also checked under Java 25 with the published core JAR: both `EURUSD.SPOT` and `EURUSD.1W` were received and validated. That check uses:

```bash
JAVA="$JAVA_HOME/bin/java" python3 scripts/reproductions/check_existing_examples.py \
  --library-jar "$validation_dir/repository/io/github/andycrutchlow/nimrod-ipc-rsock/4.3.1/nimrod-ipc-rsock-4.3.1.jar" \
  --output "$validation_dir/examples-pubsub"
```

These checks support the documented Java 21 minimum and additional Java 25 testing. They do not certify every JDK/vendor/platform combination, all Dubbo2 runtime paths or Java 25 benchmark performance. Maven 3.8.1's bundled Jansi/Guava and some application dependencies emit native-access/Unsafe deprecation warnings on Java 25; the tested builds and examples passed without changing unrelated dependencies or adding JVM flags to suppress warnings.

## Published versus benchmarked binaries

The canonical benchmark manifests remain unchanged and identify the core pre-release JAR by SHA-256. The downloaded Maven Central 4.3.1 JAR has a different hash:

| Core binary | SHA-256 |
| --- | --- |
| Archived benchmark JAR | `46ea4bafd2c93e615e52b4abbd295a9175c6b90f7518ee00156523ea9a848ad7` |
| Published 4.3.1 JAR | `57281d859c42d7c3fd350ce67148e85eb4c70682ee396659aead03afa0e399b6` |

A retained local pre-release JAR was first verified against the archived hash. ZIP-entry comparisons with the released JAR found changed class bytes in addition to Maven/manifest metadata. The binaries are **not identical**, and release performance equivalence has not been established. See [benchmark provenance](../BENCHMARKS.md#release-and-benchmark-binary-provenance). No benchmark case was run as part of release preparation.

The report generator was checked against all 180 canonical runs. All 4,205 archive files, all five SVGs, the generated summary JSON and the generated tables remained byte-for-byte unchanged. Nine Python runner tests passed without launching benchmark cases.

## Scope and publication notes

- The single root `nimrod.ipc.version` property changed from `4.3.1-SNAPSHOT` to `4.3.1`; module dependencies and processor paths inherit it.
- Future benchmark client/server version labels come from the Maven-filtered `benchmark.nimrod-version` property. Workload, timing, concurrency and measurement logic are unchanged.
- The report generator reads the measured Nimrod version from the archived manifest, independently of the current POM. Snapshot labels remain only where they identify historical evidence or describe this version transition.
- The canonical raw archive is still Git-ignored and needs a separately published location for independent public verification; see [the benchmark report](../BENCHMARKS.md#dataset-and-provenance).
- Prepare the reviewed changes for `main` through the repository's normal review process. Release preparation does not commit, merge, tag or push changes.
