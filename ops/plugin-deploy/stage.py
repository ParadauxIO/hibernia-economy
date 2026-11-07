#!/usr/bin/env python3
"""Stage a verified release via OpenSSH SFTP; never restart or migrate the server."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

PLUGINS = ('business', 'chestshop', 'treasury', 'treasury-api-plugin')


def verify_bundle(directory, expected_sha, expected_version):
    directory = Path(directory)
    manifest = json.loads((directory / 'manifest.json').read_text())
    if not re.fullmatch(r'[0-9a-f]{40}', expected_sha):
        raise ValueError('Invalid commit SHA')
    if not re.fullmatch(r'[A-Za-z0-9.-]+', expected_version):
        raise ValueError('Invalid release version')
    if manifest['sha'] != expected_sha or manifest['version'] != expected_version:
        raise ValueError('Release does not match the selected commit/version')
    names = {f'{plugin}-{expected_version}.jar': f'{plugin}.jar' for plugin in PLUGINS}
    if sorted(manifest['jars']) != sorted(names):
        raise ValueError('Manifest must contain exactly the four release plugins')
    for name in names:
        digest = hashlib.sha256((directory / name).read_bytes()).hexdigest()
        if digest != manifest['sha256'].get(name):
            raise ValueError(f'Checksum mismatch: {name}')
    return manifest, names


def quote(value):
    # OpenSSH batch syntax, not shell syntax. Reject control characters outright.
    if any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ValueError('Control characters are not allowed in SFTP paths')
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def is_current(sha):
    current = subprocess.check_output(
        ['gh', 'api', f'repos/{os.environ["GITHUB_REPOSITORY"]}/git/ref/heads/main',
         '--jq', '.object.sha'], text=True).strip()
    return current == sha


def stage(directory, version, sha, update_dir, transfer, current=is_current):
    manifest, names = verify_bundle(directory, sha, version)
    if not update_dir.startswith('/') or '..' in update_dir.split('/'):
        raise ValueError('SFTP_UPDATE_DIR must be an absolute path without ..')
    update_dir = update_dir.rstrip('/')
    quote(update_dir)
    if not update_dir or any(c in update_dir for c in '*?[]'):
        raise ValueError('SFTP_UPDATE_DIR must be non-root and contain no glob characters')
    if not current(sha):
        print('Skipped: this release has been superseded on main.')
        return False
    remote_stage = f'{update_dir}/.economy-{version}'
    transfer([f'-mkdir {quote(update_dir)}', f'-mkdir {quote(remote_stage)}'] + [
        f'put {quote(str(Path(directory).resolve() / name))} {quote(remote_stage + "/" + name)}'
        for name in names])
    # SFTP has no portable remote checksum command. Read back the uploaded bytes
    # and hash locally before making any JAR visible to Paper's update scanner.
    with tempfile.TemporaryDirectory(prefix='economy-verify-') as temporary:
        transfer([f'get {quote(remote_stage + "/" + name)} {quote(str(Path(temporary) / name))}'
                  for name in names])
        for name in names:
            if hashlib.sha256((Path(temporary) / name).read_bytes()).hexdigest() != manifest['sha256'][name]:
                raise ValueError(f'Remote checksum mismatch: {name}')
    if not current(sha):
        print('Skipped promotion: this release has been superseded on main.')
        return False
    # Treasury is promoted last. On a subsequent restart its cohort check rejects
    # mixed versions. SFTP cannot make the four renames atomic with server startup.
    ordered = sorted(names, key=lambda name: names[name] == 'treasury.jar')
    commands = []
    for name in ordered:
        target = update_dir + '/' + names[name]
        commands += [f'-rm {quote(target)}', f'rename {quote(remote_stage + "/" + name)} {quote(target)}']
    commands += [f'rmdir {quote(remote_stage)}']
    transfer(commands)
    print(f'Staged {version} ({sha}); activation awaits the next server restart.')
    return True


def main():
    strict = os.environ.get('SFTP_STRICT_HOST_KEY_CHECKING') or 'yes'
    if strict not in ('yes', 'no'):
        raise ValueError('SFTP_STRICT_HOST_KEY_CHECKING must be yes or no')
    required = ('SFTP_HOST', 'SFTP_USER', 'SFTP_UPDATE_DIR',
                'RELEASE_VERSION', 'GITHUB_SHA', 'GITHUB_REPOSITORY')
    if strict == 'yes':
        required += ('SFTP_KNOWN_HOSTS',)
    missing = [name for name in required if not os.environ.get(name, '').strip()]
    if not (os.environ.get('SFTP_PASS') or os.environ.get('SFTP_PRIVATE_KEY', '').strip()):
        missing.append('SFTP_PASS or SFTP_PRIVATE_KEY')
    if missing:
        # An unconfigured target must not break release publication or contact a server.
        # Report names only: environment values may contain credentials.
        print('::notice::Plugin staging skipped: missing ' + ', '.join(missing))
        return

    host = os.environ['SFTP_HOST']
    user = os.environ['SFTP_USER']
    port = os.environ.get('SFTP_PORT') or '22'
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]*', host):
        raise ValueError('Invalid SFTP_HOST')
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]*', user):
        raise ValueError('Invalid SFTP_USER')
    if not port.isdigit() or not 1 <= int(port) <= 65535:
        raise ValueError('Invalid SFTP_PORT')
    with tempfile.TemporaryDirectory(prefix='economy-sftp-') as temporary:
        key = Path(temporary) / 'key'
        hosts = Path(temporary) / 'known_hosts'
        if strict == 'yes':
            hosts.write_text(os.environ['SFTP_KNOWN_HOSTS'] + '\n')
            host_options = ['-oStrictHostKeyChecking=yes', f'-oUserKnownHostsFile={hosts}']
        else:
            host_options = ['-oStrictHostKeyChecking=no', '-oUserKnownHostsFile=/dev/null',
                            '-oGlobalKnownHostsFile=/dev/null']
        child_env = {k: v for k, v in os.environ.items() if not k.startswith('SFTP_')}
        if os.environ.get('SFTP_PRIVATE_KEY'):
            key.write_text(os.environ['SFTP_PRIVATE_KEY'] + '\n')
            key.chmod(0o600)
            auth = ['-oBatchMode=yes', '-i', str(key), '-oIdentitiesOnly=yes']
        elif os.environ.get('SFTP_PASS'):
            # The existing production SFTP connections use passwords. Askpass
            # keeps the secret out of argv, batch input and shell interpolation.
            askpass = Path(temporary) / 'askpass'
            askpass.write_text('#!/usr/bin/env python3\nimport os, sys\n'
                               'sys.stdout.write(os.environ["ECONOMY_SFTP_PASSWORD"] + "\\n")\n')
            askpass.chmod(0o700)
            child_env.update(SSH_ASKPASS=str(askpass), SSH_ASKPASS_REQUIRE='force',
                             DISPLAY=':0', ECONOMY_SFTP_PASSWORD=os.environ['SFTP_PASS'])
            auth = ['-oBatchMode=no', '-oPreferredAuthentications=password,keyboard-interactive',
                    '-oNumberOfPasswordPrompts=1']
        else:
            raise ValueError('Configure SFTP_PASS or SFTP_PRIVATE_KEY')

        def transfer(commands):
            # Authentication options precede -b: SSH uses the first BatchMode value.
            subprocess.run(['sftp', *auth, '-q', '-b', '-', '-P', port,
                            *host_options, '-oConnectTimeout=20',
                            '-oServerAliveInterval=15', '-oServerAliveCountMax=3',
                            f'{user}@{host}'],
                           input='\n'.join(commands) + '\n', text=True, check=True, env=child_env)

        stage('release', os.environ['RELEASE_VERSION'], os.environ['GITHUB_SHA'],
              os.environ['SFTP_UPDATE_DIR'], transfer)


if __name__ == '__main__':
    main()
