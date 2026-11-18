# Tech stack

Chosen to match the job posting, one line of reasoning each.

| Area | Choice | Why |
|---|---|---|
| Language | Java 17 (records, switch expressions, `instanceof` patterns) | Posting: Java 17 |
| Framework | Spring Boot 3.5, Spring MVC, Bean Validation, Actuator | Posting: Spring |
| Persistence | Spring Data JPA on Hibernate; H2 (PostgreSQL mode) for demo and tests, PostgreSQL profile for real runs | Posting: Hibernate. Unique constraints carry the idempotency guarantees |
| Keys and secrets | AWS SDK v2 `KmsClient`: `GenerateDataKey` AES-256, encryption context = token id; local in-memory master key for demo and tests | Posting: keys in KMS. Credentials come from the instance or task role, never from config |
| Crypto | AES-256-GCM, 96-bit random IV, token id as associated data | A ciphertext cannot be moved to another token |
| API description | springdoc-openapi 2.8.17 (`webmvc-ui`; 3.x targets Spring Boot 4), added with the owner's approval | The spec is generated from the controllers and DTOs, so it cannot drift; `docs/openapi.json` is the committed copy and `OpenApiContractTest` fails if they differ. Off by default, on in the demo profile (OWASP A05) |
| Retry | Resilience4j `resilience4j-retry` 2.2.0 (added with the owner's approval) | Tested, configurable backoff with jitter instead of a hand-written loop; no Spring AOP needed, so it stays a plain injected bean |
| Structure | Layers: api, service (interface + impl), domain, repository, gateway, crypto | Dependencies point inward; see D-14 |
| Coverage | JaCoCo 0.8.12 (added at the owner's request): report at the end of `./mvnw test`, gate in `./mvnw verify` at 99% lines and 90% branches; `python3 tools/coverage.py` lists what is uncovered | A measured number instead of a claim. Excluded: the two `main()` entry points only |
| Authentication | Spring Security 6.5 with `spring-boot-starter-oauth2-resource-server` (added with the owner's approval, 2026-11-18): OAuth2 client-credentials JWTs checked against the authorization server's JWKS (signature, issuer, audience, expiry, scopes), plus scoped API keys stored as SHA-256 hashes | What payment APIs do (OAuth2 client credentials; restricted, hashed API keys for integrators that cannot do OAuth2). A framework that is reviewed and patched instead of a hand-written filter (D-30) |
| Schema migrations | Flyway (`flyway-core`, `flyway-database-postgresql`, added with the owner's approval, 2026-11-18); Hibernate `validate` on PostgreSQL | The schema is reviewed SQL under version control, applied before the code that needs it (D-12) |
| Edge hardening | Request id, security headers, a host allowlist and a 1 MB body limit as small servlet filters | Each tested on its own; Spring Security's own header writer is off so there is one owner of the headers |
| Operator dashboard | React 18.3 + TypeScript 5.6, built with Vite 5.4; tests with Vitest 2.1 and Testing Library; `oidc-client-ts` 3.1 for sign-in; built and tested by Maven through `frontend-maven-plugin` 1.15 with Node 22 pinned (all added with the owner's approval, 2026-11-18) | The most common choice a reviewer will know; lighter than Angular for five screens; pinned versions and a lock file, so the build is the same everywhere. No UI kit: one stylesheet with light and dark tokens (D-38) |
| Browser checks | Playwright (Python, in `demo/`): `demo/check_dashboard.py` drives the real dashboard against the running app and fails on any browser error, including content security policy violations | A UI is proven in a browser, not only in a DOM simulator |
| AI guardrails | `tools/guard_secrets.py` (standard-library Python) as a Claude Code `PreToolUse` hook and a CI step; project permission rules in `.claude/settings.json` | The `CLAUDE.md` hard rules enforced by the tooling, not only by the reader (D-36) |
| Logging | Logback with a `%msg` converter that masks Luhn-valid 13 to 19 digit runs | Posting: nothing sensitive in logs |
| Tests | JUnit 5, AssertJ, Mockito, Spring Boot Test, MockMvc. Unit tests per rule and service (fast, no Spring); `@WebMvcTest` for the HTTP contract; a small `integration` package on H2 for what only a real database proves (constraints, versions, races) | Each test names the failure it would catch |
| Build | Maven wrapper | |
| Runtime | Amazon Corretto 17 on Amazon Linux 2023 image | Posting: AWS, Amazon Linux 2023 |
| Demo | Playwright + local Kokoro voice + ffmpeg | Narrated video from the real app |
