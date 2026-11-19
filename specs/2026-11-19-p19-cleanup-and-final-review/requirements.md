# Phase 19 Requirements - Cleanup, and the final code and security review

## Goal
The owner asked for three things. Remove the redundant and unused code, docs, pictures and videos. Make sure the demo matches the code. Finish with a final code review and a security review, so the codebase is ready for production.

## Scope
- **Cleanup.**
  - Methods that only tests called: `ShadowReport.matchRate`, `ReconReport.Total.differenceMinor`, `Charge.getClientId` and `Posting`'s two lease getters. The tests now assert the same facts through what production uses.
  - A unit-test check that could not fail (agent log 58).
  - A test dependency nothing used (`spring-security-test`).
  - Icons copied into four pages, now one sprite (`icons.svg`).
  - Dead references in the docs (a deleted `docs/aws.md`; mutation scripts that were never committed).
  - Merged branches, a merged worktree, and local leftovers in `var/` (logs, screenshots, finished one-off scripts, the old video).
- **The demo matches the code.** The full-flow dry run asserts every narrated claim against the screen, the streaming panel included.
- **Final code review** (`/code-review`, high effort, over `src/main`, `dashboard/src`, `demo` and `tools`). Ten findings, each confirmed before it was fixed (agent log 59):
  - nine fixed, each with a test that failed first;
  - one, `totalResults` on a single folio page, answered with Oracle's contract instead (agent log 54);
  - one, `GET /charges` across clients, for the owner.
- **Security review.**
  - The resolved dependencies against the OSV database: the published advisories in Spring Boot 3.5.16's managed versions are pinned to patched releases, and the two left are written down with why they are not reachable.
  - `npm audit`.
  - Every blob in the git history through the secret guard.
  - GitHub's secret-scanning alerts.
  - The security configuration, the error handling, the card encryption and the API-key check, read.

## Out of scope
- The move to Spring Boot 4, which removes the two Spring MVC advisories: a planned migration (standards section 13).
- Whether `payments:read` lists every client's charges (D-37, D-40): the owner decides.
- Entities that stamp their created and updated times with `Instant.now()` instead of the injected clock: audit stamps that no business rule reads.

## Done when
Every box in `validation.md` is ticked after the check it names ran on the final code.
