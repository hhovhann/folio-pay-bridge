# Phase 5 Validation - Booking events and payment requests

- [x] `PaymentRequestPolicyTest.flexibleFarAheadIsATwentyPercentDepositDueAWeekBeforeArrival`
- [x] `PaymentRequestPolicyTest.theWeekBoundaryIsExclusive`
- [x] `BookingEventServiceImplTest.aNewBookingCreatesOneOpenRequestFromThePolicy`
- [x] `BookingEventServiceImplTest.aRepeatOfTheSameEventChangesNothing`
- [x] `BookingEventServiceImplTest.aModifiedBookingSupersedesTheOpenRequestAndOpensAnother`
- [x] `BookingEventServiceImplTest.aChangeWhileAPaymentIsInProgressIsNotAppliedSoTheGuestIsNotAskedToPayTwice`
- [x] `BookingEventServiceImplTest.cancellingAfterPaymentFlagsARefundReview`
- [x] `BookingEventServiceImplTest.payingClaimsTheLinkChargesWithAKeyThatHoldsNoTokenAndClosesItOnCapture`
- [x] `BookingEventServiceImplTest.aDeclinedCardGivesTheLinkBackForAnotherCard`
- [x] `BookingEventServiceImplTest.aSecondCardWhileAPaymentIsInProgressIsRefusedAndNeverCharged`
- [x] `BookingEventServiceImplTest.aSecondCardAfterTheLinkIsPaidIsRefusedAndNeverCharged`
- [x] `BookingEventServiceImplTest.aCancelledLinkCannotBePaid`
- [x] `LinkPaymentIntegrationTest.payingALinkChargesPostsAndClosesItAndADoubleClickChargesOnce`
- [x] `LinkPaymentIntegrationTest.aLinkIsPaidOnCaptureEvenWhenOperaIsDownAndThePostingWaits`
- [x] `LinkPaymentIntegrationTest.aGuestWhoseCardIsDeclinedCanPayTheSameLinkWithAnotherCard`
- [x] `ReviewFindingsIntegrationTest.aPaidLinkCannotBePaidAgainWithAnotherCard`
- [x] `ReviewFindingsIntegrationTest.twoCardsRacingOnOneOpenLinkProduceExactlyOneCapture`
- [x] `ReviewFindingsIntegrationTest.linkChargeKeysNeverContainTheCardToken`
