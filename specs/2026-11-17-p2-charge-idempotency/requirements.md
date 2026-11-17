# Phase 2 Requirements - Idempotent virtual-card charging

## Goal
A card is charged exactly once however the request is repeated, raced, or lost on the wire.

## Scope
- Charge keyed by an idempotency key (unique in the database); same key and different parameters is `409`
- Authorise then capture, each with a derived key, retried on transient errors only
- A decline is final; a transient failure leaves the charge PENDING for a retry with the same key
- Processor port with a fake that can capture and then lose the response

## Out of scope
- Real card networks, 3-D Secure, a hosted payment page, and any real PMS or processor
