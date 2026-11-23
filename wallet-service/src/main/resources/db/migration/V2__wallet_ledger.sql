create table wallets (
    id uuid primary key,
    user_id uuid not null unique,
    username varchar(20) not null unique,
    display_name varchar(50) not null,
    created_at timestamptz not null default clock_timestamp()
);

-- Wallet accounts share the wallet ID. System accounts have no customer identity.
create table accounts (
    id uuid primary key,
    wallet_id uuid unique references wallets,
    system_name text unique,
    balance bigint not null default 0,
    last_entry_at timestamptz,
    check ((wallet_id is not null and id = wallet_id and system_name is null)
        or (wallet_id is null and system_name is not null and system_name in ('EXTERNAL_BANK', 'PAYOUT_HOLD'))),
    check (balance >= 0 or system_name is not distinct from 'EXTERNAL_BANK')
);

insert into accounts (id, system_name) values
    ('00000000-0000-0000-0000-000000000001', 'EXTERNAL_BANK'),
    ('00000000-0000-0000-0000-000000000002', 'PAYOUT_HOLD');

create table postings (
    id uuid primary key,
    reference_type text not null,
    reference_id uuid not null
);

create table transfers (
    id uuid primary key,
    from_wallet_id uuid not null references wallets,
    to_wallet_id uuid not null references wallets,
    amount bigint not null check (amount > 0),
    note varchar(140),
    created_at timestamptz not null,
    check (from_wallet_id <> to_wallet_id)
);
create index transfers_daily on transfers (from_wallet_id, created_at);

create table ledger_entries (
    id uuid primary key,
    posting_id uuid not null references postings,
    account_id uuid not null references accounts,
    type text not null,
    amount bigint not null check (amount <> 0),
    balance_after bigint,
    counterparty_id uuid references wallets,
    note varchar(140),
    created_at timestamptz not null,
    unique (posting_id, account_id)
);
create index ledger_statement on ledger_entries (account_id, created_at desc, id desc);

create function apply_entry() returns trigger language plpgsql as $$
begin
    update accounts set balance = balance + new.amount,
        last_entry_at = greatest(clock_timestamp(), last_entry_at + interval '1 microsecond')
    where id = new.account_id
    returning case when wallet_id is not null then balance end, last_entry_at
    into new.balance_after, new.created_at;
    return new;
end $$;
create trigger apply_entry before insert on ledger_entries
    for each row execute function apply_entry();

create function reject_ledger_change() returns trigger language plpgsql as $$
begin
    raise exception 'Ledger history is immutable';
end $$;
create trigger immutable_entries before update or delete or truncate on ledger_entries
    for each statement execute function reject_ledger_change();

create function verify_posting() returns trigger language plpgsql as $$
declare
    target uuid;
begin
    if tg_table_name = 'postings' then
        target := new.id;
    else
        target := new.posting_id;
    end if;
    if (select count(*) <> 2 or sum(amount) <> 0 from ledger_entries where posting_id = target) then
        raise exception 'Posting requires two balanced entries' using errcode = '23514';
    end if;
    return null;
end $$;
create constraint trigger balanced_posting after insert on postings
    deferrable initially deferred for each row execute function verify_posting();
create constraint trigger balanced_entries after insert on ledger_entries
    deferrable initially deferred for each row execute function verify_posting();
