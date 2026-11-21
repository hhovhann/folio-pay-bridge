# Phase 18 Validation - Readable test names, and the review's smaller findings

## Every test says what it proves
- [x] `CodeConventionsTest.everyTestAndTestClassSaysWhatItProvesInADisplayName`: 444 tests and 69 classes, each with a sentence. Its first run found `LuhnTest.rejectsNull`, which the rewrite had missed (a fully qualified `@Test`).
- [x] The surefire reports still carry method names. `tools/check_specs.py` reads the class from the test-suite name and finds every ticked test.

## The other conventions, enforced
- [x] `CodeConventionsTest.theNumberOfLinesOver150CharactersOnlyGoesDown`: failed at 296; 73 lines from phase 16 wrapped; 221 left
- [x] `CodeConventionsTest.everyVersionTheBuildDeclaresForADependencyOrAPluginIsAProperty`: failed on the schema validator's inline 2.0.4

## The review's smaller findings
- [x] `HttpAdapterRepliesTest.theClientIdAndSecretReachOhipAsBasicCredentialsInUtf8ExactlyAsIssued`: failed on the old code ("cannot be encoded to ISO-8859-1")
- [x] `HttpAdapterRepliesTest.operaRefusingOurCredentialsIsReplayableNotARejectionOfTheFolio`, `HttpAdapterRepliesTest.operaAnsweringWithoutATokenOrRefusingTheApplicationIsReplayableToo`: the stored reason names the enterprise id and scope; failed on the old text
- [x] `OperaSimulatorTest.aGuestCheckingInGetsARoomNoGuestInHouseHoldsEvenAfterAnotherGuestHasLeft`: failed on the old code (two guests in one room)
- [x] `OperaSimulatorTest.bookingAReservationThePmsAlreadyHoldsChangesNothing`: failed on the old code (back to due-in, no room)
- [x] Agent log 52 names `OhipContractTest.aPaymentIsPostedAndFoundBackThroughOraclesOperationsInEachCurrency`

## Commands
- [x] Eleven fixes or conventions put back on purpose, eleven caught, each named test confirmed in its surefire report (`var/mutate_p18.py`, not committed):
  - a test without a display name, and a test class without one;
  - a name that is not a sentence;
  - two tests with one name;
  - a fully qualified `@Test` with no name;
  - a new long line;
  - an inline version in the pom;
  - Basic credentials in ISO-8859-1;
  - the shorter token reason;
  - a repeated booking replacing the stay;
  - rooms numbered by the guests in house.
- [x] `./mvnw clean verify`: 458 backend tests and 15 dashboard tests; 99.38% of lines and 91.63% of branches; the coverage gate met
- [x] `python3 tools/guard_secrets.py --repo`: 0 findings; `python3 tools/guard_commands.py --self-test`: 0 wrong
- [x] `DEMO_FAST=1 uv run --project demo python demo/make_fullflow_demo.py`: the token exchange changed (UTF-8 credentials); dry run OK on Docker
- [x] `uv run --project demo python demo/check_frontdesk.py`: the simulator's rooms changed; front desk OK, no browser errors
