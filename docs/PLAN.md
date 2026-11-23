# Build plan

Baseline first (M0 to M9), then Full (M10 to M14). Design wallet-service for the Full level from the start (ledger,
locking, idempotency table, outbox table, unique constraints); retrofitting these later is expensive.

## Resolved questions

- **Idempotency-Key**: required on money POSTs in the API table (baseline) but IDM rules are Full. Decision: implement
  it fully in M3 anyway — the baseline error table lists IDEMPOTENCY_KEY_REQUIRED so the header must work.
- **Bank 503 code**: the bank API table has no `code` for 503. Use `BANK_UNAVAILABLE`.
- **ADMIN calling customer endpoints**: AUTH-5 gives admin USER+ADMIN, so yes — admin can call customer endpoints.
- **Edge proxy**: nginx in `web/` (satisfies TEC-2: no gateway framework, no registry).
- **`deposit.failed` payload `failureReason`**: maps from the bank transaction's `reason` field.
- **JWT `iss` value**: use `digital-wallet-auth`; configure the same value in all three resource servers.
- **`roles` claim format**: JSON array in the JWT (matches the `/me` response shape). Map to Spring Security
  authorities with a `ROLE_` prefix via a custom `JwtAuthenticationConverter`.
- **ID format**: UUIDv7 (time-ordered, good for cursor pagination and B-tree indexes). API-2 says "strings."
- **`balanceAfter` scope**: LED-4 says "every entry of a wallet." System account entries (EXTERNAL_BANK, PAYOUT_HOLD)
  do not store `balanceAfter`. Column is nullable; null for system account entries.
- **Webhook delivery timing**: bank-simulator fires `webhookCopies` with a small random delay between each
  (50–200 ms apart) to exercise duplicate-handling concurrency.
- **Statement cursor**: cursor encodes `(createdAt, id)`. Query uses strict less-than on the previous page's last
  item. UUIDv7 ordering makes this natural. Guarantees API-5 (no duplicates, no missed rows).

## Milestones

- [x] **M0 Foundation**: Maven parent + 5 module skeletons; `docker-compose.yml` with
  Postgres (db+user per service), Kafka, health checks; `.env.example` with all secrets documented; non-root
  Dockerfiles (OPS-9); CI running `verify`.
- [x] **M1 auth-service**: AUTH-1..6, RS256 JWT with `iss=digital-wallet-auth`, JWKS, `roles` as JSON array claim,
  admin bootstrap from env, `/me`, Flyway, springdoc at `/docs/auth/`.
- [x] **M2 wallet core**: migrations (wallets, accounts, ledger_entries, postings, system accounts EXTERNAL_BANK and
  PAYOUT_HOLD), wallet open/me/lookup/entries, transfers with deterministic row-lock ordering by wallet ID, deadlock
  retry (3+), daily limit, cursor pagination `(createdAt, id)`, resource-server security (validate JWT signature, exp,
  iss), springdoc at `/docs/wallet/`. Testcontainers concurrency tests (ACC-2 #1, #2, #6, #8) and authorization
  tests (ACC-2 #7 for wallet endpoints — cross-user requests return 404).
- [x] **M3 idempotency**: table + unique constraint, same-transaction write (IDM-4), replay header
  `Idempotent-Replayed: true` (IDM-2), 422 IDEMPOTENCY_KEY_REUSED (IDM-5), 409 REQUEST_IN_PROGRESS with
  `Retry-After: 1` (IDM-6). Tests: ACC-2 #3, #4, #5.
- [x] **M4 bank-simulator**: BNK-1..6. In-memory transaction store, POST/GET `/bank/v1/transactions`, X-Api-Key auth
  (BNK-5), idempotency by reference (BNK-2), settlement after `settleDelayMs`, signed webhook delivery with
  `webhookCopies` (50–200 ms spacing), admin config/transactions/reset endpoints, runtime failure injection (BNK-6).
  Springdoc at `/docs/bank/`. Standalone — no wallet-service dependency.
- [x] **M5 deposits + withdrawals**: wallet-side deposit (DEP-1..3) and withdrawal (WDR-1..4) flows. Bank client with
  response timeout (3s default), bounded retries with exponential backoff + jitter, circuit breaker (Resilience4j).
  Calls outside DB transactions (CON-5). Shared "apply result" code path: lock operation row, check still PENDING,
  post ledger entries, set final status, all in one transaction (RES-6). Webhook endpoint POST
  `/internal/webhooks/bank` with HMAC-SHA256 + timestamp validation (RES-9, SEC-4). Async submission after 202
  response (RES-1). Tests: ACC-2 #9 (triple webhook), #10 (bad signature).
- [x] **M6 outbox + Kafka**: outbox rows written in business transactions (EVT-1), relay with per-aggregate ordering
  (EVT-2), topic `wallet.events` with 3+ partitions, JSON envelope per Section 7. API keeps working while Kafka is
  down (EVT-3). Test: ACC-2 #15.
- [ ] **M7 notification-service**: consumer for `wallet.events`, dedupe by `eventId` (NTF-2/EVT-4), notification texts
  with money formatting `$1,250.00` (Section 5.8), list/unread-count/read/read-all APIs, resource-server security,
  DLT `wallet.events.dlt` for unparseable messages (NTF-4), admin stats counters (NTF-5), Flyway, springdoc at
  `/docs/notifications/`. Tests: ACC-2 #16, #17.
- [ ] **M8 web**: SPA (customer pages UI-2, polling for pending status and notifications every 5s UI-5, error-code
  messages UI-6, fresh idempotency key per action / same key on retry UI-4, token in sessionStorage UI-7), nginx
  routing per Section 6.1 proxy table.
- [ ] **M9 Baseline gate**: run ACC-1 B1..B7 end to end via `docker compose up --build`; fix gaps.
  **Baseline complete.**
- [ ] **M10 Resilience**: reconciliation job (30s/10s defaults, RES-7) + admin trigger (ADM-4), re-submission for
  unknown references, restart recovery (RES-8). Admin endpoints: integrity with snapshot isolation (ADM-1),
  status (ADM-2), wallets (ADM-3). Tests: ACC-2 #11, #12, #13, #14.
- [ ] **M11 Hardening**: SEC-3 (admin role on admin endpoints), SEC-5 (server-side input validation), login rate
  limit SEC-9 (5/min per username, in-memory, configurable), AUTH-6 (login doesn't reveal username existence),
  graceful shutdown (OPS-8), ACC-2 #7 remainder (deposits, withdrawals, notifications cross-user checks).
- [ ] **M12 Observability**: JSON logs with traceId/spanId/userId (OPS-4), Prometheus metrics per OPS-5
  (request rate, error rate, latency, operations by type/result, pending counts, outbox backlog, circuit state,
  notification counters), traceparent across bank webhook (OPS-6), provisioned Grafana dashboard (OPS-7),
  tracing backend container.
- [ ] **M13 Admin console UI**: integrity check, status with 2s refresh, wallets with 2s refresh, bank failure
  presets (Normal/Slow/Down/Hang/Decline/Duplicate webhooks/No webhooks), diagnostics (repeat last key, 10x
  parallel same key), links to Swagger UIs + Grafana + tracing (UI-3).
- [ ] **M14 Final verification**: full ACC-2 suite green; clean-clone `docker compose up --build` test (OPS-1);
  README with run and test instructions.

## Risk register

| # | Risk | Mitigation |
|---|------|------------|
| R1 | Ledger schema wrong in M2 — expensive to fix | Review schema against LED-1..5 and INV-1..6 before writing migrations |
| R2 | "Apply result" path (M5) — double credit or lost money | Single code path for webhook + reconciliation + duplicates; test with ACC-2 #9, #12 |
| R3 | PENDING operations stuck between M5 and M10 | Acceptable for baseline; reconciliation lands in M10 |
| R4 | Concurrency tests flaky | Use deterministic seeds where possible; retry assertions with timeout |

## Progress log

(Append a line per finished milestone: date, what changed, known gaps.)

- 2026-11-23 — M0 complete. Verified the five-module Maven reactor, Compose startup on a fresh database volume,
  database isolation, readiness probes, and non-root application containers (OPS-1/2/3/9, SEC-6). CI runs Maven verify.
- 2026-11-23 — M1 complete. Added PostgreSQL-backed registration with salted BCrypt hashes, admin bootstrap,
  RS256 login and JWKS, authenticated `/me`, and OpenAPI and Swagger UI (AUTH-1..6, API-3/6/8/9, SEC-1/2/5/6/7,
  OPS-2). Root `./mvnw -q verify` passed all 10 tests, including 7 auth-service tests against PostgreSQL 16.
  Login rate limiting (SEC-9) remains scheduled for M11.
- 2026-11-23 — M2 complete. Added the PostgreSQL wallet ledger and system accounts, wallet and statement APIs,
  transfers with ordered wallet locks and fresh-transaction retries, M1 JWKS validation, and wallet OpenAPI docs
  (LED-1..5, WAL-1..5, TRF-1..5, MNY-1..4, CON-1..4, API-3..9, SEC-1/2/5). Root `./mvnw -q verify`
  passed all 19 tests with zero skips. Nine new PostgreSQL 16 integration tests cover ACC-2 #1, #2, #6, the
  transfer portion of #7, and #8, including INV-1..6 assertions. Transfers validate the idempotency header.
  M3 adds key storage and replay. M5 supplies pending reservations and withdrawal contributions to the UTC daily
  total. Tests use balanced funding fixtures until deposits land. M6 adds the transactional outbox.
- 2026-11-23 — M3 complete. Transfers enforce IDM-1 through IDM-7 with a PostgreSQL transaction-scoped advisory
  lock and a primary key on user, endpoint, and request key. The original status, JSON body, and Location commit
  with the ledger and are retained indefinitely. M5 can reuse the same code to replay its original 202 PENDING
  response after settlement. Root `./mvnw -q verify` passed all 25 tests with zero skips, including six new
  PostgreSQL tests for ACC-2 #3–5, key validation and scoping, and reuse after rejection or commit failure.
  All nine M2 integration tests passed, including opposing transfers and fresh-transaction retries (CON-3).

- 2026-11-23 — M4 complete. Implemented the standalone in-memory bank transaction API, atomic reference replay,
  asynchronous settlement, runtime failure controls and reset invalidation (BNK-1 through BNK-6). Webhooks sign
  the exact transmitted bytes using HMAC-SHA256, use configured copy counts with 50–200 ms spacing, and never
  retry failed deliveries (SEC-4). Root `./mvnw -q verify` passed 32 tests with zero failures or skips, including
  eight bank tests using a standalone stub webhook receiver, concurrent replay and a full 30-second hang.
  Compose startup passed; the rebuilt simulator was healthy, a smoke charge settled successfully, and nginx
  served `/docs/bank/` and the bank OpenAPI schema successfully. Existing internal `/bank/v1/**` routing remains
  unchanged. Wallet bank integration, webhook receiving, deposits and withdrawals remain in M5.

- 2026-11-23 — M5 complete. Deposits and withdrawals store the original 202 response at creation. Withdrawals
  reserve funds atomically and share the transfer spending check. Bank submission runs after commit. Signed
  webhooks and definitive submission responses use the same row-locked settlement transaction (DEP-1..3,
  WDR-1..4, CON-5, IDM-1..7, RES-1..6/9, SEC-4). Root `./mvnw -q verify` passed all 37 tests with zero skips.
  Five new PostgreSQL tests cover ACC-2 #9/#10, withdrawal settlement and reversal, original-response replay,
  timeout/503 retries with funds held, and transfers racing withdrawals for funds and daily allowance.
  Compose settled a 10,000-cent deposit and 2,000-cent withdrawal and replayed both original responses.
  The first Compose smoke-test attempt returned 502. Reloading nginx restored routing, and the deposit and withdrawal checks passed on retry. The cause of the 502 is still unverified.
  M6 adds the outbox and Kafka publication. M10 adds reconciliation and restart recovery for pending operations.

- 2026-11-23 — M6 complete (EVT-1 through EVT-3, ACC-2 #15). All six event types in Section 7 commit with their
  business changes. The relay sends the stored envelope to `wallet.events`, keyed by aggregate ID, on a topic with
  at least three partitions. Each worker locks the earliest unsent row for an aggregate and waits for Kafka's
  acknowledgement before marking it sent. A failed mark leaves the same event ID ready for retry.
  Root `./mvnw -q verify` passed all 40 tests with zero failures, errors, or skips. Three new PostgreSQL 16 and
  Kafka 3.8 tests verified automatic delivery of all eight transfers committed during a broker outage, settlement
  rollback, and ordered redelivery with overlapping relays. Wallet-core tests also checked Section 7 payloads,
  transfer rollback, idempotent API replay, and duplicate webhooks.
