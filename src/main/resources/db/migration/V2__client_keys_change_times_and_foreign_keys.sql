-- Production-readiness review (phase 15). Each step is safe while the previous release still runs beside this one (D-12): new columns
-- have defaults, so rows the old release inserts are valid; nothing is dropped that the old release reads.

-- D-40: an idempotency key belongs to the client that sent it. '' is the bridge's own namespace (payment links, shadow runs), and also
-- what the previous release's rows get, so a charge it left pending is still found under the same key.
alter table charge add column client_id varchar(128) not null default '';
alter table charge drop constraint uk_charge_idempotency_key;
alter table charge add constraint uk_charge_client_key unique (client_id, idempotency_key);

-- D-35: the webhook relay looks for rows that changed recently, not rows created recently, so a posting replayed after 30 days is still
-- announced. Existing rows start from their creation time.
alter table charge add column updated_at timestamp(6) with time zone;
update charge set updated_at = created_at;
alter table charge alter column updated_at set not null;
alter table posting add column updated_at timestamp(6) with time zone;
update posting set updated_at = created_at;
alter table posting alter column updated_at set not null;
create index ix_charge_status_updated_at on charge (status, updated_at);
create index ix_posting_status_updated_at on posting (status, updated_at);
drop index ix_charge_status_created_at;
drop index ix_posting_status_created_at;

-- A version on card_token lets Spring Data insert a new card directly instead of reading the row first (its id is assigned in code).
alter table card_token add column version bigint not null default 0;

-- Referential integrity in the database, not only in the code: a charge names a stored card, a posting names a charge.
alter table charge add constraint fk_charge_card_token foreign key (card_token) references card_token (token);
alter table posting add constraint fk_posting_charge foreign key (charge_id) references charge (id);
create index ix_charge_card_token on charge (card_token);
