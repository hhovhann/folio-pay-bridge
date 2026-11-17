# Phase 9 Validation - Delivery: container, PostgreSQL profile, CI

- [ ] `./mvnw verify` passes in CI (manual: needs a push)
- [x] The image builds on Amazon Linux 2023 and runs: `docker compose up --build`, `/actuator/health` UP with liveness and readiness, a payment captured and posted, a foreign `Host` header refused with 400 (2026-11-17, after the review fixes)
- [x] The PostgreSQL profile starts against Compose: the `charge` row was read back from PostgreSQL with status CAPTURED, folio, business date and version (2026-11-17)
- [x] The same image with no profile refuses to start (no key mode, no gateway) instead of running on fakes (2026-11-17)
- [ ] The KMS profile is exercised in a real AWS account (manual)
