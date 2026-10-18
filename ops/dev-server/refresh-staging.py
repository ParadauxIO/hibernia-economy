#!/usr/bin/env python3
"""Snapshot/restore the three staging databases. See staging-refresh.md."""
import argparse
import base64
import datetime as dt
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import subprocess as sp
import tempfile
import time
import uuid
from urllib.parse import urlsplit

SCHEMAS = ("s80037_economy", "s80037_chestshops", "s80037_realty")
REPO = Path(__file__).resolve().parents[2]
CONFIGS = ("Treasury/config.yml", "ChestShopDatabase/database-settings.yml", "Realty/database.yml")


def run(argv, **kw):
    return sp.run([str(x) for x in argv], check=True, **kw)


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def cnf(path, values):
    # MariaDB option-file escaping, not shell quoting. Credentials never enter argv.
    def quote(v):
        return '"' + str(v).replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r') + '"'
    path.write_text("[client]\n" + "".join(f"{k}={quote(v)}\n" for k, v in values.items()))
    path.chmod(0o600)
    return path


def query(connection, sql, schema=None):
    args = ["mariadb", f"--defaults-file={connection}", "--connect-timeout=15", "--batch", "--skip-column-names"]
    if schema:
        args.append(schema)
    return run(args, input=sql, text=True, capture_output=True).stdout.strip()


def production_connections(root, work, no_tls=False):
    import yaml  # python3-yaml; only needed for reading production plugin configs
    result = {}
    for schema, filename in zip(SCHEMAS, CONFIGS):
        data = yaml.safe_load((root / filename).read_text())
        if "database" in data and isinstance(data["database"], dict):
            d = data["database"]
            host, port, name = d["host"], d.get("port", 3306), d["database"]
        else:
            d = data
            url = urlsplit(d["url"].removeprefix("jdbc:"))
            if url.scheme not in ("mysql", "mariadb"):
                raise ValueError(f"Unsupported database URL in {filename}")
            host, port, name = url.hostname, url.port or 3306, url.path.lstrip("/")
        if name != schema or host in ("localhost", "127.0.0.1", "::1"):
            raise ValueError(f"Expected production {schema} in {filename}")
        options = {
            "host": host, "port": port, "user": d["username"],
            "password": d["password"], "protocol": "tcp",
        }
        if no_tls:
            options["skip-ssl"] = "1"
        else:
            options.update({"ssl": "1", "ssl-verify-server-cert": "1"})
        result[schema] = cnf(work / f"{schema}.cnf", options)
    return result


def snapshot(destination, connections, kind):
    destination.mkdir(mode=0o700, parents=True, exist_ok=False)
    manifest = {"format": 1, "kind": kind, "started_at": now(), "databases": {}}
    for schema in SCHEMAS:
        c = connections[schema]
        engines = query(c, "SELECT DISTINCT ENGINE FROM information_schema.TABLES "
                        f"WHERE TABLE_SCHEMA='{schema}' AND TABLE_TYPE='BASE TABLE'")
        if kind == "production" and any(e != "InnoDB" for e in engines.splitlines()):
            raise ValueError(f"{schema}: non-InnoDB tables need a coordinated backup window")
        # Events are deliberately unsupported: never silently omit or enable jobs.
        events = query(c, f"SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA='{schema}'")
        if events != "0":
            raise ValueError(f"{schema}: scheduled events require an explicit staging policy")
        started = now()
        print(f"Exporting {schema}...", flush=True)
        path = destination / f"{schema}.sql"
        with path.open("wb") as out:
            run(["mariadb-dump", f"--defaults-file={c}", "--single-transaction", "--quick",
                 "--routines", "--triggers", "--hex-blob", "--skip-lock-tables", schema], stdout=out)
        manifest["databases"][schema] = {
            "sha256": digest(path), "bytes": path.stat().st_size,
            "started_at": started, "finished_at": now(),
            "server_version": query(c, "SELECT VERSION()"),
            "defaults": query(c, "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME "
                              f"FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{schema}'").split("\t"),
            "objects": objects(c, schema),
        }
        print(f"Exported {schema}: {path.stat().st_size:,} bytes", flush=True)
    manifest["finished_at"] = now()
    # Only a complete snapshot has a manifest. Never restore partial exports.
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Snapshot complete: {destination}", flush=True)
    return manifest


def objects(connection, schema):
    return query(connection, f"""SELECT CONCAT('table:', TABLE_NAME, ':', TABLE_TYPE) FROM information_schema.TABLES WHERE TABLE_SCHEMA='{schema}'
UNION ALL SELECT CONCAT('trigger:', TRIGGER_NAME) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='{schema}'
UNION ALL SELECT CONCAT('routine:', ROUTINE_NAME, ':', ROUTINE_TYPE) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA='{schema}'
ORDER BY 1;
""")


def validate_snapshot(path):
    manifest = json.loads((path / "manifest.json").read_text())
    if manifest.get("format") != 1 or set(manifest["databases"]) != set(SCHEMAS):
        raise ValueError("Unexpected snapshot format or database allowlist")
    for schema in SCHEMAS:
        defaults = manifest["databases"][schema]["defaults"]
        if len(defaults) != 2 or not all(re.fullmatch(r"[a-zA-Z0-9_]+", x) for x in defaults):
            raise ValueError(f"Invalid schema defaults: {schema}")
        if digest(path / f"{schema}.sql") != manifest["databases"][schema]["sha256"]:
            raise ValueError(f"Checksum mismatch: {schema}")
    return manifest


def maintenance_check(server, context, connection):
    env = dict(os.environ, SERVER_DIR=str(server))
    status = json.loads(run(["node", server / "dev-deploy/agent.mjs", "ctl", "status"],
                            env=env, capture_output=True, text=True).stdout)
    if not status["paused"] or status["serverRunning"] or status["busy"]:
        raise ValueError("Pause the deployment agent before restoring")
    for name in ("treasury-rest-api", "economy-explorer"):
        deployment = json.loads(run(["kubectl", "--context", context, "-n", "development", "get",
                                    "deployment", name, "-o", "json"], capture_output=True, text=True).stdout)
        if deployment["spec"].get("replicas", 1) != 0 or deployment.get("status", {}).get("replicas", 0) != 0:
            raise ValueError(f"Scale development/{name} to zero and wait for its pods to terminate")
    actual = query(connection, "SELECT @@datadir")
    if Path(actual).resolve() != (server / ".sandbox/mariadb").resolve():
        raise ValueError("Target is not the clone's sandbox MariaDB datadir")
    if query(connection, "SELECT @@event_scheduler") != "OFF":
        raise ValueError("Sandbox event_scheduler must be OFF")
    others = query(connection, "SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                   "WHERE ID <> CONNECTION_ID() AND USER NOT IN ('system user', 'event_scheduler')")
    if others != "0":
        raise ValueError("Other database clients are connected; stop staging writers first")


DEFINER = re.compile(rb"DEFINER\s*=\s*`(?:``|[^`])*`@`(?:``|[^`])*`")


def localize_line(line):
    # Only generated DDL lines, never INSERT values (which can contain this text).
    if re.match(rb"^(?:/\*!\d+\s+)?(?:CREATE\b|DEFINER\b)", line):
        return DEFINER.sub(b"DEFINER=CURRENT_USER", line)
    return line


def import_dump(connection, schema, path, defaults):
    # Stream so large ledger snapshots do not have to fit in memory. Dump order
    # restores rows before triggers, preventing balance triggers firing twice.
    charset, collation = defaults
    query(connection, f"DROP DATABASE IF EXISTS `{schema}`; CREATE DATABASE `{schema}` CHARACTER SET {charset} COLLATE {collation};")
    with sp.Popen(["mariadb", f"--defaults-file={connection}", "--binary-mode", schema], stdin=sp.PIPE) as p:
        try:
            total = path.stat().st_size
            consumed = 0
            report_at = 256 * 1024 * 1024
            with path.open("rb") as source:
                for line in source:
                    p.stdin.write(localize_line(line))
                    consumed += len(line)
                    if consumed >= report_at:
                        print(f"{schema}: {consumed * 100 // total}% of SQL streamed", flush=True)
                        report_at = consumed + 256 * 1024 * 1024
            p.stdin.close()
            if p.wait() != 0:
                raise RuntimeError(f"Import failed: {schema}")
        finally:
            if p.poll() is None:
                p.terminate()


def sanitize(connection):
    schema = SCHEMAS[0]
    tables = set(query(connection, "SHOW TABLES", schema).splitlines())
    statements = []
    for table in ("webhook_delivery", "webhook_subscription", "explorer_link_code", "explorer_identity"):
        if table in tables:
            statements.append(f"DELETE FROM `{table}`;")
    if "api_keys" in tables:
        statements.append("DELETE FROM api_keys;")
    if "firm_transfer_requests" in tables:
        statements.append("UPDATE firm_transfer_requests SET status='EXPIRED' WHERE status IN ('PENDING','CONFIRMED');")
        statements.append("UPDATE firm_transfer_requests SET token=SHA2(UUID(),256);")
    if "webhook_cursor" in tables:
        statements.append("UPDATE webhook_cursor SET last_dispatched_txn_id=(SELECT COALESCE(MAX(txn_id),0) FROM ledger_txns);")
    query(connection, "START TRANSACTION;\n" + "\n".join(statements) + "\nCOMMIT;", schema)


def service_identity_sql(token, signing_secret):
    """Validate the environment's existing token and reconstruct its DB identity."""
    try:
        header64, claims64, signature64 = token.split('.')
        decode = lambda s: base64.urlsafe_b64decode(s + '=' * (-len(s) % 4))
        header, claims = json.loads(decode(header64)), json.loads(decode(claims64))
        key = (base64.b64decode(signing_secret[7:], validate=True) if signing_secret.startswith('base64:')
               else hashlib.sha256(signing_secret.encode()).digest())
        expected = hmac.new(key, f'{header64}.{claims64}'.encode(), hashlib.sha256).digest()
        if len(key) < 32 or header.get('alg') != 'HS256' or not hmac.compare_digest(expected, decode(signature64)):
            raise ValueError()
        kid, issued, expires = int(header['kid']), int(claims['iat']), int(claims['exp'])
        owner, jti = uuid.UUID(claims['sub']).hex, str(uuid.UUID(claims['jti']))
        if claims.get('type') != 'SERVICE' or kid <= 0 or issued > time.time() + 60 or expires <= time.time():
            raise ValueError()
        if claims.get('acc') is not None or claims.get('firm') is not None:
            raise ValueError()
    except (ValueError, KeyError, TypeError):
        raise ValueError('Existing staging SERVICE token is invalid, expired, or signed by a different secret') from None
    return ("SET time_zone='+00:00'; INSERT INTO api_keys "
            "(key_id,key_type,account_id,firm_id,owner_uuid_bin,jwt_id,issued_at,expires_at,revoked) "
            f"VALUES ({kid},'SERVICE',NULL,NULL,UNHEX('{owner}'),'{jti}',FROM_UNIXTIME({issued}),FROM_UNIXTIME({expires}),0);")


def load_service_identity(server, context):
    secret = json.loads(run(['kubectl', '--context', context, '-n', 'development', 'get',
                             'secret', 'economy-dev-server', '-o', 'json'], capture_output=True, text=True).stdout)
    token = base64.b64decode(secret['data']['TREASURY_ADMIN_TOKEN']).decode()
    signing = (server / '.sandbox/secrets/treasury-api-jwt').read_text().strip()
    return service_identity_sql(token, signing)


def restore_service_identity(connection, server, context):
    sql = load_service_identity(server, context)
    if query(connection, 'SELECT COUNT(*) FROM api_keys', SCHEMAS[0]) != '0':
        raise ValueError('Refusing to restore service identity before copied API keys have been cleared')
    query(connection, sql, SCHEMAS[0])
    print('Restored existing staging service identity; authoritative secrets unchanged', flush=True)


def migration_preflight(connection):
    """Data assumptions from wiki/release-runbook.md, before upgrading V15."""
    schema = SCHEMAS[0]
    tables = set(query(connection, "SHOW TABLES", schema).splitlines())
    checks = []
    if "firm_players" in tables:
        checks.append(("player names collide", "SELECT COUNT(*) FROM (SELECT 1 FROM firm_players GROUP BY LOWER(current_name) HAVING COUNT(*)>1) t"))
    if "account_members" in tables:
        checks.append(("duplicate account members", "SELECT COUNT(*) FROM (SELECT 1 FROM account_members GROUP BY account_id, member_uuid_bin HAVING COUNT(*)>1) t"))
    if {"account_members", "account_authorizers"} <= tables:
        checks.append(("active authorizers without members", "SELECT COUNT(*) FROM account_authorizers au LEFT JOIN account_members m ON m.account_id=au.account_id AND m.member_uuid_bin=au.authorizer_uuid_bin AND m.left_at IS NULL WHERE au.revoked_at IS NULL AND m.account_id IS NULL"))
    checks.append(("duplicate system account names", "SELECT COUNT(*) FROM (SELECT 1 FROM accounts WHERE account_type='SYSTEM' GROUP BY display_name HAVING COUNT(*)>1) t"))
    for label, sql in checks:
        count = query(connection, sql, schema)
        if count != "0":
            raise ValueError(f"Migration preflight failed: {label}: {count}")


def restore(args, work):
    manifest = validate_snapshot(args.snapshot)
    expected = "rollback" if args.rollback else "production"
    if manifest["kind"] != expected:
        raise ValueError(f"Expected a {expected} snapshot")
    server = args.server_dir.resolve()
    password = (server / ".sandbox/secrets/db-sandbox").read_text().strip()
    connection = cnf(work / "local.cnf", {"host": "127.0.0.1", "port": 33306,
        "user": "sandbox", "password": password, "protocol": "tcp", "skip-ssl": "1"})
    maintenance_check(server, args.kube_context, connection)
    if not args.rollback:
        load_service_identity(server, args.kube_context)  # Fail before replacing data if credentials need rotation.
    deployed = json.loads((server / "dev-deploy/state.json").read_text())["deployed"]["sha"]
    checkout = server / "dev-deploy/repo"
    commit = run(["git", "rev-parse", "HEAD"], cwd=checkout, capture_output=True, text=True).stdout.strip()
    dirty = run(["git", "status", "--porcelain"], cwd=checkout, capture_output=True, text=True).stdout.strip()
    if commit != deployed or dirty:
        raise ValueError("Agent migration checkout must be clean and match the deployed plugin commit")
    if args.rollback and manifest.get("deployed_commit") != deployed:
        raise ValueError("Rollback requires the plugin commit recorded with that snapshot")
    lock_path = server / ".sandbox/staging-refresh.lock"
    with lock_path.open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        backup = args.backup_dir.resolve()
        saved = snapshot(backup, dict.fromkeys(SCHEMAS, connection), "rollback")
        saved["deployed_commit"] = deployed
        (backup / "manifest.json").write_text(json.dumps(saved, indent=2) + "\n")
        maintenance_check(server, args.kube_context, connection)
        print(f"Rollback snapshot: {backup}. Services will remain paused.", flush=True)
        for schema in SCHEMAS:
            print(f"Restoring {schema}...", flush=True)
            metadata = manifest["databases"][schema]
            import_dump(connection, schema, args.snapshot / f"{schema}.sql", metadata["defaults"])
            if objects(connection, schema) != metadata["objects"]:
                raise ValueError(f"Restored objects do not match the snapshot: {schema}")
        if not args.rollback:
            migration_preflight(connection)
            sanitize(connection)
            env = dict(os.environ, FLYWAY_URL="jdbc:mysql://127.0.0.1:33306/s80037_economy",
                       FLYWAY_SCHEMA=SCHEMAS[0], FLYWAY_USER="sandbox", FLYWAY_PASSWORD=password)
            run(["bash", "./gradlew", "--no-daemon", ":economy-flyway:flywayMigrate",
                 ":economy-flyway:flywayValidate"], cwd=checkout, env=env)
            sanitize(connection)
            restore_service_identity(connection, server, args.kube_context)
        print("Checking ledger invariants...", flush=True)
        result = query(connection, (REPO / "ops/money-invariants.sql").read_text(), SCHEMAS[0])
        if not result.startswith("OK\t"):
            raise ValueError(f"Ledger verification failed: {result}")
        run(["node", server / "sandbox-config.mjs", "--check"])
        receipt = {"completed_at": now(), "snapshot": str(args.snapshot.resolve()),
                   "backup": str(backup), "rollback": args.rollback, "invariants": result,
                   "commit": commit}
        (backup / "restore-result.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print("Restore verified. Keep writers paused until matching plugins and staging credentials are ready.")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    take = sub.add_parser("snapshot", help="Read production configs and export allowlisted DBs")
    take.add_argument("--source-config-root", type=Path, required=True)
    take.add_argument("--output", type=Path, required=True)
    take.add_argument("--source-no-tls", action="store_true", help="Explicitly allow a production DB server without TLS")
    put = sub.add_parser("restore", help="Replace paused local staging DBs; always save a rollback snapshot")
    put.add_argument("--snapshot", type=Path, required=True)
    put.add_argument("--backup-dir", type=Path, required=True)
    put.add_argument("--server-dir", type=Path, default=Path.home() / "democracycraft-clone")
    put.add_argument("--kube-context", required=True)
    put.add_argument("--rollback", action="store_true", help="Restore a rollback snapshot without sanitizing or migrating")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="staging-refresh-") as directory:
        work = Path(directory)
        if args.command == "snapshot":
            snapshot(args.output.resolve(), production_connections(args.source_config_root, work, args.source_no_tls), "production")
        else:
            restore(args, work)


if __name__ == "__main__":
    try:
        main()
    except sp.CalledProcessError as error:
        # Captured stderr may contain SQL/credentials. Do not echo it wholesale.
        raise SystemExit(f"{Path(error.cmd[0]).name} failed (exit {error.returncode}); services were not resumed")
    except (ValueError, RuntimeError, OSError) as error:
        raise SystemExit(str(error))
