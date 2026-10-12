#!/usr/bin/env python3
"""Mint a SERVICE API token for the Treasury REST API.

A SERVICE key is the backend credential the economy-explorer uses for its admin
calls (TREASURY_ADMIN_TOKEN). Nothing in-game can issue one — the TreasuryAPI
plugin refuses to let a player manage SERVICE keys — so it is minted here, with
the same signing key derivation and claims the plugin and REST API use.

It never talks to the database. It prints the token on stdout and, on stderr,
the SQL that makes the api_keys row agree with it (everything on stderr is valid
SQL, so `2> apply.sql` gives a runnable file); run that SQL, then set the token
wherever it is needed. A token is only valid once its row carries the
same jti, so nothing changes until you apply the SQL.

The signing secret is read from the JWT_SECRET environment variable (never an
argument, so it stays out of shell history and `ps`). It must be the value the
REST API runs with (`api.jwt-secret` / JWT_SECRET), byte for byte.

Re-mint for an existing SERVICE key (after rotating the secret, or before expiry):

    # find it:  SELECT key_id, bin_to_uuid(owner_uuid_bin), revoked, expires_at
    #             FROM api_keys WHERE key_type = 'SERVICE';
    JWT_SECRET='base64:…' ops/mint-service-token.py --key-id 7 --owner <uuid> > token.txt 2> apply.sql

Create the first SERVICE key:

    ops/mint-service-token.py --create --owner <uuid>     # prints an INSERT; run it,
                                                          # note the key_id it returns
    JWT_SECRET='base64:…' ops/mint-service-token.py --key-id <that id> --owner <uuid>

`--owner` is the Minecraft UUID recorded as the ledger initiator of admin
transfers made with the token; use the operator's own account.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid

MIN_KEY_BYTES = 32


def derive_key(secret: str) -> bytes:
    """Mirror of common/…/JwtKeys.deriveHmacKey."""
    if not secret or not secret.strip():
        sys.exit("JWT_SECRET is not set.")
    if secret.startswith("base64:"):
        key = base64.b64decode(secret[len("base64:"):].strip(), validate=True)
        if len(key) < MIN_KEY_BYTES:
            sys.exit(f"JWT 'base64:' key material must be at least {MIN_KEY_BYTES} bytes (got {len(key)}).")
        return key
    # A SQL comment, so stderr stays runnable as-is.
    print("-- warning: passphrase secret; using the legacy SHA-256 derivation, as the services do.",
          file=sys.stderr)
    return hashlib.sha256(secret.encode("utf-8")).digest()


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def sign(key: bytes, key_id: int, owner: uuid.UUID, jti: str, issued_at: int, expires_at: int) -> str:
    header = {"kid": str(key_id), "alg": "HS256"}
    # SERVICE tokens carry no 'acc' / 'firm' claim (JwtTokenVerifier).
    claims = {"sub": str(owner), "type": "SERVICE", "jti": jti, "iat": issued_at, "exp": expires_at}
    signing_input = ".".join(b64url(json.dumps(part, separators=(",", ":")).encode()) for part in (header, claims))
    signature = hmac.new(key, signing_input.encode("ascii"), hashlib.sha256).digest()
    return signing_input + "." + b64url(signature)


def sql_time(epoch: int) -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(epoch))


def main() -> None:
    parser = argparse.ArgumentParser(description="Mint a SERVICE API token for the Treasury REST API.")
    parser.add_argument("--owner", required=True, type=uuid.UUID, help="Minecraft UUID the key belongs to")
    parser.add_argument("--key-id", type=int, help="api_keys.key_id of the SERVICE key to mint a token for")
    parser.add_argument("--create", action="store_true", help="print the INSERT that creates a new SERVICE key row")
    parser.add_argument("--days", type=int, default=180, help="token lifetime in days (default 180, as in-game keys)")
    args = parser.parse_args()

    owner_hex = args.owner.hex
    now = int(time.time())
    expires = now + args.days * 86400

    if args.create:
        if args.key_id is not None:
            parser.error("--create makes a new row; do not pass --key-id with it")
        # A placeholder jti: no token exists for it, so the row is inert until a
        # token is minted for its key_id.
        print(
            "INSERT INTO api_keys (key_type, account_id, firm_id, owner_uuid_bin, jwt_id, issued_at, expires_at, revoked)\n"
            f"VALUES ('SERVICE', NULL, NULL, UNHEX('{owner_hex}'), '{uuid.uuid4()}', UTC_TIMESTAMP(), UTC_TIMESTAMP(), 0);\n"
            "SELECT LAST_INSERT_ID() AS key_id;"
        )
        print("-- Run that SQL, then mint the token with --key-id <the key_id it returned>.", file=sys.stderr)
        return

    if args.key_id is None or args.key_id <= 0:
        parser.error("pass --key-id <existing SERVICE key> (or --create to make one)")
    if args.days <= 0:
        parser.error("--days must be positive")

    key = derive_key(os.environ.get("JWT_SECRET", ""))
    jti = str(uuid.uuid4())
    token = sign(key, args.key_id, args.owner, jti, now, expires)

    print(
        "-- Apply this, or the token above is rejected (TOKEN_SUPERSEDED). It also\n"
        "-- invalidates every token previously minted for this key. Expect 1 row affected;\n"
        "-- 0 means the key id, owner or key type is wrong, or the key is revoked.\n"
        f"UPDATE api_keys\n"
        f"   SET jwt_id = '{jti}', issued_at = '{sql_time(now)}', expires_at = '{sql_time(expires)}'\n"
        f" WHERE key_id = {args.key_id} AND key_type = 'SERVICE' AND revoked = 0\n"
        f"   AND owner_uuid_bin = UNHEX('{owner_hex}');",
        file=sys.stderr,
    )
    print(token)


if __name__ == "__main__":
    main()
