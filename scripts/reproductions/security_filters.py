#!/usr/bin/env python3
"""Real Nimrod RMI record/list reproduction using existing sample applications."""
import argparse
import importlib.util
import json
import hashlib
import re
import os
from pathlib import Path
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('benchmark_process_helpers', ROOT / 'scripts/benchmarks/run.py')
HELPERS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPERS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--counts', nargs='+', type=int, default=[1, 100])
    parser.add_argument('--output', type=Path, default=ROOT / 'reproduction-results/security-filters')
    parser.add_argument('--port', type=int)
    parser.add_argument('--library-jar', type=Path, help='Use an explicitly built local core JAR on both peers; does not install/publish it')
    parser.add_argument('--server-only', action='store_true')
    parser.add_argument('--include-type-probe', action='store_true',
                        help='Intentionally request a List response as Object[] (expected failure, exit 2)')
    args = parser.parse_args()
    if any(count < 1 or count > 50000 for count in args.counts):
        parser.error('counts must be between 1 and 50000')
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    (output / 'client-results.json').unlink(missing_ok=True)
    port = args.port or HELPERS.free_port('127.0.0.1')
    common = ['--spring.main.web-application-type=none', '--logging.level.root=WARN']
    server_command = HELPERS.java_command('nrpc-server-basic', 'com.nimrodtechs.samples.ServerBasicApplication', [
        *common, '--spring.profiles.active=security-filter-repro', '--nimrod.rsock.server.host=127.0.0.1',
        f'--nimrod.rsock.server.port={port}'])
    client_command = HELPERS.java_command('nrpc-client-basic', 'com.nimrodtechs.samples.ClientBasicApplication', [
        *common, '--spring.profiles.active=security-filter-repro',
        f'--nimrod.rsock.client-side.server1.port={port}', f'--nimrod.rsock.client-side.server2.port={port}',
        '--repro.counts=' + ','.join(map(str, args.counts)), f'--repro.output={output / "client-results.json"}'])
    if args.include_type_probe:
        client_command.append('--repro.include-type-probe=true')
    library = None
    if args.library_jar:
        library = args.library_jar.resolve(strict=True)
        for command in (server_command, client_command):
            cp = command.index('-cp') + 1
            command[cp] = str(library) + os.pathsep + command[cp]
    effective_library = next(Path(entry) for entry in server_command[server_command.index('-cp') + 1].split(os.pathsep)
                             if re.fullmatch(r'nimrod-ipc-rsock-\d.*\.jar', Path(entry).name))
    HELPERS.save(output / 'library.json', {'overrideJar': str(library) if library else None,
                 'effectiveJar': str(effective_library),
                 'sha256': hashlib.sha256(effective_library.read_bytes()).hexdigest()})
    HELPERS.save(output / 'launch.json', {'port': port, 'serverCommand': server_command, 'clientCommand': client_command})
    server = client = None
    try:
        with (output / 'server.log').open('w') as server_log, (output / 'client.log').open('w') as client_log:
            server = subprocess.Popen(server_command, cwd=ROOT, stdout=server_log, stderr=subprocess.STDOUT, start_new_session=True)
            deadline = time.monotonic() + 30
            while True:
                if server.poll() is not None: raise RuntimeError('Server exited; inspect server.log')
                if time.monotonic() > deadline: raise TimeoutError('Server startup timeout')
                try:
                    with socket.create_connection(('127.0.0.1', port), timeout=.3): break
                except OSError: time.sleep(.1)
            print(f'REPRO_SERVER_READY port={port}', flush=True)
            if args.server_only:
                server.wait(timeout=600)
                return server.returncode
            client = subprocess.Popen(client_command, cwd=ROOT, stdout=client_log, stderr=subprocess.STDOUT, start_new_session=True)
            try: client.wait(timeout=45 * (len(args.counts) + 2) + 30)
            except subprocess.TimeoutExpired: raise TimeoutError('Client watchdog expired')
            return client.returncode
    finally:
        HELPERS.stop(client); HELPERS.stop(server)
        result_path = output / 'client-results.json'
        if result_path.exists():
            results = json.loads(result_path.read_text())
            payloads = {int(call): int(size) for call, size in re.findall(r'SECURITY_FILTER_PAYLOAD call=(\d+) serializedBytes=(\d+)', (output / 'server.log').read_text())}
            for index, result in enumerate(results, 1):
                result['serializedPayloadBytes'] = payloads.get(index)
            HELPERS.save(result_path, results)
            print(result_path.read_text(), flush=True)
            print('Results:', output, flush=True)


if __name__ == '__main__':
    try: sys.exit(main())
    except KeyboardInterrupt: sys.exit(130)
