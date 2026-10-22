#!/usr/bin/env python3
"""Run clean live acceptance repeatedly and retain redacted evidence for each run."""
import argparse
from datetime import datetime, timezone
import json
import hashlib
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JWT = re.compile(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+')


def redact(text):
    text = JWT.sub('[REDACTED JWT]', text)
    for key in ('IT_JWT_SECRET', 'IT_DB_PASSWORD', 'IT_RCON_PASSWORD'):
        value = os.environ.get(key)
        if value:
            text = text.replace(value, '[REDACTED]')
    return text


def retain(source, destination):
    if source.is_file():
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(redact(source.read_text(errors='replace')))


def collect_reports(test_dir, output, browser_report=None):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    skipped = []
    reports = ([browser_report] if browser_report and browser_report.is_file() else []) if browser_report else sorted((test_dir / 'tests/build/test-results/test').glob('TEST-*.xml'))
    for report in reports:
        root = ET.parse(report).getroot()
        suites = [root] if root.tag == 'testsuite' else list(root.iter('testsuite'))
        for suite in suites:
            for key in totals:
                totals[key] += int(suite.attrib.get(key, 0))
            for case in suite.findall('testcase'):
                if case.find('skipped') is not None:
                    skipped.append(suite.attrib['name'] + '.' + case.attrib['name'])
        retain(report, output / 'junit' / report.name)
    if not browser_report:
        retain(test_dir / 'tests/build/reports/test-timings.txt', output / 'test-timings.txt')
    return dict(**totals, skipped_cases=skipped)


def source_state():
    """Identify the tested checkout, including edits and new source files."""
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    patch = subprocess.check_output(['git', 'diff', '--binary', 'HEAD'], cwd=ROOT)
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=ROOT)
    digest = hashlib.sha256(commit.encode() + b'\0' + patch)
    for name in sorted(filter(None, untracked.split(b'\0'))):
        path = ROOT / os.fsdecode(name)
        digest.update(name + b'\0' + path.read_bytes())
    return commit, digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=2)
    parser.add_argument('--profile', choices=('core', 'protection', 'restart', 'crash', 'rest-restart', 'tax', 'groups', 'nexo', 'providers', 'realty', 'explorer', 'explorer-keycloak', 'webhook-failure'), default='core')
    parser.add_argument('--test', action='append', default=[], help='Gradle test filter; repeat for a focused run')
    args = parser.parse_args()
    if args.runs < 1:
        parser.error('--runs must be positive')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    output = ROOT / 'build/integration-acceptance' / f'{stamp}-{os.getpid()}'
    test_dir = Path(os.environ.get('IT_TESTS_DIR', ROOT / 'integration-tests')).resolve()
    work = Path(os.environ.get('IT_WORK_DIR', ROOT / 'build/integration')).resolve()
    expected_source = source_state()
    for number in range(1, args.runs + 1):
        commit, source_digest = source_state()
        if (commit, source_digest) != expected_source:
            print('Source changed between acceptance runs; start a new clean pair.', flush=True)
            return 1
        dirty = subprocess.check_output(['git', 'status', '--short'], cwd=ROOT, text=True)
        target = output / f'run-{number}'
        target.mkdir(parents=True)
        # Prevent build/preflight failure from collecting stale JUnit success.
        for report in (test_dir / 'tests/build/test-results/test').glob('TEST-*.xml'):
            report.unlink()
        browser_report = ROOT / 'economy-explorer/test-results/live-server.xml' if args.profile in ('explorer', 'explorer-keycloak') else None
        if browser_report:
            browser_report.unlink(missing_ok=True)
        started = time.monotonic()
        command = ['bash', str(ROOT / 'ops/integration/run.sh'), '--profile', args.profile]
        if args.test:
            command += ['--']
            for pattern in args.test:
                command += ['--tests', pattern]
        print(f'Acceptance {number}/{args.runs}: {target}', flush=True)
        with (target / 'run.log').open('w') as log:
            with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True) as process:
                for line in process.stdout:
                    clean = redact(line)
                    log.write(clean)
                    log.flush()
                    print(clean, end='', flush=True)
                status = process.wait()
        report = collect_reports(test_dir, target, browser_report)
        for name in ('bytebin.log', 'bytebin-captures.jsonl', 'server.log', 'server-restart.log', 'rest-api.log', 'rest-api-restart.log', 'mariadb.log', 'explorer.log', 'explorer-build.log', 'explorer-fixture.json'):
            retain(work / name, target / name)
        java_bin = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
        java = subprocess.run([java_bin, '-version'], capture_output=True, text=True)
        source_changed = source_state() != (commit, source_digest)
        report.update(commit=commit, working_tree=dirty, profile=args.profile, test_filters=args.test,
                      source_sha256=source_digest, source_changed=source_changed,
                      seconds=round(time.monotonic() - started, 2), exit_code=status,
                      java=java.stderr.strip(), command=command,
                      skip_build=os.environ.get('IT_SKIP_BUILD', '0'),
                      skip_explorer_build=os.environ.get('IT_SKIP_EXPLORER_BUILD', '0'))
        (target / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)
        # Fail closed if no tests ran, including a failed build or broken selection.
        if source_changed:
            print('Source changed during acceptance; this run cannot certify a single checkout.', flush=True)
        if status or source_changed or report['failures'] or report['errors'] or report['tests'] == report['skipped']:
            return status or 1
    print(f'Acceptance evidence: {output}', flush=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
