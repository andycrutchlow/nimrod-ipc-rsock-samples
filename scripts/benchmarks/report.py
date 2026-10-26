#!/usr/bin/env python3
"""Audit published-baseline and regenerate tables, JSON and standalone SVGs.

Python standard library only. Never launches a JVM or writes benchmark inputs.
Use --check in CI; --library-docs optionally updates the library chart copy.
"""
import argparse
import collections
import csv
import hashlib
import itertools
import json
import math
from pathlib import Path
import statistics
import sys
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[2]
DATASET = ROOT / "benchmark-results/published-baseline"
LIBRARIES = ("nimrod", "grpc", "dubbo2")
LABELS = {"nimrod": "Nimrod IPC", "grpc": "gRPC Java", "dubbo2": "Apache Dubbo2"}
COLORS = {"nimrod": "#087e8b", "grpc": "#4667b1", "dubbo2": "#bf6516"}
SIZES = (128, 1024, 10240, 102400)
CONCURRENCIES = (1, 8, 32)
PHASES = ("readiness", "warmup", "measurement")
COUNTS = ("attempted", "completed", "drainCompleted", "errors", "timeouts", "invalidResponses", "unresolved")
PAYLOADS = dict(zip(SIZES, ("128 B", "1 KB", "10 KB", "100 KB")))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(path.read_text())


def cpu_cores(path, start, end):
    """Independent reconstruction of run.py's boundary-interpolated process CPU."""
    with path.open() as stream:
        rows = list(csv.DictReader(stream))
    def at(t):
        before = [r for r in rows if int(r["epochMillis"]) <= t]
        after = [r for r in rows if int(r["epochMillis"]) >= t]
        if not before or not after:
            return None
        a, b = before[-1], after[0]
        t1, t2 = int(a["epochMillis"]), int(b["epochMillis"])
        if t1 == t2:
            return int(b["cpuTimeNanos"])
        return int(a["cpuTimeNanos"]) + (int(b["cpuTimeNanos"]) - int(a["cpuTimeNanos"])) * (t - t1) / (t2 - t1)
    first, last = at(start), at(end)
    return (last - first) / ((end - start) * 1e6) if first is not None and last is not None else None


def audit():
    expected_matrix = dict(implementations=list(LIBRARIES), payloadBytes=list(SIZES),
                           concurrency=list(CONCURRENCIES), warmupSeconds=30,
                           measurementSeconds=120, repetitions=5, performanceRuns=180,
                           timeoutChecks=0, totalRuns=180)
    suite = read(DATASET / "suite.json")
    source = read(DATASET / "source-manifest.json")
    require(suite["mode"] == "full" and suite["selectedMatrix"] == expected_matrix, "Unexpected suite matrix")
    require(source["selectedMatrix"] == expected_matrix, "Source manifest matrix differs")
    require(source["scenario"]["full"] == dict(warmupSeconds=30, measurementSeconds=120, repetitions=5)
            and source["scenario"]["payloadBytes"] == list(SIZES)
            and source["scenario"]["concurrency"] == list(CONCURRENCIES), "Source scenario differs")
    expected = {f"{lib}-{size}B-c{c}-r{rep}" for lib, size, c, rep in
                itertools.product(LIBRARIES, SIZES, CONCURRENCIES, range(1, 6))}
    directories = {p.parent.name for p in DATASET.glob("*/run-result.json")}
    require(directories == expected, f"Run set differs: missing={expected-directories}, extra={directories-expected}")
    results = read(DATASET / "suite-results.json")
    require(len(results) == 180 and {r["runId"] for r in results} == expected, "Suite run list differs")
    require(all(r == read(DATASET / r["runId"] / "run-result.json") for r in results), "Suite/run results differ")
    groups = collections.defaultdict(list)
    counts = collections.defaultdict(collections.Counter)
    environments, arguments, artifacts, sockets = {}, {}, {}, collections.defaultdict(set)
    for lib, size, c, rep in itertools.product(LIBRARIES, SIZES, CONCURRENCIES, range(1, 6)):
        name = f"{lib}-{size}B-c{c}-r{rep}"
        directory = DATASET / name
        result, launch = read(directory / "run-result.json"), read(directory / "launch.json")
        client, server = read(directory / "client/summary.json"), read(directory / "server/server-summary.json")
        require(result["valid"] and result["checkPassed"] and result["clientExitCode"] == 0
                and not result["expectedFailure"] and not result.get("error"), f"Failed run: {name}")
        require(result["selectedMatrix"] == launch["selectedMatrix"] == expected_matrix
                and not launch["smokeOnly"] and not launch["expectedFailure"], f"Run matrix differs: {name}")
        require(client["valid"] and not client["resourceSamplingError"] and server["errors"] == 0
                and not server["resourceSamplingError"], f"Invalid summary: {name}")
        for phase in PHASES:
            p = client[phase]
            require(p["valid"] and not p["histogramOverflowOrRecordingError"]
                    and all(p[k] == 0 for k in ("errors", "timeouts", "invalidResponses", "unresolved")), f"Invalid {phase}: {name}")
            require(p["attempted"] == p["completed"] + p["drainCompleted"]
                    and p["latencyNanos"]["count"] == p["completed"]
                    and p["drainLatencyNanos"]["count"] == p["drainCompleted"], f"Bad accounting: {name}/{phase}")
            require(math.isclose(p["completedRpcsPerSecond"], p["completed"] / p["windowSeconds"]), f"Throughput mismatch: {name}")
            q = p["latencyNanos"]
            require(0 < q["p50"] <= q["p95"] <= q["p99"] <= q["max"], f"Bad quantiles: {name}")
            counts[(lib, phase)].update({k: p[k] for k in COUNTS})
        require(client["warmup"]["windowSeconds"] == 30 and client["measurement"]["windowSeconds"] == 120, f"Bad durations: {name}")
        require(server["processed"] == sum(client[p]["attempted"] for p in PHASES), f"Server count differs: {name}")
        m = client["measurement"]
        require(result["measurement"] == m, f"Copied measurement differs: {name}")
        resources = read(directory / "resource-summary.json")
        require(resources["windowStartEpochMillis"] == m["startEpochMillis"]
                and resources["windowEndEpochMillis"] == m["endEpochMillis"], f"CPU boundaries differ: {name}")
        cpu = {}
        for role in ("client", "server"):
            manifest = read(directory / role / "manifest.json")
            environment = {k: manifest[k] for k in ("javaVersion", "javaVendor", "os", "osVersion", "architecture", "availableProcessors")}
            require(not environments or environment == next(iter(environments.values())), f"Environment differs: {name}")
            environments[lib] = environment
            args = manifest["jvmArguments"]
            require(lib not in arguments or arguments[lib] == args, f"JVM flags differ: {name}")
            arguments[lib] = args
            jars = {Path(a["path"]).name: a["sha256"] for a in manifest["classpathArtifacts"]}
            # Client and provider have different app jars; verify shared dependency hashes.
            for jar, digest in jars.items():
                key = (lib, jar)
                require(key not in artifacts or artifacts[key] == digest, f"Artifact changed: {name}/{jar}")
                artifacts[key] = digest
            implementation = manifest["implementation"]
            required_jars = {
                "nimrod": ("kryo5-5.5.0.jar", "rsocket-core-1.1.5.jar"),
                "grpc": ("grpc-core-1.58.0.jar", "grpc-netty-shaded-1.58.0.jar", "protobuf-java-3.25.3.jar"),
                "dubbo2": ("dubbo-2.7.23.jar", "kryo-4.0.2.jar", "kryo-serializers-0.42.jar"),
            }
            require(all(jar in jars for jar in required_jars[lib]), f"Framework versions differ: {name}/{role}")
            if lib == "nimrod":
                # Preserve the measured version independently of today's Maven dependency selection.
                require(f"nimrod-ipc-rsock-{implementation['version']}.jar" in jars,
                        f"Archived Nimrod version/artifact mismatch: {name}/{role}")
            require(implementation["library"] == lib, f"Implementation differs: {name}/{role}")
            if role == "client":
                config = manifest["config"]
                require(config == dict(runId=name, payloadBytes=size, concurrency=c, warmupSeconds=30,
                                       measurementSeconds=120, timeoutMillis=5000, drainMillis=30000,
                                       seed=42, mode="closed-loop"), f"Client config differs: {name}")
                require((lib != "nimrod" or int(implementation["requesterPoolSize"]) == c)
                        and (lib != "grpc" or implementation["channelSharing"] == "one channel, multiplexed RPCs")
                        and (lib != "dubbo2" or implementation["connections"] == 1), f"Connection configuration differs: {name}")
            else:
                require(manifest["faultInjectionDelayMillis"] == 0, f"Fault injection in {name}")
                require(implementation["serverWorkers"] == 8, f"Worker metadata differs: {name}")
            observed = read(directory / f"{role}-connections.json")["observedEstablishedSockets"]
            require(observed == (c if lib == "nimrod" else 1), f"Unexpected sockets: {name}/{role}")
            sockets[(lib, c)].add(observed)
            cpu[role] = cpu_cores(directory / role / "resources.csv", m["startEpochMillis"], m["endEpochMillis"])
            recorded = resources["roles"][role]["cpuCores"]
            require((cpu[role] is None and recorded is None) or
                    (cpu[role] is not None and recorded is not None and math.isfinite(cpu[role])
                     and cpu[role] > 0 and math.isclose(cpu[role], recorded, rel_tol=1e-10)), f"CPU differs: {name}/{role}")
            if lib == "dubbo2":
                codecs = read(directory / role / "transport-observation.json")["observedCodecs"]
                require(len(codecs) == 1 and all(v["wireSerializerId"] == 8 and v["serializeCalls"] > 0
                        and v["deserializeCalls"] > 0 for v in codecs.values()), f"Dubbo Kryo not verified: {name}/{role}")
        groups[(lib, size, c)].append(dict(repetition=rep, throughput=m["completedRpcsPerSecond"],
            **{f"{q}Millis": m["latencyNanos"][q] / 1e6 for q in ("p50", "p95", "p99")},
            clientCpuCores=cpu["client"], serverCpuCores=cpu["server"],
            totalCpuCores=sum(cpu.values()) if all(v is not None for v in cpu.values()) else None))
    rows = []
    for c, size, lib in itertools.product(CONCURRENCIES, SIZES, LIBRARIES):
        runs = groups[(lib, size, c)]
        require(len(runs) == 5, "Missing repetition")
        metrics = {key: statistics.median(r[key] for r in runs) if all(r[key] is not None for r in runs) else None
                   for key in runs[0] if key != "repetition"}
        rows.append(dict(library=lib, payloadBytes=size, concurrency=c, repetitions=runs,
                         medians=metrics, throughputMin=min(r["throughput"] for r in runs),
                         throughputMax=max(r["throughput"] for r in runs)))
    fingerprint = hashlib.sha256()
    for path in sorted(p for p in DATASET.rglob("*") if p.is_file()):
        fingerprint.update(str(path.relative_to(DATASET)).encode() + b"\0" + hashlib.sha256(path.read_bytes()).digest())
    return dict(dataset="benchmark-results/published-baseline", datasetSha256=fingerprint.hexdigest(),
                sourceGitHead=source["gitHead"], startedUtc=suite["startedUtc"], selectedMatrix=expected_matrix,
                environment=next(iter(environments.values())), jvmArguments=arguments,
                classpathArtifacts={lib: {jar: digest for (l, jar), digest in sorted(artifacts.items()) if l == lib} for lib in LIBRARIES},
                counts=[dict(library=lib, phase=phase, **counts[(lib, phase)]) for lib, phase in itertools.product(LIBRARIES, PHASES)],
                observedSockets=[dict(library=lib, concurrency=c, counts=sorted(sockets[(lib, c)])) for lib, c in itertools.product(LIBRARIES, CONCURRENCIES)],
                rows=rows)


def table(headers, rows):
    return "\n".join(["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |",
                      *("| " + " | ".join(map(str, row)) + " |" for row in rows)])


def tables(summary):
    rows = summary["rows"]
    lookup = {(r["library"], r["payloadBytes"], r["concurrency"]): r["medians"] for r in rows}
    throughput = table(["Concurrency", "Payload", "Nimrod RPC/s", "gRPC RPC/s", "Dubbo2 RPC/s", "Nimrod / gRPC"],
        [(c, PAYLOADS[size], *(f'{lookup[lib,size,c]["throughput"]:,.0f}' for lib in LIBRARIES),
          f'{lookup["nimrod",size,c]["throughput"]/lookup["grpc",size,c]["throughput"]:.2f}×') for c, size in itertools.product(CONCURRENCIES, SIZES)])
    latency = table(["Concurrency", "Payload", "Implementation", "p50 (ms)", "p95 (ms)", "p99 (ms)"],
        [(r["concurrency"], PAYLOADS[r["payloadBytes"]], LABELS[r["library"]],
          *(f'{r["medians"][q+"Millis"]:.3f}' for q in ("p50", "p95", "p99"))) for r in rows])
    cpu = table(["Concurrency", "Payload", "Implementation", "Client cores", "Server cores", "Combined cores"],
        [(r["concurrency"], PAYLOADS[r["payloadBytes"]], LABELS[r["library"]],
          *(f'{r["medians"][role+"CpuCores"]:.2f}' if r["medians"][role+"CpuCores"] is not None else "—"
            for role in ("client", "server", "total"))) for r in rows])
    counts = table(["Implementation", "Phase", "Attempted", "Completed in window", "Completed in drain", "Errors", "Timeouts", "Invalid", "Unresolved"],
        [(LABELS[r["library"]], r["phase"], *(f'{r[k]:,}' for k in COUNTS)) for r in summary["counts"]])
    return {"throughput": throughput, "latency": latency, "cpu": cpu, "counts": counts}


def chart(summary, concurrency, metric, title, unit):
    """Grouped horizontal bars, linear zero-based axis and inline values."""
    lookup = {(r["library"], r["payloadBytes"]): r["medians"][metric]
              for r in summary["rows"] if r["concurrency"] == concurrency}
    maximum = max(lookup.values())
    magnitude = 10 ** math.floor(math.log10(maximum))
    ceiling = math.ceil(maximum / magnitude) * magnitude
    left, width, top = 100, 585, 112
    elements = ['<svg xmlns="http://www.w3.org/2000/svg" width="820" height="530" viewBox="0 0 820 530" role="img" aria-labelledby="title desc">',
                f'<title id="title">{escape(title)}</title>',
                '<desc id="desc">Medians of five repetitions from published-baseline. '
                + escape("; ".join(f'{PAYLOADS[size]}: ' + ", ".join(f'{LABELS[lib]} {lookup[lib,size]:,.3f} {unit}' for lib in LIBRARIES) for size in SIZES)) + '</desc>',
                '<rect width="820" height="530" fill="#ffffff"/>',
                '<g font-family="Arial, Helvetica, sans-serif" fill="#172b3a">',
                f'<text x="24" y="32" font-size="20" font-weight="700">{escape(title)}</text>',
                f'<text x="24" y="55" font-size="13">Concurrency {concurrency} · median of 5 runs · {escape(unit)}</text>']
    for index, lib in enumerate(LIBRARIES):
        x = 24 + index * 220
        elements += [f'<rect x="{x}" y="73" width="13" height="13" rx="2" fill="{COLORS[lib]}"/>',
                     f'<text x="{x+20}" y="84" font-size="13">{LABELS[lib]}</text>']
    for tick in range(6):
        x, value = left + width * tick / 5, ceiling * tick / 5
        label = f'{value:,.0f}' if metric == "throughput" else f'{value:g}'
        elements += [f'<line x1="{x}" y1="104" x2="{x}" y2="455" stroke="#e1e7eb"/>',
                     f'<text x="{x}" y="478" text-anchor="middle" font-size="12">{label}</text>']
    for index, size in enumerate(SIZES):
        y = top + index * 88
        elements.append(f'<text x="85" y="{y+36}" text-anchor="end" font-size="14" font-weight="700">{PAYLOADS[size]}</text>')
        for j, lib in enumerate(LIBRARIES):
            value, bar_y = lookup[lib, size], y + j * 23
            bar_width = width * value / ceiling
            label = f'{value:,.0f}' if metric == "throughput" else f'{value:.2f}'
            elements += [f'<rect x="{left}" y="{bar_y}" width="{bar_width:.3f}" height="18" rx="2" fill="{COLORS[lib]}"/>',
                         f'<text x="{left+bar_width+7:.3f}" y="{bar_y+14}" font-size="12">{label}</text>']
    elements += [f'<text x="100" y="501" font-size="12">{escape(unit)} · higher is better</text>' if metric == "throughput" else
                 f'<text x="100" y="501" font-size="12">{escape(unit)} · interpret alongside throughput</text>',
                 '<text x="24" y="521" font-size="11" fill="#536570">Same-host Java 21 · 30 s warmup / 120 s measurement · 1 KB = 1,024 B</text>', '</g>', '</svg>']
    return "\n".join(elements) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check generated files without writing")
    parser.add_argument("--library-docs", type=Path, help="Library checkout or staged documentation directory")
    args = parser.parse_args()
    summary = audit()
    output = ROOT / "docs/benchmarks"
    content = tables(summary)
    files = {output / "summary.json": json.dumps(summary, indent=2) + "\n",
             output / "tables.md": "<!-- Generated by scripts/benchmarks/report.py; do not edit. -->\n\n" +
             "\n\n".join(f"## {name.title()}\n\n{value}" for name, value in content.items()) + "\n"}
    for c in CONCURRENCIES:
        files[output / f"throughput-c{c}.svg"] = chart(summary, c, "throughput", "RPC throughput by payload", "completed RPC/s")
    files[output / "p99-c32.svg"] = chart(summary, 32, "p99Millis", "RPC p99 latency by payload", "milliseconds")
    files[output / "cpu-c32.svg"] = chart(summary, 32, "totalCpuCores", "Combined client + server CPU", "CPU cores")
    report = ROOT / "BENCHMARKS.md"
    text = report.read_text()
    for name, value in content.items():
        begin, end = f"<!-- generated:{name}:start -->", f"<!-- generated:{name}:end -->"
        require(text.count(begin) == text.count(end) == 1, f"Missing or duplicate {name} markers in BENCHMARKS.md")
        before, rest = text.split(begin)
        _, after = rest.split(end)
        text = before + begin + "\n\n" + value + "\n\n" + end + after
    files[report] = text
    if args.library_docs:
        files[args.library_docs / "docs/benchmarks/throughput-c32.svg"] = files[output / "throughput-c32.svg"]
    stale = []
    for path, text in files.items():
        if args.check:
            if not path.exists() or path.read_text() != text:
                stale.append(str(path))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
    require(not stale, "Stale generated outputs: " + ", ".join(stale))
    print(f"Audited 180 successful runs; {'checked' if args.check else 'generated'} {len(files)} outputs.")
    print("Dataset SHA-256: " + summary["datasetSha256"])


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, OSError) as exc:
        sys.exit(f"Report failed: {exc}")
