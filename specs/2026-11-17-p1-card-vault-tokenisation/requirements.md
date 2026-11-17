# Phase 1 Requirements - Card vault and tokenisation

## Goal
A card is tokenised on arrival. The database holds ciphertext, a wrapped key and the digits PCI allows, never the number.

## Scope
- `Pan`: Luhn-validated, `toString` shows the last four only
- `CardVault.tokenise` and `withCard` (decrypt inside one call, wipe the key)
- `KeyService` port; `KmsKeyService` (AWS KMS, AES-256 data key, token as encryption context); `LocalKeyService` for demo and tests
- AES-256-GCM with the token id as associated data; no CVV stored
- `POST /api/cards` returns a token, bin6 and last4, never the number

## Out of scope
- Real card networks, 3-D Secure, a hosted payment page, and any real PMS or processor
