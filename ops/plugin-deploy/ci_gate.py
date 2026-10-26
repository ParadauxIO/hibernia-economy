#!/usr/bin/env python3
"""Require the full main-push CI run for this exact release commit."""
import json
import os
import re
import subprocess
import time


def api(endpoint):
    return json.loads(subprocess.check_output(['gh', 'api', endpoint], text=True, timeout=60))


def wait_for_ci(repo, sha, fetch=api, sleep=time.sleep, clock=time.monotonic, timeout=6000):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('Invalid GITHUB_REPOSITORY')
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Invalid GITHUB_SHA')
    deadline = clock() + timeout
    while clock() < deadline:
        response = fetch(f'repos/{repo}/actions/workflows/ci.yml/runs'
                         f'?branch=main&event=push&head_sha={sha}&per_page=100')
        runs = [run for run in response['workflow_runs']
                if run['head_sha'] == sha and run['head_branch'] == 'main' and run['event'] == 'push']
        # Never accept a PR, filtered manual run, old commit or older successful
        # run in preference to the newest run/attempt for this main push.
        run = max(runs, key=lambda item: item['id']) if runs else None
        if run and run['status'] == 'completed':
            if run['conclusion'] != 'success':
                raise RuntimeError(f"CI run {run['id']} concluded {run['conclusion']}; release blocked")
            attempt = run['run_attempt']
            jobs = []
            page = 1
            while True:
                batch = fetch(f"repos/{repo}/actions/runs/{run['id']}/attempts/{attempt}/jobs"
                              f'?per_page=100&page={page}')
                jobs.extend(batch['jobs'])
                if len(jobs) >= batch['total_count']:
                    break
                if not batch['jobs']:
                    raise RuntimeError('Incomplete CI job response; release blocked')
                page += 1
            gates = [job for job in jobs if job['name'] == 'ci-gate']
            if len(gates) != 1 or gates[0]['status'] != 'completed' or gates[0]['conclusion'] != 'success':
                raise RuntimeError('ci-gate did not succeed in this CI attempt; release blocked')
            # Detect a rerun started while we were reading its job list.
            latest = fetch(f"repos/{repo}/actions/runs/{run['id']}")
            if latest['run_attempt'] != attempt or latest['status'] != 'completed':
                continue
            if latest['conclusion'] != 'success':
                raise RuntimeError('CI result changed; release blocked')
            print(f"Full CI and ci-gate passed for {sha}: run {run['id']}, attempt {attempt}", flush=True)
            return run['id']
        print(f'Waiting for full main-push CI for {sha}...', flush=True)
        sleep(20)
    raise TimeoutError(f'Full CI did not pass within {timeout} seconds; release blocked')


if __name__ == '__main__':
    wait_for_ci(os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_SHA'])
