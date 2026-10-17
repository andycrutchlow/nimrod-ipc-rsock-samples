#!/usr/bin/env python3
"""Finite process supervisor. --full is required to run the 180-run matrix."""
import argparse
import csv
import datetime as dt
import itertools
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
SCENARIO = json.loads((ROOT / "benchmark-configs/unary.json").read_text())
TARGETS = {
    'nimrod': ('nrpc-server-basic', 'com.nimrodtechs.samples.ServerBasicApplication', 'nrpc-client-basic', 'com.nimrodtechs.samples.ClientBasicApplication'),
    'grpc': ('grpc-server-basic', 'com.nimrodtechs.samples.grpcserver.GrpcServerApplication', 'grpc-client-basic', 'com.nimrodtechs.samples.grpcclient.GrpcClientApplication'),
    'dubbo2': ('dubbo-server-basic', 'com.nimrodtechs.samples.benchmark.dubboserver.DubboServerApplication', 'dubbo-client-basic', 'com.nimrodtechs.samples.benchmark.dubboclient.DubboClientApplication'),
}


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, indent=2) + '\n')
    tmp.replace(path)


def summarize_resources(directory):
    summary_file = directory / 'client/summary.json'
    if not summary_file.exists():
        return
    measurement = json.loads(summary_file.read_text()).get('measurement')
    if not measurement:
        return
    start, end = measurement['startEpochMillis'], measurement['endEpochMillis']
    report = {'windowStartEpochMillis': start, 'windowEndEpochMillis': end, 'roles': {}}
    for role in ('client', 'server'):
        path = directory / role / 'resources.csv'
        rows = list(csv.DictReader(path.open())) if path.exists() else []
        samples = [r for r in rows if start <= int(r['epochMillis']) <= end]
        values = {'sampleCount': len(samples), 'samplingIntervalSeconds': 1}
        for key in ('heapUsed', 'heapCommitted', 'nonHeapUsed', 'directBytes', 'threads'):
            readings = [int(r[key]) for r in samples]
            values[key] = {'mean': sum(readings)/len(readings), 'peak': max(readings), 'last': readings[-1]} if readings else None
        # Interpolate cumulative process CPU at the common client measurement boundaries.
        def at(timestamp):
            before = [r for r in rows if int(r['epochMillis']) <= timestamp]
            after = [r for r in rows if int(r['epochMillis']) >= timestamp]
            if not before or not after:
                return None
            a, b = before[-1], after[0]
            t1, t2 = int(a['epochMillis']), int(b['epochMillis'])
            if t1 == t2:
                return int(b['cpuTimeNanos'])
            return int(a['cpuTimeNanos']) + (int(b['cpuTimeNanos'])-int(a['cpuTimeNanos']))*(timestamp-t1)/(t2-t1)
        c1, c2 = at(start), at(end)
        values['cpuCores'] = (c2-c1)/((end-start)*1e6) if c1 is not None and c2 is not None and end > start else None
        values['cpuMethod'] = 'cumulative JDK process CPU interpolated at measurement boundaries'
        osfile = directory / 'os-resources.csv'
        osrows = list(csv.DictReader(osfile.open())) if osfile.exists() else []
        rss = [int(r['rssKiB']) for r in osrows if r['role'] == role and start <= float(r['epochSeconds'])*1000 <= end]
        values['rssKiB'] = {'mean': sum(rss)/len(rss), 'peak': max(rss), 'last': rss[-1]} if rss else None
        values['missingReason'] = 'null metrics indicate no samples/bracketing observations in this short window' if not samples or values['cpuCores'] is None else None
        report['roles'][role] = values
    save(directory / 'resource-summary.json', report)


def java_command(module, main, arguments):
    cpfile = ROOT / module / 'target/benchmark-classpath.txt'
    if not cpfile.exists():
        raise RuntimeError('Build classpaths first; omit --skip-build.')
    cp = os.pathsep.join([str(ROOT / module / 'target/classes'), cpfile.read_text().strip()])
    compatibility = ['--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.util=ALL-UNNAMED', '--add-opens=java.base/java.text=ALL-UNNAMED'] if module.startswith('dubbo-') else []
    return [os.environ.get('JAVA', 'java'), '-Xms1g', '-Xmx1g', '-XX:+UseG1GC', *compatibility, '-cp', cp, main, *arguments]


def stop(process):
    if process is None or process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=8)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=3)


def free_port(host):
    with socket.socket() as sock:
        sock.bind((host, 0))
        return sock.getsockname()[1]


def snapshot_connections(process, directory, role):
    if process is None or process.poll() is not None:
        return
    if shutil.which('lsof'):
        result = subprocess.run(['lsof', '-nP', '-a', '-p', str(process.pid), '-iTCP', '-sTCP:ESTABLISHED'], capture_output=True, text=True, timeout=3)
        (directory / f'{role}-connections.txt').write_text(result.stdout + result.stderr)
        lines = [line for line in result.stdout.splitlines() if '(ESTABLISHED)' in line]
        save(directory / f'{role}-connections.json', {'observedEstablishedSockets': len(lines), 'source': 'lsof', 'timestamp': time.time(), 'lines': lines})
    else:
        save(directory / f'{role}-connections.json', {'observedEstablishedSockets': None, 'reason': 'lsof unavailable; inspect peer sockets manually'})


def run_case(base, library, size, concurrency, repetition, warmup, measurement, timeout=None, delay=0, expected_failure=False, host="127.0.0.1"):
    timeout = timeout or SCENARIO['timeoutMillis']
    name = f'{library}-{size}B-c{concurrency}-r{repetition}' + ('-timeout' if expected_failure else '')
    directory = base / name
    directory.mkdir(parents=True, exist_ok=False)
    server_dir, client_dir = directory / 'server', directory / 'client'
    server_dir.mkdir(); client_dir.mkdir()
    port = free_port(host)
    common = [f'--benchmark.run-id={name}', f'--benchmark.port={port}', f'--benchmark.host={host}',
              f'--benchmark.payload-bytes={size}', f'--benchmark.concurrency={concurrency}',
              f'--benchmark.warmup-seconds={warmup}', f'--benchmark.measurement-seconds={measurement}',
              f'--benchmark.timeout-millis={timeout}', f"--benchmark.drain-millis={2000 if expected_failure else SCENARIO['drainMillis']}", f"--benchmark.seed={SCENARIO['seed']}", '--spring.profiles.active=benchmark']
    server_module, server_main, client_module, client_main = TARGETS[library]
    server_cmd = java_command(server_module, server_main, [*common, f'--benchmark.output={server_dir}', f'--benchmark.server-delay-millis={delay}'])
    client_cmd = java_command(client_module, client_main, [*common, f'--benchmark.output={client_dir}'])
    save(directory / 'launch.json', {'library': library, 'port': port, 'serverCommand': server_cmd, 'clientCommand': client_cmd,
         'smokeOnly': warmup != 30 or measurement != 120, 'expectedFailure': expected_failure, 'runId': name})
    server = client = None
    start = time.monotonic()
    result = {'runId': name, 'library': library, 'valid': False, 'expectedFailure': expected_failure}
    try:
        with (directory / 'server.log').open('w') as slog, (directory / 'client.log').open('w') as clog, (directory / 'os-resources.csv').open('w') as oslog:
            writer = csv.writer(oslog); writer.writerow(['epochSeconds', 'role', 'pid', 'rssKiB', 'cpuPercent'])
            server = subprocess.Popen(server_cmd, cwd=ROOT, stdout=slog, stderr=subprocess.STDOUT, start_new_session=True)
            # Only a bind readiness gate; the client performs a validated application RPC separately.
            while True:
                if server.poll() is not None:
                    raise RuntimeError(f'Server exited {server.returncode}; see server.log')
                if time.monotonic() - start > 35:
                    raise TimeoutError('Server did not bind within 35 seconds')
                try:
                    with socket.create_connection((host, port), timeout=.3):
                        break
                except OSError:
                    time.sleep(.1)
            client = subprocess.Popen(client_cmd, cwd=ROOT, stdout=clog, stderr=subprocess.STDOUT, start_new_session=True)
            deadline = time.monotonic() + warmup + measurement + SCENARIO["drainMillis"]/1000 + 20
            observed = False
            while client.poll() is None:
                if server.poll() is not None:
                    raise RuntimeError(f'Server exited during run: {server.returncode}')
                if time.monotonic() > deadline:
                    raise TimeoutError('External client process watchdog expired')
                for role, process in [('server', server), ('client', client)]:
                    sample = subprocess.run(['ps', '-o', 'rss=,%cpu=', '-p', str(process.pid)], capture_output=True, text=True, timeout=2)
                    fields = sample.stdout.split()
                    if len(fields) == 2:
                        writer.writerow([time.time(), role, process.pid, *fields])
                oslog.flush()
                if not observed and (client_dir / 'phase.txt').exists() and (client_dir / 'phase.txt').read_text() in ('measurement', 'finished'):
                    snapshot_connections(server, directory, 'server'); snapshot_connections(client, directory, 'client'); observed = True
                time.sleep(.2)
            result['clientExitCode'] = client.returncode
            summary_file = client_dir / 'summary.json'
            summary = json.loads(summary_file.read_text()) if summary_file.exists() else None
            if expected_failure:
                phases = [summary.get(k) or {} for k in ['readiness', 'warmup', 'measurement']] if summary else []
                result['checkPassed'] = client.returncode != 0 and summary is not None and any(p.get('timeouts', 0) > 0 for p in phases)
                result['valid'] = False
                result['reason'] = 'Intentional delayed-handler timeout; no performance result'
            else:
                result['valid'] = client.returncode == 0 and summary is not None and summary.get('valid') is True
                result['checkPassed'] = result['valid']
                if summary:
                    result['measurement'] = summary.get('measurement')
            if not result.get('checkPassed'):
                result['error'] = 'Unexpected client failure or invalid accounting; inspect client/summary.json and logs'
    except Exception as exc:
        result['error'] = str(exc); result['checkPassed'] = False
    finally:
        stop(client); stop(server)
        if library == 'dubbo2' and not expected_failure and result.get('valid'):
            try:
                for role in ('client', 'server'):
                    observation = json.loads((directory / role / 'transport-observation.json').read_text())
                    codecs = observation['observedCodecs']
                    if len(codecs) != 1 or not all(v['serializeCalls'] > 0 and v['deserializeCalls'] > 0 and v['wireSerializerId'] == 8 for v in codecs.values()):
                        raise ValueError('Dubbo Kryo was not observed on both encode/decode paths')
            except Exception as exc:
                result.update(valid=False, checkPassed=False, error=f'Serializer verification failed: {exc}')
        summarize_resources(directory)
        result['elapsedSeconds'] = time.monotonic() - start
        save(directory / 'run-result.json', result)
    print(f"{name}: {'PASS' if result.get('checkPassed') else 'FAIL'}", flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--full', action='store_true', help='Explicitly run 180 cases with 30s warmup/120s measurement/5 repetitions')
    modes.add_argument('--smoke', action='store_true', help='Short correctness runs (default); no performance conclusions')
    modes.add_argument('--integration', action='store_true', help='All-size smoke plus deliberately delayed-handler timeout checks')
    parser.add_argument('--implementations', nargs='+', choices=TARGETS, default=list(TARGETS))
    parser.add_argument('--host', help='Local interface used by all implementations; auto-detected for Dubbo2')
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--sizes', nargs='+', type=int, choices=[128, 1024, 10240, 102400])
    parser.add_argument('--concurrency', nargs='+', type=int, choices=[1, 8, 32])
    args = parser.parse_args()
    if args.full and (args.sizes or args.concurrency or args.implementations != list(TARGETS)):
        parser.error('--full always runs the complete approved matrix; use --smoke for subsets')
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    base = (args.output or ROOT / 'benchmark-results' / stamp).resolve(); base.mkdir(parents=True, exist_ok=True)
    save(base / 'suite.json', {'mode': 'full' if args.full else 'integration' if args.integration else 'smoke', 'startedUtc': stamp, 'performanceConclusions': False})
    if not args.skip_build:
        cmd = ['mvn', *(['-o'] if args.offline else []), 'verify', 'dependency:build-classpath', '-Dmdep.outputFile=target/benchmark-classpath.txt']
        with (base / 'build.log').open('w') as log:
            subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
    host = args.host or '127.0.0.1'
    if not args.host and 'dubbo2' in args.implementations:
        command = java_command('dubbo-client-basic', 'com.nimrodtechs.samples.benchmark.dubbo.DubboSettings', [])
        probe = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=10, check=True)
        host = next(line.split('=', 1)[1] for line in probe.stdout.splitlines() if line.startswith('BENCHMARK_HOST='))
    save(base / 'topology.json', {'host': host, 'category': 'same-host separate JVMs', 'reason': 'Dubbo2 rewrites loopback URLs; all targets use the same interface'})
    sizes = args.sizes or SCENARIO['payloadBytes']
    concurrencies = args.concurrency or SCENARIO['concurrency']
    files = subprocess.run(['git', 'ls-files', '-c', '-o', '--exclude-standard'], cwd=ROOT, capture_output=True, text=True, check=True).stdout.splitlines()
    hashes = {name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in files if (ROOT/name).is_file() and not name.startswith('.idea/')}
    save(base / 'source-manifest.json', {'gitHead': subprocess.run(['git','rev-parse','HEAD'],cwd=ROOT,capture_output=True,text=True,check=True).stdout.strip(), 'files': hashes, 'scenario': SCENARIO})
    results = []
    # Rotate library order by repetition. Never run competing benchmarks in parallel.
    profile = SCENARIO['full' if args.full else 'smoke']
    for rep in range(1, profile['repetitions']+1):
        libraries = args.implementations[(rep-1) % len(args.implementations):] + args.implementations[:(rep-1) % len(args.implementations)]
        for size, concurrency in itertools.product(sizes, concurrencies):
            for library in libraries:
                results.append(run_case(base, library, size, concurrency, rep, profile['warmupSeconds'], profile['measurementSeconds'], host=host))
                save(base / 'suite-results.json', results)
    if args.integration:
        for library in args.implementations:
            results.append(run_case(base, library, 128, 1, 1, .1, .3, timeout=200, delay=1500, expected_failure=True, host=host))
            save(base / 'suite-results.json', results)
    failed = sum(not r.get('checkPassed') for r in results)
    print(f'{len(results)-failed}/{len(results)} checks passed. Results: {base}', flush=True)
    return 1 if failed else 0

if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
