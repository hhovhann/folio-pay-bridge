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
| Edge hardening | A host allowlist, a 1 MB body limit and security headers as small servlet filters; no Spring Security yet | Loopback-only pilot; authentication is a known gap (SECURITY.md) |
| Logging | Logback with a `%msg` converter that masks Luhn-valid 13 to 19 digit runs | Posting: nothing sensitive in logs |
| Tests | JUnit 5, AssertJ, Mockito, Spring Boot Test, MockMvc. Unit tests per rule and service (fast, no Spring); `@WebMvcTest` for the HTTP contract; a small `integration` package on H2 for what only a real database proves (constraints, versions, races) | Each test names the failure it would catch |
| Build | Maven wrapper | |
| Runtime | Amazon Corretto 17 on Amazon Linux 2023 image | Posting: AWS, Amazon Linux 2023 |
| Demo | Playwright + local Kokoro voice + ffmpeg | Narrated video from the real app |
