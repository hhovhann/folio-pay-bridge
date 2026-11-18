# Phase 8 Validation - Operator console

- [x] `PaymentControllerTest.invalidInputIsRejectedBeforeAnyServiceIsCalled`
- [x] `PaymentControllerTest.aValidationErrorNamesTheFieldButNeverEchoesTheSubmittedValue`
- [x] `PaymentControllerTest.domainErrorsMapToTheirHttpStatus`
- [x] `PaymentControllerTest.anUnexpectedFailureReturnsAGenericMessageAndNoInternals`
- [x] `PaymentControllerTest.responsesCarryHardeningHeadersAndAreNeverCached`
- [x] `ReviewFindingsIntegrationTest.aRequestWithAForeignHostHeaderIsRefused`
- [x] `ReviewFindingsIntegrationTest.anOversizedBodyIsRefusedBeforeItIsParsed`
- [x] `ApiIntegrationTest.theChargeListNeverExposesTheCardToken`
- [x] `OpenApiContractTest.theCommittedSpecMatchesWhatTheCodeServes`
- [x] `OpenApiContractTest.aCardNumberIsAcceptedByExactlyOneOperation`
- [x] `OpenApiContractTest.noResponseSchemaCarriesACardNumberOrTheCardToken`
- [x] The page loads, a payment completes, and Swagger UI loads, with no browser console error, under a content security policy with no inline scripts (Playwright, re-checked after the review fixes)
- [x] The demo dry run (`DEMO_FAST=1`) drives every panel and asserts the narration against the screen (re-run after the review fixes)
