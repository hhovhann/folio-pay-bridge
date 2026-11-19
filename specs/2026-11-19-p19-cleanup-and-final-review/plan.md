# Phase 19 Plan

1. Find what nothing uses:
   - a scan of the main code for methods, types and constants with no caller outside tests (not committed);
   - the dashboard's exports, the console's handlers and the configuration keys;
   - `mvn dependency:analyze`;
   - every path and every `Class.method` the docs name.
   Read each candidate before removing it; a method the framework calls stays.
2. Before removing a getter a test reads, find what proves the behaviour through the real path: break the code on purpose and see which tests fail.
3. Run the final code review. Confirm each finding by reading the code or by a test that fails first. Then fix it, with its legitimate twin tested too, so a fail-safe is not made too strict.
4. Security review:
   - OSV for the Maven dependencies;
   - `npm audit`;
   - the git history through the secret guard;
   - GitHub's alerts;
   - the configuration read.
5. Put each fix to a guard or a money path back on purpose and require its check to fail.
6. Run `./mvnw clean verify`, `tools/check_specs.py`, both guards' self-tests and `--repo`, the front desk and the dashboard in a real browser, and the full-flow dry run.
