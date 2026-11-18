# Phase 5 Requirements - Booking events and payment requests

## Goal
A booking from the PMS becomes the right payment request, and paying it charges the card and posts to the folio.

## Scope
- `PaymentRequestPolicy`: advance purchase pays in full within 24 hours; flexible far out pays 20% seven days before arrival; flexible near arrival pays in full
- Events NEW, MODIFIED, CANCELLED; repeats are idempotent; a changed amount supersedes the open request
- A cancellation after payment flags a refund review instead of guessing
- Paying a link goes through the normal payment path with the key `link:<id>`

## Out of scope
- Real card networks, 3-D Secure, a hosted payment page, and any real PMS or processor
