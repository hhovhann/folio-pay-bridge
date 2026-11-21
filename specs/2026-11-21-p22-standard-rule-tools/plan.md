# Phase 22 Plan

1. Add Checkstyle 12.3.1 (the newest release that runs on Java 17) through `maven-checkstyle-plugin` 3.6.0, and ArchUnit 1.5.0 (its `slf4j-api` is the version Spring Boot manages, so the enforcer passes), each version a property, each with its reason in `specs/tech-stack.md`.
2. Wrap every line over 150 characters with a one-off script that breaks only outside strings and comments; compile and run every test to show nothing changed.
3. Write `config/checkstyle/checkstyle.xml`; run it; fix what it finds; mark today's size exceptions with `@SuppressWarnings`.
4. Rewrite `ArchitectureTest` with ArchUnit, keeping its test names; move the source-level rules to `ProjectRulesTest`; delete `CodeConventionsTest` and `CleanCodeTest`.
5. Update the standards, `CLAUDE.md`, the README, `SECURITY.md` and every spec that cited a deleted test.
6. Run `./mvnw clean verify`, `tools/check_specs.py`, both guards, and put a violation of each rule back on purpose to see the build refuse it.
