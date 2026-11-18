# Phase 15 Requirements - Production readiness review

## Goal
Review the whole service as if it were going to production at a hotel-payments platform, fix what would hurt there, and answer the owner's questions with evidence: API versioning, central error handling, logging, Hibernate practice, clean architecture and Java conventions, prompt injection, the runtime image and the database.

## Scope
- Findings of the final code review (Claude Opus, high effort), each confirmed against the code before fixing:
  - OPERA refusing our credentials (token endpoint 4xx, 403) dead-lettered postings as permanent rejections; it is now replayable (D-34).
  - One scope (`payments:write`) let an app that may ask for money also charge any card token; payment requests now need `links:write` (D-37).
  - Idempotency keys were one global namespace: two integrators collided and could read each other's results, and the processor key was shared too. Keys are now per client and the processor key is the charge's own id (D-3, D-40).
  - Amazon Cognito client-credentials tokens (no `aud`) were refused; the scope prefix now binds a token to this API where no audience is set, only prefixed scopes count, ID tokens are refused (D-30).
  - A processor success without a reference, a 408, or a 404 from a wrong address became a blank reference or a final decline; now unreadable or transient (D-34).
  - The webhook relay's window was on creation, so a posting replayed after 30 days was never announced; it is on the last change (D-35).
  - Agent-log entries 42 and 43 and decision D-30 said more than the code did: the recorder's sample-rate check and a force-push guard that reads every argument are now built; D-30 is rewritten.
- The owner's questions: the API is versioned in the path with a deprecation mechanism (D-39); errors are RFC 9457 problem details (D-20, D-29); services log every state change at INFO and their decisions at DEBUG, never card data or tokens, and production logs are JSON (D-41); foreign keys, a version on card tokens, change timestamps, connection pool and UTC settings for Hibernate (D-12); the layer rules are a test (`ArchitectureTest`); Java conventions (imports, braces, no inline class names, verb method names); invisible characters and a command guard against prompt injection (D-24, D-36, CLAUDE.md rule 8).
- Documentation brought in line: decisions, security notes, edge cases, README, runbook, agent log.
- Engineering standards (`docs/engineering-standards.md`), loaded into every agent session by `CLAUDE.md`: each rule cites the agent-log entry it came from, the spec checker fails if an entry is not cited, and the conventions that code can check are a test (`CodeConventionsTest`). Frontend rules in `dashboard/CLAUDE.md`.

## Out of scope
- New dependencies (Testcontainers, a formatter plugin, a metrics registry): proposed to the owner, not added (CLAUDE.md rule 7).
- Multi-tenancy, hosted card entry and 3-D Secure, a crash sweeper, real AWS, a real OHIP sandbox and acquirer.
