# Phase 4 Validation - Nothing sensitive in logs

- [x] `ProductionLogConfigTest.aCardNumberLoggedByMistakeIsMaskedOnTheConsole`
- [x] `PanMaskerTest.masksAllButTheLastFourOfALuhnValidNumber`
- [x] `PanMaskerTest.findsANumberWithOtherDigitsGluedToEitherSide`
- [x] `PanMaskerTest.findsANumberWithUnusualSeparatorsOrUnicodeDigits`
- [x] `PanMaskerTest.leavesShortNumbersAndDatesAlone`
- [x] `LogRedactorTest.safeAlsoFlattensLineBreaksSoAValueCannotForgeALogEntry` (moved from `PanMaskerTest` in phase 21)
- [x] `SafeTextTest.flattensLineBreaksAndControlCharacters`
- [x] `PanTest.toStringNeverContainsMoreThanTheLastFour`
- [x] `PciIntegrationTest.theCardNumberAndTheCardTokenAreNeverLoggedEvenAtDebugLevel`
