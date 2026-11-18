# Phase 15 Plan

1. Read the specs, the agent log and every main source file; confirm each review finding against the code before changing anything (three documentation findings were confirmed by reading; the code findings by a failing test).
2. Run the full suite first for a baseline (401 backend tests, the dashboard's tests: green).
3. Version the API (`ApiPaths.V1`), move errors to problem details, scope keys per client, split `links:write`, fix the adapters and the webhook window; write a V2 migration (additive where a rolling deploy needs it).
4. A test for each fix that names the failure it catches; then put each bug back on purpose (`var/mutate_p15.py`, not committed) and require its test to fail.
5. Logging, Hibernate settings, architecture test, conventions, invisible characters, the command guard and the recorder check.
6. `./mvnw clean verify` (coverage gate), `tools/check_specs.py`, `tools/guard_secrets.py --repo`, `tools/guard_commands.py --self-test`, the Compose stack on PostgreSQL 16, the full-flow dry run, the dashboard in a real browser.
7. Documentation, the agent log, one commit, a normal push.
