# Phase 19 Validation - Cleanup, and the final code and security review

## Cleanup
- [ ] The tests that read the removed methods assert the same facts through what production uses: `ShadowIntegrationTest.compatibleRulesMatchTheLegacyStandInOnEveryBooking`, `ChargeServiceImplTest.aKeyIsLookedUpInTheCallersOwnNamespace`, `PostingServiceImplTest.theLeaseRunsFromTheClockForLongerThanOneAttempt`
- [ ] A posting that never releases its lease fails four replay tests and not the unit test that claimed to check it (agent log 58): `PaymentFlowIntegrationTest.anOperaOutageKeepsTheCapturedPaymentAsAReplayablePostingThatPostsExactlyOnce`, `PaymentFlowIntegrationTest.parallelReplaysOfOneDeadLetteredPostingPostItOnce`, `FullFlowHttpIntegrationTest.anOperaOutageDeadLettersTheCapturedPaymentAndReplayPostsItOnceOperaIsBack`, `FullFlowHttpIntegrationTest.parallelReplaysAgainstANonDeduplicatingOperaPostOnce`
- [ ] The money rule allows one floating-point exception fewer: `ArchitectureTest.moneyIsNeverFloatingPointOrBigDecimal`
- [ ] Every page draws its icons from one sprite, and the demo still renders them: the full-flow dry run with a still per scene

## The review's findings, each test failing first
- [ ] `HttpAdapterRepliesTest.aReplyThatIsNotAFolioIsUnavailableButAFolioWithNoWindowsProvesThePaymentAbsent`, with its twin `HttpAdapterRepliesTest.aFolioWithNoContentIsNotFound`
- [ ] `HttpAdapterRepliesTest.aSettlementStatementThatCannotBeReadIsUnreadableNeverLinesWithBlankReferencesOrZeroAmounts`
- [ ] `PaymentControllerTest.anAnswerFromOutsideThatCannotBeReadIsA503ToRetryNotAServerError`
- [ ] `WebhookPropertiesTest.theSendLeaseCoversTwoWholeSendsAndIsNeverUnderAMinute`
- [ ] `NotificationServiceImplTest.aLostRaceOnOneEventDoesNotHoldUpTheRestOfTheBatch`
- [ ] The dashboard's default due date is the local day: `format.test.ts` (fails with the old UTC code)
- [ ] `python3 tools/guard_commands.py --self-test`: 43 refused, 33 allowed; four deliberate breaks each caught
- [ ] `python3 tools/guard_secrets.py --self-test` (new, in CI): five checks; two deliberate breaks each caught
- [ ] `tools/check_specs.py` counts a name as passed only if every run of it passed (shown on a report with a failing first run)

## Security
- [ ] OSV: the resolved dependencies have no published advisory but the two in Spring MVC 6.2.19 written down in SECURITY.md
- [ ] `npm audit`, build and test tools included: no advisory
- [ ] Every blob in the git history through the secret guard: no finding

## The whole build
- [ ] `./mvnw clean verify` (the coverage gate), `python3 tools/check_specs.py`, `python3 tools/guard_secrets.py --repo`
- [ ] The front desk in a real browser (`demo/check_frontdesk.py`) and the dashboard (`demo/check_dashboard.py`)
- [ ] The full-flow dry run (`DEMO_FAST=1 demo/make_fullflow_demo.py`)
