#!/usr/bin/env python3
"""Run a local tool with an allowlisted Infisical secret scope, without dotenv files."""
import argparse
import json
import os
from pathlib import Path
import subprocess

# Scope boundaries also remove stale credentials inherited from a caller.
KEYS = {
    "democracycraft": {"SFTP_HOST", "SFTP_PORT", "SFTP_USER", "SFTP_PASS"},
    "statecraft": {"SFTP_HOST", "SFTP_PORT", "SFTP_USER", "SFTP_PASS"},
    "staging": {"SANDBOX_DB_PASSWORD", "ECONOMY_DEV_DB_PASSWORD", "TREASURY_API_JWT",
                "TREASURY_ADMIN_TOKEN", "VOTIFIER_DEFAULT", "VOTIFIER_MCSL",
                "REALTY_QUERY_SECRET", "DISCORDSRV_STAGING_TOKEN"},
}


def load(scope, config_path=None):
    server = Path(os.environ.get("SERVER_DIR", Path.home() / "democracycraft-clone"))
    config_path = config_path or os.environ.get("STAGING_INFISICAL_CONFIG", server / "dev-deploy/infisical.json")
    try:
        config = json.loads(Path(config_path).read_text())
        sources = config["scopes"][scope]
        domain = config["domain"]
        if not isinstance(sources, list) or not sources or not isinstance(domain, str) or not domain.startswith("https://"):
            raise ValueError()
        for source in sources:
            if not isinstance(source, dict) or not isinstance(source.get("keys"), dict):
                raise ValueError()
            if any(not isinstance(source.get(key), str) or not source[key]
                   for key in ("project_id", "environment", "path")):
                raise ValueError()
    except (OSError, ValueError, KeyError, TypeError):
        raise RuntimeError("Missing or invalid Infisical metadata configuration") from None
    values = {}
    for source in sources:
        args = [config.get("cli", "infisical"), "export", "--silent", f"--domain={domain}",
                f"--projectId={source['project_id']}", f"--env={source['environment']}",
                f"--path={source['path']}", "--format=json", "--expand=false",
                "--include-imports=false", "--secret-overriding=false"]
        if config.get("profile"):
            args.append(f"--profile={config['profile']}")
        try:
            result = subprocess.run(args, capture_output=True, text=True, timeout=60)
            if result.returncode:
                raise ValueError()
            records = json.loads(result.stdout)
            secrets = {item["key"]: item["value"] for item in records}
            for target, key in source["keys"].items():
                if target not in KEYS[scope] or target in values:
                    raise ValueError()
                value = secrets[key]
                if not isinstance(value, str) or not value or "\0" in value:
                    raise ValueError()
                values[target] = value
        except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
            # CLI stderr and malformed responses can contain credentials. Never echo them.
            raise RuntimeError(f"Unable to load complete {scope} secrets from Infisical") from None
    if values.keys() != KEYS[scope]:
        raise RuntimeError(f"Incomplete {scope} secret mapping")
    return values


def environment(scope, config_path=None):
    values = load(scope, config_path)
    env = dict(os.environ)
    for key in set().union(*KEYS.values()):
        env.pop(key, None)
    # The child needs application secrets, not the CLI's authentication token.
    for key in ("INFISICAL_TOKEN", "INFISICAL_UNIVERSAL_AUTH_CLIENT_SECRET"):
        env.pop(key, None)
    return env | values | {"STAGING_SECRET_SCOPE": scope}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path)
    parser.add_argument("scope", choices=KEYS)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        parser.error("provide a command after --")
    try:
        env = environment(args.scope, args.config)
        os.execvpe(command[0], command, env)
    except (RuntimeError, OSError) as error:
        raise SystemExit(str(error)) from None


if __name__ == "__main__":
    main()
