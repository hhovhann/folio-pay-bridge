# Roadmap

Phases are small and shippable. Each has a folder `specs/YYYY-MM-DD-pN-<name>/` with `requirements.md`, `plan.md`, `validation.md`.
A finished phase gets `Status: done YYYY-MM-DD` directly under its heading; `tools/check_specs.py` then expects every box in its `validation.md` ticked (boxes marked `(manual)` are allowed to stay open).
Decisions are in `specs/decisions.md`; what the agents got wrong is in `docs/agent-log.md`.

## Phase 1 - Card vault and tokenisation
Status: done 2026-11-17
- A card is tokenised on arrival. The database holds ciphertext, a wrapped key and the digits PCI allows, never the number.

## Phase 2 - Idempotent virtual-card charging
Status: done 2026-11-17
- A card is charged exactly once however the request is repeated, raced, or lost on the wire.

## Phase 3 - Folio posting, dead letter and replay
Status: done 2026-11-17
- A captured payment always reaches the guest folio, or waits visibly with a reason until it can.

## Phase 4 - Nothing sensitive in logs
Status: done 2026-11-17
- No log line, anywhere, shows a card number; if one ever does, it is masked.

## Phase 5 - Booking events and payment requests
Status: done 2026-11-17
- A booking from the PMS becomes the right payment request, and paying it charges the card and posts to the folio.

## Phase 6 - Reconciliation (PMS and captures against the acquirer statement)
Status: done 2026-11-17
- Finance can see, per transaction, whether captured, settled and posted agree, and what to do when they do not.

## Phase 7 - Shadow mode against the legacy rules
Status: done 2026-11-17
- A hotel can be moved over with evidence: the same bookings through old and new, with every difference named.

## Phase 8 - Operator console
Status: done 2026-11-17
- Everything above can be driven and seen from one page, including the failures.

## Phase 9 - Delivery: container, PostgreSQL profile, CI
- Runs on Amazon Linux 2023 with Java 17, against PostgreSQL, built by CI.

## Phase 10 - Demo video, agent log and outreach
- A reviewer can see it work in four minutes, and read how the agents were directed and what they got wrong.

## Phase 11 - Full-flow simulators over HTTP
Status: done 2026-11-17
- The bridge runs against an OPERA-style PMS and a processor as separate HTTP services, in Docker, with the failures a real integration has.

## Phase 12 - Measured coverage and a catalogue of edge cases
Status: done 2026-11-17
- A coverage number with a gate, a page of every edge case with its test, and a checker that keeps the page honest.

## Phase 13 - Fixes from the final code review
Status: done 2026-11-18
- Every confirmed review finding fixed with a test that fails without the fix; documentation claims corrected. Decisions recorded with the owner's approval in phase 14.

## Phase 14 - Production hardening, authentication, notifications
Status: done 2026-11-18
- OAuth2 or API keys with scopes, Flyway, signed webhooks, remote creation of payment requests, every review finding fixed and mutation-checked, AI guardrails enforced by a hook and CI. Real AWS, a real identity provider and a real OHIP sandbox are open, as manual boxes.

## Phase 15 - Production readiness review
Status: done 2026-11-18
- The final review's findings fixed (credentials never reject a folio, asking for money is not taking it, idempotency keys per client, Cognito tokens, unreadable processor replies, the webhook window); the API versioned with a deprecation path; RFC 9457 errors; logging that is safe at DEBUG and JSON in production; foreign keys and Hibernate settings; the layer rules as a test; a command guard that reads every argument.

## Phase 16 - OPERA on Oracle's published contract
Status: done 2026-11-18
- The OPERA adapter and the simulator on Oracle's OHIP operations and shapes (token, business date, billing payment, folio), checked exchange by exchange against Oracle's Swagger files; the simulator becomes a front desk (reservations, folios with room charges and payments, balance, check-in and check-out, night audit). OPERA cannot be run locally: the sandbox questions below are what the first day with real access answers.

## Phase 17 - Fixes from the OHIP code review
Status: done 2026-11-19
- Three of the review's important findings fixed, each with a test that failed first and a deliberate bug the test catches:
  - a long folio is read page by page, so its guest can be paid, and every page that cannot prove absence is still "unavailable";
  - a payment on the folio is found by its transaction number, whatever else of it can be read;
  - the hotel's OPERA settings have no default.
- The fourth finding (folio history) waits for a sandbox (Next 4).

## Phase 18 - Readable test names, and the review's smaller findings
Status: done 2026-11-19
- Every test and test class says in a `@DisplayName` sentence what it proves, enforced by the build. The build also enforces that the count of lines over 150 characters only goes down, and that every version in the pom is a property.
- The OHIP review's smaller findings are fixed:
  - UTF-8 Basic credentials, as Oracle documents them;
  - the full token reason;
  - the simulator's rooms and repeated bookings;
  - phase 16's long lines;
  - agent log 52's test.

## Phase 19 - Cleanup, and the final code and security review
Status: done 2026-11-20
- Code only tests used is removed; one check that could not fail is gone; the icons are one sprite; dead references in the docs are fixed.
- The final code review's findings are fixed, each with a test that failed first: a reply that is not a folio, an unreadable settlement statement, the guards, the spec checker, the dashboard's date, the webhook lease.
- Dependencies with published advisories are pinned to patched releases; the two left are written down.
- Every rule called enforced checks what it says: the style rules read the tests too, and every API operation is tested both succeeding and refused, measured from the requests the tests send.
- The standards gain a performance section (no cost that grows with the hotel, N+1 measured) and a table of principles with what holds each.

## Phase 20 - Who is who: every party in a card payment, for someone new
Status: done 2026-11-20
- An interactive page shows the eleven parties and one payment in nine steps: messages in seconds, money in days. Each party is marked built here, simulator, or played by the processor simulator.
- The processor simulator names the card network and the issuer's approval code, and shows the chain behind it.
- The video gets a "Who is who" chapter. The README opens with a path for each reader. Onboarding starts with an hour that needs no code.

## Phase 21 - Safe logs, an audit trail, and code rules a build holds
Status: done 2026-11-21
- Nothing sensitive in a log at any boundary (`LogRedactor`; six records that printed a card token now redacted; no raw body or exception message in a log call). An audit trail of security-relevant events that cannot be switched off (D-43).
- PCI DSS v4.0.1 mapped requirement by requirement in `SECURITY.md`: card retention (D-44), verified TLS outside the process (D-45), dependency scanning in CI (D-46).
- Policy values are configuration, demo values stay in the demo, and `CleanCodeTest` and `ArchitectureTest` hold the size limits, the entry logs, the stereotypes and one encryption service.

## Phase 22 - Standard tools for the coding rules
Status: done 2026-11-21
- Checkstyle holds imports, braces, line, method and file length, parameter count and full class names; ArchUnit holds the architecture; `ProjectRulesTest` holds the few rules no tool can check. About 920 lines of hand-written checkers are gone.

## Next
1. Multi-tenancy: operators and properties on every row, credential and webhook; per-property settings; data residency (`docs/architecture.md` section 9).
2. Hosted card entry and 3-D Secure, so the card number never reaches the service; chargeback evidence. The acquirer's own hosted checkout (for example Visa Acceptance Solutions' Unified Checkout, with its pay-by-link and 3-D Secure) takes the card in its fields and gives the bridge a token. The bridge's payment links become the acquirer's links, the vault holds no card numbers, and the bridge leaves most of PCI DSS scope (`SECURITY.md`, "Scope"). Until then, card retention counts from a card's last use; with the booking known to the vault it could count from check-out (D-44).
3. A sweeper for payments stranded by a crash between two steps; alarms on dead letters and failed webhooks.
4. Real adapters: OHIP sandbox, an acquirer's test environment, KMS and Cognito in an AWS account. For OHIP, the first sandbox session settles what Oracle's specification leaves open (D-42): whether a folio window's `totalResults` counts postings or folios (phase 19); does `postBillingPayment`'s 201 carry the postings or only a `Location`; the sign of a payment on a folio; whether payments before arrival must be deposits (`action: Deposit`, `getDepositFolio`) at each hotel; the status OHIP gives a locked reservation; the rate limits; how `getFolio` pages (does `offset` count postings, the default page size, the order of postings while the front desk is posting); whether postings billed to a generated folio are listed without `includeFolioHistory=true` (the OHIP review's third finding: if not, a replay after the folio is billed could post again). Then run Oracle's Postman collections against the sandbox, and point the bridge at it by configuration (`FOLIOPAY_HTTP_OPERA_*`).
5. Store the business date OPERA posted on (read back by `find`) beside the bridge's own, so reconciliation can explain a charge booked before night audit and posted after it.
6. CI quality and security gates beyond OSV-Scanner (D-46) and Checkstyle and ArchUnit (phase 22), each a new dependency for the owner to approve:
   - static analysis with a quality gate (SonarQube or SonarCloud);
   - a formatter that rewrites the code to Google style (Spotless with google-java-format, proposed in phase 15);
   - GitHub's CodeQL code scanning, and secret scanning with push protection;
   - mutation testing over the whole code (PIT) instead of the per-fix mutation scripts.
