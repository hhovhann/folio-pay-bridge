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
