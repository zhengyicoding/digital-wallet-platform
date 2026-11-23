create table bank_operations (
    id uuid primary key,
    wallet_id uuid not null references wallets,
    kind text not null check (kind in ('DEPOSIT','WITHDRAWAL')),
    amount bigint not null check (amount > 0),
    status text not null default 'PENDING' check (status in ('PENDING','SUCCEEDED','FAILED')),
    failure_reason text,
    created_at timestamptz not null,
    completed_at timestamptz,
    check ((status = 'PENDING') = (completed_at is null))
);
create index bank_operations_history on bank_operations(wallet_id, kind, created_at desc, id desc);
