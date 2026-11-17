# Phase 4 Requirements - Nothing sensitive in logs

## Goal
No log line, anywhere, shows a card number; if one ever does, it is masked.

## Scope
- The code never logs a PAN (`Pan.toString` shows the last four)
- A Luhn-aware masker replaces any 13 to 19 digit card-shaped run, with spaces or dashes
- The masker is wired as the `%msg` converter for every appender and for the operator log shown in the console
- A test scans every log event of a full flow, including a rejection and an error

## Out of scope
- Real card networks, 3-D Secure, a hosted payment page, and any real PMS or processor
