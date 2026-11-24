# Digital Wallet Platform

A local USD wallet with a customer SPA and an admin console. Transfers post immediately to a double-entry ledger. Deposits and withdrawals settle asynchronously through the bank simulator. Kafka carries committed wallet events to the notification service.

## Start the platform

From the repository root, use a running Docker Engine with the Compose plugin and access to pull images and Maven dependencies on the first build. Compose builds the Java services and serves the static frontend through nginx. For the default configuration, host ports 8080, 9090, 3000 and 3200 must be available. PostgreSQL and Kafka are reachable only inside the Compose network, so a local PostgreSQL on 5432 or broker on 9092 does not conflict.

```sh
cp .env.example .env
docker compose up --build
```

In a second terminal, run `docker compose ps`. All ten services should show `healthy`. Open the SPA, sign up, log in and choose **Open wallet**. To use the admin console, sign in with the bootstrap `ADMIN_USERNAME` and `ADMIN_PASSWORD` from the new stack's `.env`. The example values are for local development.

| Open | Default URL |
| --- | --- |
| Customer SPA and admin console | [Wallet](http://localhost:8080/) · [Admin console](http://localhost:8080/admin) |
| Swagger UIs through nginx | [Auth](http://localhost:8080/docs/auth/) · [Wallet](http://localhost:8080/docs/wallet/) · [Bank](http://localhost:8080/docs/bank/) · [Notifications](http://localhost:8080/docs/notifications/) |
| Prometheus | [Query UI](http://localhost:9090/) |
| Grafana | [Wallet dashboard](http://localhost:3000/d/digital-wallet) · [Recent traces and span viewer](http://localhost:3000/d/wallet-traces/digital-wallet-traces) |
| Tempo | [Readiness](http://localhost:3200/ready) |

Use `docker compose stop` in the second terminal to retain the containers and their local storage, including the PostgreSQL volume. Resume them with `docker compose start`. The bank simulator keeps its transaction records in process memory.

## Run the tests

The backend suite requires JDK 21 and a Docker daemon accessible to Testcontainers. The included wrapper supplies Maven. The frontend unit suite requires Node.js and npm. Use Node.js 20.6 or later for the browser commands below.

```sh
./mvnw verify -fae
npm --prefix web test
```

Maven writes JUnit XML to each Java service's `target/surefire-reports/`. The ACC-2 tests start their own PostgreSQL 16 and Kafka containers. The [M14 sign-off](docs/PLAN.md#m14-final-verification) maps the scenarios to individual methods.

### Customer and admin browser suites

Run the two suites in sequence against a fresh local test stack. They create users, wallets and transactions and temporarily change the simulator settings. The customer suite covers ACC-1 B2–B6 in the SPA and B7 over HTTP through nginx.

Use a new clone with a fresh `.env` and start it with the default command above (`docker compose up --build --wait` waits until all ten services are healthy). Both scripts drive the installed Chrome through the pinned dev dependency `playwright-core` 1.50.1, which never downloads browsers. Set `CHROME_PATH` if Chrome is not at the macOS default. From the repository root, in one shell:

```sh
npm --prefix web ci --ignore-scripts
export WALLET_URL=http://localhost:8080
npm --prefix web run test:browser
set -a; . ./.env; set +a
npm --prefix web run test:admin-browser
docker compose stop
```

To run beside an existing default stack instead, use a separate Compose project with the repository's port override (requires Compose 2.24.4 or later). It publishes nginx on port 19083 and Grafana on 19303 on loopback:

```sh
export COMPOSE_PROJECT_NAME=wallet-browser-check
export COMPOSE_FILE=docker-compose.yml:web/test/compose-admin.yml
docker compose up --build --wait
export WALLET_URL=http://127.0.0.1:19083
```

The [web test notes](web/README.md) describe the regression checks and the customer suite's controlled browser fixtures.
