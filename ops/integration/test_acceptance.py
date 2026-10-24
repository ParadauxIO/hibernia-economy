"""Fast checks for acceptance evidence: never turn skips into passes or retain JWTs."""
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
import contextlib
import io
import json
from unittest.mock import patch

import acceptance
import artifacts


class AcceptanceEvidenceTest(unittest.TestCase):
    def test_inventory_hashes_installed_bytes_and_excludes_secrets(self):
        scratch = acceptance.ROOT / '.context/par-520'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory)
            plugins = root / 'plugins'
            plugins.mkdir()
            paper, rest, plugin = root / 'paper.jar', root / 'rest.jar', plugins / 'Utilities.jar'
            for path in (paper, rest, plugin):
                path.write_bytes(b'original')
            (plugins / 'config.yml').write_text('password: must-not-appear')
            baseline = artifacts.inventory(paper, plugins, rest)
            plugin.write_bytes(b'changed!')  # Same filename and byte count, different content.
            changed = artifacts.inventory(paper, plugins, rest)
            self.assertNotEqual(baseline['plugins/Utilities.jar']['sha256'], changed['plugins/Utilities.jar']['sha256'])
            self.assertEqual(baseline['paper/paper.jar'], changed['paper/paper.jar'])
            self.assertNotIn(directory, json.dumps(changed))
            self.assertNotIn('config', json.dumps(changed))
            plugin.write_bytes(b'')
            with self.assertRaisesRegex(ValueError, 'Empty runtime artifact'):
                artifacts.inventory(paper, plugins, rest)

    def test_clean_pair_requires_matching_fresh_artifact_evidence(self):
        # A real child shell supplies successful JUnit results. Its plugin bytes
        # may change between runs even while the Git source stays fixed.
        scratch = acceptance.ROOT / '.context/par-520'
        scratch.mkdir(parents=True, exist_ok=True)
        manifest = {name: {'sha256': 'a' * 64, 'bytes': 10} for name in
                    ('paper/paper.jar', 'plugins/Utilities.jar', 'rest/rest.jar')}
        for mode, expected in [('stable', 0), ('changed', 1), ('missing', 1)]:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(dir=scratch) as directory:
                root = Path(directory)
                work, test_dir = root / 'work', root / 'integration-tests'
                work.mkdir()
                # A preflight failure must not reuse an earlier manifest.
                (work / 'runtime-artifacts.json').write_text(json.dumps(manifest))
                runner = root / 'ops/integration/run.sh'
                runner.parent.mkdir(parents=True)
                runner.write_text('''#!/bin/bash
set -eu
mkdir -p "$IT_TESTS_DIR/tests/build/test-results/test"
echo '<testsuite name="fixture" tests="1" failures="0" errors="0" skipped="0"><testcase name="pass"/></testsuite>' > "$IT_TESTS_DIR/tests/build/test-results/test/TEST-fixture.xml"
''' + ('' if mode == 'missing' else '''cat > "$IT_WORK_DIR/runtime-artifacts.json" <<'JSON'
''' + json.dumps(manifest) + '''
JSON
''' + ('''if [ -f "$IT_WORK_DIR/ran" ]; then sed -i 's/aaaaaaaa/bbbbbbbb/g' "$IT_WORK_DIR/runtime-artifacts.json"; fi
touch "$IT_WORK_DIR/ran"
''' if mode == 'changed' else '')))
                with patch.object(acceptance, 'ROOT', root), \
                        patch.object(acceptance, 'source_state', return_value=('commit', 'source')), \
                        patch.object(acceptance.subprocess, 'check_output', return_value=''), \
                        patch.dict(os.environ, {'IT_WORK_DIR': str(work), 'IT_TESTS_DIR': str(test_dir)}), \
                        patch('sys.argv', ['acceptance.py', '--runs', '2']), \
                        contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(acceptance.main(), expected)
                summaries = sorted(root.glob('build/integration-acceptance/*/run-*/summary.json'))
                final = json.loads(summaries[-1].read_text())
                self.assertEqual(final['artifacts_changed'], mode == 'changed')
                self.assertEqual(final['artifact_error'] is not None, mode == 'missing')
                self.assertEqual(len(summaries), 1 if mode == 'missing' else 2)

    def test_ci_rejects_real_nexo_before_build_or_runtime_start(self):
        for profile, artifact, ci, github in (
                ('nexo', '', 'true', ''), ('providers', '', '1', ''),
                ('core', '/licensed/nexo.jar', 'true', ''),
                ('nexo', '', '', 'true')):
            with self.subTest(profile=profile, artifact=artifact, ci=ci, github=github):
                env = {**os.environ, 'CI': ci, 'GITHUB_ACTIONS': github,
                       'IT_NEXO_JAR': artifact, 'JAVA_HOME': '/must-not-start-java'}
                result = subprocess.run(['bash', str(acceptance.ROOT / 'ops/integration/run.sh'),
                                         '--profile', profile], env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn('Real Nexo artifacts are prohibited in CI', result.stderr)

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
