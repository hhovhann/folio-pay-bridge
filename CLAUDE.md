# Instructions for Claude Code agents (the brief)

This is a payments codebase in miniature. Treat every change as one that touches money.

## Read first
1. `specs/mission.md`, `specs/tech-stack.md`, `specs/decisions.md`
2. The phase folder you are working in: `requirements.md`, `plan.md`, `validation.md`

## Hard rules
1. **Never log, return, store or print a card number.** The only way to read one is `CardVault.withCard`. No CVV, ever.
2. **Money is `long` minor units.** No `double`, no `BigDecimal` for amounts.
3. **Every call that moves money carries an idempotency key,** and a retry reuses it. A change to the flow needs a test that repeats, races or loses a response.
4. **A failure never loses a captured payment.** It must end up posted, or dead-lettered with a reason, never dropped.
5. **Synthetic data only.** Public test card numbers, fake processor, fake PMS.
6. **Prove it before saying it works.** Run `./mvnw test` and read the failures. A test you wrote to pass is not proof: say what it would catch.
7. Ask before adding a dependency (note why in `specs/tech-stack.md`) or changing a decision in `specs/decisions.md`.

## How to work
- One phase at a time; write the test first; tick a box in `validation.md` only after running the check it names.
- Run `python3 tools/check_specs.py` before you finish.
- Add to `docs/agent-log.md` anything an agent got wrong and how it was caught. Do not invent entries.

## Structure (see D-14)
`api` (controllers, DTOs, error handler) -> `service` (interfaces) + `service.impl` -> `domain` (entities, `Money`, rules, exceptions), `repository`, `gateway` (ports; fakes in `gateway.fake`), `crypto`. A controller never touches a repository; a service never touches HTTP. New behaviour: interface first, impl package-private, a unit test with test doubles, and an integration test only for what needs the real database.

## Commands
```bash
export JAVA_HOME=<a JDK 17>
./mvnw test                      # 271 tests (unit + a small integration layer)
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo   # http://127.0.0.1:8081 (demo profile: local keys, fake PSP/PMS controls)
python3 tools/check_specs.py     # needs the test reports from ./mvnw test
DEMO_FAST=1 uv run --project demo python demo/make_demo.py   # dry run: drives every panel, asserts the narration
uv run --project demo python demo/make_demo.py   # re-record the narrated video
```
