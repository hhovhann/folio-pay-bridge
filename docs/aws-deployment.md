# Deploying on AWS (runbook, not yet run in an account)

This is a design and a step-by-step runbook. **It has not been executed against a real AWS account**: the commands are written to be run, reviewed and corrected by whoever owns the account. Where something depends on Oracle, an acquirer or your own policies, it says so.

## 1. Where everything runs
```
                 guests / PMS webhooks
                        |  HTTPS
                  [ ALB + AWS WAF ]                      AWS (eu / me-central-1)
                        |
        +---------------+-----------------+
        |   payment bridge (ECS on EC2,   |   private subnets, Amazon Linux 2023, Corretto 17
        |   2+ tasks, non-root)           |
        +---+-----------+-------------+---+
            |           |             |
     [ RDS PostgreSQL ] [ KMS key ] [ Secrets Manager ]       inside your VPC
            |
   outbound via NAT gateway with fixed Elastic IPs  -----------------+
                                                                      |  HTTPS, OAuth
                          Oracle OPERA Cloud (hosted by Oracle, on OCI) via OHIP APIs
                          card acquirer / payment gateway (their cloud)
```
- **OPERA Cloud is not in your AWS.** It is Oracle's SaaS (hosted on Oracle Cloud Infrastructure). The bridge calls its OHIP REST APIs over the internet with an OAuth client, an application key and a hotel id. You do not run, patch or deploy any OPERA component on AWS.
- **OPERA 5** (legacy, on-premise at a property) is reached differently: a VPN or private link to the property, and an interface server there. That is a second adapter behind the same `OperaClient` port.
- **Fixed outbound IPs.** If Oracle, a hotel, or an acquirer allow-lists your source address, route the tasks' egress through a NAT gateway with Elastic IPs and give those addresses to them. Whether OHIP requires this is Oracle's to confirm.
- **Region.** Choose it with the hotel group's data-residency rules in mind (`me-central-1` is the UAE region). Card data should stay in one controlled region.

## 2. One-time setup
Replace the `<...>` values. Everything below is `aws` CLI; the same can be Terraform or CloudFormation.

### 2.1 Network
- A VPC with private subnets in two Availability Zones for the tasks and the database, public subnets for the ALB, and a NAT gateway per AZ with an Elastic IP each.
- Security groups: ALB accepts 443 from the internet; tasks accept 8081 only from the ALB; RDS accepts 5432 only from the tasks.

### 2.2 The encryption key (KMS)
```bash
KEY_ID=$(aws kms create-key --description "folio-pay-bridge card data keys" --key-usage ENCRYPT_DECRYPT --query KeyMetadata.KeyId --output text)
aws kms create-alias --alias-name alias/folio-pay-bridge --target-key-id "$KEY_ID"
aws kms enable-key-rotation --key-id "$KEY_ID"
```
Task role policy (least privilege; the encryption context must carry the token id, as the code sets it):
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["kms:GenerateDataKey", "kms:Decrypt"],
    "Resource": "arn:aws:kms:<region>:<account>:key/<key-id>",
    "Condition": { "StringLike": { "kms:EncryptionContext:token": "tok_*" } }
  }]
}
```
Keep key administration (create, rotate, delete) in a separate role from the application role. CloudTrail then records every use with the token id.

### 2.3 Database
- RDS PostgreSQL, Multi-AZ, encrypted at rest, TLS enforced (`rds.force_ssl=1`), automated backups, in the private subnets.
- Schema: a database created by an older build of this project (Hibernate `ddl-auto=update`, no `flyway_schema_history`) is refused by Flyway at start-up. For the demo, recreate it (`docker compose down -v`); a real database with data would be baselined deliberately (`flyway baseline` after checking the schema matches V1), never automatically.
- Schema: Flyway migrations in `src/main/resources/db/migration`, applied at start-up by the `postgres` profile, with Hibernate only validating (D-12). For a fleet, run them as their own step before the deploy (`flyway migrate` with the same scripts, or one task with the new image) so two versions never race to migrate; keep every migration additive until the old version is gone.
- TLS that verifies the server: `sslmode=verify-full` with the RDS CA bundle (`sslrootcert`). `sslmode=require` encrypts but does not check who answers. Put the bundle in the image or a mounted volume:
  `curl -o /app/rds-ca.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem` (add it to the Dockerfile's runtime stage, or mount it).

### 2.4 Secrets
```bash
aws secretsmanager create-secret --name folio/ohip   --secret-string '{"appKey":"<app key>","clientId":"<id>","clientSecret":"<secret>"}'
aws secretsmanager create-secret --name folio/psp    --secret-string '{"apiKey":"<acquirer api key>"}'
aws secretsmanager create-secret --name folio/db     --secret-string '{"user":"folio","password":"<generated>"}'
aws secretsmanager create-secret --name folio/webhook --secret-string '{"secret":"<32+ random characters>"}'
# only if some integrators use API keys instead of OAuth2 (tools/new_api_key.py prints the entry; only hashes are stored)
aws secretsmanager create-secret --name folio/api-keys --secret-string '{"entries":"pms-middleware:<sha256>:bookings:write payments:read"}'
```

### 2.4a Who may call the API: OAuth2 with Amazon Cognito (D-30)
```bash
POOL=$(aws cognito-idp create-user-pool --pool-name folio-integrators --query UserPool.Id --output text)
aws cognito-idp create-resource-server --user-pool-id "$POOL" --identifier https://pay.<your domain> --name folio-pay-bridge \
  --scopes ScopeName=cards:write,ScopeDescription=tokenise ScopeName=payments:write,ScopeDescription=pay \
           ScopeName=links:write,ScopeDescription=request ScopeName=bookings:write,ScopeDescription=bookings \
           ScopeName=payments:read,ScopeDescription=read ScopeName=operations,ScopeDescription=ops
aws cognito-idp create-user-pool-domain --user-pool-id "$POOL" --domain <prefix>
# one app client per integrator, client-credentials grant, only the scopes it needs
aws cognito-idp create-user-pool-client --user-pool-id "$POOL" --client-name pms-middleware --generate-secret \
  --allowed-o-auth-flows client_credentials --allowed-o-auth-flows-user-pool-client \
  --allowed-o-auth-scopes https://pay.<your domain>/bookings:write https://pay.<your domain>/payments:read
```
The bridge's settings: `FOLIOPAY_AUTH_JWT_JWK_SET_URI=https://cognito-idp.<region>.amazonaws.com/<pool id>/.well-known/jwks.json`, `FOLIOPAY_AUTH_JWT_ISSUER=https://cognito-idp.<region>.amazonaws.com/<pool id>`, `FOLIOPAY_AUTH_JWT_SCOPE_PREFIX=https://pay.<your domain>/`. Cognito access tokens from the client-credentials grant carry `client_id`, `token_use=access` and scopes prefixed with the resource server id, but **no `aud` claim**: leave `FOLIOPAY_AUTH_JWT_AUDIENCE` unset, and the scope prefix binds the token to this API (only scopes carrying it count, and an ID token is refused, D-30). With Okta, Keycloak or Entra ID, which do set `aud`, set `FOLIOPAY_AUTH_JWT_AUDIENCE` as well. The client id becomes the client's name in the logs and the owner of its idempotency keys (D-40). Tested against Cognito-shaped tokens, not yet against a real user pool.
Rotate them on a schedule. They reach the container as environment variables (see 2.6); nothing is in the image or in git.

### 2.5 Image
```bash
aws ecr create-repository --repository-name folio-pay-bridge
docker build -t folio-pay-bridge:0.1.0 .          # Amazon Linux 2023, Corretto 17, runs as uid 10001
docker tag folio-pay-bridge:0.1.0 <account>.dkr.ecr.<region>.amazonaws.com/folio-pay-bridge:0.1.0
aws ecr get-login-password | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
docker push <account>.dkr.ecr.<region>.amazonaws.com/folio-pay-bridge:0.1.0
```
Pin the image by digest in the task definition and scan it (ECR scanning).

### 2.6 The service (ECS on EC2, Amazon Linux 2023 container instances)
Task definition essentials:
```json
{
  "family": "folio-pay-bridge",
  "networkMode": "awsvpc",
  "containerDefinitions": [{
    "name": "app",
    "image": "<account>.dkr.ecr.<region>.amazonaws.com/folio-pay-bridge@sha256:<digest>",
    "portMappings": [{ "containerPort": 8081 }],
    "stopTimeout": 40,
    "readonlyRootFilesystem": true,
    "environment": [
      { "name": "SPRING_PROFILES_ACTIVE", "value": "postgres" },
      { "name": "LOGGING_CONFIG", "value": "classpath:logback-json.xml" },
      { "name": "DB_POOL_SIZE", "value": "10" },
      { "name": "FOLIOPAY_KEYS", "value": "kms" },
      { "name": "FOLIOPAY_KMS_KEY_ID", "value": "alias/folio-pay-bridge" },
      { "name": "FOLIOPAY_GATEWAY", "value": "http" },
      { "name": "FOLIOPAY_HTTP_OPERA_BASE_URL", "value": "https://<your OHIP gateway host>" },
      { "name": "FOLIOPAY_HTTP_PSP_BASE_URL", "value": "https://<your acquirer host>" },
      { "name": "FOLIOPAY_OPERA_HOTEL_ID", "value": "<hotel id>" },
      { "name": "FOLIOPAY_ALLOWED_HOSTS", "value": "<your public host name>" },
      { "name": "SERVER_ADDRESS", "value": "0.0.0.0" },
      { "name": "DB_URL", "value": "jdbc:postgresql://<rds endpoint>:5432/folio?sslmode=verify-full&sslrootcert=/app/rds-ca.pem" },
      { "name": "FOLIOPAY_AUTH_JWT_JWK_SET_URI", "value": "https://cognito-idp.<region>.amazonaws.com/<pool id>/.well-known/jwks.json" },
      { "name": "FOLIOPAY_AUTH_JWT_ISSUER", "value": "https://cognito-idp.<region>.amazonaws.com/<pool id>" },
      { "name": "FOLIOPAY_AUTH_JWT_SCOPE_PREFIX", "value": "https://pay.<your domain>/" },
      { "name": "FOLIOPAY_WEBHOOK_URL", "value": "https://<integrator endpoint>" }
    ],
    "linuxParameters": { "tmpfs": [{ "containerPath": "/tmp", "size": 64, "mountOptions": ["noexec", "nosuid"] }] },
    "secrets": [
      { "name": "FOLIOPAY_HTTP_OPERA_APP_KEY",       "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/ohip:appKey::" },
      { "name": "FOLIOPAY_HTTP_OPERA_CLIENT_ID",     "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/ohip:clientId::" },
      { "name": "FOLIOPAY_HTTP_OPERA_CLIENT_SECRET", "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/ohip:clientSecret::" },
      { "name": "FOLIOPAY_HTTP_PSP_API_KEY",         "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/psp:apiKey::" },
      { "name": "DB_USER",     "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/db:user::" },
      { "name": "DB_PASSWORD", "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/db:password::" },
      { "name": "FOLIOPAY_WEBHOOK_SECRET", "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/webhook:secret::" }
    ],
    "healthCheck": { "command": ["CMD-SHELL", "curl -fsS http://127.0.0.1:8081/actuator/health/readiness | grep -q UP || exit 1"], "interval": 15, "retries": 3, "startPeriod": 40 }
  }]
}
```
- `FOLIOPAY_KEYS=kms` and `FOLIOPAY_GATEWAY=http` are what make a production start possible. With neither set, the application refuses to start (by design). With `FOLIOPAY_GATEWAY=http` it also refuses to start without authentication (`FOLIOPAY_AUTH_JWT_*` or `FOLIOPAY_AUTH_API_KEYS`), and with KMS keys it refuses an in-memory database (a missing `postgres` profile).
- `readonlyRootFilesystem` needs a writable `/tmp` for Tomcat's work directory: the `tmpfs` above (ECS on EC2; on Fargate use a bind-mount volume at `/tmp`). Checked locally: `docker run --read-only` without it exits at start with "Unable to create tempDir".
- The health check uses `curl` (the Amazon Linux 2023 image has it; it has no `wget`). Health probes are exempt from the `Host` allowlist, because the container check calls `127.0.0.1` and the ALB sends the task's IP as the host; they reveal only UP or DOWN.
- `FOLIOPAY_DEMO_CONTROLS` stays unset (off). Never enable the demo profile in AWS.
- The ALB target group health check path is `/actuator/health/readiness`; set deregistration delay to 30 s so in-flight payments finish.
- Run at least two tasks across two AZs, so a deploy never takes the service to zero.

### 2.7 Logs and alarms
- Logs go to CloudWatch (`awslogs` driver). The application masks card numbers in messages and in exception text, and never logs them; still keep log access restricted and retention short.
- Alarms: any posting `DEAD_LETTER` older than 15 minutes; 5xx rate; payment latency; RDS CPU and connections; KMS throttling. (Metrics for the dead-letter queue are not built yet: until they are, alarm on the console count or on a log filter for `dead-lettered`.)

## 3. The deployment window (midnight to 3am Dubai = 20:00 to 23:00 UTC)
1. Merge only after `./mvnw test` and `python3 tools/check_specs.py` are green in CI.
2. Build and push the image; update the task definition with the new digest.
3. Run database migrations as their own step, **before** the new version starts, and make them backwards-compatible (add before use, drop after): two versions run side by side during a rolling deploy.
4. Rolling update, minimum healthy 100%, maximum 200%: new tasks must pass readiness before old ones drain. The application drains for up to 30 s and ECS waits 40 s before killing.
5. After the deploy: check `/actuator/health`, make one test payment against the acquirer's test environment, and confirm no posting is stuck `PENDING` or `DEAD_LETTER`.
6. Rollback is the previous task definition revision. Because every call carries an idempotency key and the PMS is asked before a repeat post, restarting mid-payment does not double-charge or double-post. A crash between a capture and its saved result is **not** covered yet (no sweeper): after any unclean restart, run reconciliation and look for `UNKNOWN_IN_SETTLEMENT` and `NOT_POSTED_TO_PMS`.

## 4. Going live checklist (what must be true first)
- [ ] A real OPERA Cloud (OHIP) adapter built against Oracle's documentation and proven in Oracle's sandbox; endpoint paths and error codes checked, not assumed. (The simulators in this repository are shaped like OHIP, not copied from it.)
- [ ] A real acquirer adapter per acquirer, with 3-D Secure results, stored-credential flags, refunds and chargeback handling.
- [x] Authentication and authorisation on every endpoint (OAuth2 client credentials or hashed API keys, scopes), failing closed. *(Built in phase 14; Cognito wiring not yet run in an account.)*
- [ ] Tenant-scoped credentials and data for many properties (`docs/architecture.md` section 9); rate limits on card endpoints (WAF rate-based rules); TLS everywhere.
- [ ] The crash-recovery sweeper, with alarms.
- [ ] KMS exercised in a real account (the steps in `docs/aws.md`), key policy reviewed.
- [x] Flyway migrations with `ddl-auto=validate` *(phase 14, run on PostgreSQL 16 locally)*.
- [ ] Backups and a tested restore.
- [ ] PCI scoping done by a qualified assessor. This repository makes no compliance claim.

## 5. Proving KMS in a real account (manual, before going live)
The KMS adapter is tested against a stubbed client only. To close that, in a throwaway AWS account:
1. Create a symmetric KMS key (alias `alias/folio-pay-bridge-test`) and an IAM role or user with `kms:GenerateDataKey` and `kms:Decrypt` on that key only, with the `kms:EncryptionContext:token` condition from section 2.2.
2. Start a local PostgreSQL (`docker run -d -p 127.0.0.1:5432:5432 -e POSTGRES_DB=folio -e POSTGRES_USER=folio -e POSTGRES_PASSWORD=folio postgres:16`): KMS keys are refused on an in-memory database by design.
3. Run `AWS_PROFILE=<that profile> AWS_REGION=<region> DB_URL=jdbc:postgresql://127.0.0.1:5432/folio DB_USER=folio DB_PASSWORD=folio java -jar target/folio-pay-bridge-0.1.0.jar --spring.profiles.active=postgres --foliopay.keys=kms --foliopay.kms.key-id=alias/folio-pay-bridge-test --foliopay.gateway=fake --foliopay.demo-controls=true`.
4. Tokenise a public test card and pay with it. In CloudTrail, `GenerateDataKey` and `Decrypt` must carry `"encryptionContext": {"token": "tok_..."}`, and `card_token.key_id` must hold the key's ARN, not the alias.
5. Call `Decrypt` with another token as the context and confirm KMS refuses. Move the alias to a new key and confirm the old card still pays (D-31).
Only then tick the boxes in phases 1, 9 and 14, with the date and the account type (never the account id).

## 6. Using Claude Code on AWS
Claude Code can run against Amazon Bedrock instead of Anthropic's own API, so the agents work inside your AWS account and billing (set `CLAUDE_CODE_USE_BEDROCK=1`, an AWS region, and credentials with Bedrock invoke permission; check Anthropic's current documentation for the exact variables and model access, because they change). Whatever the model host, the rules in `CLAUDE.md` still apply: synthetic data only, never real card numbers or production credentials in an agent session, and a human reviews and proves every change before it reaches production.
