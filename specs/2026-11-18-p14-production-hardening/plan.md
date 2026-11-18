# Phase 14 Plan

1. Re-check each review finding against the code; fix with a test first; mutation-check each fix (`var/mutate.py`, not committed: patch, run the named test, expect a failure, restore).
2. Ask the owner before each new dependency (Flyway, Spring Security and the OAuth2 resource server: approved 2026-11-18) and before recording decisions (approved).
3. Security: Spring Security filter chain with scopes; a real RSA key and a local JWKS server in the integration test; fail-closed start-up tests.
4. Flyway: generate the DDL from the entities against PostgreSQL 16, review it, add indexes, start the app with `validate`.
5. Webhooks and remote creation: tests against the real database and a real HTTP receiver.
6. `./mvnw clean verify` (coverage gate), `python3 tools/check_specs.py`, `python3 tools/guard_secrets.py --repo`, the Compose stack end to end.
7. Documentation, then a final code review, then one commit.
