# Onboarding: learn this system in five sessions

For an engineer (or a reviewer) who wants to own this code, not just read it. Each session has a goal, what to read, what to run, and one thing to break on purpose so you see the safety net work. Plan on about two hours each. Prerequisites: a JDK 17 (`export JAVA_HOME=...`), Docker, Python 3, and [`docs/architecture.md`](architecture.md) open beside you for the glossary.

## Hour 0: who is who, with no code (anyone)
**Goal:** name the eleven parties in a hotel card payment, and say which of them this project builds, which it simulates, and which it only stands in for.
- Read [`docs/who-is-who.md`](who-is-who.md) (ten minutes), then watch the [demo video](../../../releases/latest) (about eight minutes).
- If Docker runs on your machine: `docker compose up --build`, then open `http://127.0.0.1:8081/who-is-who.html`. Press Play, then click every party and read its card: who it is in real life, what it sees of the card, and what plays it in the demo.
- Then follow one payment through the real screens, in the order of the page's nine steps: the OPERA front desk (`:8090`, "New reservation: advance purchase"), the bridge console (`:8081`, "Tokenise test card", then "Pay"), the processor (`:8091`: the authorisation, the capture, the network and the bank's approval code, and the "Behind the processor" strip), and back at the front desk the folio and "Events from the bridge".
- Someone who is not going to change the code can stop here. An engineer goes on to the five sessions.

**Fast path for an engineer (the first hour):** Hour 0, then `./mvnw clean verify && python3 tools/check_specs.py` (all green, about four minutes), then read `CLAUDE.md` and the first two sections of [`engineering-standards.md`](engineering-standards.md). By the end of the hour you have run the stack, seen the proof, and know the rules a change has to keep.

## Session 1: the money flow, happy path
**Goal:** say in one minute what happens between "the PMS sends a booking" and "the payment is on the guest's folio".
- Read: `docs/architecture.md` sections 1 to 3; `specs/mission.md`; `specs/decisions.md` D-1 to D-5.
- Run: `docker compose up --build`, and open the operator dashboard at `http://127.0.0.1:8081/dashboard/` beside the classic console. In the OPERA front desk (`:8090`) send "New reservation: advance purchase". In the bridge console (`:8081`) tokenise the test card and pay the link. Watch the processor ledger (`:8091`) show AUTH and CAPTURE with only the last four digits, the folio show the payment with window, payment code and cashier, and "Events from the bridge" show `payment.captured` and `folio.posted`.
- Read the code in this order: `BookingController` → `BookingEventServiceImpl.pay` → `PaymentServiceImpl` → `ChargeServiceImpl.execute` → `PostingServiceImpl.resume`. Note how the controller never touches a repository, and the services never touch HTTP (D-14).
- Break it: in the OPERA page choose `DOWN`, pay another booking, see "Charged, not posted" and the dead letter; set `NONE` and press Replay. Then read `PostingServiceImpl` again: why can replay never post twice?

## Session 2: exactly once, under failure
**Goal:** explain idempotency keys, the lease, and "a timeout is unknown, not failed" (D-2, D-3, D-27, D-28).
- Read: `IdempotentStore`, `Charge.matches/isBehind`, `PostingRepository.lease/renew`, `ResilienceConfig.postingLease`.
- Run: `./mvnw test -Dtest='ChargeServiceImplTest,PostingServiceImplTest,PaymentFlowIntegrationTest'`.
- Break it: in `PostingServiceImpl.stillHeld`, change the condition to `false`, run `PostingServiceImplTest`: the test `aRequestWhoseLeaseRanOutAndWasTakenOverDoesNotPostAndDoesNotOverwriteTheNewHolder` fails. Put it back. This is the habit the project runs on: a test is only proof if it fails without the fix.
- Discuss: what happens if the JVM dies between capture and saving the result? (Not covered: no sweeper yet. What would you build?)

## Session 3: card data and PCI
**Goal:** walk a reviewer through envelope encryption with KMS, and why no card number reaches a log (D-6 to D-8, D-24, D-31).
- Read: `CardVaultImpl`, `Crypto`, `KmsKeyService`, `LogRedactor`, `PanMasker`, `Redacted`, `AuditLog`, `SECURITY.md` (its PCI DSS v4.0.1 table).
- Run: `./mvnw test -Dtest='PciIntegrationTest,CardVaultImplTest,KmsKeyServiceTest,PanMaskerTest,LogRedactorTest,RedactedTest,SensitiveTypesTest,AuditTrailIntegrationTest,CardRetentionIntegrationTest'`. Open the console's vault view: ciphertext, wrapped key, key id; no number.
- Break it: add `log.info("pan {}", pan.value())` in `CardVaultImpl.withCard` and run `PciIntegrationTest`: it fails on the raw log events, before any masking. Remove it.
- Discuss: which parts of a real deployment are in PCI scope, and how hosted card entry (the PSP's fields) would shrink that scope.

## Session 4: OPERA, the processor, and the outside world
**Goal:** know OPERA the way its integrators do, and what a real OHIP and acquirer integration needs beyond the simulators (D-23, D-26, D-34, D-42).
- Read: Oracle's own `postBillingPayment`, `getFolio` and `getBusinessDate` in `src/test/resources/ohip/` (trimmed from Oracle's published files), then `HttpOperaClient` beside them (OAuth client credentials, `x-app-key`, `x-hotelid`, the `criteria` body, reading the folio before a repost, `o:errorCode`), `OhipContractTest` and `OhipSpec` (how every exchange is checked against Oracle's files), `HttpPspClient` (idempotency header, 402 vs 409 vs 401 vs other 4xx), `BusinessDateServiceImpl` (night audit, D-33), `docs/aws-deployment.md` section 1 (OPERA Cloud is Oracle's SaaS).
- Work the front desk (`:8090`) as a front-desk agent would: open the in-house guest, read the folio (a room night, the balance), check in the due-in guest, post a room night, try to check out (refused: a balance is owed), pay the balance through the bridge, check out. Run night audit: every guest in house gets a room night on the day it closes, and the business date moves.
- Run: `./mvnw test -Dtest='OhipContractTest,FullFlowHttpIntegrationTest,HttpAdapterRepliesTest'`. In the OPERA page run night audit and expire tokens, then pay: the bridge fetches a new token, and OPERA posts the payment on the new date.
- Discuss: why no one can run OPERA locally, and how real access comes (Oracle's partner sandbox, a hotel's test environment); the questions the first sandbox session answers (`specs/roadmap.md`, Next 4); and for the acquirer, 3-D Secure fields, refunds and the settlement file format.

## Learning path: what to study, in order
1. **OPERA Cloud and OHIP** (the job's daily system): folios and folio windows, transaction codes and payment methods, cashiers, night audit and the business date, deposits before arrival, check-out with a zero balance. Read the cashiering (`csh`), back office (`bof`), reservations (`rsv`) and front desk (`fof`) specifications in github.com/oracle/hospitality-api-docs, and its `WORKFLOWS.md` (check-in, check-out, posting charges and payments). OPERA 5 hotels are still common during the move to the cloud: know what OXI, OWS and IFC8 are.
2. **The acquiring stack the company uses**: authorisation, capture, void, refund and tokens; hosted card entry (so a card number never reaches the service) and payment links; 3-D Secure and liability shift.
3. **Hotel payment specifics**: virtual cards from online travel agencies (charged like a card, often after arrival), chargebacks and the evidence that wins them, settlement files and reconciliation.
4. **PCI DSS 4.0.1**: what puts a system in scope, tokenisation, key management (KMS, envelope encryption, key rotation), logging rules.
5. **OPI (OPERA Payment Interface)**: Oracle's own link between OPERA and terminals and payment providers, and where a payment company's product fits beside it.

## Session 5: running it in production, and working with agents
**Goal:** deploy-readiness and the AI-first way of working.
- Read: `docs/aws-deployment.md` (ECS on Amazon Linux 2023, RDS, KMS, Secrets Manager, Cognito, the midnight-to-3am window), `SECURITY.md` (authentication and scopes), `src/main/resources/db/migration/V1__baseline.sql`, `CLAUDE.md`, `docs/agent-log.md`, `.claude/settings.json`, `tools/guard_secrets.py`.
- Run: `python3 tools/new_api_key.py demo-client "payments:read"`; start the app with that entry in `FOLIOPAY_AUTH_API_KEYS` and call `/api/v1/charges` with and without `X-Api-Key`. Run `python3 tools/guard_secrets.py --repo` and `python3 tools/check_specs.py`.
- Break it: ask an agent to add a test with a card number that passes the Luhn check but is not a public test number: the hook in `.claude/settings.json` blocks the write and tells the agent why.
- Discuss: the next phase (multi-tenancy, `docs/architecture.md` section 9) and how you would brief agents for it: spec first, decisions recorded, tests that fail without the change, a review by another model, a human who proves it.

## After the five sessions you should be able to
- Draw the flow and name every actor and term (guest, PMS, processor, acquirer, card network, issuer; folio window, business date, authorisation, capture, settlement, envelope encryption), and say which ones this project builds, simulates or only stands in for.
- Point to the test that proves any claim in the README, and break the code to show it.
- Say plainly what is simulated, what is missing, and what you would build next.
