# Phase 21 Validation - Safe logs, an audit trail, and code rules a build holds

## Nothing sensitive enters a log
- [x] A card token, a signed token, bearer and basic credentials, an API key and any field named like a secret or a card verification code are masked; ids, keys, amounts and dates stay readable: `LogRedactorTest.aCardTokenIsMasked`, `LogRedactorTest.theValueOfAFieldNamedLikeASecretIsMasked`, `LogRedactorTest.aCardVerificationCodeOrPinIsMasked`, `LogRedactorTest.idsKeysAmountsDatesAndStatusesStayReadable`
- [x] Every record that holds a secret as text prints it redacted (failed first, on `PayRequest`): `SensitiveTypesTest.everyRecordThatHoldsASecretAsTextPrintsItRedacted`
- [x] No type and no table holds a card verification code, a PIN or track data: `SensitiveTypesTest.noTypeAndNoTableHoldsACardVerificationCodeAPinOrTrackData`
- [x] A third-party error that echoes secrets is redacted before it becomes our message: `HttpAdapterRepliesTest.aThirdPartyErrorThatEchoesSecretsIsRedactedBeforeItBecomesOurMessage`
- [x] No log call is handed a whole message, and exceptions reach a log only through `Redacted`: `CleanCodeTest.noLogCallIsHandedARawBodyRequestResponseOrCommand`, `CleanCodeTest.exceptionsReachALogOnlyThroughRedacted`
- [x] A full flow at DEBUG still logs no card number and no card token: `PciIntegrationTest.theCardNumberAndTheCardTokenAreNeverLoggedEvenAtDebugLevel`

## The audit trail (D-43)
- [x] Refused credentials and scopes, a payment's whole trail and a decline are recorded; reads are not: `AuditTrailIntegrationTest.anUnknownApiKeyIsRecordedAsAFailedAuthentication`, `AuditTrailIntegrationTest.aClientRefusedForItsScopeIsRecordedAsAccessDenied`, `AuditTrailIntegrationTest.aPaymentLeavesItsWholeTrailWithNoCardTokenOrNumber`, `AuditTrailIntegrationTest.aDeclinedPaymentIsRecordedAsAFailure`, `AuditTrailIntegrationTest.readsAreNotRecordedAsCalls`
- [x] The start records the configuration with secrets only as set or not set: `AuditTrailTest.theStartRecordsTheConfigurationByValueAndEachSecretOnlyAsSetOrNotSet`
- [x] An audit value cannot forge a field or a line: `AuditLogTest.aValueWithSpacesOrLineBreaksStaysOneValue`
- [x] The application does not start with the trail switched off: `FailClosedStartupTest.withTheAuditTrailSwitchedOffItDoesNotStart`

## Retention, transport, scanning (D-44, D-45, D-46)
- [x] A card unused past its period is destroyed; one with a payment pending, one used within the period and one opened since the search are kept; a backlog drains: `CardRetentionIntegrationTest.*`
- [x] A retention under a day is refused: `VaultPropertiesTest.aRetentionPeriodUnderADayIsRefused`
- [x] Plain HTTP to OPERA or the processor, and PostgreSQL without `verify-full`, stop the start: `TransportSecurityGuardTest.*`, `FailClosedStartupTest.withARealGatewayOverPlainHttpItDoesNotStart`
- [x] (manual) OSV-Scanner runs green in CI on the first push (`.github/workflows/osv-scanner.yml`; run 44871474797 on 22ea57f, success)

## Configuration and code rules
- [x] A different deposit policy and a different webhook back-off are applied as configured: `PaymentRequestPolicyTest.anotherHotelsPolicyIsAppliedAsConfigured`, `WebhookPropertiesTest.theDeliveryBackOffComesFromTheSettings`
- [x] The demo's messy statement lives in the demo service: `DemoServiceImplTest.theMessyStatementDropsOneCaptureSettlesOneShortAndAddsOneWeNeverCaptured`, `ReconciliationServiceImplTest.theAcquirersOwnStatementIsReconciledExactlyAsTheProcessorReportsIt`
- [x] Every public service method logs its entry; the size limits hold with today's exceptions named: `CleanCodeTest.*`
- [x] Services are marked `@Service` and nothing else is; encryption only in `crypto`: `ArchitectureTest.servicesAreMarkedServiceAndNothingElseIs`, `ArchitectureTest.encryptionAndKeyManagementHappenOnlyInTheCryptoPackage`
- [x] `CodeConventionsTest.theNumberOfLinesOver150CharactersOnlyGoesDown` at 182 (from 202)

## Findings of the code review (agent log 65)
- [x] A payment repeated after its card was purged gets its first result (failed first): `ChargeServiceImplTest.aRepeatOfAFinishedChargeGetsItsFirstResultEvenAfterItsCardIsGone`, `CardRetentionIntegrationTest.aPaymentRepeatedAfterItsCardWasPurgedGetsItsFirstResult`
- [x] A card purged before the first attempt is a final refusal, never a charge pending for ever (failed first): `ChargeServiceImplTest.aCardGoneBeforeTheFirstAttemptIsAFinalRefusal`
- [x] A URL that names verify-full but switches the check off, and signing keys over plain HTTP, stop the start: `TransportSecurityGuardTest.aUrlThatNamesVerifyFullButSwitchesTheCheckOffStopsTheStart`, `TransportSecurityGuardTest.aPoolSettingThatLowersSslmodeStopsTheStart`, `TransportSecurityGuardTest.theSigningKeysOverPlainHttpStopTheStart`
- [x] A business date staleness of zero or less is refused: `OperaPropertiesTest.aStalenessOfZeroOrLessIsRefused`
- [x] The configuration recorded at start includes the signing-key address: `AuditTrailTest.theStartRecordsTheConfigurationByValueAndEachSecretOnlyAsSetOrNotSet`

## Parameterized tests (agent log 67)
- [x] A plain test that loops over written-out inputs, directly or through a local, fails the build: `CleanCodeTest.aTestWithSeveralWrittenOutInputsIsParameterized`
- [x] Twenty-one input loops are `@ParameterizedTest`s, each run named after its input; their contrasting cases are tests of their own and cited beside them (`HttpAdapterRepliesTest`, `ApiKeysTest`, `HostAllowlistFilterTest`, `OpenApiContractTest`, `FailClosedStartupTest`, `TransportSecurityGuardTest`, `OperaPropertiesTest`, `PaymentRequestPolicyTest`, `WebhookPropertiesTest`): `HttpAdapterRepliesTest.aFolioWithNoWindowsYetProvesThePaymentAbsent`, `ApiKeysTest.aListOfOnlyBlanksAndCommasHasNoKeys`, `WebhookPropertiesTest.theShippedDeliveryScheduleStarts`

## Proof the checks can fail
- [x] `var/mutate_p21.py`: each deliberate bug is caught by its named test, and each test is confirmed to have run (28 cases, all caught; the first run caught 17 of 19, and the two misses became two new tests, agent log 63)

## The whole build
- [x] `./mvnw clean verify` (the coverage gate), `python3 tools/check_specs.py`, `python3 tools/guard_secrets.py --repo`, `python3 tools/guard_commands.py --self-test`
- [x] The Compose stack starts with `foliopay.allow-plaintext-transport=true` and the full-flow dry run passes (`DEMO_FAST=1 demo/make_fullflow_demo.py`, on ports 18081/18090/18091 because 8081 was taken: `DEMO_*_PORT`)
