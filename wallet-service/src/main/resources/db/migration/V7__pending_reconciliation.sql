create index bank_operations_pending_age on bank_operations(created_at) where status='PENDING';
