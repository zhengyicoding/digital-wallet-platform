# Digital Wallet Platform

Spring Boot microservices: auth-service, wallet-service, bank-simulator, notification-service, web (static SPA + nginx
edge proxy).

- Source of truth: @docs/SPEC.md. Cite requirement IDs (e.g. WDR-3, RES-6) in tests, commit messages and PR text.
- Build order and progress: docs/PLAN.md. Tick milestones off when done; do not skip ahead.
- Ask before deviating from the spec. Field names in the spec are binding (API-9).

## Stack

Java 21, Spring Boot 3.4+, Maven (one parent POM, one module per service), PostgreSQL 16, Kafka, Flyway,
Spring Data JPA (or JDBC for the ledger hot path), Resilience4j, springdoc-openapi, Micrometer + Prometheus,
OpenTelemetry tracing, Testcontainers (Postgres + Kafka). No H2, no embedded Kafka.

## Layout

```
auth-service/  wallet-service/  bank-simulator/  notification-service/  web/
infra/         (prometheus, grafana provisioning, tracing backend)
docker-compose.yml  .env.example  docs/
```

Package by feature inside each service, e.g.
`com.acme.wallet.{ledger,transfer,deposit,withdrawal,bank,outbox,idempotency,admin}`.

## Commands

- Build + test all: `./mvnw -q verify`
- One service: `./mvnw -q -pl wallet-service -am verify`
- Single test: `./mvnw -q -pl wallet-service test -Dtest=TransferConcurrencyIT`
- Whole system: `cp .env.example .env && docker compose up --build`

## Money rules (never violate)

- Amounts are `long` cents everywhere (API, events, DB). Never `double`/`float`; no BigDecimal in the API.
- Ledger is append-only: no UPDATE/DELETE on ledger entries (LED-1). Every posting sums to zero (LED-3).
- Balance changes and `balanceAfter` are written in the same transaction as the entries.
- Never call the bank (or any HTTP) inside a DB transaction (CON-5).
- Lock wallets in a deterministic order (by wallet ID) for transfers; retry on deadlock/serialization failure at least 3
  times (CON-3).
- Correctness must not depend on JVM locks or in-memory state (CON-4). Use DB constraints + row locks.
- Applying a bank result: lock the operation row, check it is still PENDING, post entries, set final status, all in one
  transaction. Webhook, reconciliation and duplicates all go through this single code path (RES-6).
- A bank timeout/5xx/connection error means outcome UNKNOWN, never FAILED (RES-4/5).
- Events go to the outbox table in the same transaction as the change; a relay publishes to Kafka (EVT-1).
- Idempotency record is written in the same transaction as the operation (IDM-4).

## Conventions

- Hibernate `ddl-auto=none`; all schema via Flyway. Never edit an applied migration.
- Constructor injection only. DTOs are records. Never expose JPA entities.
- Errors: RFC 9457 `application/problem+json` with `code` and `traceId`; one `@RestControllerAdvice` per service.
- User ID comes from the JWT `sub` only, never from the request (SEC-2). Other users' resources return 404 (API-4).
- Secrets only from env vars; never log passwords, tokens, API keys or signatures (SEC-6/7).
- Tests: JUnit 5 + Testcontainers. Concurrency tests assert INV-1..INV-6 afterwards.

## Workflow

- Work one milestone at a time. Write tests with the code, run `./mvnw -q verify`, fix failures, then commit.
- Commit per vertical slice with the requirement IDs covered.
