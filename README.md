# Digital Wallet Platform

A local USD wallet with a customer SPA and an admin console. Transfers post immediately to a double-entry ledger. Deposits and withdrawals settle asynchronously through the bank simulator. Kafka carries committed wallet events to the notification service.

## Start the platform

From the repository root, use a running Docker Engine with the Compose plugin and access to pull images and Maven dependencies on the first build. Compose builds the Java services and serves the static frontend through nginx. For the default configuration, host ports 8080, 5432, 9092, 9090, 3000 and 3200 must be available.

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
./mvnw -q verify
npm --prefix web test
```

Maven writes JUnit XML to each Java service's `target/surefire-reports/`. The ACC-2 tests start their own PostgreSQL 16 and Kafka containers. The [M14 sign-off](docs/PLAN.md#m14-final-verification) maps the scenarios to individual methods.

### Customer and admin browser suites

Run the two suites in sequence against a fresh local test stack. They create users, wallets and transactions and temporarily change the simulator settings. The customer suite covers ACC-1 B2–B6 in the SPA and B7 over HTTP through nginx.

Use a new clone with a fresh `.env` as above. For a separate Compose project alongside the default stack, the repository includes a port override (requires Compose 2.24.4 or later). Run these commands in one shell so subsequent Compose commands address the same project.

```sh
export COMPOSE_PROJECT_NAME=wallet-browser-check
export COMPOSE_FILE=docker-compose.yml:web/test/compose-admin.yml
docker compose up --build --wait
export WALLET_URL=http://127.0.0.1:19083
```

This override publishes nginx on port 19083 and Grafana on 19303 on loopback. PostgreSQL, Kafka, Prometheus and Tempo are reachable inside the project network. To test the default stack instead, set `WALLET_URL=http://localhost:8080` in the test shell.

Both scripts use Chrome and `playwright-core`. Point `PLAYWRIGHT_CORE_PATH` at an installed copy, or install it outside the checkout:

```sh
browser_deps=$(mktemp -d)
npm install --prefix "$browser_deps" playwright-core@1.50.1
export PLAYWRIGHT_CORE_PATH="$browser_deps/node_modules/playwright-core"
```

Set `CHROME_PATH` to your Chrome executable if it differs from the macOS default shown here. From the repository root, with the test stack healthy, run:

```sh
export CHROME_PATH='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
node web/test/browser-smoke.cjs
node --env-file=.env web/test/admin-browser.cjs
docker compose stop
```

The [web test notes](web/README.md) describe the regression checks and the customer suite's controlled browser fixtures.
