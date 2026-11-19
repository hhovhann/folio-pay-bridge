# Phase 18 Plan

1. Draft a display name for each test from its method name (`var/display_names.py`, not committed). Review all 444 names and 68 class names by hand (`var/display_names_reviewed.tsv`), then insert them.
2. Read the diff of the rewrite before running anything. Check that the surefire reports still carry method names, and change `tools/check_specs.py` where class display names replace class names.
3. Write each new check and test first and see it fail on the old state:
   - display names;
   - line count;
   - pom versions;
   - UTF-8 credentials;
   - the stored token reason;
   - rooms;
   - repeated bookings.
4. Fix each one, wrap phase 16's long lines and set the line count.
5. Put each fix or broken convention back on purpose (`var/mutate_p18.py`, not committed) and require its named test to fail.
6. Run `./mvnw clean verify`, `tools/check_specs.py`, the guards, the full-flow dry run (the token exchange changed) and the front desk in a real browser (the simulator's rooms changed).
7. Update the standards (sections 1, 3, 10, 12 and 13), the agent log, the README's counts and CLAUDE.md's description of `CodeConventionsTest`.
