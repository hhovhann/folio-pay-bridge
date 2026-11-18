# Phase 1 Validation - Card vault and tokenisation

- [x] `PciIntegrationTest.theCardNumberIsNowhereInTheDatabaseAfterAFullFlow`
- [x] `CardVaultImplTest.storesOnlyCiphertextAndTheDisplayDigits`
- [x] `CardVaultImplTest.lendsTheCardNumberToTheCallerAndNothingMore`
- [x] `CardVaultImplTest.aCiphertextMovedToAnotherTokenCannotBeOpened`
- [x] `CryptoTest.ciphertextHoldsNoPlaintextAndTwoSealsDiffer`
- [x] `CryptoTest.aTamperedCiphertextIsRejected`
- [x] `PanTest.refusesNumbersThatFailTheLuhnCheck`
- [x] `KmsKeyServiceTest.asksKmsForAnAes256KeyBoundToTheToken`
- [x] `KmsKeyServiceTest.unwrapsWithTheSameEncryptionContext`
- [x] `KeyModeGuardTest.localKeysUnderAPersistentDatabaseAreRefusedBecauseEveryStoredCardWouldDieAtRestart`
- [x] `FailClosedStartupTest.withNoKeyModeItDoesNotStart`
- [x] `ApiIntegrationTest.tokenisingReturnsATokenAndNeverTheNumberNorDoesTheVaultView`
- [x] `ApiIntegrationTest.aBadCardNumberIsRejectedWithoutEchoingIt`
- [ ] The KMS adapter is exercised against real AWS (manual) - needs an AWS account, a key and an IAM role; steps in docs/aws-deployment.md section 5
