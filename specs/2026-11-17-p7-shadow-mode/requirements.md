# Phase 7 Requirements - Shadow mode against the legacy rules

## Goal
A hotel can be moved over with evidence: the same bookings through old and new, with every difference named.

## Scope
- Deterministic synthetic bookings (seeded), across five time zones
- The new service run under two rule sets: strict new (tax on the total, UTC date) and legacy-compatible (tax per night, hotel local date)
- A report of matches, mismatches by field and sample rows; each booking really goes through charge and post

## Out of scope
- Real card networks, 3-D Secure, a hosted payment page, and any real PMS or processor
