# Edge cases: what is covered, what is partly covered, what is not

"All possible cases" cannot be proven for any payment system, so this page does something more useful: it lists every case this project deliberately handles, the test that fails if the handling breaks, and, separately and plainly, the cases that are **not** handled. `python3 tools/check_specs.py` checks that every test named here exists and passed in the last run.

Status: **Covered** = a test fails if the behaviour breaks. **Partial** = covered against the simulators or fakes, not against the real system. **Not covered** = a known gap (section 12).

## 1. Card data
| Case | Handling | Test | Status |
|---|---|---|---|
| Card number written to the database | Only ciphertext, a wrapped key, first six and last four are stored | `PciIntegrationTest.theCardNumberIsNowhereInTheDatabaseAfterAFullFlow` | Covered |
| Card number written to a log | Never logged; masked if it slips in | `PciIntegrationTest.theCardNumberIsNeverLoggedAcrossTokenisationChargeAndPosting`, `ProductionLogConfigTest.aCardNumberLoggedByMistakeIsMaskedOnTheConsole` | Covered |
| Number glued to other digits, odd separators, Unicode digits, two numbers back to back | Union of every Luhn-valid window is masked | `PanMaskerTest.findsANumberWithOtherDigitsGluedToEitherSide`, `PanMaskerTest.findsANumberWithUnusualSeparatorsOrUnicodeDigits`, `PanMaskerTest.masksTwoNumbersWrittenBackToBackAsOneLongRun` | Covered |
| Number inside an exception message | Throwable converter masks it | `ProductionLogConfigTest.aCardNumberLoggedByMistakeIsMaskedOnTheConsole` | Covered |
| Ciphertext moved to another token, tampered, wrong key | Rejected (AAD binds the token) | `CardVaultImplTest.aCiphertextMovedToAnotherTokenCannotBeOpened`, `CryptoTest.aTamperedCiphertextIsRejected`, `CryptoTest.aWrongKeyIsRejected` | Covered |
| Key of the wrong size | Refused, no partial output | `CryptoFailureTest.aKeyOfTheWrongSizeIsRefusedWhenSealing` | Covered |
| Card held in memory while a retry waits | Vault opened per attempt, never around the wait | `ChargeServiceImplTest.theCardNumberIsNotHeldWhileARetryWaitsOrDuringCapture` | Covered |
| Card number printed through a request object | `toString` is redacted | `CardRequestTest.aCardNumberNeverAppearsWhenTheRequestObjectIsPrinted`, `PspSimControllerTest.aCardNumberNeverAppearsWhenTheAuthorisationRequestIsPrinted` | Covered |
| Number returned by any API or listed in the API description | No response or schema carries it | `OpenApiContractTest.noResponseSchemaCarriesACardNumberOrTheCardToken`, `ApiIntegrationTest.theChargeListNeverExposesTheCardToken` | Covered |
| Number reaches a system other than the processor | Only the processor receives it, and keeps four digits | `FullFlowHttpIntegrationTest.theCardNumberReachesOnlyTheProcessorAndOnlyItsLastFourAreKept` | Covered |
| Invalid card number or expiry | Refused without echoing it | `PanTest.refusesNumbersThatFailTheLuhnCheck`, `ApiIntegrationTest.aBadCardNumberIsRejectedWithoutEchoingIt` | Covered |
| Real KMS | Encryption context bound to the token | `KmsKeyServiceTest.asksKmsForAnAes256KeyBoundToTheToken` | Partial (stubbed client; not run in a real AWS account) |

## 2. Charging and idempotency
| Case | Handling | Test | Status |
|---|---|---|---|
| Same request repeated | One charge, one posting | `PaymentFlowIntegrationTest.repeatingTheRequestLeavesOneChargeRowAndOnePostingRow` | Covered |
| Same key, different amount, card, folio, booking or date | 409, never a silent OK for the original | `ChargeServiceImplTest.theSameKeyWithADifferentAmountIsAConflictAndNeverReachesTheProcessor`, `ChargeServiceImplTest.theSameKeyWithADifferentFolioOrBusinessDateIsAConflictNotASilentReplayOfTheFirst`, `ReviewFindingsIntegrationTest.theSameKeyWithADifferentFolioIsAConflictNotAnOkForTheOriginal` | Covered |
| Zero, negative, over-ceiling or overflowing amount | Refused | `ChargeServiceImplTest.aZeroAmountIsRefusedBeforeAnythingIsStored`, `MoneyFormatTest.refusesAnAmountAboveTheCeilingSoArithmeticCannotOverflow`, `PaymentRequestPolicyTest.aHugeTotalCannotOverflowTheDepositIntoANonsenseAmount` | Covered |
| Unknown card token | Refused before anything is stored or sent | `ChargeServiceImplTest.anUnknownCardTokenIsRefusedBeforeAnythingIsStoredOrSent` | Covered |
| Unknown currency, currency with 0 or 3 decimals | Validated; formatted per currency | `MoneyFormatTest.refusesACurrencyCodeThatIsNotACurrency`, `MoneyFormatTest.formatsEachCurrencyWithItsOwnDecimals` | Covered |
| Client uses an internal key prefix | Refused | `PaymentControllerTest.invalidInputIsRejectedBeforeAnyServiceIsCalled` | Covered |
| Money conservation across processor, charges and postings | Totals agree | `PaymentFlowIntegrationTest.moneyIsConservedAcrossProcessorChargesAndPostings` | Covered |

## 3. Concurrency and races
| Case | Handling | Test | Status |
|---|---|---|---|
| Parallel duplicates | One row, state stays CAPTURED | `PaymentFlowIntegrationTest.parallelDuplicatesEndWithOneChargeRowOneCapturedStateAndOnePostingRow` | Covered |
| Twin loses the insert | Reads the winner's row | `IdempotentStoreTest.aTwinThatLosesTheInsertReturnsTheWinnersRow` | Covered |
| Loser of an optimistic-lock race holds the capture | Capture is carried onto the stored row | `IdempotentStoreTest.aLostRaceWhereWeKnowMoreCarriesOurResultOntoTheStoredRowAndSavesAgain`, `ChargeServiceImplTest.aRequestThatLosesTheRaceCarriesItsCaptureOntoWhateverIsStoredPending` | Covered |
| Loser's state is older | Stored state wins; nothing already learned is lost | `IdempotentStoreTest.aLostRaceWhereTheStoredStateKnowsMoreReturnsTheStoredState`, `ChargeServiceImplTest.aRequestThatLosesTheRaceToAFinishedChargeReturnsTheFinishedOne`, `PostingServiceImplTest.aRequestThatLosesTheRaceToAPostedEntryReturnsThePostedOne` | Covered |
| Two cards race on one payment link | Exactly one capture | `ReviewFindingsIntegrationTest.twoCardsRacingOnOneOpenLinkProduceExactlyOneCapture` | Covered |
| Two events create two live links | One live link per booking | `BookingEventServiceImplTest.aParallelTwinThatCreatesTheLiveLinkFirstIsTreatedAsTheRepeatItIs` | Covered |
| Order of the state ranks | Capture beats decline beats pending; posted beats dead letter beats pending | `EntityStateTest.aCaptureBeatsADeclineWhichBeatsPending`, `EntityStateTest.aPostedBeatsADeadLetterWhichBeatsPending` | Covered |

## 4. Processor failures
| Case | Handling | Test | Status |
|---|---|---|---|
| Transient failure | Retried with the same key, bounded | `ChargeServiceImplTest.aTransientFailureIsRetriedWithTheSameKeyUntilItSucceeds`, `ChargeServiceImplTest.whenRetriesRunOutTheChargeStaysPendingSoTheSameKeyCanTryAgain` | Covered |
| Decline | Final, never retried, nothing posted | `ChargeServiceImplTest.aDeclineIsFinalAndNotRetried`, `PaymentFlowIntegrationTest.aDeclineIsStoredAsFinalAndNothingReachesThePms` | Covered |
| Capture declined after authorisation | Hold released; a failing release does not hide the decline | `ChargeServiceImplTest.aCaptureDeclinedAfterAuthorisationReleasesTheHold`, `ChargeServiceImplTest.aHoldThatCannotBeReleasedDoesNotHideTheDecline`, `FullFlowHttpIntegrationTest.aCaptureDeclinedAfterAuthorisationReleasesTheHoldAtTheProcessor` | Covered |
| Processor captures, then the reply is lost | Retried with the same key, charged once | `PaymentFlowIntegrationTest.aLostResponseAfterCaptureIsRetriedWithoutChargingTwice`, `FullFlowHttpIntegrationTest.aProcessorThatCapturesThenGoesQuietIsRetriedWithTheSameKeyAndChargesOnce` | Covered (real HTTP, simulator) |
| Processor unreachable, 5xx, wrong API key | Transient, transient, our bug (not retried) | `HttpAdaptersTest.aDeclineIsFinalAndAProcessorThatCannotBeReachedIsTransient`, `HttpAdaptersTest.aServerErrorFromTheProcessorIsTransient`, `HttpAdaptersTest.aWrongApiKeyIsOurBugNotARetryAndTheMessageNeverCarriesTheCardNumber` | Covered |

## 5. Posting to the PMS (OPERA)
| Case | Handling | Test | Status |
|---|---|---|---|
| Outage | Retried, then dead-lettered with the reason; replay posts once | `PostingServiceImplTest.anOutageIsRetriedThenDeadLetteredWithAReasonNeverDropped`, `FullFlowHttpIntegrationTest.anOperaOutageDeadLettersTheCapturedPaymentAndReplayPostsItOnceOperaIsBack` | Covered |
| Rejection (closed folio, unknown reservation) | Dead-lettered at once with the PMS reason; replay does not retry | `PostingServiceImplTest.replayingARejectedPostingNeverCallsOperaAgain`, `FullFlowHttpIntegrationTest.aClosedFolioIsRejectedWithOperasReasonAndReplayLeavesItAlone`, `HttpAdaptersTest.aReservationThatDoesNotExistIsRejectedWithOperasReason` | Covered |
| PMS posts, then the reply is lost (the PMS does not deduplicate) | The bridge looks for its own reference before posting again | `PostingServiceImplTest.aRetryAfterALostResponseFindsTheEarlierPostInsteadOfPostingTwice`, `FullFlowHttpIntegrationTest.aReplyLostAfterOperaPostedIsFoundAgainNotPostedTwice` | Covered (simulator) |
| Rate limit (429), lock conflict (409) | Waited out and retried; the fault is asserted to have fired | `FullFlowHttpIntegrationTest.aRateLimitIsWaitedOutAndRetried`, `FullFlowHttpIntegrationTest.aLockConflictOnTheReservationIsRetried` | Covered (simulator) |
| Access token expires | Replaced once, transparently; still refused after a refresh is "wait", not "bad request" | `FullFlowHttpIntegrationTest.anExpiredAccessTokenIsReplacedOnceWithoutFailingThePayment`, `HttpAdaptersTest.aTokenThatIsAlwaysRefusedIsReportedAsWaitNotAsABadRequest` | Covered (simulator) |
| Wrong credentials; token service unreachable | Rejection; unavailable | `HttpAdaptersTest.wrongCredentialsAreARejectionNotARetry`, `HttpAdaptersTest.anOperaThatCannotBeReachedIsUnavailableAtTheTokenStep` | Covered |
| Missing app key or hotel header | Refused | `FullFlowHttpIntegrationTest.operaRefusesACallWithoutTheApplicationKeyOrTheHotelHeader`, `SimulatorEndpointsTest.everyResourceCallNeedsAValidTokenTheRightAppKeyAndTheMatchingHotelHeader` | Covered |
| Amount sent as a decimal, read back in minor units, any currency | Exact | `HttpAdaptersTest.aPaymentIsFoundBackWithItsAmountRestoredToMinorUnitsInEachCurrency` | Covered |
| Hostile or oversized PMS message | One line, 255 characters | `PostingServiceImplTest.anUpstreamMessageIsStoredAsOneBoundedLineSoItCannotForgeLogEntriesOrOverflowTheColumn`, `ReviewFindingsIntegrationTest.aStoredUpstreamMessageIsBoundedAndSingleLine` | Covered |
| The real OPERA Cloud / OPERA 5 | Not exercised | none | Not covered (no public image; needs OHIP sandbox) |

## 6. Business date and night audit
| Case | Handling | Test | Status |
|---|---|---|---|
| Date between midnight and night audit | Taken from the PMS, not the clock | `BusinessDateServiceImplTest.theDateComesFromThePmsNotTheWallClock`, `FullFlowHttpIntegrationTest.theBusinessDateComesFromOperaAndMovesOnlyAtNightAudit` | Covered |
| Client sends another date | Refused | `BusinessDateServiceImplTest.aDifferentDateFromTheClientIsRefused`, `ReviewFindingsIntegrationTest.aBusinessDateThatIsNotTheHotelsIsRefusedAndTheDefaultComesFromThePms` | Covered |
| PMS down when a guest pays | Last known date for up to 24 hours; payment is taken, posting waits | `BusinessDateServiceImplTest.whenThePmsIsDownTheLastKnownDateKeepsThePaymentFlowing`, `FullFlowHttpIntegrationTest.whenOperaIsDownThePaymentIsStillTakenUsingTheLastKnownBusinessDateAndThePostingWaits` | Covered |
| No earlier answer, or one older than a day | The outage is not hidden | `BusinessDateServiceImplTest.withNoEarlierAnswerAPmsOutageIsNotHidden`, `BusinessDateServiceImplTest.anAnswerOlderThanADayIsNotTrusted` | Covered |

## 7. Payment links and booking events
| Case | Handling | Test | Status |
|---|---|---|---|
| Paid link paid again by another card | Refused (410), no second charge | `ReviewFindingsIntegrationTest.aPaidLinkCannotBePaidAgainWithAnotherCard`, `BookingEventServiceImplTest.aSecondCardAfterTheLinkIsPaidIsRefusedAndNeverCharged` | Covered |
| Another card while a payment is in progress | 409 | `BookingEventServiceImplTest.aSecondCardWhileAPaymentIsInProgressIsRefusedAndNeverCharged` | Covered |
| Declined card, then another card | Link given back; the second card works | `LinkPaymentIntegrationTest.aGuestWhoseCardIsDeclinedCanPayTheSameLinkWithAnotherCard`, `BookingEventServiceImplTest.aDeclinedCardGivesTheLinkBackForAnotherCard` | Covered |
| Pending charge | Claim kept; only the same card resumes | `BookingEventServiceImplTest.aPendingChargeKeepsTheClaimSoOnlyTheSameCardResumesIt` | Covered |
| Double-click | One charge | `LinkPaymentIntegrationTest.payingALinkChargesPostsAndClosesItAndADoubleClickChargesOnce` | Covered |
| Captured while the posting waits | Link closes on capture | `LinkPaymentIntegrationTest.aLinkIsPaidOnCaptureEvenWhenOperaIsDownAndThePostingWaits` | Covered |
| Request refused before money moved | Claim given back | `BookingEventServiceImplTest.aRequestRefusedBeforeAnyMoneyMovedGivesTheClaimBack` | Covered |
| Cancelled, superseded or unknown link | 410 / 404 | `BookingEventServiceImplTest.aCancelledLinkCannotBePaid`, `ApiEndpointsIntegrationTest.payingAnUnknownOrDeadLinkIsRefusedWithTheRightStatus` | Covered |
| Booking changed while a payment is in progress | Not applied yet | `BookingEventServiceImplTest.aChangeWhileAPaymentIsInProgressIsNotAppliedSoTheGuestIsNotAskedToPayTwice` | Covered |
| Booking changed or cancelled after payment | Refund review, no new request | `BookingEventServiceImplTest.aChangeAfterPaymentNeedsAHumanNotANewRequest`, `BookingEventServiceImplTest.cancellingAfterPaymentFlagsARefundReview` | Covered |
| Repeated event, modified booking, deposit boundary, tiny total | Idempotent; superseded; exclusive week boundary; asked in full | `BookingEventServiceImplTest.aRepeatOfTheSameEventChangesNothing`, `BookingEventServiceImplTest.aModifiedBookingSupersedesTheOpenRequestAndOpensAnother`, `PaymentRequestPolicyTest.theWeekBoundaryIsExclusive`, `PaymentRequestPolicyTest.aTotalTooSmallForADepositIsAskedInFullNeverAsAZeroAmountRequest` | Covered |
| Link id guessable, token in a key | 128-bit id; token hashed | `BookingEventServiceImplTest.payingClaimsTheLinkChargesWithAKeyThatHoldsNoTokenAndClosesItOnCapture`, `ReviewFindingsIntegrationTest.linkChargeKeysNeverContainTheCardToken` | Covered |
| Event ordering and duplicate delivery from a real PMS | Not modelled (no event id or sequence) | none | Not covered |

## 8. Reconciliation
| Case | Handling | Test | Status |
|---|---|---|---|
| Clean statement | All matched | `ReconciliationServiceImplTest.aCleanStatementMatchesEverythingWithNoExceptions`, `FullFlowHttpIntegrationTest.reconciliationAgainstTheProcessorsOwnSettlementFileMatchesEverythingPosted` | Covered |
| Missing, short, wrong-currency, unknown, duplicate, unposted | Each named | `ReconciliationServiceImplTest.aCaptureTheAcquirerNeverSettledIsMissing`, `ReconciliationServiceImplTest.aShortSettlementIsAnAmountDifference`, `ReconciliationServiceImplTest.aSettlementInAnotherCurrencyIsNotAMatchEvenWithTheSameNumber`, `ReconciliationServiceImplTest.aLineWeNeverCapturedIsUnknown`, `ReconciliationServiceImplTest.aDoublePresentmentIsNotSilentlyDropped`, `ReconciliationServiceImplTest.aSettledButUnpostedCaptureIsFlaggedForThePms` | Covered |
| Shadow-mode captures | Excluded from revenue | `ReconciliationServiceImplTest.shadowRunsAreNotRevenueAndStayOutOfTheReport` | Covered |
| Bad statement: wrong header, negative, unknown currency, formula in a reference, bad amount | Refused with the row number | `SettlementParserTest.aWrongHeaderFailsLoudly`, `SettlementParserTest.aNegativeAmountIsNotAcceptedAsASettlement`, `SettlementParserTest.anUnknownCurrencyIsRefused`, `SettlementParserTest.aReferenceThatCouldRunAsASpreadsheetFormulaIsRefused`, `SettlementParserTest.aBadAmountIsNotSilentlySkipped` | Covered |
| Refunds, chargebacks, partial captures, fees in the statement | Not modelled | none | Not covered |

## 9. Input, errors and the browser
| Case | Handling | Test | Status |
|---|---|---|---|
| Malformed JSON, wrong content type, wrong method, unknown route | One safe error shape | `ApiEndpointsIntegrationTest.theFrameworksOwnErrorsAreTurnedIntoOneSafeShape` | Covered |
| Validation error | Names the field, never the value | `PaymentControllerTest.aValidationErrorNamesTheFieldButNeverEchoesTheSubmittedValue` | Covered |
| Unexpected failure | Generic 500, no internals | `PaymentControllerTest.anUnexpectedFailureReturnsAGenericMessageAndNoInternals` | Covered |
| Downstream outage | 503 with a retry hint | `PaymentControllerTest.aDownstreamOutageIsA503TellingTheClientToRetryWithTheSameKey` | Covered |
| Log forging through ids, messages, line breaks | Ids restricted; text flattened and bounded | `SafeTextTest.flattensLineBreaksAndControlCharacters`, `PanMaskerTest.safeAlsoFlattensLineBreaksSoAValueCannotForgeALogEntry` | Covered |
| Foreign Host header (DNS rebinding), oversized body | 400, 413 | `ReviewFindingsIntegrationTest.aRequestWithAForeignHostHeaderIsRefused`, `ReviewFindingsIntegrationTest.anOversizedBodyIsRefusedBeforeItIsParsed` | Covered |
| Security headers, no caching | Present | `PaymentControllerTest.responsesCarryHardeningHeadersAndAreNeverCached` | Covered |
| The API description drifts from the code | Test fails | `OpenApiContractTest.theCommittedSpecMatchesWhatTheCodeServes` | Covered |

## 10. Configuration, startup and deployment
| Case | Handling | Test | Status |
|---|---|---|---|
| No key mode, or no gateway | The application does not start | `FailClosedStartupTest.withNoKeyModeItDoesNotStart`, `FailClosedStartupTest.withNoGatewayItDoesNotStartInsteadOfPretendingToChargeCards` | Covered |
| Local key under a persistent database | Refused | `KeyModeGuardTest.localKeysUnderAPersistentDatabaseAreRefusedBecauseEveryStoredCardWouldDieAtRestart` | Covered |
| Demo endpoints and API description in production | Not served | `DemoControlsOffIntegrationTest.demoEndpointsAndTheApiDescriptionAreNotServed` | Covered |
| Settings printed | Credentials hidden | `HttpAdaptersTest.theSettingsHideTheirCredentialsWhenPrinted` | Covered |
| Docker image, PostgreSQL, readiness, refusal to start without settings | Verified by hand, not by a test | see `specs/2026-11-17-p9-delivery-aws/validation.md` | Partial |

## 11. The outside world itself (the simulators)
| Case | Test | Status |
|---|---|---|
| Every simulator endpoint and each refusal | `SimulatorEndpointsTest.*`, `FullFlowHttpIntegrationTest.*` | Covered |

## 12. Not covered: be honest about these
1. **The real OPERA Cloud (OHIP) and OPERA 5.** Only simulators exist; paths and error codes are simplified and unchecked against Oracle's documentation.
2. **A real processor or acquirer**, 3-D Secure, hosted card entry, stored credentials, refunds, chargebacks and dispute evidence.
3. **Crash between steps.** A JVM crash after a capture and before it is saved can strand a payment; there is no sweeper.
4. **Authentication and authorisation**, tenant scoping of keys and tokens, rate limiting.
5. **Booking-event ordering and duplicate delivery** from a real PMS; refund-review flags are not persisted.
6. **Reject-and-repair** for a posting refused because night audit passed while it waited.
7. **Real KMS** in a real AWS account; Flyway migrations; timeouts, circuit breaker and bulkhead; load and soak behaviour.
8. **Multi-property** operation (one hotel id is configuration here).
9. **Clock and timezone edge cases** beyond the business-date rule: daylight-saving zones, leap days.
10. **Concurrency beyond one JVM**: the database guarantees hold across instances, but this was tested with threads in one process, not with several containers.

Coverage of the code by tests is measured, not claimed: `python3 tools/coverage.py` (99.8% lines, 92.3% branches at the time of writing; the gate in `./mvnw verify` fails below 99% and 90%). Coverage shows lines were run, not that every case above is right; this page is the list of cases.
