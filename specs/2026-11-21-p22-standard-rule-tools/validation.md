# Phase 22 Validation - Standard tools for the coding rules

## Checkstyle holds the style and size rules
- [x] `./mvnw validate` runs Checkstyle over `src/main` and `src/test` with 0 violations (`config/checkstyle/checkstyle.xml`)
- [x] Every line over 150 characters is wrapped; all test runs pass before and after (619, then 612 once the two replaced classes were deleted)
- [x] Size exceptions from before the check carry `@SuppressWarnings("checkstyle:...")` on their method (19 methods), and one file is named in `config/checkstyle/suppressions.xml`

## ArchUnit holds the architecture
- [x] `ArchitectureTest.*` keeps its test names on ArchUnit: `ArchitectureTest.controllersNeverTouchARepositoryAnImplementationAnAdapterOrTheVaultsCrypto`, `ArchitectureTest.servicesAreMarkedServiceAndNothingElseIs`, `ArchitectureTest.moneyIsNeverFloatingPointOrBigDecimal`, `ArchitectureTest.dependenciesAreInjectedThroughTheConstructor`, `ArchitectureTest.nothingPrintsToTheConsoleDirectly`

## One small test for the rest
- [x] `ProjectRulesTest.everyPublicServiceMethodBeginsByLoggingItsNameAndItsSafeParameters`, `ProjectRulesTest.noLogCallIsHandedARawBodyRequestResponseOrCommand`, `ProjectRulesTest.exceptionsReachALogOnlyThroughRedacted`, `ProjectRulesTest.aTestWithSeveralWrittenOutInputsIsParameterized`, `ProjectRulesTest.everyTestAndTestClassSaysWhatItProvesInADisplayName`, `ProjectRulesTest.everyVersionTheBuildDeclaresForADependencyOrAPluginIsAProperty`
- [x] `CodeConventionsTest` and `CleanCodeTest` are deleted; every spec that cited them cites what replaced them (`tools/check_specs.py`, 0 errors)

## Proof the checks can fail
- [x] `var/mutate_p22.py`: a violation of each rule put back on purpose is refused by the build, and each named test is confirmed to have run (22 of 22 caught: 8 Checkstyle, 9 ArchUnit, 5 `ProjectRulesTest`)

## The whole build
- [x] `./mvnw clean verify` (0 Checkstyle violations, 612 backend test runs and 16 dashboard tests, 99.41% lines and 91.76% branches), `python3 tools/check_specs.py` (0 errors), `python3 tools/guard_secrets.py --repo`, `python3 tools/guard_commands.py --self-test`
- [ ] (manual) CI on GitHub runs Checkstyle and the tests green
