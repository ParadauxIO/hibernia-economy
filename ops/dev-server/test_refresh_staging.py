"""Run: MARIADB_HOME=/path/to/mariadb python3 -m unittest discover -s ops/dev-server -p 'test_*.py'."""
from contextlib import ExitStack
import importlib.util
import json
import base64
import hashlib
import hmac
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("refresh", Path(__file__).with_name("refresh-staging.py"))
refresh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refresh)


def service_token(expired=False):
    encode = lambda x: base64.urlsafe_b64encode(json.dumps(x).encode()).decode().rstrip('=')
    secret = 'test-staging-secret-material-32-chars'
    header = encode({'kid': '1', 'alg': 'HS256'})
    claims = encode({'type': 'SERVICE', 'sub': '00000000-0000-0000-0000-000000000001',
                     'jti': '00000000-0000-0000-0000-000000000002',
                     'iat': int(time.time()) - 100, 'exp': int(time.time()) + (-1 if expired else 3600)})
    signed = f'{header}.{claims}'
    sig = base64.urlsafe_b64encode(hmac.new(hashlib.sha256(secret.encode()).digest(), signed.encode(), hashlib.sha256).digest()).decode().rstrip('=')
    return signed + '.' + sig, secret


class Guards(unittest.TestCase):
    def test_service_identity_rejects_wrong_secret_and_expiry(self):
        token, secret = service_token()
        with self.assertRaisesRegex(ValueError, 'SERVICE token'):
            refresh.service_identity_sql(token, 'different-secret')
        token, secret = service_token(expired=True)
        with self.assertRaisesRegex(ValueError, 'SERVICE token'):
            refresh.service_identity_sql(token, secret)

    def test_source_options_are_accepted_by_dump_client(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for schema, name in zip(refresh.SCHEMAS, refresh.CONFIGS):
                path = root / name
                path.parent.mkdir()
                if name == 'LuckPerms/config.yml':
                    config = {'data': {'address': 'db.example:3307', 'database': schema,
                                       'username': 'test', 'password': 'test'}}
                else:
                    config = {'url': f'mysql://db.example/{schema}', 'username': 'test', 'password': 'test'}
                path.write_text(json.dumps(config))
            with ExitStack() as resources:
                connections = refresh.production_connections(root, resources)
                options = Path(f'/proc/self/fd/{connections["s80037_luckperms"]}').read_text()
                self.assertIn('port="3307"', options)
                for connection in connections.values():
                    subprocess.run(['mariadb-dump', f'--defaults-file=/proc/self/fd/{connection}', '--version'],
                                   check=True, capture_output=True, pass_fds=(connection,))

    def test_client_credentials_are_anonymous_and_closed_after_failure(self):
        password = 'test secret#with"quotes\\and-slashes'
        with self.assertRaisesRegex(RuntimeError, 'simulated failure'):
            with ExitStack() as resources:
                fd = refresh.cnf(resources, {'user': 'test', 'password': password})
                path = Path(f'/proc/self/fd/{fd}')
                self.assertTrue(os.readlink(path).startswith('/memfd:'))
                self.assertEqual(os.fstat(fd).st_nlink, 0)
                self.assertEqual(os.fstat(fd).st_mode & 0o777, 0o600)
                self.assertFalse(os.get_inheritable(fd))
                for client in ('mariadb', 'mariadb-dump', 'mariadb'):
                    result = subprocess.run(
                        [client, f'--defaults-file={path}', '--print-defaults'],
                        check=True, capture_output=True, text=True, pass_fds=(fd,))
                    self.assertIn('--password=' + password, result.stdout)
                    self.assertNotIn(password, ' '.join(result.args))
                raise RuntimeError('simulated failure')
        with self.assertRaises(OSError):
            os.fstat(fd)

    def test_definer_rewrite_does_not_change_insert_values(self):
        ddl = b'/*!50013 DEFINER=`prod`@`%` SQL SECURITY DEFINER */\n'
        self.assertIn(b'DEFINER=CURRENT_USER', refresh.localize_line(ddl))
        row = b"INSERT INTO notes VALUES ('DEFINER=`prod`@`%`');\n"
        self.assertEqual(row, refresh.localize_line(row))

    def test_running_agent_blocks_restore(self):
        status = subprocess.CompletedProcess([], 0, '{"paused":false,"serverRunning":true,"busy":false}')
        with patch.object(refresh, "run", return_value=status), patch.object(refresh, "query") as query:
            with self.assertRaisesRegex(ValueError, "Pause"):
                refresh.maintenance_check(Path('/unused'), 'test', Path('/unused.cnf'))
            query.assert_not_called()

    def test_wrong_datadir_blocks_restore(self):
        status = {'paused': True, 'serverRunning': False, 'busy': False}
        deployment = {'spec': {'replicas': 0}, 'status': {'replicas': 0}}
        results = [subprocess.CompletedProcess([], 0, json.dumps(x))
                   for x in (status, deployment, deployment)]
        with patch.object(refresh, 'run', side_effect=results), patch.object(refresh, 'query', return_value='/prod/data'):
            with self.assertRaisesRegex(ValueError, 'datadir'):
                refresh.maintenance_check(Path('/unused'), 'test', Path('/unused.cnf'))

    def test_running_cluster_writer_blocks_restore(self):
        status = {'paused': True, 'serverRunning': False, 'busy': False}
        deployment = {'spec': {'replicas': 0}, 'status': {'replicas': 1}}
        results = [subprocess.CompletedProcess([], 0, json.dumps(x)) for x in (status, deployment)]
        with patch.object(refresh, 'run', side_effect=results), patch.object(refresh, 'query') as query:
            with self.assertRaisesRegex(ValueError, 'Scale'):
                refresh.maintenance_check(Path('/unused'), 'test', Path('/unused.cnf'))
            query.assert_not_called()


@unittest.skipUnless(os.environ.get("MARIADB_HOME"), "set MARIADB_HOME to run isolated MariaDB restore tests")
class RoundTrip(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="staging-test-")
        cls.root = Path(cls.tmp.name)
        home = Path(os.environ['MARIADB_HOME'])
        cls.log = (cls.root / 'server.log').open('w')
        subprocess.run([str(home / 'scripts/mariadb-install-db'), '--no-defaults',
                        f'--basedir={home}', f'--datadir={cls.root / "data"}',
                        '--auth-root-authentication-method=normal'], check=True,
                       stdout=cls.log, stderr=cls.log)
        cls.server = subprocess.Popen([str(home / 'bin/mariadbd'), '--no-defaults',
            f'--basedir={home}', f'--datadir={cls.root / "data"}',
            f'--socket={cls.root / "db.sock"}', f'--pid-file={cls.root / "db.pid"}',
            '--skip-networking', '--innodb-buffer-pool-size=32M'], stdout=cls.log, stderr=cls.log)
        cls.resources = ExitStack()
        cls.addClassCleanup(cls.resources.close)
        cls.connection = refresh.cnf(cls.resources, {
            'socket': cls.root / 'db.sock', 'user': 'root', 'skip-ssl': '1'})
        for _ in range(100):
            try:
                refresh.query(cls.connection, 'SELECT 1')
                return
            except subprocess.CalledProcessError:
                time.sleep(.1)
        cls.server.terminate()
        cls.server.wait(timeout=20)
        raise RuntimeError('Scratch MariaDB failed to start: ' + (cls.root / 'server.log').read_text())

    @classmethod
    def tearDownClass(cls):
        cls.server.terminate()
        cls.server.wait(timeout=30)
        cls.log.close()
        cls.tmp.cleanup()

    def test_snapshot_restore_and_sanitization(self):
        for schema in refresh.SCHEMAS:
            refresh.query(self.connection, f'CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;')
            refresh.query(self.connection, """
CREATE TABLE tally (n INT NOT NULL) ENGINE=InnoDB;
INSERT INTO tally VALUES (0);
CREATE TABLE items (id INT PRIMARY KEY, note TEXT) ENGINE=InnoDB;
CREATE TRIGGER add_item AFTER INSERT ON items FOR EACH ROW UPDATE tally SET n=n+1;
CREATE VIEW item_view AS SELECT * FROM items;
CREATE PROCEDURE item_count() SELECT COUNT(*) FROM items;
INSERT INTO items VALUES (1, 'DEFINER=`prod`@`%`');
""", schema)
        economy = refresh.SCHEMAS[0]
        refresh.query(self.connection, """
CREATE TABLE api_keys (key_id INT PRIMARY KEY, key_type VARCHAR(16), account_id INT, firm_id INT,
owner_uuid_bin BINARY(16), jwt_id CHAR(36), issued_at DATETIME, expires_at DATETIME, revoked INT, token TEXT) ENGINE=InnoDB;
INSERT INTO api_keys (key_id,revoked,token) VALUES (9,0,'production-token');
CREATE TABLE webhook_subscription (id INT PRIMARY KEY, secret TEXT) ENGINE=InnoDB;
CREATE TABLE webhook_delivery (subscription_id INT, FOREIGN KEY (subscription_id) REFERENCES webhook_subscription(id)) ENGINE=InnoDB;
INSERT INTO webhook_subscription VALUES (1, 'production-secret');
INSERT INTO webhook_delivery VALUES (1);
CREATE TABLE explorer_link_code (code TEXT) ENGINE=InnoDB;
INSERT INTO explorer_link_code VALUES ('live-code');
CREATE TABLE explorer_identity (id INT) ENGINE=InnoDB;
INSERT INTO explorer_identity VALUES (1);
CREATE TABLE firm_transfer_requests (status VARCHAR(20), token VARCHAR(64)) ENGINE=InnoDB;
INSERT INTO firm_transfer_requests VALUES ('PENDING', 'pending-token'), ('ACCEPTED', 'accepted-token');
""", economy)
        destination = self.root / 'snapshot'
        manifest = refresh.snapshot(destination, dict.fromkeys(refresh.SCHEMAS, self.connection), 'production')
        refresh.validate_snapshot(destination)
        for schema in refresh.SCHEMAS:
            metadata = manifest['databases'][schema]
            refresh.import_dump(self.connection, schema, destination / f'{schema}.sql', metadata['defaults'])
            self.assertEqual(refresh.objects(self.connection, schema), metadata['objects'])
            self.assertEqual(refresh.query(self.connection, 'SELECT n FROM tally', schema), '1')
            self.assertEqual(refresh.query(self.connection, 'SELECT note FROM item_view', schema), 'DEFINER=`prod`@`%`')
            self.assertEqual(refresh.query(self.connection, 'CALL item_count()', schema), '1')
            refresh.query(self.connection, "INSERT INTO items VALUES (2, 'new')", schema)
            self.assertEqual(refresh.query(self.connection, 'SELECT n FROM tally', schema), '2')
        refresh.sanitize(self.connection)
        self.assertEqual(refresh.query(self.connection, "SELECT COUNT(*) FROM api_keys", economy), '0')
        token, secret = service_token()
        refresh.query(self.connection, refresh.service_identity_sql(token, secret), economy)
        self.assertEqual(refresh.query(self.connection, "SELECT key_id,key_type,revoked,token IS NULL FROM api_keys", economy), '1\tSERVICE\t0\t1')
        self.assertEqual(refresh.query(self.connection, "SELECT GROUP_CONCAT(status ORDER BY status) FROM firm_transfer_requests", economy), 'ACCEPTED,EXPIRED')
        self.assertEqual(refresh.query(self.connection, "SELECT COUNT(*) FROM firm_transfer_requests WHERE token IN ('pending-token','accepted-token')", economy), '0')
        for table in ('webhook_delivery', 'webhook_subscription', 'explorer_link_code', 'explorer_identity'):
            self.assertEqual(refresh.query(self.connection, f'SELECT COUNT(*) FROM {table}', economy), '0')
        with (destination / f'{economy}.sql').open('a') as f:
            f.write('-- corrupted\n')
        with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
            refresh.validate_snapshot(destination)
