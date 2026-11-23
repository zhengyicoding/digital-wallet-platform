# Digital Wallet Platform: Requirements Specification

2026-11-22

This document specifies the Digital Wallet Platform, a set of Spring Boot microservices with a web frontend. A customer registers, opens a wallet, adds money from a bank account, sends money to other customers, withdraws money to the bank and gets a notification for every movement. The bank is an external system: it is slow, it fails and it repeats its callbacks. Every balance stays correct in spite of that, also while many requests run at the same time and while a service is restarted.

## Contents

1. Delivery levels
2. System overview
3. Services
4. Business rules
5. Functional requirements
6. API
7. Events
8. Non-functional requirements
9. Frontend
10. Technology and constraints
11. Acceptance tests

## 1. Delivery levels

This document defines two delivery levels.

| Level | Definition |
| --- | --- |
| Baseline | The minimum acceptable delivery. Every service starts, the services communicate over all interfaces of Section 2, and the functions of the platform work end to end through the frontend under nominal conditions. |
| Full | All requirements of this document. In addition to the baseline, the system stays correct under concurrent requests, failures of its dependencies and restarts, and it meets all security and operations requirements. |

Nominal conditions:

- The database, Kafka and the bank are available. The bank approves every transaction and sends every webhook once.
- Requests do not overlap in time.
- No service is stopped while an operation is in progress.

The baseline level consists of the requirements in the following table, verified under nominal conditions. Where a baseline requirement also describes behaviour outside nominal conditions, that part belongs to the full level. Every requirement that is not in the table belongs to the full level.

| Section | Baseline scope |
| --- | --- |
| 3 Services | The five services; of the infrastructure containers, PostgreSQL and Kafka |
| 4 Business rules | MNY-1 to MNY-4, LED-1 to LED-5, the operations of Section 4.3 |
| 5.1 auth-service | AUTH-1 to AUTH-5 |
| 5.2 to 5.5 wallet-service | WAL-1 to WAL-5, TRF-1 to TRF-5, DEP-1 to DEP-3, WDR-1 to WDR-4 |
| 5.7 bank-simulator | BNK-1, BNK-4 |
| 5.8 notification-service | NTF-1, NTF-3 |
| 6 API | API-1 to API-9; all endpoints of Sections 6.2, 6.3 and 6.5 except GET `/api/v1/notifications/admin/stats`; POST `/internal/webhooks/bank`; POST `/bank/v1/transactions` |
| 7 Events | The topic, the envelope and the event types |
| 8.4 Security | SEC-1, SEC-2, SEC-5 to SEC-8 |
| 8.5 Operations and observability | OPS-1 to OPS-3 |
| 9 Frontend | UI-1, UI-2, UI-5 to UI-7 |
| 10 Technology and constraints | TEC-1, TEC-2 |
| 11 Acceptance tests | ACC-1 |

## 2. System overview

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                           Digital Wallet Platform                            │
├──────────────────────────────────────────────────────────────────────────────┤
│                                    +---------+                               │
│                                    | browser |                               │
│                                    +---------+                               │
│                                         |  HTTP, JSON, JWT                   │
│                                         v                                    │
│        +------------------------------------------------------------------+  │
│        |               web: static frontend and edge proxy                |  │
│        +------------------------------------------------------------------+  │
│              |                    |                               |          │
│              v                    v                               v          │
│        +-----------+        +-----------+    +-------+    +---------------+  │
│        |   auth-   |        |  wallet-  |--->| Kafka |--->| notification- |  │
│        |  service  |        |  service  |    +-------+    |    service    |  │
│        +-----------+        +-----------+     events      +---------------+  │
│              |                |   |   ^                           |          │
│              v                v   |   |                           v          │
│          [auth DB]   [wallet DB]  |   | signed webhooks   [notification DB]  │
│                              REST |   |                                      │
│                                   v   |                                      │
│                               +-----------+                                  │
│                               |   bank-   |                                  │
│                               | simulator |                                  │
│                               +-----------+                                  │
└──────────────────────────────────────────────────────────────────────────────┘
```

- The frontend sends its requests only to web, which serves the frontend and forwards the requests to the services (Section 6.1).
- wallet-service calls bank-simulator over REST. bank-simulator reports results back with signed webhooks.
- wallet-service publishes events to Kafka, and notification-service consumes them.
- Every service owns its data. No service reads or writes the tables of another service.

## 3. Services

| Service | Responsibility | Data it owns |
| --- | --- | --- |
| auth-service | Registration, login, issuing JWTs, publishing the public keys | Users and password hashes |
| wallet-service | Wallets, ledger, transfers, deposits, withdrawals, idempotency, outbox, reconciliation with the bank | Wallets, ledger entries, transfers, deposits, withdrawals, idempotency records, outbox |
| bank-simulator | Stands in for the external bank: accepts charges and payouts, settles them later, sends webhooks, produces failures on request | Bank transactions (in memory) |
| notification-service | Consumes wallet events and keeps a list of notifications per user | Notifications, IDs of processed events |
| web | Static frontend and edge proxy | None |

Infrastructure containers: PostgreSQL, Kafka, Prometheus, Grafana and a tracing backend. One PostgreSQL server with one database and one database user per service is acceptable.

## 4. Business rules

### 4.1 Money

- MNY-1 There is one currency, USD. An amount is an integer number of cents in the API, in events and in the database.
- MNY-2 The amount of a transfer, a deposit or a withdrawal is between 1 and 200,000 cents.
- MNY-3 A wallet sends at most 500,000 cents per UTC calendar day. The daily total is the sum of the transfers sent and the withdrawals requested on that day. A withdrawal that fails no longer counts.
- MNY-4 The limits of MNY-2 and MNY-3 come from configuration.

### 4.2 Ledger

- LED-1 A balance changes only through ledger entries. An entry is never updated or deleted.
- LED-2 The ledger has one account per wallet and two system accounts: EXTERNAL_BANK (money that came from the bank or went to it) and PAYOUT_HOLD (money reserved for withdrawals in progress). The system accounts are created by a database migration and cannot be reached through the customer API.
- LED-3 Every business event is posted as a group of entries whose amounts add up to zero:

| Business event | Entries |
| --- | --- |
| Transfer of x from wallet A to wallet B | A -x, B +x |
| A deposit of x succeeds | EXTERNAL_BANK -x, wallet +x |
| A withdrawal of x is requested | wallet -x, PAYOUT_HOLD +x |
| The withdrawal succeeds | PAYOUT_HOLD -x, EXTERNAL_BANK +x |
| The withdrawal fails | PAYOUT_HOLD -x, wallet +x |
- LED-4 Every entry of a wallet stores the balance of the wallet after the entry (balanceAfter).
- LED-5 The following invariants hold at every moment for any reader of committed data:

| ID | Invariant |
| --- | --- |
| INV-1 | The amounts of all ledger entries add up to zero, and so do the entries of every single posting. |
| INV-2 | The balance reported for an account equals the sum of its entries. |
| INV-3 | No wallet balance is negative, and the balance of PAYOUT_HOLD is not negative. |
| INV-4 | The balance of PAYOUT_HOLD equals the sum of the amounts of all PENDING withdrawals. |
| INV-5 | The balance of EXTERNAL_BANK equals the sum of all succeeded withdrawals minus the sum of all succeeded deposits. |
| INV-6 | Within one wallet, in posting order, every balanceAfter equals the previous balanceAfter plus the amount of the entry. |

### 4.3 Operations

| Operation | Processing | Status |
| --- | --- | --- |
| Transfer | Synchronous, in one database transaction | COMPLETED |
| Deposit | Asynchronous: created as PENDING, charged at the bank, finished when the bank reports the result | PENDING, then SUCCEEDED or FAILED |
| Withdrawal | Asynchronous: the amount is reserved and the withdrawal is created as PENDING, the bank pays it out, finished when the bank reports the result | PENDING, then SUCCEEDED or FAILED |

COMPLETED, SUCCEEDED and FAILED are final. A final status never changes.

## 5. Functional requirements

### 5.1 auth-service

- AUTH-1 A user registers with a username (3 to 20 characters: lowercase letters, digits and underscore; unique), a password (8 to 72 characters and at most 72 bytes in UTF-8) and a displayName (1 to 50 characters).
- AUTH-2 Passwords are stored only as salted hashes made with BCrypt or Argon2.
- AUTH-3 Login returns an access token: a JWT signed with RS256 that is valid for 30 minutes and carries the claims iss, sub (the user ID), username, name (the display name), roles, iat and exp. auth-service may create the signing key pair when it starts.
- AUTH-4 The public keys are published as a JWKS document. wallet-service and notification-service load these keys and validate tokens with them. They do not call auth-service for every request.
- AUTH-5 There are two roles, USER and ADMIN. Every registered user has USER. One administrator is created at startup from the environment variables ADMIN_USERNAME and ADMIN_PASSWORD. The administrator has both roles.
- AUTH-6 A failed login does not reveal whether the username exists.

### 5.2 wallet-service: wallets and statement

- WAL-1 A user opens a wallet with POST `/api/v1/wallets`. A user has at most one wallet. If the wallet exists, the call returns it. Concurrent calls of the same user create one wallet.
- WAL-2 When a wallet is opened, wallet-service copies username and name from the token into the wallet. It does not ask auth-service for them.
- WAL-3 GET `/api/v1/wallets/me` returns the balance, the amount reserved by pending withdrawals, the daily limit and the amount sent today.
- WAL-4 GET `/api/v1/wallets/lookup` finds a wallet by username and returns only the wallet ID, the username and the display name.
- WAL-5 GET `/api/v1/wallets/me/entries` returns the statement: the ledger entries of the wallet, newest first, each with its type, signed amount, balanceAfter, counterparty, note and the ID of the transfer, deposit or withdrawal it belongs to. The list can be filtered by type.

| Entry type | Sign | Posted when |
| --- | --- | --- |
| DEPOSIT | + | A deposit succeeds |
| TRANSFER_OUT | - | The wallet sends a transfer |
| TRANSFER_IN | + | The wallet receives a transfer |
| WITHDRAWAL | - | A withdrawal is requested |
| WITHDRAWAL_REVERSAL | + | A withdrawal fails |

### 5.3 wallet-service: transfers

- TRF-1 A transfer moves an amount from the caller's wallet to another wallet. Both ledger entries, both balances and the transfer record are written in one database transaction.
- TRF-2 A transfer is rejected, and changes nothing, when the recipient wallet does not exist (RECIPIENT_NOT_FOUND), when it is the caller's own wallet (SAME_WALLET), when the balance is too low (INSUFFICIENT_FUNDS) or when the daily limit would be exceeded (DAILY_LIMIT_EXCEEDED).
- TRF-3 A transfer has an optional note of at most 140 characters that both parties see.
- TRF-4 The sender and the receiver can read a transfer with GET `/api/v1/transfers/{id}`.
- TRF-5 A completed transfer is final. There is no cancellation and no refund.

### 5.4 wallet-service: deposits

- DEP-1 POST `/api/v1/deposits` creates a deposit with the status PENDING and answers with 202. No ledger entry is posted yet.
- DEP-2 wallet-service submits a CHARGE to the bank with the deposit ID as the reference.
- DEP-3 When the bank reports SUCCEEDED, the wallet is credited once and the deposit becomes SUCCEEDED. When the bank reports FAILED, the deposit becomes FAILED with the reason given by the bank, and nothing is posted.

### 5.5 wallet-service: withdrawals

- WDR-1 POST `/api/v1/withdrawals` reserves the amount (wallet to PAYOUT_HOLD) and creates a withdrawal with the status PENDING in one database transaction, then answers with 202. It is rejected with INSUFFICIENT_FUNDS or DAILY_LIMIT_EXCEEDED under the same rules as a transfer.
- WDR-2 wallet-service submits a PAYOUT to the bank with the withdrawal ID as the reference.
- WDR-3 When the bank reports SUCCEEDED, the reserved amount moves to EXTERNAL_BANK and the withdrawal becomes SUCCEEDED. When the bank reports FAILED, the reserved amount returns to the wallet, the withdrawal becomes FAILED, and its amount no longer counts toward the daily total.
- WDR-4 A customer cannot cancel a withdrawal.

### 5.6 wallet-service: administration

- ADM-1 GET `/api/v1/admin/integrity` checks INV-1 to INV-6 and reports each result. The report is computed from one consistent snapshot of the database, so it is correct while requests are running.
- ADM-2 GET `/api/v1/admin/status` reports the number of pending deposits, the number of pending withdrawals, the age of the oldest pending operation, the number of unsent outbox events, the age of the oldest of them and the state of the circuit breaker for the bank.
- ADM-3 GET `/api/v1/admin/wallets` lists all wallets with their balances.
- ADM-4 POST `/api/v1/admin/reconciliation` starts a reconciliation run (RES-7) at once, for every PENDING operation whatever its age.

### 5.7 bank-simulator

- BNK-1 POST `/bank/v1/transactions` creates a CHARGE or a PAYOUT with the status PENDING. After settleDelayMs the transaction becomes SUCCEEDED or FAILED.
- BNK-2 The API is idempotent by reference. A request with a known reference and the same type and amount returns the existing transaction and executes nothing. A known reference with a different type or amount is answered with 409.
- BNK-3 GET `/bank/v1/transactions/{reference}` returns the transaction, or 404 if the bank never created it.
- BNK-4 After a transaction has settled, the simulator sends a webhook to the URL in its configuration, webhookCopies times. It does not send a webhook again when the delivery fails.
- BNK-5 Requests to `/bank/v1/**` carry the header X-Api-Key.
- BNK-6 Failures are switched on and off at runtime through `/bank/admin/config` (Section 6.6).

### 5.8 notification-service

- NTF-1 notification-service consumes the topic wallet.events and creates the notifications listed below.
- NTF-2 An event creates each of its notifications once, however many times it is delivered.
- NTF-3 A user lists their notifications newest first, filters the unread ones, reads the unread count, and marks one or all of them as read.
- NTF-4 A message that cannot be parsed is written to the topic wallet.events.dlt with its key and value, and the consumer continues with the next message.
- NTF-5 GET `/api/v1/notifications/admin/stats` reports how many events were processed, how many duplicates were ignored and how many messages were written to the dead-letter topic. The counters start at zero when the service starts.

| Event | Recipient | Text |
| --- | --- | --- |
| transfer.completed | Sender | You sent $12.50 to Bob Lee |
| transfer.completed | Receiver | You received $12.50 from Alice Chen |
| deposit.succeeded | Owner | Your deposit of $100.00 is complete |
| deposit.failed | Owner | Your deposit of $100.00 failed |
| withdrawal.requested | Owner | Your withdrawal of $20.00 is in progress |
| withdrawal.succeeded | Owner | Your withdrawal of $20.00 is complete |
| withdrawal.failed | Owner | Your withdrawal of $20.00 failed and the money is back in your wallet |

An amount in a text is written as $, the dollars with a comma between groups of three digits, a point and two digits for the cents, for example $1,250.00.

## 6. API

### 6.1 Conventions

- API-1 The system is reached on one port of the host, by default at `http://localhost:8080`. The port comes from configuration. The base path is `/api/v1`. Bodies are JSON in UTF-8. Timestamps are ISO 8601 in UTC.
- API-2 IDs are strings.
- API-3 A request to an endpoint under `/api/v1` is authenticated with Authorization: Bearer `<access token>`. A missing or invalid token is answered with 401 UNAUTHENTICATED, a missing role with 403 FORBIDDEN. In the column Auth of the tables in this section, user means a valid token with the role USER and admin a valid token with the role ADMIN.
- API-4 A user reads only their own resources. A request for a resource of another user is answered with 404 NOT_FOUND, exactly as for a resource that does not exist.
- API-5 Lists under `/api/v1` are returned newest first with cursor pagination: the parameters limit (default 20, maximum 100) and cursor, and the response { "items": [...], "nextCursor": "..." }, where nextCursor is null on the last page. A limit outside 1 to 100 and a cursor that cannot be decoded are answered with 400 VALIDATION_FAILED. A client that pages through a list while rows are added sees no row twice and misses no row that existed when it started.
- API-6 An error is returned as application/problem+json (RFC 9457) with type, title, status and detail, and with two additional members: code and traceId. For an error that this section names, code is the code given there. A validation error also has errors, a list of field and message. If errors with different codes apply to a request, the response reports one of them. An unexpected failure is answered with 500 INTERNAL_ERROR and shows no stack trace and no SQL message.
- API-7 The responses of POST `/api/v1/transfers`, POST `/api/v1/deposits` and POST `/api/v1/withdrawals` have a Location header with the path of the new resource.
- API-8 Every Spring Boot service publishes its OpenAPI document and a Swagger UI under `/docs/<name>/`, where `<name>` is auth, wallet, notifications or bank. These paths need no token.
- API-9 The field names shown in this document are binding. Fields that are not shown are left to the implementation.

The tables of this section list for every endpoint the errors of its own. They do not repeat 400 VALIDATION_FAILED for a malformed query parameter, the errors of API-3 and 500 INTERNAL_ERROR.

The edge proxy routes by path:

| Path | Destination |
| --- | --- |
| / | Static frontend |
| `/api/v1/auth/**` | auth-service |
| `/api/v1/notifications` and `/api/v1/notifications/**` | notification-service |
| `/api/v1/**` (all other paths) | wallet-service |
| `/bank/admin/**` | bank-simulator, for the administration console |
| `/docs/auth/**`, `/docs/wallet/**`, `/docs/notifications/**`, `/docs/bank/**` | The service with that name (API-8) |

`/internal/**` of wallet-service, `/bank/v1/**` of bank-simulator, the JWKS document and the health and metrics endpoints of the services are reachable only inside the service network. Grafana, Prometheus and the user interface of the tracing backend are published on host ports of their own.

### 6.2 auth-service

| Method and path | Auth | Purpose | Success | Errors |
| --- | --- | --- | --- | --- |
| POST `/api/v1/auth/register` | none | Create a user | 201 | 400 VALIDATION_FAILED, 409 USERNAME_TAKEN |
| POST `/api/v1/auth/login` | none | Return an access token for a username and a password | 200 | 400 VALIDATION_FAILED, 401 INVALID_CREDENTIALS, 429 RATE_LIMITED (SEC-9) |
| GET `/api/v1/auth/me` | user | User ID, username, display name and roles of the caller | 200 | none |
| GET `/.well-known/jwks.json` | none | Public keys for token validation | 200 | none |

Request and response of POST `/api/v1/auth/login`:

```json
{
  "username": "alice",
  "password": "correct-horse-battery"
}
```

```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIwMTk0YTNmMCJ9.c2lnbmF0dXJl",
  "tokenType": "Bearer",
  "expiresIn": 1800
}
```

Request of POST `/api/v1/auth/register`, and the response of that endpoint and of GET `/api/v1/auth/me`:

```json
{
  "username": "alice",
  "password": "correct-horse-battery",
  "displayName": "Alice Chen"
}
```

```json
{
  "id": "0194a3f0-1c2d-7a10-8f3e-6b1d2c3e4f50",
  "username": "alice",
  "displayName": "Alice Chen",
  "roles": [
    "USER"
  ]
}
```

### 6.3 wallet-service: customer API

| Method and path | Auth | Purpose | Success | Errors |
| --- | --- | --- | --- | --- |
| POST `/api/v1/wallets` | user | Open the caller's wallet (WAL-1) | 201 when created, 200 when it existed | none |
| GET `/api/v1/wallets/me` | user | The caller's wallet (WAL-3) | 200 | 404 WALLET_NOT_FOUND |
| GET `/api/v1/wallets/lookup?username={username}` | user | Find a recipient (WAL-4) | 200 | 404 WALLET_NOT_FOUND |
| GET `/api/v1/wallets/me/entries` | user | Statement (WAL-5); parameters type, limit, cursor | 200 | 404 WALLET_NOT_FOUND |
| POST `/api/v1/transfers` | user | Send money; body toWalletId, amount, note | 201 | see the next table |
| GET `/api/v1/transfers/{id}` | user | A transfer that the caller sent or received | 200 | 404 NOT_FOUND |
| POST `/api/v1/deposits` | user | Add money; body amount | 202 | see the next table |
| GET `/api/v1/deposits/{id}` | user | A deposit of the caller | 200 | 404 NOT_FOUND |
| GET `/api/v1/deposits` | user | Deposits of the caller; parameters status, limit, cursor | 200 | none |
| POST `/api/v1/withdrawals` | user | Withdraw money; body amount | 202 | see the next table |
| GET `/api/v1/withdrawals/{id}` | user | A withdrawal of the caller | 200 | 404 NOT_FOUND |
| GET `/api/v1/withdrawals` | user | Withdrawals of the caller; parameters status, limit, cursor | 200 | none |

Errors of POST `/api/v1/transfers`, POST `/api/v1/deposits` and POST `/api/v1/withdrawals`:

| Status and code | When |
| --- | --- |
| 400 IDEMPOTENCY_KEY_REQUIRED | The header Idempotency-Key is missing, empty or longer than 64 characters (IDM-1) |
| 400 VALIDATION_FAILED | A field is missing or malformed, or the amount is outside MNY-2 |
| 404 WALLET_NOT_FOUND | The caller has not opened a wallet |
| 409 REQUEST_IN_PROGRESS | A request with the same key is still running (IDM-6) |
| 422 IDEMPOTENCY_KEY_REUSED | The key was used before with a different body (IDM-5) |
| 422 RECIPIENT_NOT_FOUND, 422 SAME_WALLET | Transfers only (TRF-2) |
| 422 INSUFFICIENT_FUNDS, 422 DAILY_LIMIT_EXCEEDED | Transfers and withdrawals |

A transfer request and its response:

```http
POST /api/v1/transfers
Authorization: Bearer <access token>
Idempotency-Key: 6f1c2f0e-8f0b-4c58-9a51-3f6c1d2b7a10
Content-Type: application/json

{
  "toWalletId": "0194a3f2-8d2f-7e1b-8c6f-3a5d9b2e4f81",
  "amount": 1250,
  "note": "Lunch"
}
```

```json
{
  "id": "0194a3f8-2b3c-7f2c-ad70-4b6e0c3f5a92",
  "fromWalletId": "0194a3f2-6c1e-7d0a-9b5e-2f4c8a1d3e70",
  "toWalletId": "0194a3f2-8d2f-7e1b-8c6f-3a5d9b2e4f81",
  "amount": 1250,
  "currency": "USD",
  "note": "Lunch",
  "status": "COMPLETED",
  "createdAt": "2027-01-15T18:04:11.482Z"
}
```

An error response:

```json
{
  "type": "https://wallet.example/problems/insufficient-funds",
  "title": "Insufficient funds",
  "status": 422,
  "detail": "The wallet holds 6750 cents and the transfer needs 9000.",
  "code": "INSUFFICIENT_FUNDS",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

The wallet of Alice after a deposit of 10,000 cents, a transfer of 1,250 cents to Bob and a withdrawal of 2,000 cents that is still pending:

```json
{
  "id": "0194a3f2-6c1e-7d0a-9b5e-2f4c8a1d3e70",
  "username": "alice",
  "displayName": "Alice Chen",
  "currency": "USD",
  "balance": 6750,
  "reserved": 2000,
  "dailyLimit": 500000,
  "sentToday": 3250,
  "createdAt": "2027-01-15T17:58:02.114Z"
}
```

The response of GET `/api/v1/wallets/lookup?username=bob`:

```json
{
  "walletId": "0194a3f2-8d2f-7e1b-8c6f-3a5d9b2e4f81",
  "username": "bob",
  "displayName": "Bob Lee"
}
```

The first page of her statement with limit=2:

```json
{
  "items": [
    {
      "id": "0194a3fb-4d60-7b5f-9aa3-7e9b3f6c8dc5",
      "type": "WITHDRAWAL",
      "amount": -2000,
      "balanceAfter": 6750,
      "counterparty": null,
      "note": null,
      "referenceType": "WITHDRAWAL",
      "referenceId": "0194a3fb-4d5e-7a4e-8f92-6d8a2e5b7cb4",
      "createdAt": "2027-01-15T18:09:45.120Z"
    },
    {
      "id": "0194a3f8-2b40-7c60-abb4-8fac4a7d9ed6",
      "type": "TRANSFER_OUT",
      "amount": -1250,
      "balanceAfter": 8750,
      "counterparty": {
        "walletId": "0194a3f2-8d2f-7e1b-8c6f-3a5d9b2e4f81",
        "displayName": "Bob Lee"
      },
      "note": "Lunch",
      "referenceType": "TRANSFER",
      "referenceId": "0194a3f8-2b3c-7f2c-ad70-4b6e0c3f5a92",
      "createdAt": "2027-01-15T18:04:11.482Z"
    }
  ],
  "nextCursor": "eyJ0IjoiMjAyNy0wMS0xNVQxODowNDoxMS40ODJaIiwiaSI6IjAxOTRhM2Y4In0"
}
```

A deposit. A withdrawal has the same fields:

```json
{
  "id": "0194a3f5-0a1b-7c3d-be81-5c7f1d4a6ba3",
  "amount": 10000,
  "currency": "USD",
  "status": "SUCCEEDED",
  "failureReason": null,
  "createdAt": "2027-01-15T18:01:30.250Z",
  "completedAt": "2027-01-15T18:01:31.007Z"
}
```

### 6.4 wallet-service: administration and internal API

| Method and path | Auth | Purpose | Success |
| --- | --- | --- | --- |
| GET `/api/v1/admin/integrity` | admin | Result of the invariant checks (ADM-1) | 200 |
| GET `/api/v1/admin/status` | admin | Pending operations, outbox backlog, circuit state (ADM-2) | 200 |
| GET `/api/v1/admin/wallets` | admin | All wallets with walletId, username, displayName, balance, reserved; parameters limit, cursor | 200 |
| POST `/api/v1/admin/reconciliation` | admin | Start a reconciliation run (ADM-4) | 202 |
| POST `/internal/webhooks/bank` | signature | Result of a bank transaction (Section 6.6) | 200 |

The integrity report and the status for the example of Section 6.3:

```json
{
  "checkedAt": "2027-01-15T18:10:00.000Z",
  "ok": true,
  "checks": [
    {
      "id": "INV-1",
      "ok": true,
      "detail": "6 entries in 3 postings, sum 0"
    },
    {
      "id": "INV-2",
      "ok": true,
      "detail": "4 accounts checked"
    },
    {
      "id": "INV-3",
      "ok": true,
      "detail": "lowest wallet balance 1250"
    },
    {
      "id": "INV-4",
      "ok": true,
      "detail": "hold 2000, pending withdrawals 2000"
    },
    {
      "id": "INV-5",
      "ok": true,
      "detail": "external -10000, deposits 10000, withdrawals 0"
    },
    {
      "id": "INV-6",
      "ok": true,
      "detail": "4 wallet entries checked"
    }
  ],
  "totals": {
    "wallets": 8000,
    "payoutHold": 2000,
    "externalBank": -10000
  }
}
```

```json
{
  "pendingDeposits": 0,
  "pendingWithdrawals": 1,
  "oldestPendingSeconds": 15,
  "outboxUnsent": 0,
  "outboxOldestSeconds": 0,
  "bankCircuit": "CLOSED"
}
```

bankCircuit is CLOSED, OPEN or HALF_OPEN.

### 6.5 notification-service

| Method and path | Auth | Purpose | Success | Errors |
| --- | --- | --- | --- | --- |
| GET `/api/v1/notifications` | user | Notifications of the caller; parameters unreadOnly, limit, cursor | 200 | none |
| GET `/api/v1/notifications/unread-count` | user | Number of unread notifications as count | 200 | none |
| POST `/api/v1/notifications/{id}/read` | user | Mark one notification as read; repeating the call changes nothing | 204 | 404 NOT_FOUND |
| POST `/api/v1/notifications/read-all` | user | Mark all notifications of the caller as read | 204 | none |
| GET `/api/v1/notifications/admin/stats` | admin | The counters eventsProcessed, duplicatesIgnored, deadLettered (NTF-5) | 200 | none |

A notification:

```json
{
  "id": "0194a3f8-3c00-7d71-bcc5-90bd5b8eafe7",
  "type": "transfer.completed",
  "text": "You sent $12.50 to Bob Lee",
  "read": false,
  "referenceId": "0194a3f8-2b3c-7f2c-ad70-4b6e0c3f5a92",
  "createdAt": "2027-01-15T18:04:11.913Z"
}
```

### 6.6 bank-simulator

| Method and path | Auth | Purpose | Success | Errors |
| --- | --- | --- | --- | --- |
| POST `/bank/v1/transactions` | API key | Create a charge or a payout; body reference, type, amount | 202 when created, 200 when the reference is known | 400 VALIDATION_FAILED, 401 UNAUTHENTICATED, 409 REFERENCE_CONFLICT, 503 |
| GET `/bank/v1/transactions/{reference}` | API key | The transaction with this reference | 200 | 401 UNAUTHENTICATED, 404 NOT_FOUND, 503 |
| GET `/bank/admin/config` | none | The current failure settings | 200 | none |
| PUT `/bank/admin/config` | none | Replace the failure settings; a field that is missing takes its default | 200 | 400 VALIDATION_FAILED |
| GET `/bank/admin/transactions` | none | All bank transactions, newest first | 200 | none |
| POST `/bank/admin/reset` | none | Delete all transactions and restore the default settings | 204 | none |

A bank transaction, as returned by both `/bank/v1` endpoints and as sent in the webhook body:

```json
{
  "bankTransactionId": "bk-000017",
  "reference": "0194a3f5-0a1b-7c3d-be81-5c7f1d4a6ba3",
  "type": "CHARGE",
  "amount": 10000,
  "status": "SUCCEEDED",
  "reason": null
}
```

type is CHARGE or PAYOUT. status is PENDING, SUCCEEDED or FAILED. reason is set for FAILED. The webhook is sent to wallet-service with two headers (SEC-4). The timestamp is the time of sending in seconds since the epoch. The signature is the HMAC-SHA256, in lowercase hexadecimal, of the timestamp, a dot and the raw request body, computed with a secret that both services read from their configuration:

```text
POST /internal/webhooks/bank
Content-Type: application/json
X-Bank-Timestamp: 1800036091
X-Bank-Signature: <64 hexadecimal characters>
```

wallet-service answers 200 when it has applied the result or had applied it before, 401 INVALID_SIGNATURE when the signature is wrong or the timestamp is more than 5 minutes old (RES-9), and 404 NOT_FOUND for an unknown reference.

The failure settings:

| Field | Default | Effect |
| --- | --- | --- |
| down | false | Every request to `/bank/v1/**` is answered with 503 at once, and nothing is created |
| responseDelayMs | 50 | Delay before every response to `/bank/v1/**` |
| hangRate | 0 | Share of the POST `/bank/v1/transactions` requests for which the transaction is created and settles, but the HTTP response is held back for 30 seconds |
| settleDelayMs | 500 | Time between the creation of a transaction and its final status |
| declineRate | 0 | Share of the transactions that settle as FAILED with the reason DECLINED |
| webhookCopies | 1 | How many times every webhook is sent; with 0 none is sent |

A rate is a number from 0 to 1.

## 7. Events

wallet-service publishes to the Kafka topic wallet.events. The message key is the ID of the transfer, deposit or withdrawal (aggregateId). The value is a JSON envelope:

```json
{
  "eventId": "0194a3f8-2b41-7e82-8dd6-a1ce6c9fb0f8",
  "type": "transfer.completed",
  "version": 1,
  "occurredAt": "2027-01-15T18:04:11.482Z",
  "aggregateId": "0194a3f8-2b3c-7f2c-ad70-4b6e0c3f5a92",
  "payload": {
    "transferId": "0194a3f8-2b3c-7f2c-ad70-4b6e0c3f5a92",
    "amount": 1250,
    "currency": "USD",
    "note": "Lunch",
    "from": {
      "walletId": "0194a3f2-6c1e-7d0a-9b5e-2f4c8a1d3e70",
      "userId": "0194a3f0-1c2d-7a10-8f3e-6b1d2c3e4f50",
      "displayName": "Alice Chen"
    },
    "to": {
      "walletId": "0194a3f2-8d2f-7e1b-8c6f-3a5d9b2e4f81",
      "userId": "0194a3f0-9e8d-7b21-9a4f-7c2e3d4f5a61",
      "displayName": "Bob Lee"
    }
  }
}
```

| Type | Published when | Payload |
| --- | --- | --- |
| transfer.completed | A transfer commits | transferId, amount, currency, note, from and to with walletId, userId, displayName |
| deposit.succeeded, deposit.failed | A deposit reaches its final status | depositId, walletId, userId, amount, currency, failureReason |
| withdrawal.requested | A withdrawal is created | withdrawalId, walletId, userId, amount, currency |
| withdrawal.succeeded, withdrawal.failed | A withdrawal reaches its final status | withdrawalId, walletId, userId, amount, currency, failureReason |
- EVT-1 An event is written to an outbox table in the same database transaction as the change it describes, and a relay publishes the outbox rows to Kafka. An event is published if and only if its transaction committed.
- EVT-2 Delivery is at least once. The events of one aggregate are published in the order in which they were written. The topic has at least 3 partitions.
- EVT-3 While Kafka is unavailable, the API keeps working at its normal speed. The backlog is published after Kafka is back, without manual work.
- EVT-4 Every event has a unique eventId, and consumers use it to ignore duplicates (NTF-2).
- EVT-5 Consumers ignore fields and event types that they do not know. A consumer ignores an event with a version that it does not know.

## 8. Non-functional requirements

### 8.1 Concurrency

- CON-1 Concurrent operations on the same wallet leave the same balances as some serial order of the same operations. No update is lost.
- CON-2 The balance check and the balance change of a transfer or a withdrawal are atomic. A wallet that holds 10,000 cents and receives twenty concurrent requests to send 1,000 cents completes exactly ten of them.
- CON-3 Transfers in opposite directions between the same two wallets never fail because of a deadlock. If the database aborts a transaction because of a deadlock or a serialization failure, the service repeats it, at least three times, and the client does not see that error.
- CON-4 Correctness does not depend on locks or data inside one JVM.
- CON-5 wallet-service calls the bank outside database transactions. No transaction is open during a call to the bank.
- CON-6 Constraints in the database enforce one wallet per user, one record per idempotency key and a wallet balance of zero or more.

### 8.2 Idempotency

- IDM-1 POST `/api/v1/transfers`, POST `/api/v1/deposits` and POST `/api/v1/withdrawals` require the header Idempotency-Key, a string of 1 to 64 characters that the client chooses.
- IDM-2 A key belongs to one user and one endpoint. A request that repeats a key with the same body returns the stored response of the first request, with the same status code and body and with the additional header Idempotent-Replayed: true. Nothing is executed a second time. The stored body of a deposit or a withdrawal still says PENDING after the operation is final. The client reads the current status with GET.
- IDM-3 Only successful responses (2xx) are stored. A request that was rejected or that failed has changed nothing, so a later request with the same key is executed normally.
- IDM-4 The idempotency record is written in the same database transaction as the operation. If the client does not get the response, also because the service stops after the commit, the repeated request returns the stored response.
- IDM-5 A request that repeats a stored key with a different body is answered with 422 IDEMPOTENCY_KEY_REUSED and executes nothing.
- IDM-6 While a request with the same key is still running, a second request does not execute. It waits and returns the response of the first, or it is answered with 409 REQUEST_IN_PROGRESS and Retry-After: 1.
- IDM-7 Keys are kept for at least 24 hours.

### 8.3 Bank integration

- RES-1 POST `/api/v1/deposits` and POST `/api/v1/withdrawals` answer without waiting for the bank: while the bank is slow, they answer before the bank does, and while the bank is down, they answer with 202.
- RES-2 The reference of a bank transaction is the ID of the deposit or withdrawal.
- RES-3 Every call to the bank has a response timeout (default: 3 seconds), a bounded number of retries with exponential backoff and jitter, and a circuit breaker. While the circuit is open, operations stay PENDING and are submitted later.
- RES-4 A timeout, a connection error or a 5xx response means that the outcome is unknown. It is never treated as a failure. The service finds out the outcome by repeating the call with the same reference or with GET `/bank/v1/transactions/{reference}`.
- RES-5 Only a bank transaction with the status SUCCEEDED or FAILED finishes an operation. No HTTP status of a submission finishes it, and a PENDING operation is never failed because of its age. An operation that stays PENDING is visible in GET `/api/v1/admin/status`.
- RES-6 The result of the bank is applied exactly once, whether it arrives in a webhook, in several copies of the webhook, through the reconciliation job, or through several of these at the same time.
- RES-7 A reconciliation job handles every operation that has been PENDING for more than 30 seconds, at the latest 10 seconds after that (both values are defaults). It asks the bank for the transaction. If the bank does not know the reference, the job submits the operation again.
- RES-8 If wallet-service is killed while operations are in progress and started again, every PENDING operation reaches its final status without manual work, no wallet is credited twice, and no reserved money is lost.
- RES-9 A webhook is accepted only with a valid signature and a timestamp that is at most 5 minutes old. A webhook for an operation that is already final is answered with 200 and changes nothing.

### 8.4 Security

- SEC-1 Every endpoint under `/api/v1` except registration and login requires a valid token. A service validates the signature, exp and iss of a token.
- SEC-2 The user ID always comes from the token, never from the body or a parameter of the request. The server checks for every resource that it belongs to the caller (API-4).
- SEC-3 The administration endpoints of Sections 6.4 and 6.5 require the role ADMIN.
- SEC-4 wallet-service authenticates itself to the bank with X-Api-Key. The bank authenticates its webhooks with the signature of Section 6.6.
- SEC-5 All input is validated on the server. Amounts that are negative, zero, not integers or too large are rejected, and so are oversized fields.
- SEC-6 Secrets (database passwords, the API key, the webhook secret, and the signing key if auth-service does not create it at startup) come from environment variables. None of them is committed to Git. The repository contains a file .env.example.
- SEC-7 Logs contain no passwords, tokens, API keys or signatures.
- SEC-8 The frontend inserts text that users entered (notes, display names) as text, never as HTML.
- SEC-9 POST `/api/v1/auth/login` accepts at most 5 attempts per username per minute. A further attempt is answered with 429 RATE_LIMITED and a Retry-After header. The two numbers come from configuration. The state of this limit may be kept in the memory of auth-service.

### 8.5 Operations and observability

- OPS-1 On a clean clone in which .env has been created from .env.example, `docker compose up --build` starts the whole system. The services wait for their dependencies with health checks.
- OPS-2 The database schema is created by Flyway or Liquibase migrations. Hibernate does not create or change tables.
- OPS-3 Every Spring Boot service has a liveness and a readiness endpoint. Readiness reflects the connection to the database of the service, where it has one.
- OPS-4 The logs of the Spring Boot services are structured (JSON), one event per line, with the service name. An event that is logged while a request is handled also has traceId and spanId, and the user ID when it is known. wallet-service logs the ID and the outcome of every transfer, deposit and withdrawal.
- OPS-5 Every Spring Boot service exposes metrics in the Prometheus format: request rate, error rate and latency per endpoint. wallet-service adds operations by type and result, the number and age of pending deposits and withdrawals, the outbox backlog and the age of its oldest row, calls to the bank by outcome and the state of the circuit breaker. notification-service adds the counters of NTF-5.
- OPS-6 The W3C trace context (traceparent) is passed on over HTTP, and the traces are sent to the tracing backend. One trace of a webhook contains the span of bank-simulator and the span of wallet-service.
- OPS-7 One Grafana dashboard is provisioned from files in the repository. It shows at least the request rate, the error rate, the p95 latency, operations by result, pending operations, the outbox backlog and the state of the circuit breaker.
- OPS-8 A Spring Boot service shuts down gracefully: running requests finish, and notification-service commits the offsets of the events that it has processed.
- OPS-9 Every image that is built from the repository runs as a user that is not root.

## 9. Frontend

- UI-1 The frontend is a single-page web application that web serves at /.
- UI-2 Pages for customers:
- Sign up and log in.
- Home: a button that opens the wallet if the user has none; then the balance, the reserved amount, the amount sent today next to the daily limit, the username and the wallet ID.
- Send: enter a username, look up the recipient, show the display name, enter the amount and a note, confirm, show the result.
- Add money and Withdraw: enter the amount, then show the operation with its status until the status is final.
- Activity: the statement with running balances and a "Load more" button that uses the cursor, and the lists of deposits and withdrawals with their status.
- Notifications: the unread count, the list, and the actions to mark one or all as read.
- UI-3 An administration console that only administrators see:
- Integrity: a button that calls GET `/api/v1/admin/integrity` and shows every check.
- Status: the values of GET `/api/v1/admin/status`, refreshed every 2 seconds, and a button for POST `/api/v1/admin/reconciliation`.
- Wallets: all wallets with their balances, refreshed every 2 seconds.
- Bank: a form for the failure settings and one button per preset: Normal (defaults), Slow (responseDelayMs 2000, settleDelayMs 5000), Down (down true), Hang (hangRate 1, webhookCopies 0), Decline (declineRate 1), Duplicate webhooks (webhookCopies 3), No webhooks (webhookCopies 0).
- Diagnostics: "Repeat the last money request with the same key" and "Send the last money request 10 times in parallel with the same key", each with the status codes of the responses.
- Links to the Swagger UI of every service, to Grafana and to the tracing backend.
- UI-4 Every user action that moves money creates a new Idempotency-Key. A retry of the same action, by the page or by the user after a network error or a 409 REQUEST_IN_PROGRESS, sends the same key again.
- UI-5 The status of a pending deposit or withdrawal and new notifications appear within 5 seconds without a reload. Polling is sufficient.
- UI-6 An error with a code of Section 6 is shown with a message that the frontend chooses by the code. Business rules are enforced only by the backend. The frontend checks at most the format of the input.
- UI-7 The access token is kept in memory or in sessionStorage. A 401 response leads back to the login page.

## 10. Technology and constraints

| Area | Required |
| --- | --- |
| Language and framework | Java 21, Spring Boot 3.4 or later, Maven or Gradle |
| Persistence | PostgreSQL 16 or later; Spring Data JPA or Spring JDBC; Flyway or Liquibase |
| Messaging | Apache Kafka with Spring for Apache Kafka |
| Security | Spring Security in auth-service, wallet-service and notification-service; the latter two are OAuth 2.0 resource servers |
| Resilience | Resilience4j |
| API documentation | springdoc-openapi |
| Observability | Spring Boot Actuator, Micrometer, Prometheus, Grafana, OpenTelemetry tracing |
| Tests | JUnit 5; Testcontainers for PostgreSQL and Kafka |
| Delivery | Docker, Docker Compose |
- TEC-1 Consistency between the services is reached with local database transactions, the outbox and idempotent operations. Distributed transaction technology (XA, JTA or Seata) is not used.
- TEC-2 The system consists of the services and the infrastructure containers of Section 3. It contains no service registry, no configuration server and no second message broker.

## 11. Acceptance tests

### 11.1 Baseline level

- ACC-1 The scenarios in the following table succeed on a system that was started as described in OPS-1. Scenarios B2 to B6 are performed through the frontend.

| # | Scenario | Expected result |
| --- | --- | --- |
| B1 | The system is started | Every container becomes healthy without a further manual step |
| B2 | Alice and Bob sign up, log in and open their wallets | Each of them has one wallet with a balance of $0.00 |
| B3 | Alice adds $100.00 | The deposit is PENDING and then SUCCEEDED; her balance is $100.00; her statement shows a DEPOSIT entry; she has one notification |
| B4 | Alice looks up Bob by his username and sends him $12.50 with a note | Her balance is $87.50 and his is $12.50; both statements show the transfer with the note and the balance after it; each of them has a new notification |
| B5 | Alice withdraws $20.00 | The withdrawal is PENDING and $20.00 is reserved, then it is SUCCEEDED; her balance is $67.50; she has two new notifications |
| B6 | Alice sends more than her balance, sends money to her own username, and looks up a username that does not exist | The messages for INSUFFICIENT_FUNDS, SAME_WALLET and WALLET_NOT_FOUND are shown; no balance changes |
| B7 | GET `/api/v1/wallets/me` is called without a token, and Bob requests the deposit of Alice by its ID | 401 UNAUTHENTICATED and 404 NOT_FOUND |

### 11.2 Full level

- ACC-2 Every scenario in the following table is an automated test. The tests run against PostgreSQL and Kafka, not against in-memory substitutes.

| # | Scenario | Expected result | Requirements |
| --- | --- | --- | --- |
| 1 | 32 threads send 2,000 random transfers among 10 wallets, including transfers in opposite directions between the same two wallets | No request fails for a technical reason; INV-1 to INV-6 hold | CON-1, CON-3 |
| 2 | A wallet holds 10,000 cents and receives 20 concurrent transfers of 1,000 cents | 10 complete, 10 are rejected with INSUFFICIENT_FUNDS, the balance is 0 | CON-2 |
| 3 | The same transfer with the same key is sent 10 times at the same moment | One transfer exists; every response is the stored response or REQUEST_IN_PROGRESS | IDM-2, IDM-6 |
| 4 | A stored key is sent again with a different amount | 422 IDEMPOTENCY_KEY_REUSED; one transfer exists | IDM-5 |
| 5 | The response of a completed transfer is lost and the client repeats the request | The stored response with Idempotent-Replayed: true; one transfer exists | IDM-4 |
| 6 | Transfers that together exceed the daily limit, sent one after another and concurrently | The amount sent never exceeds the limit; the other transfers are rejected with DAILY_LIMIT_EXCEEDED | MNY-3 |
| 7 | User A requests a transfer between two other users, and a deposit, a withdrawal and a notification of user B | 404 NOT_FOUND every time | API-4 |
| 8 | A statement is read page by page while new entries are posted | No entry appears twice and no earlier entry is missing | API-5 |
| 9 | The bank sends the webhook of a succeeded deposit three times | The wallet is credited once | RES-6, DEP-3 |
| 10 | A webhook arrives with a wrong signature, and one with a timestamp that is 10 minutes old | 401 INVALID_SIGNATURE; nothing changes | RES-9 |
| 11 | The bank creates a payout, holds back the response and sends no webhook (hangRate 1, webhookCopies 0) | No money is released after the timeout; the withdrawal becomes SUCCEEDED through the reconciliation job | RES-4, RES-7 |
| 12 | A webhook and the reconciliation job apply the same result at the same moment | The result is applied once | RES-6 |
| 13 | Three withdrawals are requested while the bank is down, then the bank returns | Every request is answered with 202; all three withdrawals become final | RES-1, RES-3 |
| 14 | The bank declines a payout | The withdrawal is FAILED, the reserved amount is back in the wallet, the statement shows WITHDRAWAL_REVERSAL, and sentToday no longer includes the amount | WDR-3, MNY-3 |
| 15 | Transfers complete while Kafka is unavailable, then Kafka returns | The transfers succeed; afterwards the event of every transfer is on the topic | EVT-1, EVT-3 |
| 16 | The same event is delivered twice | One notification per recipient | NTF-2 |
| 17 | A message that cannot be parsed is on the topic, followed by valid events | The message is in wallet.events.dlt; the valid events are processed | NTF-4 |
