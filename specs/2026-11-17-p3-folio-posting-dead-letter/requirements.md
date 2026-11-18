# Phase 3 Requirements - Folio posting, dead letter and replay

## Goal
A captured payment always reaches the guest folio, or waits visibly with a reason until it can.

## Scope
- PMS port (Oracle OPERA / OHIP) with a fake: outage, lost response after posting, closed folio rejection
- One posting per charge (unique key); the PMS deduplicates on the external reference
- Retry on outage; then DEAD_LETTER with category, error and attempts; replay posts exactly once
- A rejection (closed folio) is not retried by replay: the posting waits for a person to fix the folio, and replay returns it untouched

## Out of scope
- Real card networks, 3-D Secure, a hosted payment page, and any real PMS or processor
