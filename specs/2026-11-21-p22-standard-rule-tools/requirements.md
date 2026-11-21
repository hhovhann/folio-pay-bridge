# Phase 22 Requirements - Standard tools for the coding rules

## Goal
The owner asked why the project has classes like `CleanCodeTest`, when the rules belong in the engineering instructions and should be followed when the code is written. They asked for any redundant class to be removed: "we don't need to make rubbish".

The answer has two parts.
- The rules stay in the instructions (`docs/engineering-standards.md`, `CLAUDE.md`). A check on every build is what makes them hold. In phase 21 a written rule and a careful review both missed a request object that printed a card token, and seventeen service methods without their entry log; the build caught both.
- But about 920 lines of hand-written checking code had grown in four classes, with three different ways of reading source. Most of it rebuilt two standard tools, and our own standards prefer a reviewed standard to a hand-written version (KISS, log 38).

Owner's decision (2026-11-21): use standard tools for the standard rules, and keep one small test for the few rules no standard tool can check.

## Scope
1. **Checkstyle**, run in `validate` on every build, over `src/main` and `src/test`:
   - imports: sorted, static first, no wildcard, none unused;
   - braces on every `if`, `for`, `while`;
   - lines of at most 150 characters (every existing longer line wrapped, so the rule holds everywhere with no count to keep);
   - methods of at most 30 lines and 4 parameters, files of at most 350 lines;
   - no class written out in full in code.

   Code that broke a size limit before the limit was checked carries `@SuppressWarnings("checkstyle:...")`, so the exception is visible where it is, and can only be removed.
2. **ArchUnit**, in `ArchitectureTest`, rewritten with the same test names (the specs cite them): the layers, ports and adapters, the domain without Spring, package-private implementations, encryption only in `crypto`, `@Service` on services, money never floating point; plus no field injection and no console output.
3. **One small test, `ProjectRulesTest`**, for the rules no standard tool checks:
   - every public service method opens with its entry log;
   - no log call is handed a whole message or an exception's message;
   - several written-out inputs are a `@ParameterizedTest`;
   - every test and test class has a display name written as a sentence;
   - every version the build declares is a property.
4. `CodeConventionsTest` and `CleanCodeTest` are deleted. `SensitiveTypesTest` and `PciIntegrationTest` stay: they test this system's PCI properties, which no tool checks.
5. The instructions name the tool that holds each rule; every spec that cited a deleted test cites what replaced it.

## Not in this phase
- Spotless with google-java-format (a formatter, proposed in phase 15) and SonarQube or SonarCloud: roadmap Next 6, each a new dependency for the owner.
