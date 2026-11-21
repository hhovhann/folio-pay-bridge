-- PCI DSS v4.0.1 3.2.1: a card number is kept only as long as it is needed (D-44). A card records when it was last used; a scheduled purge
-- destroys the card number of one unused for longer than foliopay.vault.retention, and keeps the row (bin6, last4, the dates) so the
-- charges that name it stay valid. Safe beside the previous release (D-12): the new column has a default, so a card it stores is valid.
alter table card_token add column last_used_at timestamp(6) with time zone not null default now();
update card_token set last_used_at = created_at;
alter table card_token add column purged_at timestamp(6) with time zone;
create index ix_card_token_retention on card_token (purged_at, last_used_at);
