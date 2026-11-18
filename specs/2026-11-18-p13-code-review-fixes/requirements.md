# Phase 13 Requirements - Fixes from the final code review

## Goal
Fix every finding of the final code review (Claude Opus, read-only, ranked) that held up when checked against the code, each with a test that fails without the fix.

## Scope
- H1: a charge left pending before night audit stays resumable after it; a payment-link claim is given back only when the stored charge shows no money moved.
- H2: one request at a time talks to OPERA about a posting; the fake PMS counts every post so tests can see a second one.
- M1-M7: no posting left PENDING after an unexpected error or unreadable reply; processor 429/409 are transient; no lock around the PMS date call; chunked bodies capped; wider PAN separators; a log test that reads raw events; reconciliation totals per currency.
- Low: optimistic-lock races are 409; HTTP mode fails closed without addresses or credentials; one token refresh for many 401s; a stricter PSP simulator; flaky or vacuous tests fixed; documentation claims corrected.

## Out of scope
- Decisions in `specs/decisions.md` are not changed here: a clarification of D-21 and a new D-28 (posting lease) are proposed to the owner.
- CSRF protection on the simulators' control endpoints, a sweeper for stale leases, paging reconciliation by settlement date (listed as not covered in `docs/edge-cases.md`).
