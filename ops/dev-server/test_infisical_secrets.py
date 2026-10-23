"""Secret loading contracts: no fallback, no cross-scope injection, no value logging."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import infisical_secrets as secrets


class InfisicalSecrets(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.config = Path(self.tmp.name) / 'infisical.json'
        self.mapping = {key: key for key in secrets.KEYS['democracycraft']}
        self.config.write_text(json.dumps({'domain': 'https://secrets.example.test', 'scopes': {
            'democracycraft': [{'project_id': 'test-project', 'environment': 'prod',
                               'path': '/sources/democracycraft', 'keys': self.mapping}],
        }}))
        self.values = {'SFTP_HOST': 'test.invalid', 'SFTP_PORT': '2022',
                       'SFTP_USER': 'test', 'SFTP_PASS': 'literal ${HOME} \' " \\ \n secret'}

    def response(self, values=None):
        values = self.values if values is None else values
        return subprocess.CompletedProcess([], 0, json.dumps([
            {'key': key, 'value': value} for key, value in values.items()]))

    def test_literal_values_and_exact_source_scope(self):
        with patch.object(secrets.subprocess, 'run', return_value=self.response()) as run:
            self.assertEqual(secrets.load('democracycraft', self.config), self.values)
        argv = run.call_args.args[0]
        for argument in ('--env=prod', '--path=/sources/democracycraft', '--expand=false',
                         '--include-imports=false', '--secret-overriding=false'):
            self.assertIn(argument, argv)
        self.assertNotIn(self.values['SFTP_PASS'], ' '.join(argv))

    def test_failure_never_reuses_inherited_secret_or_leaks_response(self):
        failure = subprocess.CompletedProcess([], 1, 'sensitive-value', 'sensitive-value')
        for result in (failure, self.response({'SFTP_HOST': 'test.invalid'}),
                       subprocess.CompletedProcess([], 0, 'sensitive-value')):
            with self.subTest(result=result.returncode), patch.object(secrets.subprocess, 'run', return_value=result), \
                    patch.dict(os.environ, {'SFTP_PASS': 'stale-sensitive-value'}):
                with self.assertRaises(RuntimeError) as error:
                    secrets.environment('democracycraft', self.config)
                self.assertNotIn('sensitive-value', str(error.exception))

    def test_child_receives_only_allowlisted_scope_without_cli_token(self):
        with patch.object(secrets.subprocess, 'run', return_value=self.response(self.values | {'UNRELATED': 'secret'})), \
                patch.dict(os.environ, {'SANDBOX_DB_PASSWORD': 'stale', 'INFISICAL_TOKEN': 'cli-token'}):
            env = secrets.environment('democracycraft', self.config)
        self.assertEqual(env['SFTP_PASS'], self.values['SFTP_PASS'])
        self.assertEqual(env['STAGING_SECRET_SCOPE'], 'democracycraft')
        for key in ('UNRELATED', 'SANDBOX_DB_PASSWORD', 'INFISICAL_TOKEN'):
            self.assertNotIn(key, env)

    def test_missing_config_fails_without_calling_cli(self):
        with patch.object(secrets.subprocess, 'run') as run:
            with self.assertRaises(RuntimeError):
                secrets.load('democracycraft', Path(self.tmp.name) / 'absent')
            run.assert_not_called()

    def test_unexpected_mapping_cannot_inject_process_settings(self):
        config = json.loads(self.config.read_text())
        config['scopes']['democracycraft'][0]['keys']['NODE_OPTIONS'] = 'SFTP_PASS'
        self.config.write_text(json.dumps(config))
        with patch.object(secrets.subprocess, 'run', return_value=self.response()):
            with self.assertRaises(RuntimeError):
                secrets.load('democracycraft', self.config)


class HostStartup(unittest.TestCase):
    def test_generated_startup_script_contains_no_database_passwords(self):
        import shutil
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / 'start-local.sh'
            shutil.copyfile(Path(__file__).with_name('host') / 'start-local.sh', script)
            (root / 'server.properties').write_text('server-port=25565\n')
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            for name in ('node', 'socat', 'java', 'mariadbd'):
                stub = bin_dir / name
                stub.write_text('#!/bin/sh\nexit 0\n')
                stub.chmod(0o700)
            bwrap = bin_dir / 'bwrap'
            bwrap.write_text('#!/usr/bin/python3\nimport json,os\nprint(json.dumps({k:os.environ[k] for k in ("SANDBOX_DB_SQL","ECONOMY_DEV_DB_SQL")}))\n')
            bwrap.chmod(0o700)
            env = dict(os.environ, PATH=str(bin_dir) + ':' + os.environ['PATH'],
                       JAVA_HOME=str(root), MARIADB_HOME=str(root),
                       STAGING_SECRET_SCOPE='staging', DB_ONLY='1',
                       CLUSTER_DB_BIND='none', SANDBOX_DB_PASSWORD="one'\\two",
                       ECONOMY_DEV_DB_PASSWORD='other-private-password')
            result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True, check=True)
            inner = (root / '.sandbox/inner.sh').read_text()
            self.assertNotIn(env['SANDBOX_DB_PASSWORD'], inner)
            self.assertNotIn(env['ECONOMY_DEV_DB_PASSWORD'], inner)
            self.assertIn('$SANDBOX_DB_SQL', inner)
            sql_values = json.loads(result.stdout)
            self.assertEqual(sql_values['SANDBOX_DB_SQL'], "one''\\\\two")
            self.assertEqual(sql_values['ECONOMY_DEV_DB_SQL'], 'other-private-password')


class RuntimeLauncher(unittest.TestCase):
    def test_agent_bootstrap_preserves_process_id(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            deploy = root / 'dev-deploy'
            deploy.mkdir()
            (deploy / 'infisical_secrets.py').write_text(
                'import json,os,sys\nprint(json.dumps({"pid":os.getpid(),"args":sys.argv[1:]}))\n')
            env = dict(os.environ, SERVER_DIR=str(root))
            env.pop('STAGING_SECRET_SCOPE', None)
            agent = Path(__file__).with_name('agent.mjs').resolve()
            with subprocess.Popen(['node', str(agent)], env=env, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, text=True) as child:
                stdout, stderr = child.communicate(timeout=10)
                self.assertEqual(child.returncode, 0, stderr)
                result = json.loads(stdout)
                self.assertEqual(result['pid'], child.pid)
                self.assertEqual(result['args'][:2], ['staging', '--'])
                self.assertEqual(result['args'][-1], str(agent))

    def test_cli_failure_does_not_start_child_or_echo_secrets(self):
        import sys
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / 'infisical'
            fake.write_text('#!/bin/sh\necho secret-response\necho secret-response >&2\nexit 1\n')
            fake.chmod(0o700)
            config = root / 'infisical.json'
            config.write_text(json.dumps({'domain': 'https://example.test', 'cli': str(fake),
                'scopes': {'democracycraft': [{'project_id': 'test', 'environment': 'dev',
                    'path': '/', 'keys': {key: key for key in secrets.KEYS['democracycraft']}}]}}))
            marker = root / 'child-started'
            result = subprocess.run([sys.executable, str(Path(secrets.__file__)), '--config', str(config),
                'democracycraft', '--', sys.executable, '-c',
                'import pathlib,sys; pathlib.Path(sys.argv[1]).touch()', str(marker)],
                capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(marker.exists())
            self.assertNotIn('secret-response', result.stdout + result.stderr)
