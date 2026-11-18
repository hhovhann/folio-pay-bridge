#!/usr/bin/env python3
"""Makes a new API key for one integrator (D-30).

  python3 tools/new_api_key.py <client-name> "<scope> <scope> ..." [--env live|test]

Prints the key ONCE, for the integrator (send it over a secure channel), and the line for foliopay.auth.api-keys, which holds only the
key's SHA-256: put that line in Secrets Manager, never in git. The key is prefixed (fpb_live_ / fpb_test_) so secret scanners, including
tools/guard_secrets.py, recognise a leaked one. To rotate: add the new entry, let the integrator switch, then remove the old entry.
Scopes: cards:write payments:write bookings:write payments:read operations
"""
import hashlib
import re
import secrets
import sys

SCOPES = {"cards:write", "payments:write", "bookings:write", "payments:read", "operations"}


def main(argv):
    env = "test"
    if "--env" in argv:
        i = argv.index("--env")
        env = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    if len(argv) != 2 or env not in ("live", "test") or not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", argv[0]):
        print(__doc__, file=sys.stderr)
        return 2
    scopes = argv[1].split()
    unknown = [s for s in scopes if s not in SCOPES]
    if not scopes or unknown:
        print(f"Unknown scope(s): {unknown}. Known: {sorted(SCOPES)}", file=sys.stderr)
        return 2
    key = f"fpb_{env}_" + secrets.token_urlsafe(32).replace("-", "a").replace("_", "b")
    digest = hashlib.sha256(key.encode()).hexdigest()
    print("API key (give to the integrator once, do not store it):")
    print("  " + key)
    print("Configuration entry (foliopay.auth.api-keys, from Secrets Manager):")
    print(f"  {argv[0]}:{digest}:{' '.join(scopes)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
