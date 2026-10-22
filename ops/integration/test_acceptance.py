"""Fast checks for acceptance evidence: never turn skips into passes or retain JWTs."""
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch

import acceptance


class AcceptanceEvidenceTest(unittest.TestCase):
    def test_source_fingerprint_detects_tracked_and_untracked_edits(self):
        scratch = acceptance.ROOT / '.context/par-502'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory)
            def git(*args):
                subprocess.run(['git', *args], cwd=root, check=True, capture_output=True)
            git('init')
            (root / 'source.txt').write_text('baseline')
            git('add', 'source.txt')
            git('-c', 'user.name=Acceptance Test', '-c', 'user.email=test@example.invalid',
                'commit', '-m', 'fixture')
            with patch.object(acceptance, 'ROOT', root):
                baseline = acceptance.source_state()
                (root / 'source.txt').write_text('changed')
                self.assertNotEqual(acceptance.source_state(), baseline)
                (root / 'source.txt').write_text('baseline')
                self.assertEqual(acceptance.source_state(), baseline)
                (root / 'new-test.txt').write_text('new test')
                self.assertNotEqual(acceptance.source_state(), baseline)

    def test_redacts_jwt_and_configured_secrets_without_removing_failure_context(self):
        with patch.dict(os.environ, {'IT_DB_PASSWORD': 'unique-database-secret'}):
            text = acceptance.redact('HTTP 403 token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature DB=unique-database-secret')
        self.assertIn('HTTP 403', text)
        self.assertNotIn('eyJ', text)
        self.assertNotIn('unique-database-secret', text)

    def test_counts_failed_and_skipped_cases_and_preserves_diagnostics(self):
        scratch = acceptance.ROOT / '.context/par-499'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory)
            reports = root / 'tests/build/test-results/test'
            reports.mkdir(parents=True)
            (reports / 'TEST-example.xml').write_text('''<testsuite name="example" tests="3" failures="1" errors="0" skipped="1">
              <testcase name="ok"/><testcase name="blocked"><skipped/></testcase>
              <testcase name="bad"><failure message="expected 12.34, got 0"/></testcase>
              <system-out>token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature</system-out>
            </testsuite>''')
            result = acceptance.collect_reports(root, root / 'evidence')
            self.assertEqual((result['tests'], result['failures'], result['skipped']), (3, 1, 1))
            self.assertEqual(result['skipped_cases'], ['example.blocked'])
            saved = (root / 'evidence/junit/TEST-example.xml').read_text()
            self.assertIn('expected 12.34, got 0', saved)
            self.assertNotIn('eyJ', saved)


    def test_browser_suite_counts_do_not_include_stale_java_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stale = root / 'tests/build/test-results/test'
            stale.mkdir(parents=True)
            (stale / 'TEST-stale.xml').write_text('<testsuite name="stale" tests="99"/>')
            report = root / 'browser.xml'
            report.write_text('<testsuites tests="2"><testsuite name="browser" tests="2" failures="1" skipped="0">'
                              '<testcase name="ok"/><testcase name="bad"><failure>UI mismatch</failure></testcase>'
                              '</testsuite></testsuites>')
            result = acceptance.collect_reports(root, root / 'evidence', report)
            self.assertEqual((result['tests'], result['failures'], result['skipped']), (2, 1, 0))
            self.assertFalse((root / 'evidence/junit/TEST-stale.xml').exists())
            self.assertIn('UI mismatch', (root / 'evidence/junit/browser.xml').read_text())


if __name__ == '__main__':
    unittest.main()
