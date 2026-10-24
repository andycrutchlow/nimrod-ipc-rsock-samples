#!/usr/bin/env python3
"""Finite pricing and wildcard pub/sub regressions against an explicitly built core JAR."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'benchmarks'))
import run as helpers
import check_examples as pricing


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library-jar', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=helpers.ROOT / 'reproduction-results/existing-examples')
    args = parser.parse_args()
    library = args.library_jar.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    def java_command(module, main_class, arguments):
        command = helpers.java_command(module, main_class, arguments)
        if module.startswith(('nrpc-', 'pubsub-')):
            cp = command.index('-cp') + 1
            command[cp] = str(library) + os.pathsep + command[cp]
        return command

    # Exercise the existing checks without changing their workloads or benchmark launcher.
    pricing.java_command = java_command
    pricing_status = pricing.main()
    results = {'pricingPassed': pricing_status == 0, 'pubsubPassed': False,
               'libraryJar': str(library), 'sha256': hashlib.sha256(library.read_bytes()).hexdigest()}
    publisher = subscriber = None
    try:
        port = helpers.free_port('127.0.0.1')
        common = ['--spring.main.web-application-type=none', '--logging.level.root=WARN',
                  '--spring.application.name=security-filter-regression',
                  '--logging.level.com.nimrodtechs.samples=INFO']
        pub_command = java_command('pubsub-publisher-basic', 'com.nimrodtechs.samples.PublisherBasicApplication',
                                   [*common, f'--nimrod.rsock.publisher.port={port}'])
        sub_command = java_command('pubsub-subscriber-basic', 'com.nimrodtechs.samples.SubscriberBasicApplication',
                                   [*common, f'--nimrod.rsock.subscriber.setup[0]=publisher1,localhost,{port}'])
        helpers.save(output / 'launch.json', {'publisher': pub_command, 'subscriber': sub_command})
        with (output / 'publisher.log').open('w') as pub_log, (output / 'subscriber.log').open('w') as sub_log:
            publisher = subprocess.Popen(pub_command, cwd=helpers.ROOT, stdout=pub_log,
                                         stderr=subprocess.STDOUT, start_new_session=True)
            pricing.await_port(publisher, port)
            subscriber = subprocess.Popen(sub_command, cwd=helpers.ROOT, stdin=subprocess.PIPE,
                                          stdout=sub_log, stderr=subprocess.STDOUT, start_new_session=True)
            subscriber.stdin.write(b'1\n')  # Existing EURUSD.* console subscription.
            subscriber.stdin.flush()
            deadline = time.monotonic() + 30
            pattern = re.compile(r'RECEIVED -> EURUSD\.(SPOT|1W).*MarketData\[ccyPair=EURUSD, tenor=(SPOT|1W), '
                                 r'BidPrice=(1\.123\d+), AskPrice=(1\.123\d+), createdAt=(\d+)\]')
            while time.monotonic() < deadline:
                if publisher.poll() is not None or subscriber.poll() is not None:
                    raise RuntimeError('Pub/sub peer exited unexpectedly')
                records = pattern.findall((output / 'subscriber.log').read_text())
                if {record[0] for record in records} == {'SPOT', '1W'}:
                    assert all(subject == tenor and int(created) > 0
                               for subject, tenor, bid, ask, created in records)
                    results.update(pubsubPassed=True, receivedMessages=len(records), subjects=['EURUSD.SPOT', 'EURUSD.1W'])
                    break
                time.sleep(.1)
            else:
                raise TimeoutError('No validated wildcard messages from both subjects within 30 seconds')
    except Exception as error:
        results['pubsubError'] = str(error)
    finally:
        helpers.stop(subscriber)
        helpers.stop(publisher)
        helpers.save(output / 'checks.json', results)
    print(json.dumps(results, indent=2), flush=True)
    print('Pub/sub results:', output, flush=True)
    return 0 if results['pricingPassed'] and results['pubsubPassed'] else 1


if __name__ == '__main__':
    sys.exit(main())
