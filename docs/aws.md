# AWS notes (design; the container and PostgreSQL parts are verified locally, KMS is not)

- **Runtime:** ECS on EC2 or EC2 with Amazon Linux 2023, Corretto 17 (the Dockerfile uses `amazoncorretto:17-al2023`, built and run locally on 2026-11-17). Runs as a fixed non-root user id. Health check: `GET /actuator/health`.
- **Keys:** set `foliopay.keys=kms` and `foliopay.kms.key-id`. The app uses `KmsClient.create()`: region and credentials come from the instance or task role, so no key or secret is ever in configuration. With `foliopay.keys` unset the application refuses to start: there is no silent fallback to the throwaway local key.
- **Key policy sketch** for the task role: `kms:GenerateDataKey` and `kms:Decrypt` on the one key, with a condition that the encryption context contains `token`. Separate roles for the app and for key administration. Key rotation on. CloudTrail records every use, with the token id as context.
- **Database:** RDS PostgreSQL, encrypted at rest, TLS in transit, private subnets. The table `card_token` holds only ciphertext and the wrapped data key. Schema management should be Flyway with Hibernate `validate`; the pilot uses `ddl-auto` (D-12).
- **Logs:** CloudWatch. The Logback mask is the last line of defence; the code does not log card numbers at all.
- **Gateways:** the processor and OPERA adapters are the fakes. `foliopay.gateway` must name a real adapter before a production start; with nothing set the application refuses to start. Per-hotel OPERA settings (`foliopay.opera.*`: hotel id, folio window, payment code, cashier) are configuration, and the business date is read from OPERA.
- **Not covered here:** network segmentation and the rest of the PCI DSS scope (a service that handles a PAN is in scope), the hosted payment page and 3-D Secure, authentication and authorisation on the API (none in the pilot: it binds to loopback), secrets rotation for PMS and processor credentials, a sweeper that re-drives charges and postings stranded by a crash between steps, and alarms on the dead-letter queue.

## Closing the "KMS against real AWS" box (manual)
The adapter is only tested against a stubbed `KmsClient`. To tick the box in phase 1 and phase 9 you need a throwaway AWS account:
1. Create a symmetric KMS key (alias `alias/folio-pay-bridge-test`) and an IAM role/user with `kms:GenerateDataKey` and `kms:Decrypt` on it only.
2. Run with `AWS_PROFILE=<that profile> AWS_REGION=<region> java -jar target/folio-pay-bridge-0.1.0.jar --foliopay.keys=kms --foliopay.kms.key-id=alias/folio-pay-bridge-test --foliopay.demo-controls=true`.
3. Tokenise a public test card, pay with it, and check in CloudTrail that `GenerateDataKey` and `Decrypt` events carry `"encryptionContext": {"token": "tok_..."}`.
4. Try a `Decrypt` with a different token as the context and confirm KMS refuses.
Only then tick the box, and note the date and the account type (not the account id).
