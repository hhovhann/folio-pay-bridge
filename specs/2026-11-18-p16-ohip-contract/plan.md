# Phase 16 Plan

1. Research what can be run (nothing of OPERA's, locally) and where the contract is published (`oracle/hospitality-api-docs`, UPL). Read the four operations the bridge needs, with their schemas, in Oracle's files.
2. Compare them with the adapter and the simulator and list every difference (paths, token flow, body, business date, lookup, errors).
3. Ask the owner before adding the validator (CLAUDE.md rule 7). Choose a version whose dependencies converge with Spring Boot's (agent log 53).
4. Port first (`find` names the reservation; no date on a payment; cashier number, payment method). Then the adapter, the fake, the simulator and its front desk.
5. `OhipContractTest` with a recording interceptor; a test that the check refuses the old shapes; tests for each new reply case (partial folio, credit sign, generated postings, 204, decimals).
6. Break each new protection on purpose and require its test to fail (`mutate_ohip.py` in the scratchpad, not committed).
7. `./mvnw clean verify`, `tools/check_specs.py`, `tools/guard_secrets.py --repo`, the full-flow dry run on Docker, the front desk in a real browser.
8. Decisions (D-42, D-23 and D-33 corrected), tech stack, agent log, standards, docs; a branch for the owner to merge beside the work in progress on `main`.
