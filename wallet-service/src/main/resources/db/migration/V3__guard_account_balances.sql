-- Opening an account starts it at zero. Only the ledger insert trigger advances its balance.
create function reject_direct_balance_write() returns trigger language plpgsql as $$
begin
    raise exception 'Account balances must be posted to the ledger' using errcode = '23514';
end $$;

create trigger account_starts_at_zero before insert on accounts
    for each row when (new.balance <> 0 or new.last_entry_at is not null)
    execute function reject_direct_balance_write();

create trigger ledger_manages_balance before update of balance,last_entry_at on accounts
    for each row when (pg_trigger_depth() = 0)
    execute function reject_direct_balance_write();
