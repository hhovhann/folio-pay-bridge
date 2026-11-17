# Phase 1 Plan - Card vault and tokenisation

1. Write `Luhn`, `Pan`, `Crypto`, `KeyService`, `CardVault`, entity and repository
2. Add `KmsKeyService` behind `foliopay.keys=kms`; test it against a stubbed `KmsClient`
3. Add the tokenise endpoint with Bean Validation
