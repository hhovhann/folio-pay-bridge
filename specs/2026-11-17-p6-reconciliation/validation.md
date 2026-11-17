# Phase 6 Validation - Reconciliation (PMS and captures against the acquirer statement)

- [x] `ReconciliationServiceImplTest.aCleanStatementMatchesEverythingWithNoExceptions`
- [x] `ReconciliationServiceImplTest.aCaptureTheAcquirerNeverSettledIsMissing`
- [x] `ReconciliationServiceImplTest.aShortSettlementIsAnAmountDifference`
- [x] `ReconciliationServiceImplTest.aSettlementInAnotherCurrencyIsNotAMatchEvenWithTheSameNumber`
- [x] `ReconciliationServiceImplTest.aLineWeNeverCapturedIsUnknown`
- [x] `ReconciliationServiceImplTest.aSettledButUnpostedCaptureIsFlaggedForThePms`
- [x] `ReconciliationServiceImplTest.aDoublePresentmentIsNotSilentlyDropped`
- [x] `ReconciliationServiceImplTest.shadowRunsAreNotRevenueAndStayOutOfTheReport`
- [x] `SettlementParserTest.aBadAmountIsNotSilentlySkipped`
- [x] `SettlementParserTest.aNegativeAmountIsNotAcceptedAsASettlement`
- [x] `SettlementParserTest.aReferenceThatCouldRunAsASpreadsheetFormulaIsRefused`
- [x] `SettlementParserTest.aWrongHeaderFailsLoudly`
