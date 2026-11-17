# Phase 3 Validation - Folio posting, dead letter and replay

- [x] `PostingServiceImplTest.postsAPaymentToTheReservationFolioWindowWithTheHotelsSettingsAndTheProcessorReference`
- [x] `PostingServiceImplTest.anOutageIsRetriedThenDeadLetteredWithAReasonNeverDropped`
- [x] `PostingServiceImplTest.aRejectionIsDeadLetteredAtOnceWithoutRetry`
- [x] `PostingServiceImplTest.aRetryAfterALostResponseFindsTheEarlierPostInsteadOfPostingTwice`
- [x] `PostingServiceImplTest.aReplayLooksForTheEarlierPostBeforePostingAgain`
- [x] `PostingServiceImplTest.replayingARejectedPostingNeverCallsOperaAgain`
- [x] `PostingServiceImplTest.replayingAnOutageDeadLetterPostsItOnceOperaIsBack`
- [x] `PostingServiceImplTest.aRepeatedPaymentForAPostedChargeDoesNotPostAgain`
- [x] `PostingServiceImplTest.anUpstreamMessageIsStoredAsOneBoundedLineSoItCannotForgeLogEntriesOrOverflowTheColumn`
- [x] `PaymentFlowIntegrationTest.anOperaOutageKeepsTheCapturedPaymentAsAReplayablePostingThatPostsExactlyOnce`
- [x] `PaymentFlowIntegrationTest.aLostResponseAfterThePmsPostLeavesOnePostOnTheFolio`
- [x] `PaymentFlowIntegrationTest.aClosedFolioIsRejectedAndReplayLeavesItAloneEvenAfterTheFolioWouldAccept`
- [x] `ReviewFindingsIntegrationTest.aStoredUpstreamMessageIsBoundedAndSingleLine`
- [x] `ReviewFindingsIntegrationTest.aBusinessDateThatIsNotTheHotelsIsRefusedAndTheDefaultComesFromThePms`
- [x] `MoneyFormatTest.formatsEachCurrencyWithItsOwnDecimals`
