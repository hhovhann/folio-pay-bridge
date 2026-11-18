# Phase 13 Plan

1. Verify each finding against the code; drop none silently.
2. For each confirmed finding: a test that fails on the old code, then the fix.
3. Put the bug back on purpose (mutation check) for the important fixes and confirm the test fails.
4. Correct every documentation claim the review found false.
5. `./mvnw clean verify` (coverage gate) and `python3 tools/check_specs.py`.
