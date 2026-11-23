create table outbox (
    sequence_id bigint generated always as identity primary key,
    event_id uuid not null unique,
    aggregate_id uuid not null,
    envelope jsonb not null,
    sent_at timestamptz
);

create index outbox_pending on outbox(sequence_id) where sent_at is null;
create index outbox_pending_aggregate on outbox(aggregate_id, sequence_id) where sent_at is null;
