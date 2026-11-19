# Phase 17 Plan

1. Check each finding against the code and Oracle's bundled specification (`src/test/resources/ohip/csh.json`). The review's output is evidence, not authority.
2. Write a test for each finding and see it fail on the old code (all five failed, each for the reason the review gave):
   - a long folio read page by page;
   - a found payment with an unreadable amount or date;
   - the start without each hotel setting;
   - a paged folio in the contract test.
3. Fix the adapter (`HttpOperaClient.find`, `searchWindow`, `nextPage`, `found`), the port (`Posted` with an optional amount and date), the settings (`OperaProperties`), the simulator (paging), and the tests and profiles that relied on the old defaults.
4. Put each fixed bug back on purpose with `var/mutate_p17.py`, which is not committed. The script reads each named test's surefire report, so a run that executed nothing cannot count as caught (agent log 31).
5. Run `./mvnw clean verify`, `tools/check_specs.py`, `tools/guard_secrets.py --repo` and `tools/guard_commands.py --self-test`, then the full-flow dry run on Docker. That run starts the Compose profile, which now has to supply the hotel's settings.
6. Update the docs:
   - D-42's account of `getFolio`;
   - the roadmap's sandbox questions;
   - the edge cases;
   - the agent log entry, cited in the engineering standards;
   - phase 16's validation line for the renamed test.
