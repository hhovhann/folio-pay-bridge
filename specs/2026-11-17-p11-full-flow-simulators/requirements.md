# Phase 11 Requirements - Full-flow simulators over HTTP

## Goal
Show the whole payment flow against separate systems over real HTTP, not in-memory fakes: a PMS (OPERA-style), a card processor, the bridge, a guest.

## Scope
- An OPERA simulator in the shape of OHIP: OAuth client credentials with an application key, expiring bearer tokens, `x-hotelid`, the hotel business date (moves only at night audit), payments posted to a reservation's folio window with a payment code and cashier, lookup by reference. It does not deduplicate. It can be down, rate-limit, report a lock conflict, post and then reply too late, and expire its tokens. A front-desk page shows its folios.
- A processor simulator: authorise, capture, void, settlement file; deduplicates on the Idempotency-Key header; can decline, decline a capture, and capture then reply too late. Keeps only the last four digits of a card. A dashboard page shows its ledger.
- The PMS pushes booking events to the bridge.
- HTTP adapters in the bridge for both ports (`foliopay.gateway=http`), with connect and read timeouts, status mapping (rejection vs retryable), transparent token refresh, and look-before-repost.
- Docker Compose runs bridge, PostgreSQL and both simulators; the same jar, started through a different main class.

## Out of scope
- Real Oracle OPERA or OHIP: there is no public container for it, and OHIP needs partner access and a sandbox account. The simulator's resource paths are simplified and unverified against Oracle's documentation. A real adapter must check them.
- A real processor, 3-D Secure, authentication between the bridge and its callers.
