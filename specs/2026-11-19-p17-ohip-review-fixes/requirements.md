# Phase 17 Requirements - Fixes from the OHIP code review

## Goal
Fix the three important findings of the code review of phase 16 (`/code-review`, high effort) that the owner chose: 1, 2 and 4. Each is confirmed by a test that fails on the old code, then fixed, then put back on purpose to prove the test catches it.

## Scope
- **Finding 1: a long folio stopped its guest being paid.** The bridge looks for its payment before every post, the first one too, and a folio window that said `hasMore` ended every look as "unavailable". Read the window page by page with Oracle's `folioWindowNo` and `offset` until the last page. Keep the fail-safe: anything that cannot prove the payment absent stays "unavailable", never "not found":
  - a page with nothing on it after a promise of more;
  - a window with no number to ask for;
  - a reply without the window asked for;
  - a total larger than what was read;
  - more than 50 pages.
- **Finding 2: a found payment with an unreadable amount or date was "unavailable".** Our reference and OPERA's transaction number identify the payment. Read the amount and date if they can be read; the port's `Posted` carries them as optional. Shadow mode reports an unreadable amount as a mismatch.
- **Finding 4: the hotel's OPERA settings defaulted to the simulator's.** The hotel id, payment method and cashier have no default (`@Validated`, `@NotBlank`, `@Positive`), and a start without one fails. The demo and Compose profiles and the tests set the simulated hotel's values.
- The simulator pages `getFolio` as Oracle defines it, with a switch for the page size; `OhipContractTest` checks every page, asked and answered, against the contract.

## Out of scope
- Finding 3 (`includeFolioHistory`, postings billed to a generated folio). It depends on what OPERA returns, which only a sandbox can show. It is listed in `specs/roadmap.md`, Next 4.
- The review's six smaller findings:
  - the token error text;
  - Basic credentials encoding;
  - the inline version in the pom;
  - long lines;
  - agent log 52 naming its test;
  - the simulator's room numbers.
- How OPERA really pages: whether `offset` counts postings, its page size and the order of postings. These are sandbox questions. The adapter is written so that a wrong guess fails safe: it reports "unavailable" and never posts twice.
