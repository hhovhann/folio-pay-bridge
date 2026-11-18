# Onboarding: learn this system in five sessions

For an engineer (or a reviewer) who wants to own this code, not just read it. Each session has a goal, what to read, what to run, and one thing to break on purpose so you see the safety net work. Plan on about two hours each. Prerequisites: a JDK 17 (`export JAVA_HOME=...`), Docker, Python 3, and [`docs/architecture.md`](architecture.md) open beside you for the glossary.

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
- Read: `CardVaultImpl`, `Crypto`, `KmsKeyService`, `PanMasker`, `Redacted`, `SECURITY.md`.
- Run: `./mvnw test -Dtest='PciIntegrationTest,CardVaultImplTest,KmsKeyServiceTest,PanMaskerTest,RedactedTest'`. Open the console's vault view: ciphertext, wrapped key, key id; no number.
- Break it: add `log.info("pan {}", pan.value())` in `CardVaultImpl.withCard` and run `PciIntegrationTest`: it fails on the raw log events, before any masking. Remove it.
- Discuss: which parts of a real deployment are in PCI scope, and how hosted card entry (the PSP's fields) would shrink that scope.

## Session 4: OPERA, the processor, and the outside world
**Goal:** know what a real OHIP and acquirer integration needs that the simulators only imitate (D-23, D-26, D-34).
- Read: `HttpOperaClient` (OAuth token refresh, `x-app-key`, `x-hotelid`, status mapping, look before repost), `HttpPspClient` (idempotency header, 402 vs 409 vs 401 vs other 4xx), `BusinessDateServiceImpl` (night audit, D-33), `docs/aws-deployment.md` section 1 (OPERA Cloud is Oracle's SaaS).
- Run: `./mvnw test -Dtest='FullFlowHttpIntegrationTest,HttpAdapterRepliesTest'`. In the OPERA page run night audit and expire tokens, then pay: the bridge fetches a new token and posts on the new date.
- Discuss: what to verify first in Oracle's OHIP sandbox (paths, error codes, rate limits, how a payment is looked up by reference), and in an acquirer's test environment (3-D Secure fields, refunds, settlement file format).

## Session 5: running it in production, and working with agents
**Goal:** deploy-readiness and the AI-first way of working.
- Read: `docs/aws-deployment.md` (ECS on Amazon Linux 2023, RDS, KMS, Secrets Manager, Cognito, the midnight-to-3am window), `SECURITY.md` (authentication and scopes), `src/main/resources/db/migration/V1__baseline.sql`, `CLAUDE.md`, `docs/agent-log.md`, `.claude/settings.json`, `tools/guard_secrets.py`.
- Run: `python3 tools/new_api_key.py demo-client "payments:read"`; start the app with that entry in `FOLIOPAY_AUTH_API_KEYS` and call `/api/charges` with and without `X-Api-Key`. Run `python3 tools/guard_secrets.py --repo` and `python3 tools/check_specs.py`.
- Break it: ask an agent to add a test with a card number that passes the Luhn check but is not a public test number: the hook in `.claude/settings.json` blocks the write and tells the agent why.
- Discuss: the next phase (multi-tenancy, `docs/architecture.md` section 9) and how you would brief agents for it: spec first, decisions recorded, tests that fail without the change, a review by another model, a human who proves it.

## After the five sessions you should be able to
- Draw the flow and name every actor and term (PMS, folio window, business date, acquirer, capture, settlement, envelope encryption).
- Point to the test that proves any claim in the README, and break the code to show it.
- Say plainly what is simulated, what is missing, and what you would build next.
