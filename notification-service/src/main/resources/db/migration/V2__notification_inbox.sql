-- NTF-2 / EVT-4: durable dedupe commits atomically with all recipients.
create table processed_events (
    event_id uuid primary key,
    processed_at timestamptz not null default clock_timestamp()
);
create table notifications (
    id uuid primary key,
    event_id uuid not null references processed_events(event_id),
    user_id uuid not null,
    type text not null,
    text text not null,
    read boolean not null default false,
    reference_id uuid not null,
    created_at timestamptz not null default clock_timestamp(),
    unique(event_id, user_id)
);
create index notifications_user_page on notifications(user_id, created_at desc, id desc);
create index notifications_unread on notifications(user_id, created_at desc, id desc) where not read;
