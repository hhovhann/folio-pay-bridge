# Phase 6 Requirements - Reconciliation (PMS and captures against the acquirer statement)

## Goal
Finance can see, per transaction, whether captured, settled and posted agree, and what to do when they do not.

## Scope
- Settlement statement as CSV (`psp_ref,amount_minor`), parsed strictly
- Matching per captured charge: missing, short or over, not posted to the PMS; and lines we never captured
- A report with expected and settled totals and the difference

## Out of scope
- Real card networks, 3-D Secure, a hosted payment page, and any real PMS or processor
