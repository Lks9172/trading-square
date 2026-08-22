create table notification.outbox (
    id uuid primary key,
    channel varchar(32) not null references notification.delivery_state(channel) on delete cascade,
    idempotency_key char(64) not null,
    operation varchar(64) not null,
    payload text not null,
    status varchar(16) not null default 'PENDING',
    created_at timestamptz not null,
    available_at timestamptz not null,
    attempts integer not null default 0,
    lease_owner varchar(64),
    leased_until timestamptz,
    delivered_at timestamptz,
    provider_message_id varchar(128) not null default '',
    last_error varchar(128),
    constraint notification_outbox_idempotency_uq unique (channel, idempotency_key),
    constraint notification_outbox_key_ck check (idempotency_key ~ '^[0-9a-f]{64}$'),
    constraint notification_outbox_operation_ck check (operation ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    constraint notification_outbox_payload_ck check (
        btrim(payload) <> '' and octet_length(payload) <= 262144
    ),
    constraint notification_outbox_status_ck check (
        status in ('PENDING', 'IN_FLIGHT', 'RETRY', 'DELIVERED', 'DEAD')
    ),
    constraint notification_outbox_attempts_ck check (attempts >= 0),
    constraint notification_outbox_lease_ck check (
        (status = 'IN_FLIGHT' and lease_owner is not null and leased_until is not null)
        or (status <> 'IN_FLIGHT' and lease_owner is null and leased_until is null)
    ),
    constraint notification_outbox_delivered_ck check (
        (status = 'DELIVERED' and delivered_at is not null)
        or (status <> 'DELIVERED' and delivered_at is null)
    )
);

create index notification_outbox_dispatch_idx
    on notification.outbox (available_at, created_at, id)
    include (attempts)
    where status in ('PENDING', 'RETRY', 'IN_FLIGHT');

comment on table notification.outbox is
    'Transactional notification outbox. Provider delivery remains at-least-once because Telegram has no idempotency-key API.';
