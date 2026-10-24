"""Matrix orchestration tests: never launch Maven, Java, sockets or timed workloads."""
import contextlib
import importlib.util
import io
import itertools
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


RUNNER_PATH = Path(__file__).resolve().parents[1] / 'run.py'
SPEC = importlib.util.spec_from_file_location('benchmark_runner', RUNNER_PATH)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class MatrixTests(unittest.TestCase):
    def execute(self, arguments):
        """Exercise main/manifest writing with all external execution replaced by fakes."""
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)

            def fake_command(command, **kwargs):
                if command[0] == 'mvn':
                    return subprocess.CompletedProcess(command, 0)
                if command[:2] == ['git', 'ls-files']:
                    return subprocess.CompletedProcess(command, 0, stdout='')
                if command[:2] == ['git', 'rev-parse']:
                    return subprocess.CompletedProcess(command, 0, stdout='test-revision\n')
                if command[0] == 'java-host-probe':
                    return subprocess.CompletedProcess(command, 0, stdout='BENCHMARK_HOST=127.0.0.1\n')
                raise AssertionError(f'Unexpected subprocess: {command}')

            def fake_case(*args, **kwargs):
                return {'checkPassed': True, 'selectedMatrix': kwargs['selected_matrix']}

            with mock.patch.object(runner.subprocess, 'run', side_effect=fake_command) as commands, \
                    mock.patch.object(runner.subprocess, 'Popen', side_effect=AssertionError('Must not launch benchmark processes')), \
                    mock.patch.object(runner, 'java_command', return_value=['java-host-probe']), \
                    mock.patch.object(runner, 'run_case', side_effect=fake_case) as cases, \
                    contextlib.redirect_stdout(io.StringIO()):
                status = runner.main([*arguments, '--output', str(output)])

            self.assertEqual(0, status)
            suite = json.loads((output / 'suite.json').read_text())
            manifest = json.loads((output / 'source-manifest.json').read_text())
            results = json.loads((output / 'suite-results.json').read_text())
            matrix = suite['selectedMatrix']
            self.assertEqual(matrix, manifest['selectedMatrix'])
            self.assertEqual(matrix['totalRuns'], len(results))
            self.assertTrue(all(result['selectedMatrix'] == matrix for result in results))
            return suite, list(cases.call_args_list), list(commands.call_args_list)

    def assert_performance_cases(self, calls, libraries, sizes, concurrency, repetitions, warmup, measurement):
        expected = set(itertools.product(libraries, sizes, concurrency, range(1, repetitions + 1)))
        actual = []
        for call in calls:
            _, library, size, workers, repetition, warm, measured = call.args
            actual.append((library, size, workers, repetition))
            self.assertEqual(warmup, warm)
            self.assertEqual(measurement, measured)
            self.assertFalse(call.kwargs.get('expected_failure', False))
        self.assertEqual(len(expected), len(actual))
        self.assertEqual(expected, set(actual))

    def test_default_full_retains_180_runs_and_library_rotation(self):
        suite, calls, _ = self.execute(['--full', '--skip-build'])
        self.assertEqual('full', suite['mode'])
        matrix = suite['selectedMatrix']
        self.assertEqual(180, matrix['performanceRuns'])
        self.assertEqual(0, matrix['timeoutChecks'])
        self.assert_performance_cases(calls, list(runner.TARGETS), [128, 1024, 10240, 102400], [1, 8, 32], 5, 30, 120)
        for repetition in range(1, 6):
            libraries = [call.args[1] for call in calls if call.args[2:5] == (128, 1, repetition)]
            default = list(runner.TARGETS)
            shift = (repetition - 1) % len(default)
            self.assertEqual(default[shift:] + default[:shift], libraries)

    def test_requested_nimrod_command_runs_40_full_duration_cases(self):
        # Same options as the user's command, with output redirected to a temporary directory.
        suite, calls, commands = self.execute([
            '--full', '--implementations', 'nimrod',
            '--sizes', '128', '1024', '10240', '102400',
            '--concurrency', '8', '32', '--offline',
        ])
        self.assertEqual('full', suite['mode'])
        self.assertEqual(40, suite['selectedMatrix']['totalRuns'])
        self.assert_performance_cases(calls, ['nimrod'], [128, 1024, 10240, 102400], [8, 32], 5, 30, 120)
        maven_calls = [call.args[0] for call in commands if call.args[0][0] == 'mvn']
        self.assertEqual([['mvn', '-o', 'verify', 'dependency:build-classpath', '-Dmdep.outputFile=target/benchmark-classpath.txt']], maven_calls)

    def test_each_full_filter_works_independently(self):
        options = [
            (['--implementations', 'nimrod'], ['nimrod'], [128, 1024, 10240, 102400], [1, 8, 32], 60),
            (['--sizes', '1024'], list(runner.TARGETS), [1024], [1, 8, 32], 45),
            (['--concurrency', '32'], list(runner.TARGETS), [128, 1024, 10240, 102400], [32], 60),
        ]
        for filters, libraries, sizes, workers, count in options:
            with self.subTest(filters=filters):
                suite, calls, _ = self.execute(['--full', '--skip-build', *filters])
                self.assertEqual(count, suite['selectedMatrix']['totalRuns'])
                self.assert_performance_cases(calls, libraries, sizes, workers, 5, 30, 120)

    def test_filtered_full_rotates_selected_libraries_only(self):
        _, calls, _ = self.execute([
            '--full', '--skip-build', '--implementations', 'grpc', 'nimrod',
            '--sizes', '102400', '--concurrency', '8',
        ])
        self.assert_performance_cases(calls, ['grpc', 'nimrod'], [102400], [8], 5, 30, 120)
        self.assertEqual(['grpc', 'nimrod', 'nimrod', 'grpc'] * 2 + ['grpc', 'nimrod'], [call.args[1] for call in calls])

    def test_default_and_explicit_smoke_keep_short_phases(self):
        for arguments in ([], ['--smoke']):
            with self.subTest(arguments=arguments):
                suite, calls, _ = self.execute([*arguments, '--skip-build'])
                self.assertEqual('smoke', suite['mode'])
                self.assertEqual(36, suite['selectedMatrix']['totalRuns'])
                self.assert_performance_cases(calls, list(runner.TARGETS), [128, 1024, 10240, 102400], [1, 8, 32], 1, .3, .7)

    def test_integration_preserves_three_delayed_handler_checks(self):
        suite, calls, _ = self.execute(['--integration', '--skip-build'])
        self.assertEqual('integration', suite['mode'])
        self.assertEqual(39, suite['selectedMatrix']['totalRuns'])
        self.assertEqual(3, suite['selectedMatrix']['timeoutChecks'])
        self.assert_performance_cases(calls[:36], list(runner.TARGETS), [128, 1024, 10240, 102400], [1, 8, 32], 1, .3, .7)
        for call, library in zip(calls[36:], runner.TARGETS):
            self.assertEqual((library, 128, 1, 1, .1, .3), call.args[1:])
            self.assertTrue(call.kwargs['expected_failure'])
            self.assertEqual(200, call.kwargs['timeout'])
            self.assertEqual(1500, call.kwargs['delay'])

    def test_filtered_smoke_and_integration_preserve_existing_selection(self):
        filters = ['--implementations', 'nimrod', '--sizes', '1024', '--concurrency', '8']
        for mode, count in [('--smoke', 1), ('--integration', 2)]:
            with self.subTest(mode=mode):
                suite, calls, _ = self.execute([mode, '--skip-build', *filters])
                self.assertEqual(count, suite['selectedMatrix']['totalRuns'])
                self.assert_performance_cases(calls[:1], ['nimrod'], [1024], [8], 1, .3, .7)
                if mode == '--integration':
                    self.assertEqual(('nimrod', 128, 1, 1, .1, .3), calls[1].args[1:])

    def test_exact_requested_command_parses_without_side_effects(self):
        args = runner.parse_args([
            '--full', '--implementations', 'nimrod', '--sizes', '128', '1024', '10240', '102400',
            '--concurrency', '8', '32', '--offline', '--output', 'benchmark-results/nimrod-single',
        ])
        self.assertTrue(args.full)
        self.assertTrue(args.offline)
        self.assertEqual(Path('benchmark-results/nimrod-single'), args.output)
        self.assertEqual(40, runner.selected_matrix(args)['totalRuns'])

    def test_case_records_selected_matrix_without_changing_launch_configuration(self):
        matrix = runner.selected_matrix(runner.parse_args(['--full', '--implementations', 'nimrod', '--sizes', '128', '--concurrency', '8']))
        with tempfile.TemporaryDirectory() as temporary, \
                mock.patch.object(runner, 'free_port', return_value=40280), \
                mock.patch.object(runner, 'java_command', return_value=['java']) as launch, \
                mock.patch.object(runner.subprocess, 'Popen', side_effect=RuntimeError('No real process in test')), \
                contextlib.redirect_stdout(io.StringIO()):
            base = Path(temporary)
            result = runner.run_case(base, 'nimrod', 128, 8, 1, 30, 120, selected_matrix=matrix)
            directory = base / 'nimrod-128B-c8-r1'
            self.assertEqual(matrix, result['selectedMatrix'])
            self.assertEqual(matrix, json.loads((directory / 'launch.json').read_text())['selectedMatrix'])
            self.assertEqual(matrix, json.loads((directory / 'run-result.json').read_text())['selectedMatrix'])
            for call in launch.call_args_list:
                self.assertIn('--benchmark.warmup-seconds=30', call.args[2])
                self.assertIn('--benchmark.measurement-seconds=120', call.args[2])
                self.assertIn('--benchmark.timeout-millis=5000', call.args[2])
                self.assertIn('--benchmark.drain-millis=30000', call.args[2])
                self.assertIn('--benchmark.seed=42', call.args[2])


if __name__ == '__main__':
    unittest.main()
