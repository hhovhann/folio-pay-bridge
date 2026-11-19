# Phase 16 Validation - OPERA on Oracle's published contract

## Oracle's contract, both sides
- [x] `OhipContractTest.aPaymentIsPostedAndFoundBackThroughOraclesOperationsInEachCurrency` (all four operations; USD, KWD, JPY and 800.00)
- [x] `OhipContractTest.aPaymentLandsOnOperasBusinessDateWhichMovesOnlyAtNightAudit`
- [x] `OhipContractTest.operasOwnRefusalsAreRejectionsCarryingItsErrorCode`
- [x] `OhipContractTest.anExpiredTokenIsReplacedOnceAndAnOutageIsUnavailable`
- [x] `OhipContractTest.theRequestIdTravelsToOperaWhenThereIsOne`
- [x] `OhipContractTest.theCheckRefusesTheInventedShapesTheBridgeUsedBeforeD42` (the check has teeth: old path, old body, missing header, unknown query parameter)

## Replies the adapter must not misread
- [x] `HttpAdapterRepliesTest.aFolioReturnedOnlyInPartIsUnavailableNotAbsent`
- [x] `HttpAdapterRepliesTest.aPaymentListedAsACreditIsFoundAndAChargeWithOurReferenceIsNot`
- [x] `HttpAdapterRepliesTest.aFolioWithNoContentIsNotFound`
- [x] `HttpAdapterRepliesTest.anOperaReplyWithoutATransactionNumberIsNotRecordedAsPostedWithABlankReference` (empty 201, a generated charge, a blank number)
- [x] `HttpAdapterRepliesTest.aFoundPaymentIsFoundByItsTransactionNumberEvenWhenItsAmountOrDateCannotBeRead` (including a fraction of a cent; phase 17 reversed what this box first pinned, as aFoundPaymentWeCannotReadIsAnOutageNotACrash: agent log 54)
- [x] `HttpAdapterRepliesTest.aBusinessDateWeCannotReadIsAnOutageNotACrash` (including another hotel's date)
- [x] `HttpAdapterRepliesTest.httpModeRefusesToStartWithoutItsAddressesAndCredentials` (enterprise id and scope)

## The simulator as a front desk
- [x] `SimulatorEndpointsTest.theTokenEndpointWantsBasicCredentialsTheAppKeyTheEnterpriseAndAScopeAndRefusesWhenDown`
- [x] `SimulatorEndpointsTest.aPaymentIsRefusedForEachReasonARealPmsWouldGiveWithOperasErrorCode`
- [x] `SimulatorEndpointsTest.aGuestChecksInIsChargedPaysAndCanCheckOutOnlyWhenTheFolioIsSettled`
- [x] `SimulatorEndpointsTest.nightAuditPostsARoomNightToEveryGuestInHouseOnTheDayItCloses`
- [x] `SimulatorEndpointsTest.aThrottleAndALockConflictEachHitOnlyTheNextPaymentThenClear`

## The bridge end to end
- [x] `FullFlowHttpIntegrationTest.aPaymentIsAuthorisedCapturedAndPostedToTheReservationFolioOverHttp` (payment method, cashier number, OPERA's business date, the processor's reference in the remark)
- [x] `FakeGatewaysTest.operaPostsPaymentsAndFindsThemByReferenceAndReportsTheClockDateAsItsBusinessDate` (the fake posts on its own date too)
- [x] `ShadowServiceImplTest.aPostingOperaCannotFindIsAMismatchOnBothFieldsAndTheSamplesAreBounded`

## Commands
- [x] `./mvnw clean verify -DskipDashboard=true`: 447 backend tests on the branch, 448 after the merge with the enforcer commit; 99.36% of lines and 91.53% of branches; the enforcer rules pass with the new dependency
- [x] Six protections broken on purpose, six caught: partial folio, a charge taken for our payment, trailing zeros, a fraction of a cent, a simulator shape Oracle does not define, the check's strict reading
- [x] `python3 tools/guard_secrets.py --repo`: 0 findings
- [x] `DEMO_FAST=1 uv run --project demo python demo/make_fullflow_demo.py`: every narrated claim asserted on the Docker stack, the image built from this branch
- [x] `uv run --project demo python demo/check_frontdesk.py`: reservations, a folio, check-in, a room night, check-out refused then allowed after a payment over OHIP, night audit, phone width, no browser error; screenshots looked at
- [ ] A real OHIP sandbox (manual): the questions in `specs/roadmap.md`, Next 4
