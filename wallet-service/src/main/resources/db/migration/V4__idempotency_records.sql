-- Successful responses are retained indefinitely (IDM-7).
create table idempotency_records (
    user_id uuid not null,
    endpoint text not null,
    request_key varchar(64) not null check (char_length(request_key) > 0),
    request_body text not null,
    response_status smallint not null check (response_status between 200 and 299),
    response_body text not null,
    location text not null,
    primary key (user_id, endpoint, request_key)
);
