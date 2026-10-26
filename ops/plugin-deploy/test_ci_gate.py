import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location('ci_gate', Path(__file__).with_name('ci_gate.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
SHA = 'a' * 40


def run(**changes):
    return dict(id=42, run_attempt=1, head_sha=SHA, head_branch='main', event='push',
                status='completed', conclusion='success') | changes


def jobs(**changes):
    return {'total_count': 1, 'jobs': [dict(name='ci-gate', status='completed', conclusion='success') | changes]}


class CiGateTest(unittest.TestCase):
    def wait(self, responses):
        fetch = Mock(side_effect=responses)
        result = gate.wait_for_ci('owner/repo', SHA, fetch=fetch, sleep=lambda seconds: None,
                                  clock=Mock(side_effect=range(100)), timeout=5)
        return result, fetch

    def test_exact_push_and_successful_gate_required(self):
        result, fetch = self.wait([{'workflow_runs': [run()]}, jobs(), run()])
        self.assertEqual(result, 42)
        self.assertIn('head_sha=' + SHA, fetch.call_args_list[0].args[0])
        self.assertIn('/attempts/1/jobs', fetch.call_args_list[1].args[0])

    def test_waits_for_ci_to_appear_and_finish(self):
        result, _ = self.wait([{'workflow_runs': []}, {'workflow_runs': [run(status='in_progress')]},
                              {'workflow_runs': [run()]}, jobs(), run()])
        self.assertEqual(result, 42)

    def test_pr_manual_develop_and_other_commits_cannot_authorize_release(self):
        for change in ({'event': 'pull_request'}, {'event': 'workflow_dispatch'},
                       {'head_branch': 'develop'}, {'head_sha': 'b' * 40}):
            with self.subTest(change=change):
                with self.assertRaises(TimeoutError):
                    self.wait([{'workflow_runs': [run(**change)]}] * 6)

    def test_failure_cancel_skip_and_timeout_block(self):
        for conclusion in ('failure', 'cancelled', 'skipped', 'timed_out', 'neutral'):
            with self.subTest(conclusion=conclusion), self.assertRaises(RuntimeError):
                self.wait([{'workflow_runs': [run(conclusion=conclusion)]}])

    def test_missing_or_unsuccessful_gate_blocks_even_successful_run(self):
        for batch in ({'total_count': 0, 'jobs': []}, jobs(conclusion='skipped'),
                      jobs(conclusion='failure'), jobs(status='in_progress')):
            with self.subTest(batch=batch), self.assertRaises(RuntimeError):
                self.wait([{'workflow_runs': [run()]}, batch])

    def test_newer_failed_run_wins_over_old_success(self):
        with self.assertRaises(RuntimeError):
            self.wait([{'workflow_runs': [run(id=41), run(conclusion='failure')]}])

    def test_rerun_during_inspection_is_rechecked(self):
        result, fetch = self.wait([{'workflow_runs': [run()]}, jobs(), run(run_attempt=2, status='queued'),
                                  {'workflow_runs': [run(run_attempt=2)]}, jobs(), run(run_attempt=2)])
        self.assertEqual(result, 42)
        self.assertIn('/attempts/2/jobs', fetch.call_args_list[4].args[0])

    def test_api_errors_fail_closed(self):
        with self.assertRaises(OSError):
            self.wait([OSError('GitHub unavailable')])

    def test_job_pagination(self):
        first = {'total_count': 101, 'jobs': [{'name': 'other'}] * 100}
        second = jobs() | {'total_count': 101}
        result, fetch = self.wait([{'workflow_runs': [run()]}, first, second, run()])
        self.assertEqual(result, 42)
        self.assertIn('page=2', fetch.call_args_list[2].args[0])


if __name__ == '__main__':
    unittest.main()
