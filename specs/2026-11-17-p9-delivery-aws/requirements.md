# Phase 9 Requirements - Delivery: container, PostgreSQL profile, CI

## Goal
Runs on Amazon Linux 2023 with Java 17, against PostgreSQL, built by CI.

## Scope
- Multi-stage Dockerfile on Amazon Corretto 17 / Amazon Linux 2023, non-root
- `postgres` Spring profile and a Compose file
- GitHub Actions: `mvnw verify`
- IAM policy sketch for the KMS key (GenerateDataKey and Decrypt with the encryption context)

## Out of scope
- Real card networks, 3-D Secure, a hosted payment page, and any real PMS or processor
