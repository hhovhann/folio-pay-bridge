-- Baseline schema (D-12). Generated from the JPA entities against PostgreSQL 16, then reviewed and given indexes for the queries the code
-- runs. Hibernate validates against it at start-up (ddl-auto=validate); it never changes it. Later changes are new, additive migrations:
-- add, deploy, and drop only in a later release, because two versions run side by side during a rolling deploy.

-- Card data: ciphertext, the wrapped data key, the ARN of the KMS key that wrapped it (D-31), and the digits PCI allows. No PAN, no CVV.
create table card_token (
    token        varchar(255)                not null primary key,
    sealed_pan   bytea                       not null,
    wrapped_key  bytea                       not null,
    key_id       varchar(255)                not null,
    bin6         varchar(6)                  not null,
    last4        varchar(4)                  not null,
    expiry       varchar(5)                  not null,
    created_at   timestamp(6) with time zone not null
);

-- One attempt to take money. The unique idempotency key is what makes "exactly once" true (D-2).
create table charge (
    id              varchar(255)                not null primary key,
    version         bigint,
    idempotency_key varchar(255)                not null,
    booking_ref     varchar(255)                not null,
    folio_id        varchar(255)                not null,
    business_date   date                        not null,
    card_token      varchar(255)                not null,
    amount_minor    bigint                      not null,
    currency        varchar(3)                  not null,
    status          varchar(255)                not null check (status in ('PENDING', 'CAPTURED', 'DECLINED')),
    psp_ref         varchar(255),
    last_error      varchar(255),
    attempts        integer                     not null,
    created_at      timestamp(6) with time zone not null,
    constraint uk_charge_idempotency_key unique (idempotency_key)
);
create index ix_charge_status_business_date on charge (status, business_date); -- reconciliation by period (D-32)
create index ix_charge_psp_ref on charge (psp_ref);                              -- matching settlement lines
create index ix_charge_created_at on charge (created_at);                        -- ledger, newest first
create index ix_charge_status_created_at on charge (status, created_at);         -- the webhook relay's recent window

-- A captured payment on its way to the folio: POSTED, or DEAD_LETTER with a reason (D-5). The lease lets one request talk to OPERA (D-28).
create table posting (
    id               varchar(255)                not null primary key,
    version          bigint,
    idempotency_key  varchar(255)                not null,
    charge_id        varchar(255)                not null,
    folio_id         varchar(255)                not null,
    amount_minor     bigint                      not null,
    currency         varchar(3)                  not null,
    business_date    date                        not null,
    psp_ref          varchar(255),
    status           varchar(255)                not null check (status in ('PENDING', 'POSTED', 'DEAD_LETTER')),
    opera_ref        varchar(255),
    failure_category varchar(255) check (failure_category in ('OPERA_UNAVAILABLE', 'REJECTED_BY_OPERA')),
    last_error       varchar(255),
    attempts         integer                     not null,
    lease_until      timestamp(6) with time zone,
    lease_owner      varchar(36),
    created_at       timestamp(6) with time zone not null,
    constraint uk_posting_idempotency_key unique (idempotency_key)
);
create index ix_posting_charge_id on posting (charge_id);
create index ix_posting_status_created_at on posting (status, created_at);       -- the webhook relay's recent window
create index ix_posting_created_at on posting (created_at);

-- A request for a guest or payer to pay. open_slot is the reference while the link is OPEN or PAYING: one live link per booking (D-19).
create table payment_link (
    id                varchar(255) not null primary key,
    version           bigint,
    booking_ref       varchar(255) not null,
    open_slot         varchar(255),
    folio_id          varchar(255) not null,
    amount_minor      bigint       not null,
    currency          varchar(3)   not null,
    due_date          date         not null,
    reason            varchar(255) not null,
    status            varchar(255) not null check (status in ('OPEN', 'PAYING', 'PAID', 'CANCELLED', 'SUPERSEDED')),
    payer_fingerprint varchar(255),
    constraint uk_payment_link_open_slot unique (open_slot)
);
create index ix_payment_link_booking_ref on payment_link (booking_ref);

-- Webhook events for the integrator (D-35): derived from the rows above, delivered at least once, never dropped.
create table notification (
    id              varchar(255)                not null primary key,
    version         bigint,
    event_id        varchar(120)                not null,
    type            varchar(32)                 not null check (type in ('PAYMENT_CAPTURED', 'PAYMENT_DECLINED', 'FOLIO_POSTED', 'FOLIO_DEAD_LETTERED')),
    subject_id      varchar(64)                 not null,
    payload         varchar(4000)               not null,
    status          varchar(16)                 not null check (status in ('PENDING', 'DELIVERED', 'FAILED')),
    attempts        integer                     not null,
    next_attempt_at timestamp(6) with time zone not null,
    lease_until     timestamp(6) with time zone,
    last_error      varchar(255),
    created_at      timestamp(6) with time zone not null,
    delivered_at    timestamp(6) with time zone,
    constraint uk_notification_event_id unique (event_id)
);
create index ix_notification_due on notification (status, next_attempt_at);
create index ix_notification_created_at on notification (created_at);
