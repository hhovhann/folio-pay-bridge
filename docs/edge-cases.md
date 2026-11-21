# Edge cases: what is covered, what is partly covered, what is not

"All possible cases" cannot be proven for any payment system, so this page does something more useful: it lists every case this project deliberately handles, the test that fails if the handling breaks, and, separately and plainly, the cases that are **not** handled. `python3 tools/check_specs.py` checks that every test named here exists and passed in the last run.

Status: **Covered** = a test fails if the behaviour breaks. **Partial** = covered against the simulators or fakes, not against the real system. **Not covered** = a known gap (section 12).

## 1. Card data
| Case | Handling | Test | Status |
|---|---|---|---|
| Card number written to the database | Only ciphertext, a wrapped key, first six and last four are stored | `PciIntegrationTest.theCardNumberIsNowhereInTheDatabaseAfterAFullFlow` | Covered |
| Card number written to a log | Never logged (checked on the raw events, before any masking, on the success and the failure path); masked if it slips in | `PciIntegrationTest.theCardNumberAndTheCardTokenAreNeverLoggedEvenAtDebugLevel`, `ProductionLogConfigTest.aCardNumberLoggedByMistakeIsMaskedOnTheConsole` | Covered |
| Number glued to other digits, separators of up to four characters (`4111 - 1111`, a JSON array of groups), Unicode digits, two numbers back to back | Union of every Luhn-valid window is masked; letters and line breaks still end a run | `PanMaskerTest.findsANumberWithSeparatorsOfThreeOrFourCharacters`, `PanMaskerTest.stillLeavesWordsAndLineBreaksAsBoundaries`, `PanMaskerTest.findsANumberWithOtherDigitsGluedToEitherSide`, `PanMaskerTest.findsANumberWithUnusualSeparatorsOrUnicodeDigits`, `PanMaskerTest.masksTwoNumbersWrittenBackToBackAsOneLongRun` | Covered |
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
| A charge left pending (capture reply lost) is retried after night audit | A repeat that names no date resumes the stored charge on the date it was taken; only a new charge asks the PMS | `ChargeServiceImplTest.aPendingChargeIsResumedAfterNightAuditWhenTheRepeatNamesNoDateAndKeepsItsOwnDate`, `ChargeServiceImplTest.aNewChargeWithoutADateTakesTheHotelsBusinessDateFromThePms`, `EntityStateTest.aChargeMatchesARepeatOnlyWhenEveryFieldThatDecidesWhereTheMoneyGoesIsTheSame`, `LinkPaymentIntegrationTest.aChargeLeftPendingBeforeNightAuditIsResumedAfterItAndNoSecondCardIsCharged` | Covered |
| Zero, negative, over-ceiling or overflowing amount | Refused | `ChargeServiceImplTest.aZeroAmountIsRefusedBeforeAnythingIsStored`, `MoneyFormatTest.refusesAnAmountAboveTheCeilingSoArithmeticCannotOverflow`, `PaymentRequestPolicyTest.aHugeTotalCannotOverflowTheDepositIntoANonsenseAmount` | Covered |
| Unknown card token | Refused before anything is stored or sent | `ChargeServiceImplTest.anUnknownCardTokenIsRefusedBeforeAnythingIsStoredOrSent` | Covered |
| Unknown currency, currency with 0 or 3 decimals | Validated; formatted per currency | `MoneyFormatTest.refusesACurrencyCodeThatIsNotACurrency`, `MoneyFormatTest.formatsEachCurrencyWithItsOwnDecimals` | Covered |
| Client uses an internal key prefix (`link:`, `post:`, `shadow-`) | Refused | `PaymentControllerTest.invalidInputIsRejectedBeforeAnyServiceIsCalled` | Covered |
| Money conservation across processor, charges and postings | Totals agree | `PaymentFlowIntegrationTest.moneyIsConservedAcrossProcessorChargesAndPostings` | Covered |

## 3. Concurrency and races
| Case | Handling | Test | Status |
|---|---|---|---|
| Parallel duplicates | One row, state stays CAPTURED, one folio entry (the fake PMS counts every post, it does not deduplicate) | `PaymentFlowIntegrationTest.parallelDuplicatesEndWithOneChargeRowOneCapturedStateAndOnePostingRow` | Covered |
| Parallel replays of one posting (two operators, a retrying client) | A lease in the database lets one request talk to OPERA; the others return the stored state | `FullFlowHttpIntegrationTest.parallelReplaysAgainstANonDeduplicatingOperaPostOnce`, `PaymentFlowIntegrationTest.parallelReplaysOfOneDeadLetteredPostingPostItOnce`, `PostingServiceImplTest.whileAnotherRequestHoldsTheLeaseOperaIsNotCalledAndTheStoredStateIsReturned`, `PostingServiceImplTest.theLeaseRunsFromTheClockForLongerThanOneAttempt` | Covered (without the lease the HTTP test sees 8 posts; a lease not released after a dead letter fails the four replay tests) |
| Twin loses the insert | Reads the winner's row | `IdempotentStoreTest.aTwinThatLosesTheInsertReturnsTheWinnersRow` | Covered |
| Loser of an optimistic-lock race holds the capture | Capture is carried onto the stored row | `IdempotentStoreTest.aLostRaceWhereWeKnowMoreCarriesOurResultOntoTheStoredRowAndSavesAgain`, `ChargeServiceImplTest.aRequestThatLosesTheRaceCarriesItsCaptureOntoWhateverIsStoredPending` | Covered |
| Loser's state is older | Stored state wins; nothing already learned is lost | `IdempotentStoreTest.aLostRaceWhereTheStoredStateKnowsMoreReturnsTheStoredState`, `ChargeServiceImplTest.aRequestThatLosesTheRaceToAFinishedChargeReturnsTheFinishedOne`, `PostingServiceImplTest.aRequestThatLosesTheRaceToAPostedEntryReturnsThePostedOne` | Covered |
| Two cards race on one payment link | Exactly one capture | `ReviewFindingsIntegrationTest.twoCardsRacingOnOneOpenLinkProduceExactlyOneCapture` | Covered |
| Two deliveries of one event create two live links | One live link per booking (unique open-slot column) | `LinkPaymentIntegrationTest.parallelDeliveriesOfOneBookingEventLeaveOneLiveLink`, `BookingEventServiceImplTest.aParallelTwinThatCreatesTheLiveLinkFirstIsTreatedAsTheRepeatItIs` | Covered |
| An event races another change to the same link | 409 "send it again", not a 500 | `PaymentControllerTest.aConcurrentChangeToTheSameRecordIsAConflictToRetryNotAServerError` | Covered |
| Order of the state ranks | Capture beats decline beats pending; posted beats dead letter beats pending | `EntityStateTest.aCaptureBeatsADeclineWhichBeatsPending`, `EntityStateTest.aPostedBeatsADeadLetterWhichBeatsPending` | Covered |

## 4. Processor failures
| Case | Handling | Test | Status |
|---|---|---|---|
| Transient failure | Retried with the same key, bounded | `ChargeServiceImplTest.aTransientFailureIsRetriedWithTheSameKeyUntilItSucceeds`, `ChargeServiceImplTest.whenRetriesRunOutTheChargeStaysPendingSoTheSameKeyCanTryAgain` | Covered |
| Decline | Final, never retried, nothing posted | `ChargeServiceImplTest.aDeclineIsFinalAndNotRetried`, `PaymentFlowIntegrationTest.aDeclineIsStoredAsFinalAndNothingReachesThePms` | Covered |
| Capture declined after authorisation | Hold released; a failing release does not hide the decline | `ChargeServiceImplTest.aCaptureDeclinedAfterAuthorisationReleasesTheHold`, `ChargeServiceImplTest.aHoldThatCannotBeReleasedDoesNotHideTheDecline`, `FullFlowHttpIntegrationTest.aCaptureDeclinedAfterAuthorisationReleasesTheHoldAtTheProcessor` | Covered |
| Processor captures, then the reply is lost | Retried with the same key, charged once | `PaymentFlowIntegrationTest.aLostResponseAfterCaptureIsRetriedWithoutChargingTwice`, `FullFlowHttpIntegrationTest.aProcessorThatCapturesThenGoesQuietIsRetriedWithTheSameKeyAndChargesOnce` | Covered (real HTTP, simulator) |
| Processor rate limit (429), same key still in flight (409) | Transient: waited out and retried with the same key | `HttpAdapterRepliesTest.aProcessorRateLimitOrAParallelRequestWithTheSameKeyIsWaitedOutNotTreatedAsOurBug` | Covered |
| Any other processor 4xx | Our bug, not retried, no card number in the message | `HttpAdapterRepliesTest.aDeclineIsFinalAndAnyOther4xxIsOursAndNotRetried` | Covered |
| Processor unreachable, 5xx, wrong API key | Transient, transient, our bug (not retried) | `HttpAdaptersTest.aDeclineIsFinalAndAProcessorThatCannotBeReachedIsTransient`, `HttpAdaptersTest.aServerErrorFromTheProcessorIsTransient`, `HttpAdaptersTest.aWrongApiKeyIsOurBugNotARetryAndTheMessageNeverCarriesTheCardNumber` | Covered |

## 5. Posting to the PMS (OPERA)
| Case | Handling | Test | Status |
|---|---|---|---|
| Outage | Retried, then dead-lettered with the reason; replay posts once | `PostingServiceImplTest.anOutageIsRetriedThenDeadLetteredWithAReasonNeverDropped`, `FullFlowHttpIntegrationTest.anOperaOutageDeadLettersTheCapturedPaymentAndReplayPostsItOnceOperaIsBack` | Covered |
| Rejection (closed folio, unknown reservation) | Dead-lettered at once with the PMS reason; replay does not retry | `PostingServiceImplTest.replayingARejectedPostingNeverCallsOperaAgain`, `FullFlowHttpIntegrationTest.aClosedFolioIsRejectedWithOperasReasonAndReplayLeavesItAlone`, `HttpAdaptersTest.aReservationThatDoesNotExistIsRejectedWithOperasReason` | Covered |
| PMS posts, then the reply is lost (the PMS does not deduplicate) | The bridge looks for its own reference before posting again | `PostingServiceImplTest.aRetryAfterALostResponseFindsTheEarlierPostInsteadOfPostingTwice`, `FullFlowHttpIntegrationTest.aReplyLostAfterOperaPostedIsFoundAgainNotPostedTwice` | Covered (simulator) |
| A reply without a transaction number, or a business date we cannot read | Treated as "unavailable": retried, looked for, dead-lettered as replayable; never POSTED with a blank reference, never a crash | `HttpAdapterRepliesTest.anOperaReplyWithoutATransactionNumberIsNotRecordedAsPostedWithABlankReference`, `HttpAdapterRepliesTest.aFoundPaymentWithoutATransactionNumberIsNeitherPostedAgainNorRecordedAsPosted`, `HttpAdapterRepliesTest.aBusinessDateWeCannotReadIsAnOutageNotACrash` | Covered |
| Any other error while posting (a bug) | Dead-lettered as replayable, never left PENDING | `PostingServiceImplTest.anUnexpectedErrorIsDeadLetteredAsReplayableNeverLeftPending` | Covered |
| Rate limit (429), lock conflict (409) | Waited out and retried; the fault is asserted to have fired | `FullFlowHttpIntegrationTest.aRateLimitIsWaitedOutAndRetried`, `FullFlowHttpIntegrationTest.aLockConflictOnTheReservationIsRetried` | Covered (simulator) |
| Access token expires | Replaced once, transparently; still refused after a refresh is "wait", not "bad request" | `FullFlowHttpIntegrationTest.anExpiredAccessTokenIsReplacedOnceWithoutFailingThePayment`, `HttpAdaptersTest.aTokenThatIsAlwaysRefusedIsReportedAsWaitNotAsABadRequest` | Covered (simulator) |
| Many requests refused with the same expired token at once | One new token, not one per request | `HttpAdapterRepliesTest.manyRequestsRefusedWithTheSameExpiredTokenCauseOneNewTokenNotOneEach` | Covered |
| Wrong credentials (token refused, 401 after a fresh token, 403); token service unreachable | Unavailable: replayable once fixed, never a rejection of the folio (D-34); unavailable | `HttpAdaptersTest.wrongCredentialsAreOursToFixAndReplayableNotARejectionOfTheFolio`, `HttpAdaptersTest.anOperaThatCannotBeReachedIsUnavailableAtTheTokenStep` | Covered |
| Missing app key or hotel header | Refused | `FullFlowHttpIntegrationTest.operaRefusesACallWithoutTheApplicationKeyOrTheHotelHeader`, `SimulatorEndpointsTest.everyResourceCallNeedsAValidTokenTheRightAppKeyAndTheMatchingHotelHeader` | Covered |
| Amount sent as a decimal, read back in minor units, any currency | Exact | `HttpAdaptersTest.aPaymentIsFoundBackWithItsAmountRestoredToMinorUnitsInEachCurrency` | Covered |
| Hostile or oversized PMS message | One line, 255 characters | `PostingServiceImplTest.anUpstreamMessageIsStoredAsOneBoundedLineSoItCannotForgeLogEntriesOrOverflowTheColumn`, `ReviewFindingsIntegrationTest.aStoredUpstreamMessageIsBoundedAndSingleLine` | Covered |
| A folio window longer than one page (it says it has more) | Read page by page (`folioWindowNo`, `offset`) to our payment or the last page, so a long stay can still be paid | `HttpAdapterRepliesTest.aLongFolioIsReadPageByPageSoItsGuestCanBePaid`, `OhipContractTest.aPaymentOnALongFolioIsFoundOnALaterPage` | Partial (OPERA's paging not seen in a sandbox) |
| A folio page that cannot prove our payment absent (empty after a promise of more, no window number, the wrong window, fewer read than the total, no end) | "Unavailable", never "not there": posting again would post twice | `HttpAdapterRepliesTest.aFolioReturnedOnlyInPartIsUnavailableNotAbsent`, `HttpAdapterRepliesTest.aFolioPageThatCannotProveOurPaymentAbsentIsUnavailableNeverNotFound` | Covered |
| A reply that is not a folio (a renamed or wrapped shape, an empty 200) | "Unavailable": it cannot prove our payment absent; Oracle's 204 "Folio not found" and a folio with no windows still can, so a stay's first payment posts | `HttpAdapterRepliesTest.aReplyThatIsNotAFolioIsUnavailableButAFolioWithNoWindowsProvesThePaymentAbsent`, `HttpAdapterRepliesTest.aFolioWithNoContentIsNotFound` | Partial (against Oracle's specification, not a real OPERA) |
| Our payment on the folio with an amount or date we cannot read (no currency, too many decimals) | Found by its transaction number; the amount or date is empty | `HttpAdapterRepliesTest.aFoundPaymentIsFoundByItsTransactionNumberEvenWhenItsAmountOrDateCannotBeRead` | Covered |
| A payment listed as a credit (negative), or a charge that carries our reference | Found with its amount; a charge is not our payment | `HttpAdapterRepliesTest.aPaymentListedAsACreditIsFoundAndAChargeWithOurReferenceIsNot` | Covered |
| OHIP answers 201 with no postings, only generated ones (a surcharge), or none for a guest not yet in house (204 folio) | No transaction number: look before posting again; a 204 folio is "not posted yet" | `HttpAdapterRepliesTest.anOperaReplyWithoutATransactionNumberIsNotRecordedAsPostedWithABlankReference`, `HttpAdapterRepliesTest.aFolioWithNoContentIsNotFound` | Covered |
| A whole amount (800.00) or more decimals than the currency has | Trailing zeros kept, exact; a fraction of a cent is an unreadable amount, never cut (the payment is still found) | `OhipContractTest.aPaymentIsPostedAndFoundBackThroughOraclesOperationsInEachCurrency`, `HttpAdapterRepliesTest.aFoundPaymentIsFoundByItsTransactionNumberEvenWhenItsAmountOrDateCannotBeRead` | Covered |
| A request or reply that departs from Oracle's published OHIP contract (path, header, field, enum, status) | The build fails: every exchange of the adapter with the simulator is checked against Oracle's Swagger files (D-42) | `OhipContractTest.theCheckRefusesTheInventedShapesTheBridgeUsedBeforeD42`, and every `OhipContractTest` scenario | Covered |
| The real OPERA Cloud / OPERA 5 | Oracle's published contract is checked; the system itself is not exercised | `OhipContractTest` | Partial (OPERA cannot be run locally; the sandbox questions are in `specs/roadmap.md`) |

## 6. Business date and night audit
| Case | Handling | Test | Status |
|---|---|---|---|
| Date between midnight and night audit | Taken from the PMS, not the clock | `BusinessDateServiceImplTest.theDateComesFromThePmsNotTheWallClock`, `FullFlowHttpIntegrationTest.theBusinessDateComesFromOperaAndMovesOnlyAtNightAudit` | Covered |
| Client sends another date | Refused | `BusinessDateServiceImplTest.aDifferentDateFromTheClientIsRefused`, `ReviewFindingsIntegrationTest.aBusinessDateThatIsNotTheHotelsIsRefusedAndTheDefaultComesFromThePms` | Covered |
| PMS down when a guest pays | Last known date for up to 24 hours; payment is taken, posting waits | `BusinessDateServiceImplTest.whenThePmsIsDownTheLastKnownDateKeepsThePaymentFlowing`, `FullFlowHttpIntegrationTest.whenOperaIsDownThePaymentIsStillTakenUsingTheLastKnownBusinessDateAndThePostingWaits` | Covered |
| A slow or black-holed PMS | Each request waits on its own timeout; no lock makes the others queue | `BusinessDateServiceImplTest.aSlowPmsAnswerForOneRequestDoesNotHoldUpAnother` | Covered |
| No earlier answer, or one older than a day | The outage is not hidden | `BusinessDateServiceImplTest.withNoEarlierAnswerAPmsOutageIsNotHidden`, `BusinessDateServiceImplTest.anAnswerOlderThanADayIsNotTrusted` | Covered |

## 7. Payment links and booking events
| Case | Handling | Test | Status |
|---|---|---|---|
| Paid link paid again by another card | Refused (410), no second charge | `ReviewFindingsIntegrationTest.aPaidLinkCannotBePaidAgainWithAnotherCard`, `BookingEventServiceImplTest.aSecondCardAfterTheLinkIsPaidIsRefusedAndNeverCharged` | Covered |
| Another card while a payment is in progress | 409 | `BookingEventServiceImplTest.aSecondCardWhileAPaymentIsInProgressIsRefusedAndNeverCharged` | Covered |
| Declined card, then another card | Link given back; the second card works | `LinkPaymentIntegrationTest.aGuestWhoseCardIsDeclinedCanPayTheSameLinkWithAnotherCard`, `BookingEventServiceImplTest.aDeclinedCardGivesTheLinkBackForAnotherCard` | Covered |
| Pending charge | Claim kept; only the same card resumes, also after night audit | `BookingEventServiceImplTest.aPendingChargeKeepsTheClaimSoOnlyTheSameCardResumesIt`, `LinkPaymentIntegrationTest.aChargeLeftPendingBeforeNightAuditIsResumedAfterItAndNoSecondCardIsCharged` | Covered |
| Double-click | One charge | `LinkPaymentIntegrationTest.payingALinkChargesPostsAndClosesItAndADoubleClickChargesOnce` | Covered |
| Captured while the posting waits | Link closes on capture | `LinkPaymentIntegrationTest.aLinkIsPaidOnCaptureEvenWhenOperaIsDownAndThePostingWaits` | Covered |
| A request fails | The claim follows the stored charge, not the type of error: none or declined gives the link back, pending keeps it, captured closes it; unreadable keeps it | `BookingEventServiceImplTest.aRequestRefusedBeforeAnyChargeExistedGivesTheClaimBack`, `BookingEventServiceImplTest.aRefusalAfterAPendingChargeExistsKeepsTheClaimSoNoSecondCardCanBeCharged`, `BookingEventServiceImplTest.aFailureAfterTheCaptureStillClosesTheLink`, `BookingEventServiceImplTest.aFailureAfterADeclineGivesTheLinkBack`, `BookingEventServiceImplTest.whenTheChargeCannotEvenBeReadTheClaimIsKeptBecauseThatCanNeverChargeTwice` | Covered |
| Cancelled, superseded or unknown link | 410 / 404 | `BookingEventServiceImplTest.aCancelledLinkCannotBePaid`, `ApiEndpointsIntegrationTest.payingAnUnknownOrDeadLinkIsRefusedWithTheRightStatus` | Covered |
| Booking changed while a payment is in progress | Not applied yet | `BookingEventServiceImplTest.aChangeWhileAPaymentIsInProgressIsNotAppliedSoTheGuestIsNotAskedToPayTwice` | Covered |
| Booking changed or cancelled after payment | Refund review, no new request | `BookingEventServiceImplTest.aChangeAfterPaymentNeedsAHumanNotANewRequest`, `BookingEventServiceImplTest.cancellingAfterPaymentFlagsARefundReview` | Covered |
| Repeated event, modified booking, deposit boundary, tiny total | Idempotent; superseded; exclusive week boundary; asked in full | `BookingEventServiceImplTest.aRepeatOfTheSameEventChangesNothing`, `BookingEventServiceImplTest.aModifiedBookingSupersedesTheOpenRequestAndOpensAnother`, `PaymentRequestPolicyTest.theWeekBoundaryIsExclusive`, `PaymentRequestPolicyTest.aTotalTooSmallForADepositIsAskedInFullNeverAsAZeroAmountRequest` | Covered |
| Link id guessable, token in a key | Random UUID id (122 random bits); token hashed | `EntityStateTest.aPaymentLinkMovesThroughItsStatesAndFreesItsBookingSlotWhenClosed` (id shape), `BookingEventServiceImplTest.payingClaimsTheLinkChargesWithAKeyThatHoldsNoTokenAndClosesItOnCapture`, `ReviewFindingsIntegrationTest.linkChargeKeysNeverContainTheCardToken` | Covered |
| Event ordering and duplicate delivery from a real PMS | Not modelled (no event id or sequence) | none | Not covered |

## 8. Reconciliation
| Case | Handling | Test | Status |
|---|---|---|---|
| Clean statement | All matched | `ReconciliationServiceImplTest.aCleanStatementMatchesEverythingWithNoExceptions`, `FullFlowHttpIntegrationTest.reconciliationAgainstTheProcessorsOwnSettlementFileMatchesEverythingPosted` | Covered |
| Missing, short, wrong-currency, unknown, duplicate, unposted | Each named | `ReconciliationServiceImplTest.aCaptureTheAcquirerNeverSettledIsMissing`, `ReconciliationServiceImplTest.aShortSettlementIsAnAmountDifference`, `ReconciliationServiceImplTest.aSettlementInAnotherCurrencyIsNotAMatchEvenWithTheSameNumber`, `ReconciliationServiceImplTest.aLineWeNeverCapturedIsUnknown`, `ReconciliationServiceImplTest.aDoublePresentmentIsNotSilentlyDropped`, `ReconciliationServiceImplTest.aSettledButUnpostedCaptureIsFlaggedForThePms` | Covered |
| Captures and settlements in several currencies | Totals per currency, each item with its own currencies; never added across currencies | `ReconciliationServiceImplTest.totalsAreKeptPerCurrencySoYenAndDollarsAreNeverAddedUp`, `ReconciliationServiceImplTest.aCurrencyDifferenceShowsEachSideInItsOwnCurrency` | Covered |
| Shadow-mode captures | Excluded from revenue | `ReconciliationServiceImplTest.shadowRunsAreNotRevenueAndStayOutOfTheReport` | Covered |
| Bad statement: wrong header, negative, unknown currency, formula in a reference, bad amount | Refused with the row number | `SettlementParserTest.aWrongHeaderFailsLoudly`, `SettlementParserTest.aNegativeAmountIsNotAcceptedAsASettlement`, `SettlementParserTest.anUnknownCurrencyIsRefused`, `SettlementParserTest.aReferenceThatCouldRunAsASpreadsheetFormulaIsRefused`, `SettlementParserTest.aBadAmountIsNotSilentlySkipped` | Covered |
| The acquirer's own statement unreadable (a line without its reference, a whole amount or a currency; no lines; an empty reply) | Unreadable: the run answers 503 to try again, never lines with blank references or zeros | `HttpAdapterRepliesTest.aSettlementStatementThatCannotBeReadIsUnreadableNeverLinesWithBlankReferencesOrZeroAmounts`, `PaymentControllerTest.anAnswerFromOutsideThatCannotBeReadIsA503ToRetryNotAServerError` | Covered |
| Refunds, chargebacks, partial captures, fees in the statement | Not modelled | none | Not covered |

## 9. Input, errors and the browser
| Case | Handling | Test | Status |
|---|---|---|---|
| Malformed JSON, wrong content type, wrong method, unknown route | One safe error shape | `ApiEndpointsIntegrationTest.theFrameworksOwnErrorsAreTurnedIntoOneSafeShape` | Covered |
| Validation error | Names the field, never the value | `PaymentControllerTest.aValidationErrorNamesTheFieldButNeverEchoesTheSubmittedValue` | Covered |
| Unexpected failure | Generic 500, no internals | `PaymentControllerTest.anUnexpectedFailureReturnsAGenericMessageAndNoInternals` | Covered |
| Downstream outage | 503 with a retry hint | `PaymentControllerTest.aDownstreamOutageIsA503TellingTheClientToRetryWithTheSameKey` | Covered |
| Log forging through ids, messages, line breaks | Ids restricted; text flattened and bounded | `SafeTextTest.flattensLineBreaksAndControlCharacters`, `LogRedactorTest.safeAlsoFlattensLineBreaksSoAValueCannotForgeALogEntry` | Covered |
| Foreign Host header (DNS rebinding), oversized body | 400, 413 | `ReviewFindingsIntegrationTest.aRequestWithAForeignHostHeaderIsRefused`, `ReviewFindingsIntegrationTest.anOversizedBodyIsRefusedBeforeItIsParsed` | Covered |
| Oversized chunked body (no declared length) | Read up to the limit, then 413; a body within it reaches the controller whole | `RequestSizeFilterTest.aChunkedBodyOverTheLimitIsRefusedWithoutReachingTheController`, `RequestSizeFilterTest.aChunkedBodyWithinTheLimitReachesTheControllerWhole` | Covered |
| Security headers, no caching | Present | `PaymentControllerTest.responsesCarryHardeningHeadersAndAreNeverCached` | Covered |
| The API description drifts from the code | Test fails | `OpenApiContractTest.theCommittedSpecMatchesWhatTheCodeServes` | Covered |

## 10. Configuration, startup and deployment
| Case | Handling | Test | Status |
|---|---|---|---|
| No key mode, or no gateway | The application does not start | `FailClosedStartupTest.withNoKeyModeItDoesNotStart`, `FailClosedStartupTest.withNoGatewayItDoesNotStartInsteadOfPretendingToChargeCards` | Covered |
| Local key under a persistent database | Refused | `KeyModeGuardTest.localKeysUnderAPersistentDatabaseAreRefusedBecauseEveryStoredCardWouldDieAtRestart` | Covered |
| Demo endpoints and API description in production | Not served | `DemoControlsOffIntegrationTest.demoEndpointsAndTheApiDescriptionAreNotServed` | Covered |
| Settings printed | Credentials hidden | `HttpAdaptersTest.theSettingsHideTheirCredentialsWhenPrinted` | Covered |
| HTTP mode without an address or credential | Does not start (no localhost defaults) | `HttpAdapterRepliesTest.httpModeRefusesToStartWithoutItsAddressesAndCredentials` | Covered |
| Docker image, PostgreSQL, readiness, refusal to start without settings | Verified by hand, not by a test | see `specs/2026-11-17-p9-delivery-aws/validation.md` | Partial |

## 11. The outside world itself (the simulators)
| Case | Test | Status |
|---|---|---|
| Every simulator endpoint and each refusal | `SimulatorEndpointsTest.*`, `FullFlowHttpIntegrationTest.*` | Covered |
| A capture or void naming an unknown authorisation, or a capture for another amount | 404, 422 | `SimulatorEndpointsTest.aCaptureOrVoidMustNameARealAuthorisationAndACaptureMustMatchItsAmount` | Covered |
| A repeat of an approved key while a decline is armed | Gets its first answer | `SimulatorEndpointsTest.aRepeatOfAnApprovedKeyGetsItsFirstAnswerEvenWhileADeclineIsArmed` | Covered |
| An unknown fault name | 400, not 500 | `SimulatorEndpointsTest.anUnknownFaultIsABadRequestNotACrash` | Covered |

## 11a. Authentication, notifications and remote creation (phase 14)
| Case | Handling | Test | Status |
|---|---|---|---|
| No credentials, unknown API key, a wrong key beside a valid token | 401 with a code, never treated as anonymous | `SecurityIntegrationTest.withNoCredentialsTheApiIsRefusedButHealthAndTheConsolePageAreNot`, `SecurityIntegrationTest.aWrongApiKeyIsRefusedEvenAlongsideAValidToken` | Covered |
| Expired token, another API's token, another issuer's token, a forged signature | Refused (JWKS, issuer, audience, expiry) | `SecurityIntegrationTest.aTokenThatIsExpiredForAnotherApiFromAnotherIssuerOrForgedIsRefused` | Covered (local JWKS; Cognito not run) |
| A client using an operation its scopes do not allow | 403 with a code | `SecurityIntegrationTest.aClientCanDoOnlyWhatItsScopesAllow` | Covered |
| A real gateway with no authentication configured | Refuses to start | `FailClosedStartupTest.withARealGatewayAndNoAuthenticationItDoesNotStart` | Covered |
| No hotel id, payment method or cashier for OPERA | Refuses to start: no default stands in for the hotel's own (D-17) | `FailClosedStartupTest.withoutEachOfTheHotelsOperaSettingsItDoesNotStart` | Covered |
| A webhook receiver that is down or answers 5xx | Retried with growing waits, FAILED after the last attempt, redeliverable | `WebhookIntegrationTest.aReceiverThatFailsKeepsTheEventPendingForALaterAttempt`, `NotificationTest.aFailureWaitsTwiceAsLongEachTimeUpToAnHourAndTheLastAttemptEndsFailedNotDropped` | Covered |
| Two instances relaying at once | Each event sent once (a lease per send) | `WebhookIntegrationTest.twoRelaysRunningAtOnceDeliverEachEventOnce` | Covered |
| A send that outlives its lease, and the other instance's change wins | The lease covers two whole sends (from the timeouts); the lost race is logged and the rest of the batch is sent | `WebhookPropertiesTest.theSendLeaseCoversTwoWholeSendsAndIsNeverUnderAMinute`, `NotificationServiceImplTest.aLostRaceOnOneEventDoesNotHoldUpTheRestOfTheBatch` | Covered |
| More events waiting than one batch | The backlog drains | `WebhookIntegrationTest.aBacklogLargerThanOneBatchDrainsInsteadOfStallingOnTheFirstPage` | Covered |
| A forged or altered webhook | HMAC-SHA256 over timestamp and body; the receiver refuses it | `WebhookSignatureTest.signsTheTimestampAndTheBodyWithHmacSha256`, `SimulatorEndpointsTest.theHotelsWebhookReceiverRefusesAnUnsignedEvent` | Covered |
| A card number or token in a webhook | Never put in a payload | `NotificationServiceImplTest.theCardTokenIsNeverInAPayload` | Covered |
| A remote payment request sent twice, changed, due in the past | Repeat changes nothing; new terms replace the open one; past due is refused | `RemoteCreationIntegrationTest.aRequestCreatesOneOpenLinkAndSendingItAgainChangesNothing`, `RemoteCreationIntegrationTest.aPastDueDateAZeroAmountOrAnUnsafeReferenceIsRefusedWithoutStoringAnything` | Covered |

## 11b. Found by the production-readiness review (phase 15)
| Case | Handling | Test | Status |
|---|---|---|---|
| Two integrators use the same idempotency key | Two payments; keys are per client and neither can read the other's charge (D-40); the processor's key is the charge's own id (D-3) | `SecurityIntegrationTest.twoClientsUsingTheSameIdempotencyKeyGetTwoSeparatePayments`, `ChargeServiceImplTest.aKeyIsLookedUpInTheCallersOwnNamespace` | Covered |
| An app allowed to create payment requests tries to charge a card | Refused: `links:write` cannot pay or tokenise, `payments:write` cannot create requests (D-37) | `SecurityIntegrationTest.anAppThatMayAskForMoneyCannotTakeItAndAPayerCannotCreateRequests` | Covered |
| OPERA refuses our credentials (token endpoint 4xx, 403) or answers 404 without its own error code | Unavailable: the posting can be replayed once the configuration is fixed, never a permanent rejection (D-34) | `HttpAdapterRepliesTest.operaRefusingOurCredentialsIsReplayableNotARejectionOfTheFolio`, `HttpAdapterRepliesTest.a404WithoutTheirOwnErrorCodeIsAWrongAddressNotARefusal` | Partial (simulated replies) |
| The processor says yes but sends no reference | Unreadable: the charge stays PENDING with its hold, never CAPTURED with a blank reference or DECLINED | `HttpAdapterRepliesTest.aProcessorSuccessWithoutAReferenceIsUnreadableNeverABlankReference`, `ChargeServiceImplTest.aCaptureAnsweredWithoutAReferenceStaysPendingAndKeepsTheHold` | Covered |
| The processor answers 408, or 404 from a wrong address | Transient: retried with the same key, never a decline of the guest's card | `HttpAdapterRepliesTest.a404WithoutTheirOwnErrorCodeIsAWrongAddressNotARefusal` | Covered |
| A dead-lettered posting is replayed more than 30 days after it was created | `folio.posted` is still sent: the relay's window is on the last change | `WebhookIntegrationTest.aPostingReplayedLongAfterItWasCreatedIsStillAnnounced`, `WebhookIntegrationTest.aPaymentThatLastChangedBeforeTheLookbackWindowIsNotRescanned` | Covered |
| A Cognito access token (no `aud`), an ID token, an unprefixed scope, scopes as an `scp` list | Accepted when the scope prefix binds it to this API; ID token refused; only prefixed scopes count; `scp` read too | `SecurityConfigTest.aCognitoAccessTokenWithoutAnAudienceIsAcceptedWhenTheScopePrefixBindsItToThisApi`, `SecurityConfigTest.withAPrefixOnlyThisApisScopesCount`, `SecurityConfigTest.scopesSentAsAListAreReadToo` | Partial (no real Cognito) |
| Invisible characters in outside text (bidirectional override, zero-width, Unicode tags) | Removed from processor and PMS text; refused in a payment request description | `SafeTextTest.removesInvisibleFormattingCharactersThatShowAPersonOneThingAndAProgramAnother`, `RemoteCreationIntegrationTest.aPastDueDateAZeroAmountOrAnUnsafeReferenceIsRefusedWithoutStoringAnything` | Covered |
| An error from Spring MVC, a domain rule or a servlet filter | One RFC 9457 problem detail with a stable code, whoever refused the request | `ApiEndpointsIntegrationTest.errorsAreProblemDetailsWithAStableCode` | Covered |
| An endpoint of an old API version is still called | `Deprecation`, `Sunset` and successor `Link` headers on every response, and a log line naming the client (D-39) | `ApiDeprecationInterceptorTest.aDeprecatedEndpointCarriesDeprecationSunsetAndItsSuccessor` | Covered |
| DEBUG logging switched on in production | Debug lines carry ids, keys and amounts, never a card number or a card token; JSON lines stay masked | `PciIntegrationTest.theCardNumberAndTheCardTokenAreNeverLoggedEvenAtDebugLevel`, `JsonLogConfigTest.eachLineIsOneJsonObjectWithTheRequestIdAndNoCardNumber` | Covered |
| A layer reaches past its neighbour (a controller to a repository, a service to HTTP, the domain to Spring) | The build fails | `ArchitectureTest.controllersNeverTouchARepositoryAnImplementationAnAdapterOrTheVaultsCrypto`, `ArchitectureTest.servicesNeverTouchHttpOrTheApiLayer`, `ArchitectureTest.theDomainKnowsNoSpringAndNoOtherLayer` | Covered |

## 11c. Safe logs, the audit trail, retention and transport (phase 21)
| Case | Handling | Test | Status |
|---|---|---|---|
| A record holding a card token is logged whole | Its `toString()` prints `[redacted]`; every record holding a secret is built and printed by the test | `SensitiveTypesTest.everyRecordThatHoldsASecretAsTextPrintsItRedacted` | Covered |
| A card token, a signed token, a bearer or basic credential, an API key or a `password=` value slips into a log line | Masked by `LogRedactor` in both formats, exceptions, the log context and the console's lines | `LogRedactorTest` | Covered |
| A word that only looks like a secret field (`secret,` in a sentence, `key order-1`) | Left readable: a field needs `:` or `=` after its name | `LogRedactorTest.idsKeysAmountsDatesAndStatusesStayReadable` | Covered |
| The processor or OPERA echoes a card number, a token or a credential in its error code | Redacted before it becomes our message, the stored error or the API reply | `HttpAdapterRepliesTest.aThirdPartyErrorThatEchoesSecretsIsRedactedBeforeItBecomesOurMessage` | Covered |
| A log call is handed a whole body, request, response or command, or an exception's message | The build fails | `CleanCodeTest.noLogCallIsHandedARawBodyRequestResponseOrCommand`, `CleanCodeTest.exceptionsReachALogOnlyThroughRedacted` | Covered |
| A field, record component or column for a CVV, PIN or track data | The build fails | `SensitiveTypesTest.noTypeAndNoTableHoldsACardVerificationCodeAPinOrTrackData` | Covered |
| An unknown API key, or a client refused for its scope | Audited with path, method and origin, never the key | `AuditTrailIntegrationTest.anUnknownApiKeyIsRecordedAsAFailedAuthentication`, `AuditTrailIntegrationTest.aClientRefusedForItsScopeIsRecordedAsAccessDenied` | Covered |
| An audit value tries to forge a field or a line (`evil outcome=SUCCESS`, a line break) | Stays one value | `AuditLogTest.aValueWithSpacesOrLineBreaksStaysOneValue` | Covered |
| The audit trail is switched off by a logging setting | The application does not start | `FailClosedStartupTest.withTheAuditTrailSwitchedOffItDoesNotStart` | Covered |
| A secret or a URL with credentials in the configuration recorded at start-up | Secrets as set or not set; addresses as scheme, host and port | `AuditTrailTest.theStartRecordsTheConfigurationByValueAndEachSecretOnlyAsSetOrNotSet` | Covered |
| A card unused past its retention period | Its number and key are destroyed; it can no longer be charged | `CardRetentionIntegrationTest.aCardUnusedForLongerThanThePeriodLosesItsNumberAndCanNoLongerBeCharged` | Covered |
| A card with a payment still pending, however old | Never purged | `CardRetentionIntegrationTest.aCardWithAPaymentStillPendingIsNeverPurged` | Covered |
| A card opened between the purge's search and its write | Kept: the write checks again | `CardRetentionIntegrationTest.thePurgeChecksTheCardAgainAsItWrites` | Covered |
| A payment started on a card between the purge's search and its write | Kept: the write checks for a pending payment again | `CardRetentionIntegrationTest.thePurgeChecksForAPaymentPendingAsItWrites` | Covered |
| A batch full of old cards with payments pending | Skipped by the search, so the cards behind them are still purged | `CardRetentionIntegrationTest.cardsWithAPaymentPendingDoNotHoldUpThePurgeOfOthers` | Covered |
| More expired cards than one batch | Drained batch by batch | `CardRetentionIntegrationTest.aPurgeTakesOneBatchAtATimeAndDrainsThemAll` | Covered |
| A card stored at booking for a stay further ahead than the retention period | Purged before the stay: the period must be set longer than the booking window | None (D-44) | Not covered: needs the booking on the card |
| A charge started on a card in the instant its purge wins | A final refusal: nothing reached the processor, and the payment link goes back to the guest | `ChargeServiceImplTest.aCardGoneBeforeTheFirstAttemptIsAFinalRefusal` | Covered |
| A payment repeated after its card was purged | Gets its first result: the key is looked up before the card | `CardRetentionIntegrationTest.aPaymentRepeatedAfterItsCardWasPurgedGetsItsFirstResult` | Covered |
| A database URL that names verify-full but switches the check off, or a signing-key address over plain HTTP | The application does not start | `TransportSecurityGuardTest` | Covered |
| OPERA or the processor over plain HTTP, PostgreSQL without `verify-full` | The application does not start, unless plaintext is allowed by name for the Compose demo | `TransportSecurityGuardTest`, `FailClosedStartupTest.withARealGatewayOverPlainHttpItDoesNotStart` | Covered |
| A hotel with a different deposit policy or a slower webhook back-off | Applied as configured | `PaymentRequestPolicyTest.anotherHotelsPolicyIsAppliedAsConfigured`, `WebhookPropertiesTest.theDeliveryBackOffComesFromTheSettings` | Covered |

## 12. Not covered: be honest about these
1. **The real OPERA Cloud (OHIP) and OPERA 5.** Only a simulator exists. The four OHIP operations the bridge uses follow Oracle's published contract and every exchange is checked against it (D-42), but what the contract leaves open (the 201's body, a payment's sign, deposits before arrival, a lock's status, rate limits) needs a sandbox; OPERA 5's interfaces (OXI, OWS) are not built.
2. **A real processor or acquirer**, 3-D Secure, hosted card entry, stored credentials, refunds, chargebacks and dispute evidence.
3. **Crash between steps.** A JVM crash after a capture and before it is saved can strand a payment; there is no sweeper.
4. **Tenant scoping** of credentials and data, mutual TLS, and rate limiting per client (authentication and scopes are built: section 11a).
5. **Booking-event ordering and duplicate delivery** from a real PMS; refund-review flags are not persisted.
6. **Reject-and-repair** for a posting OPERA refuses for good (a checked-out folio): an operator posts it in OPERA by hand; the bridge has no workflow for it.
7. **Real KMS and Cognito** in a real AWS account; circuit breaker and bulkhead (timeouts exist; D-33 explains why the business date is asked for every new charge even during an outage); load and soak behaviour.
8. **Multi-property** operation (one hotel id is configuration here).
9. **Clock and timezone edge cases** beyond the business-date rule: daylight-saving zones, leap days.
10. **Concurrency beyond one JVM**: the database guarantees hold across instances, but this was tested with threads in one process, not with several containers.
11. **A posting lease held by a request that crashed** blocks replay of that posting until it runs out (derived from the timeouts: about four minutes with the defaults); there is no alarm for it.
12. **A posting dead-lettered across night audit** is posted by OPERA on the day it is replayed, while the bridge's record keeps the business date of the capture: reconciliation must explain the difference until OPERA's date is stored too (`specs/roadmap.md`, Next 5).
13. **The simulators' control endpoints** (`/sim/reset`, `/sim/night-audit`, faults) have no Host check or CSRF protection: a web page open in the same browser could reset a local demo. They are demo tools, never deployed.
14. **Webhook ordering**: events are delivered at least once and may arrive out of order (a posting before its capture after a retry); receivers order by the data, not by arrival.
15. **Compose with local keys over PostgreSQL**: `docker compose restart app` keeps the database but makes new keys, so stored cards can no longer be opened. The guard refuses local keys over a persistent database unless `foliopay.allow-ephemeral-keys=true`, which only the Compose demo profile sets; use `docker compose down -v` to start clean.
16. **Card number copies in memory**: the vault zeroes its byte buffer, but the number also exists as a Java `String` while the processor call is built, which cannot be zeroed.
17. **Reconciliation without a period** (`from`/`to` left out) still reads every captured charge; with a period it reads only that period and the references the statement names.
18. **The migrations against PostgreSQL in the test run.** Unit and integration tests use H2 in PostgreSQL mode with a schema generated from the entities, so the Flyway migrations, the foreign keys and PostgreSQL's own locking are exercised only in the Compose stack (`docker compose up`, the full-flow dry run). Testcontainers would run them in `./mvnw verify` (a test dependency, for the owner to approve).
19. **Rate limiting.** No per-client limit on tokenising or paying (card testing); it belongs in front of the service (AWS WAF rate-based rules, API Gateway usage plans) and is in the deployment runbook, not built here.
20. **Pagination.** The ledger lists return the newest 500 rows; there is no cursor for reading further back.

Coverage of the code by tests is measured, not claimed: `python3 tools/coverage.py` (99.5% lines, 91.5% branches at the time of writing, 437 backend tests plus 15 dashboard tests; the gate in `./mvnw verify` fails below 99% and 90%). Coverage shows lines were run, not that every case above is right; this page is the list of cases.
