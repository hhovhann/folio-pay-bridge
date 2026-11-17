# Phase 12 Requirements - Measured coverage and a catalogue of edge cases

## Goal
Replace "we tested it" with a number and a list: how much code the tests run, and which cases are handled, which test proves each, and which are not handled.

## Scope
- JaCoCo coverage with a gate in `./mvnw verify`.
- `docs/edge-cases.md`: every handled case with its test and status, and an explicit list of cases not covered.
- `tools/check_specs.py` fails if a test named in a checklist or in the edge-case page is missing or did not pass.
- A full narrated demo that walks the edge cases on screen against the HTTP simulators.

## Out of scope
- Claiming every possible case is covered. It cannot be proven; the page says what is not.
