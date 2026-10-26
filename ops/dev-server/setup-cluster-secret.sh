#!/usr/bin/env bash
# Seed an unmanaged cluster from Infisical without minting or rotating credentials.
# Existing Infisical-managed installations reconcile this Secret automatically.
set -euo pipefail
if [[ ${STAGING_SECRET_SCOPE:-} != staging ]]; then
  exec python3 "$(dirname "$(readlink -f "$0")")/infisical_secrets.py" staging -- bash "$0" "$@"
fi
if (( $# )); then
  echo 'Owner arguments are no longer supported: manage the service identity in Infisical.' >&2
  exit 1
fi
python3 - <<'PY'
import json
import os
import subprocess

values = {key: os.environ[source] for key, source in {
    'DB_PASSWORD': 'ECONOMY_DEV_DB_PASSWORD',
    'JWT_SECRET': 'TREASURY_API_JWT',
    'TREASURY_ADMIN_TOKEN': 'TREASURY_ADMIN_TOKEN',
}.items()}
values['DB_USERNAME'] = 'economy_dev'
secret = {
    'apiVersion': 'v1', 'kind': 'Secret',
    'metadata': {'name': 'economy-dev-server', 'namespace': 'development',
                 'labels': {'app.kubernetes.io/part-of': 'hibernia-economy-dev-server'}},
    'type': 'Opaque', 'stringData': values,
}
result = subprocess.run(['kubectl', 'apply', '-f', '-'], input=json.dumps(secret),
                        text=True, capture_output=True)
if result.returncode:
    raise SystemExit('Could not seed the cluster Secret; check context and permissions')
print('Cluster Secret seeded from Infisical; no credentials rotated.')
PY
