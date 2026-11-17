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
- Schema: the pilot lets Hibernate create tables. **For production add Flyway migrations and `ddl-auto=validate`, run as a separate step before a deploy** (not yet built; see the roadmap).

### 2.4 Secrets
```bash
aws secretsmanager create-secret --name folio/ohip   --secret-string '{"appKey":"<app key>","clientId":"<id>","clientSecret":"<secret>"}'
aws secretsmanager create-secret --name folio/psp    --secret-string '{"apiKey":"<acquirer api key>"}'
aws secretsmanager create-secret --name folio/db     --secret-string '{"user":"folio","password":"<generated>"}'
```
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
      { "name": "FOLIOPAY_KEYS", "value": "kms" },
      { "name": "FOLIOPAY_KMS_KEY_ID", "value": "alias/folio-pay-bridge" },
      { "name": "FOLIOPAY_GATEWAY", "value": "http" },
      { "name": "FOLIOPAY_HTTP_OPERA_BASE_URL", "value": "https://<your OHIP gateway host>" },
      { "name": "FOLIOPAY_HTTP_PSP_BASE_URL", "value": "https://<your acquirer host>" },
      { "name": "FOLIOPAY_OPERA_HOTEL_ID", "value": "<hotel id>" },
      { "name": "FOLIOPAY_ALLOWED_HOSTS", "value": "<your public host name>" },
      { "name": "SERVER_ADDRESS", "value": "0.0.0.0" },
      { "name": "DB_URL", "value": "jdbc:postgresql://<rds endpoint>:5432/folio?sslmode=require" }
    ],
    "secrets": [
      { "name": "FOLIOPAY_HTTP_OPERA_APP_KEY",       "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/ohip:appKey::" },
      { "name": "FOLIOPAY_HTTP_OPERA_CLIENT_ID",     "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/ohip:clientId::" },
      { "name": "FOLIOPAY_HTTP_OPERA_CLIENT_SECRET", "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/ohip:clientSecret::" },
      { "name": "FOLIOPAY_HTTP_PSP_API_KEY",         "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/psp:apiKey::" },
      { "name": "DB_USER",     "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/db:user::" },
      { "name": "DB_PASSWORD", "valueFrom": "arn:aws:secretsmanager:<region>:<account>:secret:folio/db:password::" }
    ],
    "healthCheck": { "command": ["CMD-SHELL", "wget -qO- http://127.0.0.1:8081/actuator/health/readiness | grep -q UP || exit 1"], "interval": 15, "retries": 3, "startPeriod": 40 }
  }]
}
```
- `FOLIOPAY_KEYS=kms` and `FOLIOPAY_GATEWAY=http` are what make a production start possible. With neither set, the application refuses to start (by design).
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
- [ ] Authentication and authorisation on every endpoint; tenant-scoped keys and tokens; rate limits on card endpoints; TLS everywhere.
- [ ] The crash-recovery sweeper, with alarms.
- [ ] KMS exercised in a real account (the steps in `docs/aws.md`), key policy reviewed.
- [ ] Flyway migrations; backups and a tested restore.
- [ ] PCI scoping done by a qualified assessor. This repository makes no compliance claim.

## 5. Using Claude Code on AWS
Claude Code can run against Amazon Bedrock instead of Anthropic's own API, so the agents work inside your AWS account and billing (set `CLAUDE_CODE_USE_BEDROCK=1`, an AWS region, and credentials with Bedrock invoke permission; check Anthropic's current documentation for the exact variables and model access, because they change). Whatever the model host, the rules in `CLAUDE.md` still apply: synthetic data only, never real card numbers or production credentials in an agent session, and a human reviews and proves every change before it reaches production.
