# Phase 12 Validation - Measured coverage and a catalogue of edge cases

- [x] `./mvnw verify` passes the coverage gate: 99.83% of lines and 92.34% of branches (gate: 99% and 90%), 2026-11-17
- [x] `python3 tools/check_specs.py` checks the tests named in `docs/edge-cases.md` and fails if one is missing or did not pass
- [x] `FullFlowHttpIntegrationTest.aRateLimitIsWaitedOutAndRetried`
- [x] `FullFlowHttpIntegrationTest.aLockConflictOnTheReservationIsRetried`
- [x] `SimulatorEndpointsTest.aThrottleAndALockConflictEachHitOnlyTheNextPaymentThenClear`
- [x] `HttpAdaptersTest.aPaymentIsFoundBackWithItsAmountRestoredToMinorUnitsInEachCurrency`
- [x] `EntityStateTest.aCaptureBeatsADeclineWhichBeatsPending`
- [x] `DemoServiceImplTest.whenTheOutsideWorldIsSeparateTheStateSaysSoAndTheFaultsCannotBeArmedHere`
- [x] The full-flow demo's dry run (`DEMO_FAST=1 make_fullflow_demo.py`) drives every edge-case scene against the Docker stack and asserts the narration (2026-11-17)
