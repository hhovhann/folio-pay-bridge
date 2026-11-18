# Phase 14 Requirements - Production hardening, authentication, notifications

## Goal
Take the pilot from "safe on loopback" to "shaped for production at a hotel-payments platform's scale": every finding of the two final reviews fixed with a test that fails without the fix; authentication and authorisation the way payment APIs do it; the schema under migrations; the integrator told what happened; AI guardrails enforced by tooling.

## Scope
- Review fixes: a processor refusal is final and releases the hold; an unclassified charge error is saved PENDING with the hold kept; the posting lease is derived from the timeouts, owned and renewed before each post; reconciliation per settlement period and per currency; the business date never goes back, and the first PMS answer after an outage is used at once; error codes with Retry-After; the full exception logged with a request id; the KMS key ARN stored per token; KMS keys refused on an in-memory database; the size limit for HTTP/2 bodies; health probes exempt from the host allowlist; the in-memory log copy only in the demo; Unicode digits outside the Basic Multilingual Plane masked; superseding a link in one transaction; the console filter fixed; OPERA error bodies parsed as JSON; a found OPERA payment without a transaction number never recorded as POSTED.
- Authentication (D-30): Spring Security resource server for OAuth2 client-credentials JWTs (JWKS, issuer, audience, expiry, scopes, optional scope prefix), and hashed scoped API keys; scopes per endpoint; fails closed with a real gateway.
- Flyway (D-12): a reviewed V1 baseline with indexes; `ddl-auto=validate` on PostgreSQL.
- Webhooks (D-35): payment captured or declined, posted or dead-lettered, derived from stored state, HMAC-signed, leased per send, retried with backoff, FAILED after the last attempt, redeliverable; a receiver in the OPERA simulator that verifies signatures.
- Remote creation of payment requests (`POST /api/links`).
- AI guardrails (D-36): a card-number and secret guard as a Claude Code hook and a CI step; project permission rules.
- Documentation: architecture and glossary, product map, security, runbook, decisions D-28 to D-36.

## Out of scope
- Multi-tenancy (designed in `docs/architecture.md` section 9), 3-D Secure and a hosted payment page, guest messaging, chargebacks, a sweeper for payments stranded by a crash.
- Running KMS, Cognito and the runbook in a real AWS account.
