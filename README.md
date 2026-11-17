# Folio Pay Bridge: a hotel payments slice (concept pilot)

Built as a work sample for an **AI-first Senior Java Engineer (payments)** role. It shows, in miniature, two services a hotel-payments platform needs: **charging virtual cards** and **posting the payment to the hotel PMS (Oracle OPERA)**. Around them: payment-link automation, reconciliation against an acquirer statement, and a shadow mode that compares the new rules with a legacy stand-in.

**Synthetic data only.** The card processor and the PMS are in-memory fakes behind real ports. Public test card numbers. Not any company's software. Nothing here is compliance advice and no PCI certification is claimed.

**Stack:** Java 17 · Spring Boot 3.5 · Spring Data JPA / Hibernate · Resilience4j · springdoc OpenAPI · AWS SDK (KMS) · Amazon Linux 2023 image · built with Claude Code (Sonnet 5.5) agents directed by a written brief ([`CLAUDE.md`](CLAUDE.md)), then reviewed by hand and independently by Claude Opus 5.5 and Claude Fable 5.2.

## Honest limits (read first)
- **Simulators, not integrations.** No real OPERA Cloud (OHIP), OPERA 5, processor, hosted payment page or 3-D Secure. The OPERA port models what a real posting needs (hotel, reservation, folio window, payment code, cashier, PMS business date, look-before-repost). Two adapters exist: in-memory fakes, and HTTP clients for the OHIP-style and processor simulators in `compose.yaml`. The simulators are not Oracle software.
- **No authentication.** The API binds to loopback and the demo endpoints are off by default, but there is no login. Do not expose it to a network. [`SECURITY.md`](SECURITY.md) lists what a real deployment needs.
- **KMS is not proven against AWS.** `KmsKeyService` is tested against a stubbed client. Steps to close that are in [`docs/aws.md`](docs/aws.md); the box stays open until someone runs them.
- **A crash between steps can strand a payment.** Retries, dead letters and replay cover outages and lost responses, not the JVM dying after a capture and before the result is saved. There is no sweeper yet.
- Idempotency keys are global (not per property); schema comes from Hibernate, not migrations; no circuit breaker or timeouts on downstream calls.
- Shadow-mode rules (tax rounding, business date) are illustrative stand-ins for what a real legacy system does.
- The Docker image and the PostgreSQL profile were built and run locally on 2026-11-17; CI has not run on GitHub yet.

## What is proven, and by which test
| Claim | Test that would fail if it were false |
|---|---|
| The card number is in no database column after a full flow | `PciIntegrationTest.theCardNumberIsNowhereInTheDatabaseAfterAFullFlow` (every column of every table, text and binary) |
| A ciphertext cannot be moved to another token; tampering is detected | `CardVaultImplTest.aCiphertextMovedToAnotherTokenCannotBeOpened`, `CryptoTest` |
| A card number logged by mistake is masked in the production log config, also inside exception text | `ProductionLogConfigTest` (loads the real `logback-spring.xml`), `PanMaskerTest` |
| The card number is not held in memory while a retry waits | `ChargeServiceImplTest.theCardNumberIsNotHeldWhileARetryWaitsOrDuringCapture` |
| Transient failures are retried with the same key; a decline is not retried; a declined capture releases the hold | `ChargeServiceImplTest` (real Resilience4j policy) |
| A repeated or raced payment leaves one charge row, one CAPTURED state and one posting row; the same key with a different folio or date is refused | `PaymentFlowIntegrationTest`, `ReviewFindingsIntegrationTest` (real unique constraints and version column) |
| A request that loses a race never overwrites a capture or a post | `IdempotentStoreTest` |
| A payment link cannot be charged twice, even by two cards at once | `ReviewFindingsIntegrationTest.twoCardsRacingOnOneOpenLinkProduceExactlyOneCapture`, `...aPaidLinkCannotBePaidAgainWithAnotherCard` |
| A captured payment whose posting fails is dead-lettered with a reason; replay posts it once; a rejected one is not retried; OPERA is asked before a repeat post | `PostingServiceImplTest`, `PaymentFlowIntegrationTest` |
| The business date comes from the PMS, and a different one is refused | `ReviewFindingsIntegrationTest.aBusinessDateThatIsNotTheHotelsIsRefusedAndTheDefaultComesFromThePms` |
| Reconciliation names missing, short, wrong-currency, unknown, duplicate and unposted items, and ignores shadow runs | `ReconciliationServiceImplTest` |
| Ids, upstream messages and CSV cannot forge log lines or spreadsheet formulas; amounts cannot overflow | `PaymentControllerTest`, `SafeTextTest`, `SettlementParserTest`, `PaymentRequestPolicyTest` |
| The app refuses to start with no key mode or no gateway; demo endpoints and the API description are absent by default | `FailClosedStartupTest`, `KeyModeGuardTest`, `DemoControlsOffIntegrationTest` |
| The published OpenAPI file is what the code serves; only `POST /api/cards` accepts a card number; no response carries card data | `OpenApiContractTest` |
| Every ticked box in a spec points at a test that ran and passed | `tools/check_specs.py` (reads the surefire reports) |

Tests: **271** (unit tests per rule and service with test doubles; `@WebMvcTest` for the HTTP contract; an `integration` package on H2 and against the HTTP simulators for what only a real database or network proves). Coverage is measured, not claimed: **99.8% of lines and 92.3% of branches** (JaCoCo; `python3 tools/coverage.py`), enforced by a gate in `./mvnw verify`. The only uncovered lines are an unreachable `NoSuchAlgorithmException` catch; the two `main()` methods are excluded. [`docs/edge-cases.md`](docs/edge-cases.md) lists every edge case, the test that fails if it breaks, and what is **not** covered; `tools/check_specs.py` fails the build if a named test is missing.

## Architecture
```
api            controllers, DTOs, one @RestControllerAdvice          -> service
service        use-case interfaces; service.impl = package-private implementations
domain         entities, Money, pure rules, exceptions (no Spring beyond JPA annotations)
repository     Spring Data repositories
gateway        ports PspClient / OperaClient; gateway.fake = in-memory fakes (only with foliopay.gateway=fake)
crypto         KeyService (local for demo, KMS for production), AES-256-GCM
config         Resilience4j retries, clock, host allowlist, size limit, security headers
```
API description: [`docs/openapi.json`](docs/openapi.json), generated by springdoc and checked against the code by `OpenApiContractTest`; Swagger UI at `/swagger-ui/index.html` in the demo profile only.

Decisions and their reasons are in [`specs/decisions.md`](specs/decisions.md) (D-1 to D-25). Retry is a Resilience4j policy per downstream (D-15); concurrency is the database's job (D-16, D-22); the app fails closed (D-17); a payment link has one claim at a time (D-19); OPERA is a port with two possible adapters (D-23).

## Run
```bash
export JAVA_HOME=<a JDK 17>
./mvnw test                                              # 271 tests
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo   # console at http://127.0.0.1:8081, Swagger UI at /swagger-ui/index.html
python3 tools/check_specs.py                             # after the tests
docker compose up --build                                # PostgreSQL + demo profile, port on loopback only
DEMO_FAST=1 uv run --project demo python demo/make_demo.py   # dry run of the narrated demo
```
Without the `demo` profile the app needs `foliopay.keys=kms` (plus `foliopay.kms.key-id`) and a real gateway adapter, and it will not start on the fakes: that is deliberate.

## Full flow in Docker (not a dummy demo)
```bash
docker compose up --build
# bridge console   http://127.0.0.1:8081    guest, operator, reconciliation, API docs
# OPERA front desk http://127.0.0.1:8090    folios, business date, night audit, faults, sends booking events
# processor        http://127.0.0.1:8091    ledger, settlement, faults
```
Four containers over real HTTP: the bridge, PostgreSQL, an OPERA simulator and a processor simulator. The PMS announces a booking; the bridge makes a payment link; a guest pays; the processor authorises and captures; the payment is posted to the reservation's folio window with a payment code and cashier. From the two simulator pages you can expire OPERA's access token, rate-limit it, take it down, make it post and then reply too late, run night audit (the business date moves), or make the processor capture and then go quiet, and watch the bridge recover. `FullFlowHttpIntegrationTest` does the same through real HTTP in 14 tests.

**This is not Oracle OPERA.** There is no public container image of OPERA or OPERA Cloud: it is a managed service (or a licensed on-premise install for OPERA 5). The simulator follows the shape of OHIP (OAuth client credentials with an application key, bearer tokens, `x-hotelid`, a business date that moves at night audit, payments to a folio window, no deduplication) but its resource paths are simplified and not checked against Oracle's documentation. Running against the real thing needs OHIP partner access and its sandbox; that box in phase 11 stays open.

## Deploying on AWS
[`docs/aws-deployment.md`](docs/aws-deployment.md) is a step-by-step runbook (VPC, KMS, RDS, Secrets Manager, ECS on Amazon Linux 2023, the midnight-to-3am deployment window, a going-live checklist, and how OPERA Cloud fits: it is Oracle's SaaS, called over HTTPS, not something you run in AWS). It has not been executed in a real account.

## Demo video
Four minutes, narrated, captions burned in: attached to the [v0.1.0 release](../../releases/tag/v0.1.0). Captions (`.vtt`, `.srt`) are in [`demo/out`](demo/out).

## How it was built
Specs first, one folder per capability under `specs/` (requirements, plan, validation), a roadmap, and written decisions. A box is ticked only when the named test or check passes. [`docs/agent-log.md`](docs/agent-log.md) lists what the agents got wrong and how each was caught, including a second pass in which two other models reviewed the code and found, among other things, that a paid payment link could be paid again by another card.

## Roadmap
1. A sweeper that re-drives stale PENDING charges and creates postings for CAPTURED charges that have none, with a test that kills the flow mid-way.
2. Authentication and roles, tenant-scoped keys and tokens, rate limits.
3. Real OPERA Cloud (OHIP) and OPERA 5 adapters: OAuth, rate limits, reject-and-repair for a wrong business date.
4. Flyway migrations and `ddl-auto=validate`; timeouts, circuit breaker and bulkhead on downstream calls.
5. PSP commands that carry 3-D Secure and stored-credential fields; persisted refund-review flags and booking-event sequencing.
6. Hosted card entry for guests (the number never reaches the service); chargeback cases with scheme deadlines and evidence packs; virtual-card collection from booking channels as bookings arrive; guest messaging; many properties.
