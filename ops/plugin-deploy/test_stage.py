import hashlib
import io
import importlib.util
import json
import os
import subprocess
from pathlib import Path
import shlex
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('stage', Path(__file__).with_name('stage.py'))
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


class StagingTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.version = '2026.10.42.1-g' + 'a' * 12
        self.sha = 'a' * 40
        names = [f'{name}-{self.version}.jar' for name in stage.PLUGINS]
        for name in names:
            (self.root / name).write_bytes(name.encode())
        self.manifest = dict(version=self.version, sha=self.sha, jars=names,
                             sha256={name: hashlib.sha256(name.encode()).hexdigest() for name in names})
        self.save_manifest()
        self.remote = {}
        self.promoted = []
        self.corrupt = False

    def save_manifest(self):
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest))

    def transfer(self, commands):
        for command in commands:
            op, *args = shlex.split(command)
            if op == 'put':
                self.remote[args[1]] = Path(args[0]).read_bytes()
            elif op == 'get':
                Path(args[1]).write_bytes(b'corrupt' if self.corrupt else self.remote[args[0]])
            elif op == 'rename':
                self.remote[args[1]] = self.remote.pop(args[0])
                self.promoted.append(args[1])
            elif op == '-rm':
                self.remote.pop(args[0], None)

    def run_stage(self, current=lambda sha: True):
        return stage.stage(self.root, self.version, self.sha, '/plugins/update', self.transfer, current)

    def test_verified_release_promoted_with_stable_names_and_treasury_last(self):
        self.assertTrue(self.run_stage())
        self.assertEqual({Path(name).name for name in self.promoted},
                         {name + '.jar' for name in stage.PLUGINS})
        self.assertEqual(self.promoted[-1], '/plugins/update/treasury.jar')
        for plugin in stage.PLUGINS:
            self.assertEqual(self.remote[f'/plugins/update/{plugin}.jar'],
                             (self.root / f'{plugin}-{self.version}.jar').read_bytes())

    def test_local_corruption_never_uploaded(self):
        (self.root / self.manifest['jars'][0]).write_bytes(b'bad')
        with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
            self.run_stage()
        self.assertEqual(self.remote, {})

    def test_remote_corruption_never_promoted(self):
        self.corrupt = True
        with self.assertRaisesRegex(ValueError, 'Remote checksum mismatch'):
            self.run_stage()
        self.assertEqual(self.promoted, [])

    def test_superseded_release_never_uploaded(self):
        self.assertFalse(self.run_stage(lambda sha: False))
        self.assertEqual(self.remote, {})

    def test_superseded_during_upload_never_promoted(self):
        answers = iter([True, False])
        self.assertFalse(self.run_stage(lambda sha: next(answers)))
        self.assertEqual(self.promoted, [])

    def test_manifest_identity_and_completeness(self):
        for change in ({'sha': 'b' * 40}, {'version': 'old'},
                       {'jars': self.manifest['jars'][:-1]}, {'jars': ['../../escape.jar']}):
            with self.subTest(change=change):
                original = self.manifest.copy()
                self.manifest.update(change)
                self.save_manifest()
                with self.assertRaises(ValueError):
                    self.run_stage()
                self.manifest = original
        self.assertEqual(self.remote, {})

    def test_upload_failure_never_promotes(self):
        def fail(commands):
            raise OSError('connection lost')
        with self.assertRaises(OSError):
            stage.stage(self.root, self.version, self.sha, '/plugins/update', fail, lambda sha: True)
        self.assertEqual(self.promoted, [])

    def test_paths_cannot_inject_batch_commands(self):
        with self.assertRaises(ValueError):
            stage.quote('/plugins\nrm *')
        self.assertEqual(shlex.split('get ' + stage.quote('/a path/"quote"')),
                         ['get', '/a path/"quote"'])


class AuthenticationTest(unittest.TestCase):
    def test_password_is_only_passed_to_askpass_environment(self):
        password = 'literal $HOME `command` "quote"'
        env = dict(SFTP_HOST='test.invalid', SFTP_USER='user.id', SFTP_PASS=password,
                   SFTP_KNOWN_HOSTS='test.invalid ssh-ed25519 example', SFTP_UPDATE_DIR='/plugins/update',
                   RELEASE_VERSION='release', GITHUB_SHA='a' * 40, GITHUB_REPOSITORY='owner/repo')
        real_run = subprocess.run

        def inspect(argv, **kwargs):
            self.assertNotIn(password, str(argv))
            self.assertNotIn(password, kwargs['input'])
            self.assertLess(argv.index('-oBatchMode=no'), argv.index('-b'))
            child_env = kwargs['env']
            self.assertFalse(any(key.startswith('SFTP_') for key in child_env))
            result = real_run([child_env['SSH_ASKPASS']], env=child_env, capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout, password + '\n')

        with patch.dict(os.environ, env, clear=True), patch.object(stage.subprocess, 'run', side_effect=inspect) as run:
            with patch.object(stage, 'stage', side_effect=lambda directory, version, sha, path, transfer: transfer(['pwd'])):
                stage.main()
            run.assert_called_once()

    def test_missing_configuration_skips_without_io_or_secret_output(self):
        complete = dict(SFTP_HOST='test.invalid', SFTP_USER='user.id', SFTP_PASS='private-password',
                        SFTP_KNOWN_HOSTS='known-host', SFTP_UPDATE_DIR='/plugins/update',
                        RELEASE_VERSION='release', GITHUB_SHA='a' * 40, GITHUB_REPOSITORY='owner/repo')
        cases = [({}, 'SFTP_HOST')]
        for name in complete:
            for value in (None, ''):
                env = complete.copy()
                if value is None:
                    del env[name]
                else:
                    env[name] = value
                cases.append((env, name))
        for env, missing in cases:
            with self.subTest(missing=missing, empty=missing in env):
                output = io.StringIO()
                with patch.dict(os.environ, env, clear=True), patch('sys.stdout', output):
                    with patch.object(stage, 'stage') as deploy, patch.object(stage, 'tempfile') as temporary:
                        stage.main()
                        deploy.assert_not_called()
                        temporary.TemporaryDirectory.assert_not_called()
                self.assertIn('Plugin staging skipped', output.getvalue())
                self.assertIn(missing, output.getvalue())
                self.assertNotIn('private-password', output.getvalue())

    def test_invalid_configured_port_still_fails(self):
        env = dict(SFTP_HOST='test.invalid', SFTP_USER='user.id', SFTP_PASS='private-password',
                   SFTP_KNOWN_HOSTS='known-host', SFTP_UPDATE_DIR='/plugins/update', SFTP_PORT='invalid',
                   RELEASE_VERSION='release', GITHUB_SHA='a' * 40, GITHUB_REPOSITORY='owner/repo')
        with patch.dict(os.environ, env, clear=True), patch.object(stage, 'stage') as deploy:
            with self.assertRaisesRegex(ValueError, 'Invalid SFTP_PORT'):
                stage.main()
            deploy.assert_not_called()


if __name__ == '__main__':
    unittest.main()
