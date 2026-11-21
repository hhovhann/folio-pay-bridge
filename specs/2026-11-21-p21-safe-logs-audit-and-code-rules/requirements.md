# Phase 21 Requirements - Safe logs, an audit trail, and code rules a build holds

## Goal
The owner reviewed the code and asked for:
- Configuration in place of hardcoded values.
- Logs in the services, with method names and parameters.
- `@Service` on services.
- Fewer comments.
- Tests that cover success, refusal and edge cases, and read configuration where that makes sense.
- Clean code: names, method size, parameter counts.
- These rules written down so the next generated code does not repeat a mistake.

Then the owner set the security rules:
- Never log a card number, a CVV, a password, an access token or a key.
- Never log a raw payment body.
- Sanitise exception messages and third-party errors.
- Redact at every logging and observability boundary.
- Still audit security-relevant events (PCI DSS Requirement 10).
- One encryption service.
- Spring Boot rules C1-C8: no card numbers in entities, no CVV, no secrets in configuration, TLS outside, authorisation on sensitive operations, scanned dependencies, audited events.

The owner's examples and rules are taken as requirements. Where one would weaken a rule already held, this file says what was done instead and why.

## What was measured first (2026-11-21)
- **Stereotypes.** Every class in `service.impl` already has `@Service`. `@Component` sits on filters, HTTP adapters, fakes, the key services and the simulators. Those are infrastructure, not services, except the two simulator engines.
- **Logging.** Services log every state change at INFO and their decisions at DEBUG (D-41). Seven public service methods log nothing: `PaymentServiceImpl`, `OverviewServiceImpl`, `DemoServiceImpl`, the reconciliation entry points, two `find` methods.
- **Sensitive data.**
  - The log layer masks card numbers only. A card token, which can be charged, a bearer token, an API key and a `password=` field pass through.
  - Five records print a card token from their generated `toString()`: `ChargeCommand`, `PaymentCommand`, `LinkPayRequest`, `CardResponse`, `DemoService.VaultRow`.
  - The dashboard's recent-log view and third-party error codes are cleaned to one bounded line, not masked.
- **Audit.** No dedicated audit trail. A refused credential, a refused scope and each opening of a card are not logged at all.
- **Constants.** 70 named constants in `src/main`. Most are facts of a standard, a protocol or the schema, and must not be configurable. A few are policy (the deposit percent and lead time, the webhook back-off, the business date's staleness, batch and list sizes). Four are demo values inside the production reconciliation service.
- **Clean code** (the JDK's own parser, 515 methods): 9 method bodies over 30 lines, 25 methods over 4 parameters (most are constructors receiving Spring beans or entity fields), 3 classes over 300 lines. The worst are in the simulators.
- **TLS.** The webhook must be `https`. The OPERA and processor addresses, and the PostgreSQL connection, are not checked.

## Scope
1. **Nothing sensitive enters a log, at any boundary.**
   - One redactor masks card numbers, card tokens, JWTs, bearer and basic credentials, API keys, and the value of any field named like a secret or a card verification code.
   - It is applied to the text and the JSON formats (message, exception, log context), the dashboard's recent lines, and the error codes read from OPERA and the processor.
   - Every type with a sensitive component prints a redacted `toString()`.
   - No log call is handed a raw body, request or response.
   - No field, record component or column holds a card verification code.
2. **An audit trail (PCI DSS v4.0.1 Requirement 10.2).**
   - What it records: refused credentials, refused scopes, every state-changing API call (client, route, status), each card stored and each card opened, each payment captured, declined or refused, the start and stop of the trail, and the non-secret payment configuration in force at start-up.
   - It has its own logger, held at INFO whatever the application's level.
   - Card identity is a one-way fingerprint, never the token.
3. **Logs in services.** Every public service method logs its entry at DEBUG, naming the operation and the safe identifiers (ids, keys, amounts, statuses).
4. **Configuration, not hardcoded policy.**
   - The deposit rule, the webhook back-off, the business date's staleness, and the relay's batch size become validated properties, with values in `application.properties`.
   - The demo statement's distortion moves out of the production service.
   - Facts of a standard, protocol or schema, and security floors, stay named constants (written down as a rule).
5. **TLS for everything outside.** With the HTTP gateway, the OPERA and processor addresses must be `https`. A PostgreSQL URL must ask for `sslmode=verify-full`. Only an explicit `foliopay.allow-plaintext-transport=true` (the local Compose stack) relaxes this.
6. **One encryption service.** `Cipher`, `KeyGenerator` and the KMS client are used only in `dev.foliopay.crypto` (`ArchitectureTest`).
7. **Stereotypes.**
   - `@Service` for application logic: `service.impl` and the simulator engines.
   - `@Component` for infrastructure.
   - Held by `ArchitectureTest`.
8. **Clean-code limits a build holds** (the JDK parser, no new dependency):
   - Method bodies of 30 lines at most.
   - At most 4 parameters, constructors excepted.
   - Classes of 300 lines at most.
   - Today's exceptions are named in the test and may only be removed.
9. **Comments.** Comments that restate the code or repeat a decision record are removed from the service layer. Comments that say why stay.
10. **Rules written down.** The engineering standards, `CLAUDE.md`, `SECURITY.md` (C1-C8 and the PCI requirements with their status) and the agent log.

## Not in this phase (need the owner)
- **Retention of vaulted cards** (PCI DSS 3.2.1): how long a card stays after the stay ends is a business and legal decision.
- **A vulnerability scan in CI** (OSV-Scanner or OWASP Dependency-Check): a new build dependency (CLAUDE.md rule 7). Today: Dependabot, plus OSV before a release.
- **Hosted card entry**, so no card number reaches the bridge (roadmap Next 2): an architecture change.
- **Audit-log retention, immutability and time synchronisation** (10.5, 10.6, 10.7): CloudWatch log-group retention, S3 Object Lock and Amazon Time Sync, in the deployment, not the code.
- **Localised message texts**: error `detail` texts stay beside their stable `code` (D-29). A message bundle is worth it only when a second language exists.
