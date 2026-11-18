# Product map: a hotel-payments platform, and what this repository covers

Hotel-payments platforms in this market publish broadly the same service families. This page maps each one to what exists here, so nobody has to guess what is real. **Built** = code with tests that fail if it breaks. **Simulated** = built against a simulator, not the real counterpart. **Not built** = a known gap, with the shape it would take.

This is a work sample, not any company's software, and it does not claim to match any company's product.

## Payment links and booking automation
| Capability | Here | Where |
|---|---|---|
| Bookings arrive from the PMS (new, modified, cancelled) and become payment requests under the hotel's booking policy (advance purchase, flexible) | **Built** | `POST /api/v1/booking-events`, `PaymentRequestPolicy`, `BookingEventServiceImpl` (D-19) |
| One live request per booking; a change replaces it; a request being paid is not changed; a cancellation after payment flags a refund review | **Built** | `LinkPaymentIntegrationTest`, `BookingEventServiceImplTest` |
| A payer pays a link; a double-click charges once; a second card cannot pay a link being paid or already paid | **Built** | `ReviewFindingsIntegrationTest.twoCardsRacingOnOneOpenLinkProduceExactlyOneCapture` |
| Requests created by other teams and apps (F&B, events, spa) with their own amount and due date: a "remote creation" API | **Built** | `POST /api/v1/links`, `RemoteCreationIntegrationTest` |
| Payment posted to the PMS automatically (OPERA folio window, payment method, cashier, on OPERA's business date), never twice, never lost | **Simulated, on Oracle's published contract** (OPERA; a test checks every exchange against Oracle's OHIP specification) | `PostingServiceImpl`, `HttpOperaClient`, `OhipContractTest`, `FullFlowHttpIntegrationTest` (D-5, D-23, D-28, D-42) |
| "Payment responses sent via API": the integrator is told a payment was captured, declined, posted or stuck | **Built** | Signed webhooks, `WebhookIntegrationTest` (D-35) |
| Branded messages by email, SMS, WhatsApp; reminder workflows; scheduling | **Not built** | A notification channel per tenant (template, sender, schedule) reading the same events as the webhooks |
| Reusable "pay any amount" links for travel agents and groups; bulk upload; portal | **Not built** | A link type without a fixed amount, one charge per payer, each with its own idempotency key |

## Gateway and APIs
| Capability | Here | Where |
|---|---|---|
| APIs for third-party and internal apps, secured for machines | **Built** | OAuth2 client credentials (JWT, JWKS, audience, scopes) or hashed scoped API keys (D-30), `SecurityIntegrationTest` |
| Acquirer independence: keep the hotel's own acquirer and PSP | **Built (port)**, **Simulated** (one PSP) | `PspClient` port with an HTTP adapter; a second acquirer is a second adapter, the core does not change |
| Card tokenisation; keys in KMS; nothing sensitive in logs | **Built** (KMS against a stub) | `CardVaultImpl`, `KmsKeyService`, `PciIntegrationTest` (D-6 to D-8, D-31) |
| 3-D Secure payments and a hosted payment page | **Not built** | The PSP's hosted fields or 3DS SDK so the PAN never reaches the bridge; the authorisation carries the 3DS result (ECI, authentication value) for liability shift |
| Virtual cards (VCC) from OTAs charged without manual keying | **Built** (the charge-and-post core) | Tokenise, authorise, capture, post to folio: this repository's main flow. Not built: receiving VCCs from OTA channel feeds |

## Revenue assurance
| Capability | Here | Where |
|---|---|---|
| Reconciliation of acquirer settlement against captures and PMS postings, by settlement period, per currency, with named exceptions | **Built** | `ReconciliationServiceImpl` (D-11, D-32) |
| Reconciliation of cash deposits and channel commissions | **Not built** | Same matching engine, different statements (bank file, OTA commission invoice) |
| Chargeback avoidance and dispute defence (evidence packs, deadlines) | **Not built** | Store the accepted booking terms and 3DS result per payment; a dispute case with scheme deadlines |
| Fraud screening (risk rules, suspicious-attempt alerts) | **Not built** | Usually the PSP's risk engine; the bridge would record and surface its decisions |

## Promotions and landing pages
| Capability | Here |
|---|---|
| Branded landing pages for F&B, events, packages, vouchers with immediate payment | **Not built.** They would create payment requests through the same remote creation API and use the hosted payment page |

## Portal
| Capability | Here | Where |
|---|---|---|
| A portal where hotel teams see payments, chase what is stuck and reconcile | **Built** (one property) | The operator dashboard at `/dashboard/` (D-38): overview, payments and folio status with replay, payment links and new requests, webhooks with redeliver, reconciliation. Not built: consolidated views across properties and operators (needs multi-tenancy), user management, bulk upload |

## Platform qualities
| Quality | Here |
|---|---|
| Hundreds of properties, many operators, many countries | **Designed, not built:** multi-tenancy, per-property settings and data residency in `docs/architecture.md` section 9 |
| Runs on AWS | Docker image on Amazon Linux 2023 with Corretto 17; runbook for ECS, RDS, KMS, Secrets Manager (`docs/aws-deployment.md`, not yet run in an account) |
| High availability | Stateless instances; every concurrency rule in the database; graceful shutdown; readiness probes |
| PCI DSS and privacy | Controls a reviewer looks for (tokenisation, KMS, no PAN in logs, least privilege, audit trail). No certification is claimed |
| AI-first engineering, reviewed and proved | `CLAUDE.md` brief, specs per phase, `docs/agent-log.md`, AI guardrails enforced by a hook and CI (D-36), mutation checks of key tests |
