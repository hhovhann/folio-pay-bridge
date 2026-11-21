# Phase 21 Plan

1. Tests first for the security rules:
   - The redactor: every kind of secret is masked, ordinary ids, keys, amounts and dates stay readable.
   - A sensitive `toString()` on every type.
   - No CVV anywhere.
   - No raw body in a log call.
   - The audit events, with their fields and without secrets.
   - The TLS guard.
   See each fail.
2. Write `LogRedactor` and route every boundary through it: the text and exception converters, the JSON encoder (with the log context), `RecentLogs`, `ErrorBodies`.
3. Give the five records a redacted `toString()`.
4. Write `AuditLog` (logger `dev.foliopay.audit`, pinned to INFO) and call it from:
   - the refusal handlers;
   - an interceptor for state-changing calls;
   - the vault (stored, opened);
   - the charge (captured, declined, refused);
   - a start-up listener (trail started, configuration in force) and shutdown (trail stopped).
5. Write `TransportSecurityGuard` beside `KeyModeGuard`. Set `foliopay.allow-plaintext-transport=true` in the Compose profile only.
6. Move the policy constants to validated properties, and the demo distortion to the demo service.
7. Add a DEBUG entry line to every public service method. Remove restating comments in the service layer.
8. `ArchitectureTest`: one encryption service, stereotypes. `CleanCodeTest`: the size limits with named exceptions, and every public service method logs.
9. Write the standards, `CLAUDE.md`, `SECURITY.md`, the agent log, the roadmap.
10. Run `./mvnw clean verify`, `tools/check_specs.py`, both guards, and the mutation checks: put each bug back and see its test fail.
