#!/usr/bin/env python3
"""Check the original HTTP-triggered pricing examples with benchmark profiles disabled."""
import json
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from run import ROOT, TARGETS, java_command, free_port, stop, save


def await_port(process, port):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f'Application exited {process.returncode}')
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=.3):
                return
        except OSError:
            time.sleep(.1)
    raise TimeoutError('Application bind readiness failed')


def main():
    directory = ROOT / 'benchmark-results' / ('legacy-pricing-' + str(time.time_ns()))
    directory.mkdir(parents=True)
    results = []
    for library in ('nimrod', 'grpc'):
        case = directory / library; case.mkdir()
        server = client = None
        try:
            rpc_port, http_port = free_port('127.0.0.1'), free_port('127.0.0.1')
            smodule, smain, cmodule, cmain = TARGETS[library]
            sargs = ['--logging.level.root=WARN', '--spring.profiles.active=default']
            cargs = [f'--server.port={http_port}', '--logging.level.root=WARN', '--spring.profiles.active=default']
            if library == 'nimrod':
                sargs += [f'--nimrod.rsock.server.port={rpc_port}']
                cargs += [f'--nimrod.rsock.client-side.server1.port={rpc_port}', f'--nimrod.rsock.client-side.server2.port={rpc_port}']
                route = ''
                price = '1.1234'
            else:
                sargs += [f'--grpc.server.port={rpc_port}', '--grpc.server.address=127.0.0.1']
                cargs += [f'--grpc.client.pricing.address=static://127.0.0.1:{rpc_port}']
                route = '/grpc'
                price = '1.2345'
            with (case/'server.log').open('w') as slog, (case/'client.log').open('w') as clog:
                server = subprocess.Popen(java_command(smodule,smain,sargs),cwd=ROOT,stdout=slog,stderr=subprocess.STDOUT,start_new_session=True)
                await_port(server,rpc_port)
                client = subprocess.Popen(java_command(cmodule,cmain,cargs),cwd=ROOT,stdout=clog,stderr=subprocess.STDOUT,start_new_session=True)
                await_port(client,http_port)
                responses = []
                for endpoint in ('price','parallel-price'):
                    with urllib.request.urlopen(f'http://127.0.0.1:{http_port}{route}/{endpoint}?ccyPair=EUR%2FUSD&tenor=SPOT&threads=8',timeout=30) as response:
                        value = json.load(response)
                    values = value['responses'] if endpoint == 'parallel-price' else [value]
                    assert len(values) == (8 if endpoint == 'parallel-price' else 1)
                    assert all(v['price']==price and v['ccyPair']=='EUR/USD' and v['tenor']=='SPOT' and v['timeReceived']>=v['timeSent'] for v in values)
                    responses.append({'endpoint':endpoint,'validatedResponses':len(values)})
                results.append({'library':library,'passed':True,'checks':responses})
        except Exception as exc:
            results.append({'library':library,'passed':False,'error':str(exc)})
        finally:
            stop(client);stop(server)
    save(directory/'checks.json',results)
    for result in results:
        print(result,flush=True)
    print('Results:',directory)
    return 0 if all(result['passed'] for result in results) else 1

if __name__ == '__main__':
    sys.exit(main())
