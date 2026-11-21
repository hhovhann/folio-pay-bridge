# Phase 19 Validation - Cleanup, and the final code and security review

## Cleanup
- [x] The tests that read the removed methods assert the same facts through what production uses: `ShadowIntegrationTest.compatibleRulesMatchTheLegacyStandInOnEveryBooking`, `ChargeServiceImplTest.aKeyIsLookedUpInTheCallersOwnNamespace`, `PostingServiceImplTest.theLeaseRunsFromTheClockForLongerThanOneAttempt`
- [x] A posting that never releases its lease fails four replay tests and not the unit test that claimed to check it (agent log 58; the mutation re-run on the final code): `PaymentFlowIntegrationTest.anOperaOutageKeepsTheCapturedPaymentAsAReplayablePostingThatPostsExactlyOnce`, `PaymentFlowIntegrationTest.parallelReplaysOfOneDeadLetteredPostingPostItOnce`, `FullFlowHttpIntegrationTest.anOperaOutageDeadLettersTheCapturedPaymentAndReplayPostsItOnceOperaIsBack`, `FullFlowHttpIntegrationTest.parallelReplaysAgainstANonDeduplicatingOperaPostOnce`
- [x] The money rule allows one floating-point exception fewer: `ArchitectureTest.moneyIsNeverFloatingPointOrBigDecimal`
- [x] Every page draws its icons from one sprite, and the demo still renders them: the full-flow dry run with a still per scene

## The review's findings, each test failing first
- [x] `HttpAdapterRepliesTest.aReplyThatIsNotAFolioIsUnavailableButAFolioWithNoWindowsProvesThePaymentAbsent`, `HttpAdapterRepliesTest.aFolioWithNoWindowsYetProvesThePaymentAbsent`, with its twin `HttpAdapterRepliesTest.aFolioWithNoContentIsNotFound`
- [x] `HttpAdapterRepliesTest.aSettlementStatementThatCannotBeReadIsUnreadableNeverLinesWithBlankReferencesOrZeroAmounts`, `HttpAdapterRepliesTest.anEmptyStatementIsNothingSettledAndAReadableLineIsReadExactly`
- [x] `PaymentControllerTest.anAnswerFromOutsideThatCannotBeReadIsA503ToRetryNotAServerError`
- [x] `WebhookPropertiesTest.theSendLeaseCoversTwoWholeSendsAndIsNeverUnderAMinute`
- [x] `NotificationServiceImplTest.aLostRaceOnOneEventDoesNotHoldUpTheRestOfTheBatch`
- [x] The dashboard's default due date is the local day: `format.test.ts` (fails with the old UTC code)
- [x] `python3 tools/guard_commands.py --self-test`: 43 refused, 33 allowed; four deliberate breaks each caught (run when the fix was made, agent log 59; the self-test re-run on the final code)
- [x] `python3 tools/guard_secrets.py --self-test` (new, in CI): five checks; two deliberate breaks each caught (run when the fix was made; the self-test re-run on the final code)
- [x] `tools/check_specs.py` counts a name as passed only if every run of it passed (shown on a report with a failing first run when the fix was made)

## Every rule called enforced checks what it says (agent log 60)
- [x] The style rules read the tests too, and a class written out in full in a test fails the build: `CodeConventionsTest.classesAreImportedNotWrittenOutInFull` (a deliberate break caught)
- [x] Every operation is refused without credentials and to every scope but its own, and an operation without a stated scope fails: `SecurityIntegrationTest.everyOperationTheApiDescribesIsRefusedWithoutCredentialsAndToEveryScopeButItsOwn` (two deliberate breaks caught: a reading client allowed to create requests, an operations endpoint left open)
- [x] Sending again an event that does not exist is a not-found problem: `WebhookIntegrationTest.sendingAgainAnEventThatDoesNotExistIsANotFoundProblem` (a deliberate break caught)
- [x] `python3 tools/check_specs.py` fails on an operation never seen succeed or refused, and on one that takes input refused only for credentials (three deliberate edits of the recorded calls, each caught)
- [x] N+1 measured: `QueryCountIntegrationTest.*` (a planted lookup per row took `/charges` from 4 statements to 31 and failed it, when the test was written)

## Security
- [x] OSV: the resolved dependencies have no published advisory but the two in Spring MVC 6.2.19 written down in SECURITY.md (160 dependencies, re-run 2026-11-20)
- [x] `npm audit`, build and test tools included: no advisory
- [x] Every blob in the git history through the secret guard: no finding (857 blobs, re-run 2026-11-20)

## The whole build
- [x] `./mvnw clean verify` (the coverage gate), `python3 tools/check_specs.py`, `python3 tools/guard_secrets.py --repo`
- [x] The front desk in a real browser (`demo/check_frontdesk.py`) and the dashboard (`demo/check_dashboard.py`)
- [x] The full-flow dry run (`DEMO_FAST=1 demo/make_fullflow_demo.py`)
