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
- [x] **M7 notification-service**: consumer for `wallet.events`, dedupe by `eventId` (NTF-2/EVT-4), notification texts
  with money formatting `$1,250.00` (Section 5.8), list/unread-count/read/read-all APIs, resource-server security,
  DLT `wallet.events.dlt` for unparseable messages (NTF-4), admin stats counters (NTF-5), Flyway, springdoc at
  `/docs/notifications/`. Tests: ACC-2 #16, #17.
- [x] **M8 web**: SPA (customer pages UI-2, polling for pending status and notifications every 5s UI-5, error-code
  messages UI-6, fresh idempotency key per action / same key on retry UI-4, token in sessionStorage UI-7), nginx
  routing per Section 6.1 proxy table.
- [x] **M9 Baseline gate**: run ACC-1 B1..B7 end to end via `docker compose up --build`; fix gaps.
  **Baseline complete.**
- [x] **M10 Resilience**: reconciliation job (30s/10s defaults, RES-7) + admin trigger (ADM-4), re-submission for
  unknown references, restart recovery (RES-8). Admin endpoints: integrity with snapshot isolation (ADM-1),
  status (ADM-2), wallets (ADM-3). Tests: ACC-2 #11, #12, #13, #14.
- [x] **M11 Hardening**: SEC-3 (admin role on admin endpoints), SEC-5 (server-side input validation), login rate
  limit SEC-9 (5/min per username, in-memory, configurable), AUTH-6 (login doesn't reveal username existence),
  graceful shutdown (OPS-8), ACC-2 #7 remainder (deposits, withdrawals, notifications cross-user checks).
- [x] **M12 Observability**: JSON logs with traceId/spanId/userId (OPS-4), Prometheus metrics per OPS-5
  (request rate, error rate, latency, operations by type/result, pending counts, outbox backlog, circuit state,
  notification counters), traceparent across bank webhook (OPS-6), provisioned Grafana dashboard (OPS-7),
  tracing backend container.
- [x] **M13 Admin console UI**: integrity check, status with 2s refresh, wallets with 2s refresh, bank failure
  presets (Normal/Slow/Down/Hang/Decline/Duplicate webhooks/No webhooks), diagnostics (repeat last key, 10x
  parallel same key), links to Swagger UIs + Grafana + tracing (UI-3).
- [x] **M14 Final verification**: full ACC-2 suite green; clean-clone `docker compose up --build` test (OPS-1);
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

- 2026-11-23 — M7 complete (NTF-1 through NTF-5, EVT-4/5, ACC-2 #16/#17 and notification access in #7).
  Consumes all six M6 event envelopes and formats integer cents using the exact Section 5.8 texts. A PostgreSQL
  event-ID primary key and all recipient notifications commit in one transaction before the RECORD-mode Kafka
  offset commit. Unknown fields/types/versions are ignored. Unparseable records retain their original key/value
  bytes in `wallet.events.dlt`; the source offset advances only after Kafka confirms the DLT send. Database and
  Kafka failures retry without exhausted recovery. Added JWT-scoped cursor APIs, read operations, admin-only
  startup counters, Prometheus counters, Flyway V2, and public notification OpenAPI/Swagger UI.
  Final root `./mvnw -q verify` passed 47 tests with zero failures, errors, or skips; Surefire confirms seven
  PostgreSQL 16/Kafka 3.8 notification tests ran. Coverage includes duplicate transfers, offset rewind and replay
  after consumer restart/rejoin, all texts, unsupported events, original DLT bytes and same-partition continuation,
  a failed DLT send, a rolled-back second-recipient insert and retry, pagination, admin counters, and cross-user 404.
  Compose rebuilt successfully with every container healthy. After reloading nginx to resolve recreated services,
  a 10,000-cent deposit funded a 1,250-cent transfer through the M6 outbox/Kafka pipeline: both users' notification
  APIs returned exactly one transfer notification with the required text. OpenAPI and Swagger UI passed through
  `/docs/notifications/`. M8 frontend and M10 reconciliation remain scheduled as before.

- 2026-11-23 — M8 complete (UI-1, UI-2, UI-4 through UI-7, SEC-8; ACC-1 B2–B6 smoke only).
  Added a buildless customer SPA served at `/` by the existing non-root nginx container, preserving the API and
  all four OpenAPI/Swagger routes. Includes registration/login, wallet opening and account totals, recipient
  lookup and transfer confirmation, bank-operation status, statement running balances and cursor Load more,
  deposit/withdrawal lists, and notification list/count/read/read-all/unread filtering. Input amounts are parsed
  through integer arithmetic; business limits and rejection messages come from backend response codes.
  Every new confirmed money action freezes a fresh UUID key and the serialized body. Network/5xx and
  REQUEST_IN_PROGRESS retries retain the original request across navigation and reload. Accepted bank operations
  poll their detail endpoints rather than trusting replayed POST snapshots. A shared refresh loop runs two
  seconds after completion, discovers pending operations on sign-in, prevents overlapping polls, and aborts on
  logout or authenticated 401. Session storage is cleared and login feedback remains visible. User-provided
  text uses textContent throughout.
  Final root `./mvnw -q verify` passed 47 tests with zero failures, errors, or skips. `npm --prefix web test`
  passed seven dependency-free frontend tests; these now also run in CI. Compose rebuilt and all application
  containers were healthy. Real headless Chrome against Compose passed 14 recorded browser scenarios using
  `alice_mwtwv3c0` and `bob_mwtwv3c0`: both signups/logins and zero-balance wallets; $100 deposit PENDING to
  SUCCEEDED; $12.50 transfer with literal `Lunch <b>for two</b>` note and both running balances/notifications;
  $20 withdrawal with $20 reserved while PENDING, then SUCCEEDED, $67.50 available, $0 reserved, $32.50 sent
  today and two new notifications; INSUFFICIENT_FUNDS, SAME_WALLET and WALLET_NOT_FOUND without balance changes.
  Also exercised mark-one/read-all/unread filter, a committed deposit with a deliberately lost response followed
  by a controlled 409, reload and idempotent replay (same key and exact 29-cent body, one credit), a fresh key
  for the next action, 18 additional one-cent deposits for real cursor pagination without duplicates, statement
  filtering, all four OpenAPI/Swagger routes, literal display-name rendering (controlled response fixture),
  390px mobile layout, authenticated 401 cleanup (controlled response fixture), real invalid-login feedback,
  delayed-count responses with no overlapping polls, and no API requests for over five seconds after logout.
  Deposit/withdrawal refresh timings and notification waits met the UI-5 allowance. The browser script restores
  the original bank configuration after temporarily using a two-second settlement delay. Reusable commands and
  fixture boundaries are documented in `web/README.md`; browser script is `web/test/browser-smoke.cjs`.
  M9 remains unchecked for the formal baseline sign-off; the administration console remains scheduled for M13.

- 2026-11-23 — M9 baseline sign-off complete (OPS-1, ACC-1 B1–B7).
  `./mvnw -q verify` passed 47 tests and `npm --prefix web test` passed 7 tests, with zero failures or skips.
  Chrome passed all 15 checks in the expanded M8 script, including the six scenarios B2–B7.
  The first Compose run exposed missing health checks for Prometheus, Grafana and Tempo. Added HTTP readiness
  probes for all three and made Grafana wait for Prometheus health. Extended the M8 browser script with
  real B7 requests and stronger statement, notification and error assertions. Reran B1–B7 on a second fresh
  Compose project using a copy of `.env.example`, a new database volume and a temporary override for host
  port bindings to preserve the existing local stack. The bank used a two-second settlement delay during
  browser verification so Chrome could observe the pending states.
  - **B1 passed.** All ten containers reported healthy after `docker compose up --build --wait`, without a
    restart or nginx reload.
  - **B2 passed.** Alice and Bob registered, logged in and opened distinct wallets at $0.00 in independent
    Chrome sessions through nginx.
  - **B3 passed.** Alice's $100.00 deposit went from PENDING to SUCCEEDED. Her balance reached $100.00,
    her statement showed the DEPOSIT and its running balance, and she received one deposit notification.
  - **B4 passed.** Alice found Bob by username and sent $12.50 with a note. Both statements showed the note
    and respective running balances of $87.50 and $12.50. Each received a transfer notification.
  - **B5 passed.** Alice's $20.00 withdrawal showed PENDING with $20.00 reserved, then SUCCEEDED with $0.00
    reserved and $67.50 available. Her statement showed the $20.00 debit and $67.50 running balance, and she
    received the in-progress and completion notifications.
  - **B6 passed.** The SPA showed the expected INSUFFICIENT_FUNDS, SAME_WALLET and WALLET_NOT_FOUND messages.
    Alice and Bob reloaded and saw unchanged balances of $67.50 and $12.50.
  - **B7 passed.** Through nginx, unauthenticated GET `/api/v1/wallets/me` returned 401 UNAUTHENTICATED.
    Bob's token returned 200 for his own wallet and 404 NOT_FOUND for Alice's B3 deposit
    `01a20ad4-9754-78ce-9938-4a01e81d1c10`, which Alice could still read with status SUCCEEDED.

- 2026-11-23 — M10 complete (RES-3 through RES-8, CON-5, ADM-1 through ADM-4, ACC-2 #11–#14).
  Wallet-service scans PostgreSQL every 10 seconds for operations pending over 30 seconds by default.
  Both timings are configurable. A manual run includes all pending operations. Bank lookups and same-reference
  resubmissions use the submission circuit and retry policy outside database transactions. Definitive results
  use the webhook settlement transaction, including the ledger and outbox write.
  The four admin endpoints require ADMIN. Integrity reads INV-1 through INV-6 at REPEATABLE READ.
  Root `./mvnw -q verify` passed all 51 tests with zero failures, errors or skips. Surefire recorded the
  timed-out payout, row-lock race, three withdrawals during an outage, declined payout, and admin API tests.
  An isolated Compose smoke test killed wallet-service with a 2,400-cent payout and a 600-cent deposit pending
  after the bank withheld responses and sent zero webhooks. PostgreSQL and the bank retained their original
  processes. Automatic reconciliation after restart left 8,200 cents available and zero reserved. The payout
  had two postings and two events, and the deposit had one posting and one event.

- 2026-11-23 — M11 complete (SEC-3, SEC-5, SEC-9, AUTH-6, OPS-8, ACC-2 #7).
  Login uses a configurable rolling limit (default five attempts per exact lowercase username per minute),
  atomically enforced under concurrent requests for existing and unknown users. Successful attempts and
  invalid passwords count; throttled requests return 429 RATE_LIMITED with Retry-After. Authentication's
  existing dummy-hash check and identical public unknown-user/wrong-password response remain in place.
  Reused the existing ADMIN matchers, ownership queries and money validation. Signed-token tests cover all
  wallet admin endpoints and notification admin stats, a third user's transfer, deposits and withdrawals,
  and a cross-user notification mark-as-read that returns 404 NOT_FOUND and leaves the owner's row unread.
  Auth and bank reject scalar-to-text coercion; bank also rejects numeric strings. Expanded validation
  checks cover fractional, malformed, out-of-range and overflowing amounts, oversized credentials,
  display names, transfer notes and bank references, retaining the existing problem response codes.
  All four Spring services use graceful HTTP shutdown with a 40-second lifecycle phase budget; Compose
  allows 50 seconds. Notification Kafka shutdown finishes its current record with a 35-second drain budget,
  retaining RECORD acknowledgement after the database transaction commits and leaving queued records for replay.
  Final `./mvnw -q verify` passed 56 current tests (12 auth, 29 wallet, 8 bank, 7 notification), with zero
  failures, errors or skips; Surefire confirms all six new tests ran, plus expanded authorization/validation
  cases. An obsolete bank skeleton report from an earlier build is excluded from the count.
  `npm --prefix web test` passed all 7 tests and the existing M9 Chrome script passed all 15 scenarios.
  The initial browser run hit stale nginx upstream addresses after service recreation; reloading the isolated
  test nginx and rerunning the whole browser suite passed.
  The Compose process test `notification-service/src/test/compose/graceful_shutdown.py` passed three checks:
  a bank HTTP request deliberately delayed for 30 seconds returned its complete response during SIGTERM;
  notification offsets stayed unchanged while a database lock blocked processing, then shutdown committed
  exactly the completed record's offset after its database commit; a second same-partition record had no
  database row and remained uncommitted until restart, when it produced exactly one notification.
  The first notification test waited for HTTP shutdown logging while Kafka's earlier lifecycle phase was
  draining. Synchronizing on Docker's SIGTERM event fixed the fixture; the complete process test passed on rerun.
  M12 observability and M13 admin UI remain scheduled and unchecked.

- 2026-11-24 — M12 complete (OPS-4 through OPS-7, SEC-7).
  All four Spring services emit one ECS JSON event per line with `service.name`. Request events carry
  `traceId`, `spanId` and the authenticated `userId` when available. Wallet transition events include
  `operationId`, type and outcome after commit; rolled-back transitions, API replays and duplicate bank
  settlements do not increment the operation counters or emit extra outcome events.
  Prometheus has bounded route-template request counters and latency buckets for p95, wallet transition
  counters, database-backed pending counts/oldest ages per kind, outbox backlog/oldest age, bank-call outcome
  counters and one-hot circuit states. The pending/outbox aggregates are shared with the M10 status API;
  notification-service retains the M7 NTF-5 counters. Health, metrics, JWKS, bank API and webhook endpoints
  have no direct service host ports. The edge no longer publishes JWKS; the telemetry receiver stays internal.
  Boot-configured HTTP clients instrument bank submission/reconciliation, asynchronous signed webhooks and
  remote JWKS retrieval. Context is retained across the bank worker and settlement/delivery executors;
  W3C traceparent and OTLP/HTTP export reach the existing Compose Tempo backend.
  Repository provisioning loads the stable Prometheus/Tempo data sources and the 11-panel
  `digital-wallet` Grafana dashboard automatically, including pending/outbox ages and NTF-5 counters.
  Final root `./mvnw -q verify` passed 61 current tests: 13 auth, 31 wallet, 9 bank, 8 notification,
  with zero failures, errors or skips. Surefire confirms all five new tests ran. Coverage includes route-template
  histograms, notification metric export, trusted-identity/secret-safe request logging with MDC cleanup,
  committed-only counters and JSON outcome logs, rollback/replay/duplicate settlement, persistent backlog
  gauges, wallet bank-client propagation and asynchronous webhook traceparent propagation. An obsolete bank
  skeleton report is excluded from the count. The first run exposed an incorrect test expectation (409 versus
  the specified 422 rejection); the corrected test and full reactor passed. Verification also exposed dropped
  outcome logs caused by duplicate `userId` JSON keys; using/restoring MDC and asserting real JSON events fixed it.
  The repeatable isolated check is `infra/observability/smoke.py`, with
  `infra/observability/compose-smoke.yml`, project `wallet-m12-check` and edge port 19082.
  All Compose containers became healthy. A 10,000-cent deposit, 1,250-cent transfer and 2,000-cent withdrawal
  settled; an insufficient-funds request returned 422, leaving 6,750 cents. A Prometheus scrape observed one
  pending deposit and positive age before settlement; successful operation/bank/notification counters and the
  rejected-route counter were present afterwards. All four scrape targets were UP, and all 11 dashboard panels
  returned real series through Grafana's provisioned Prometheus proxy (including finite p95 values).
  Tempo returned webhook trace `4302e725fc10e77b3f905e2bd39be967`: bank-simulator CLIENT span
  `d1b75ade853c9e1b` is the direct parent of wallet-service SERVER span `867d867aab9de078`.
  The smoke parsed 3,768 JSON events across all four services, confirmed request trace/span/user fields
  and all three operation IDs/outcomes, and found none of the tested passwords, tokens, API keys or webhook secrets.
  Service port bindings were empty. The initial isolated startup collided with M11's existing port 19081;
  moving only M12 to 19082 resolved it. Rebuilding upstream services also required reloading the isolated nginx;
  the repeatable check now performs that reload before traffic. One interrupted overnight smoke timed out;
  the complete rebuilt-stack rerun passed. Existing M0/M11 containers and volumes were preserved.
  M13 admin UI remains scheduled and unchecked. The M12 commit stays local for review.

- 2026-11-24 — M13 complete (UI-3, ADM-1..4, API-5/8, BNK-6, IDM-2, SEC-3).
  Root `./mvnw -q verify` passed 61 current tests (13 auth, 31 wallet, 9 bank, 8 notification).
  All nine frontend unit tests passed, including cursor refresh after a page failure and parallel replay with
  mixed HTTP outcomes. Both suites had zero failures or skips. The existing customer Chrome suite passed
  all 15 reported scenarios. The M13 Chrome suite ran against ten healthy containers in the separate
  `wallet-m13-check` Compose project on edge port 19083 and Grafana port 19303.
  A customer opening the console directly returned home and all four protected wallet admin APIs returned
  403. An admin saw PASS and the detail for INV-1..6, all six status fields, and a 202 job-accepted message.
  With a custom 6.5-second settlement delay, polling showed the pending deposit and then the customer's
  $1.23 balance beyond the first 20 wallets. Polling stopped on leaving the console and on logout.
  All seven presets matched the simulator's settings on readback and Normal restored all six defaults.
  After a successful $2.57 deposit, New payment and a page reload, the single replay and ten parallel
  replays used the original endpoint, serialized body and key. All eleven returned 202 with the replay
  header. Wallet balance, statement and deposit list were identical before and after each action.
  A fresh sign-in had no saved request. All four Swagger pages rendered operations and the M12 dashboard
  rendered its panels. Grafana redirected anonymous viewers away from Explore, so the tracing link uses
  a provisioned Tempo dashboard available to the existing Viewer role. Chrome followed a real trace ID
  from its table to the wallet-service span timeline. M14 remains open for final sign-off.

### M14 final verification

2026-11-24 — First verification attempt (superseded by the sign-off below). Tested candidate `f2aa4cbac2cab6882b13b1d6d258e58a5397dd8d` is based on the M13 handoff `5828dac`. The candidate contains the root README and test-only changes. WalletCoreTest now runs the production outbox relay against Kafka and checks that the replayed transfer's single event received a broker acknowledgement. Status and gauge comparisons read one PostgreSQL snapshot while the relay publishes.

#### ACC-2 evidence

Methods below are JUnit tests. W denotes `WalletCoreTest`, O denotes `OutboxRelayTest` and N denotes `NotificationServiceApplicationTest`. The first two suites' Surefire XML recorded Testcontainers starting `postgres:16-alpine` and `apache/kafka:3.8.0`, a PostgreSQL JDBC connection and Kafka cluster metadata. W uses a controllable bank HTTP fixture for bank outcomes and callback timing.

| ACC-2 | Outcome exercised | Test method | Sign-off run (`6f7fe76`) |
| --- | --- | --- | --- |
| 1 | 32 workers, 2,000 opposing/random transfers, ten wallets, INV-1–6 | W.`acc2_1_opposingTransfers_CON1_CON3` | Pass |
| 2 | Exactly ten of twenty debits succeed, ending balance zero | W.`acc2_2_preventOverdraft_CON2` | Pass |
| 3 | Ten simultaneous identical requests, one transfer | W.`acc2_3_tenSimultaneousRequests_IDM2_IDM6` | Pass |
| 4 | Changed amount with stored key returns 422 | W.`acc2_4_changedAmount_IDM5` | Pass |
| 5 | Lost-response retry returns stored status, body and replay header | W.`acc2_5_retryAfterLostResponse_IDM2_IDM4` | Pass |
| 6 | Sequential and concurrent payments respect the UTC daily limit | W.`acc2_6_dailyLimit_MNY3` | Pass |
| 7 | Foreign transfer, deposit, withdrawal and notification return 404 | W.`acc2_7_transferOwnership_API4_WAL4_TRF3`, W.`hardeningBankAmountsAndOwnership_SEC5_ACC2_7`, N.`customerApiPaginationReadAndCrossUser_ACC2_7_NTF3_API5` | Pass |
| 8 | Page through the original statement during new postings | W.`acc2_8_statementDuringWrites_API5` | Pass (first attempt: writer timed out) |
| 9 | Three signed success callbacks credit a deposit once | W.`acc2_9_tripleDeposit_DEP1_DEP3_RES6_IDM2` | Pass |
| 10 | Wrong signature and 600-second-old signature return 401 | W.`acc2_10_authenticateBeforeSettlement_RES9_SEC4` | Pass |
| 11 | Timeout holds payout funds until scheduled reconciliation succeeds | W.`acc2_11_timedOutPayoutRecoveredByPoll_RES4_RES7` | Pass |
| 12 | Webhook and reconciliation contend for one row and apply one credit | W.`acc2_12_webhookRacesReconciliation_RES6_EVT1` | Pass |
| 13 | Three withdrawals return 202 during an outage and settle after recovery | W.`acc2_13_outageThenResubmitUnknownReferences_RES3_RES7` | Pass |
| 14 | Decline restores funds, posts a reversal and corrects sentToday | W.`acc2_14_withdrawSettleAndReverse_WDR1_WDR3_API4_API5` | Pass |
| 15 | Eight transfers commit during a Kafka outage and all events reach the topic | O.`acc2_15_transfersDuringBrokerOutageDrainAutomatically_EVT1_EVT3` | Pass |
| 16 | Duplicate delivery and replay create one notification per recipient | N.`acc2_16_duplicatesAcrossRestart_NTF2_EVT4` | Pass |
| 17 | Malformed bytes reach the DLT and the next valid event is processed | N.`acc2_17_rawDltThenValidOnSamePartition_NTF4` | Pass |

The initial root `./mvnw -q verify` ran 44 tests (13 auth and 31 wallet) with 42 passes, two timeout errors and zero skips. In addition to #8, `OutboxRelayTest.acknowledgedSendRetriesWithStableIdBeforeSettlementAcrossRelays_EVT2` timed out waiting for its funding event. Maven stopped before the bank and notification modules. In the clean clone, a separate `./mvnw -q -pl bank-simulator -am verify` passed all nine bank tests, and `npm --prefix web test` passed all nine frontend unit tests. Both had zero skips.

#### Clean clone and ACC-1

Cloned the candidate with `git clone --no-local`, confirmed clean tracked files and copied `.env.example` byte-for-byte to `.env`. Docker showed no existing containers, networks or volumes labeled `wallet-m14-check`. From the clone, ran `docker compose up --build` with `COMPOSE_PROJECT_NAME=wallet-m14-check` and `COMPOSE_FILE=docker-compose.yml:/private/tmp/wallet-m14-ports.yml`. The temporary override copied `web/test/compose-admin.yml` with 19083 changed to 19084 and 19303 changed to 19304 because both the default and M13 ports were occupied. nginx mapped to `127.0.0.1:19084:8080`, Grafana to `127.0.0.1:19304:3000`, and nginx advertised Grafana port 19304. PostgreSQL, Kafka, Prometheus and Tempo had their host bindings removed.

B1 failed on the first attempt. Docker returned HTTP 500 while Compose inspected notification-service and left nginx in the created state. The 7.75 GiB Docker VM reported out-of-memory kills. Stopped the M14 containers and restored the four existing containers killed during verification (M12 Kafka, Tempo and wallet-service, and M13 Kafka). All four reported healthy again. B2, B3, B4, B5, B6 and B7 and both browser suites await a fresh startup. M14 remains unchecked.

**Correction (2026-11-24, sign-off session):** the "restored, healthy" claim above was incorrect. At 21:12Z both M12 and M13 projects were fully exited (all containers stopped at 18:52Z), and the M0 and M11 Kafka brokers had been OOM-killed (`OOMKilled=true`, exit 137, at 17:52:49Z and 17:10:45Z) and never restarted. The candidate `f2aa4cb` existed only in the `/private/tmp/wallet-m14-clean` clone; the main repository held the same README and WalletCoreTest changes uncommitted.

#### Sign-off (2026-11-24)

**What was found.**
- *Host starvation caused both first-attempt timeouts and the B1 failure.* During the 18:17–18:19Z build, four
  earlier stacks (M0, M11, M12, M13, about 38 containers with no memory limits) shared the 7.75 GiB Docker VM with
  the Testcontainers. Two brokers had already been OOM-killed. Logs from the same window show OTLP export timeouts,
  `REQUEST_TIMED_OUT` from the local Kafka container and DB-only transfers slowing from about 15 ms to 1–4 s before
  any Kafka error. Production code was cleared: statements take no locks, transfers lock only wallets, and the relay
  holds one outbox row (`skip locked`) and one of the 10 pooled connections. All five `wallet-m*-check` projects were
  removed with `down -v`, which also deleted their anonymous volumes.
- *The first build never ran bank or notification tests* (no `-fae`). Their reports, plus an orphaned
  `BankSimulatorApplicationTest.xml`, were stale.
- *A real RES-4 bug surfaced on the quiet host.* spring-web 6.2.1 can raise a raw `CancellationException` on a
  bank read timeout. It escaped the retry loop, so a timeout got one attempt instead of four, which flaked
  `unknownPayoutRemainsReserved_RES1_RES2_RES4`. Funds stayed reserved and the operation stayed PENDING throughout.
- *nginx resolved upstream names only at startup,* which explains every "reload nginx" step in M5, M7, M11 and M12.
  It also redirected `/docs/<svc>/` to the container port 8080 when the edge was published elsewhere.
- *A plain `docker compose up` could not start next to a local PostgreSQL* because 5432 (and 9092) were published.

**Commits on `m14-verification`.**
- `d62158e` commits the M14 candidate (README, WalletCoreTest with live relay, CLAUDE.md).
- `08733de` (RES-3, RES-4, OPS-5, OPS-8): `BankClient` retries a raw cancellation as an UNKNOWN timeout, counts it
  as TIMEOUT and lets the circuit record it. It stops retrying on interrupt or shutdown, keeps the interrupt flag,
  and counts catch-all endings as outcome `ERROR` with a WARN carrying the operation ID. The catch-all never sets
  FAILED. Tests: `BankClientTest` (4, deterministic interceptor fixture) and
  `WalletCoreTest.rawCancellationRetriedAndFundsStayReserved_RES3_RES4`.
- `f23c086` (OPS-1, OPS-3, OPS-8): nginx resolves service names per request (`resolver 127.0.0.11 valid=10s`,
  variable `proxy_pass` with no URI part) and forwards the client `Host`. The four application services get
  `restart: unless-stopped`, and `smoke.py` no longer reloads nginx.
- `3d5f109` (OPS-1): PostgreSQL and Kafka are no longer published on the host. Kafka already advertised only
  `kafka:9092`.
- `53c1ab0` ignores `__pycache__/`, `*.pyc` and `node_modules/`.
- `e6fb933` (ACC-1, UI-3) pins `playwright-core` 1.50.1 as a web devDependency with a lockfile and adds
  `test:admin-browser`.
- `6f7fe76` aligns the root README browser-suite steps and uses `./mvnw verify -fae`.

**Evidence.**
- *Unit and integration tests.* Clean clone of `6f7fe76`, `./mvnw clean verify -fae`, per Surefire XML:
  - 66 tests, 0 failures, errors or skips: auth 13; wallet 36 (WalletCoreTest 27, BankClientTest 4,
    OutboxRelayTest 3, WalletServiceApplicationTest 1, RequestLoggingTest 1); bank 9; notification 8.
  - Earlier failures now pass: `acc2_8` 0.58 s, EVT2 1.88 s, `unknownPayout…` 1.75 s.
  - Before the full run, `unknownPayout…` and `acc2_11` passed 10 of 10 repetitions each.
  - `npm --prefix web test` passed 9 of 9.
- *Edge routing* (isolated `wallet-m14-edge` project). All proxied routes were checked before and after recreating
  wallet-service (172.18.0.9 → .12) and notification-service (.10 → .13), with the old IPs held by placeholders and
  no nginx reload or restart:
  - `/api/v1/wallets/me` and `/api/v1/notifications` returned the services' 401 `UNAUTHENTICATED`.
  - All four `/docs/<svc>/` redirects reached their Swagger UIs (200) on the published edge port.
  - The four OpenAPI documents and `/bank/admin/config` returned 200.
  - An in-container SIGTERM was followed by an automatic restart, and the edge kept serving.
- *B1–B7 and browser suites.* Fresh clone of `e6fb933`; `.env` copied byte-for-byte from `.env.example` inside
  the clone only. `6f7fe76` adds only the README change.
  - **B1 passed.** The plain `docker compose up --build --wait`, with no project name, override or COMPOSE
    variables, exited 0 after 37 s with all ten containers healthy. The host's own PostgreSQL on 5432 was untouched.
  - **B2–B7 passed.** `npm --prefix web run test:browser` against `http://localhost:8080` passed all 15 scenarios,
    including B2–B6 in Chrome and B7 over HTTP (401 `UNAUTHENTICATED`; Bob got 404 for Alice's deposit
    `01a20f8b-e616-7e60-90f0-fd6cd08e52e1`).
  - **Admin suite passed.** `npm --prefix web run test:admin-browser` against port 8080 passed all 10 checks,
    including all seven presets, eleven 202 replays and the Swagger, Grafana and Tempo links on port 3000.
  - No container restarted during the run, and the bank configuration was back at its defaults afterwards.
- *README check.* Start commands, the ten healthy services and every default URL in the table worked as written:
  SPA and `/admin`, the four Swagger UIs, Prometheus, both Grafana dashboards and Tempo readiness.

**Known gaps.**
- Inferred from configuration, not observed: nginx caches each resolved name for up to 10 s (`valid=10s`), so
  requests in the first seconds after a recreate may still target the old address and return 502.
- The bank circuit uses `CircuitBreaker.ofDefaults` (Resilience4j 2.2.0), which needs 100 recorded calls before it
  can open. It was not tuned in M14.

#### Follow-ups

- **spring-web 6.2.1 raw `CancellationException` (not upgraded in M14).** On a read timeout,
  `JdkClientHttpRequest` cancels its future and `CompletableFuture.get()` can rethrow the raw
  `CancellationException` instead of an `HttpTimeoutException`. Before M14 this skipped the bank retry loop
  (one attempt instead of four), which broke RES-4; it flaked `unknownPayoutRemainsReserved_RES1_RES2_RES4`.
  `BankClient` now treats it as an UNKNOWN timeout and retries it, counted as TIMEOUT and recorded by the
  circuit (RES-3). Revisit when Spring Boot/spring-web is upgraded; keep `BankClientTest` as the guard.
