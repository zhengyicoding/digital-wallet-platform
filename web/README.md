# Customer web application (M8)

The buildless SPA in `public/` runs at `http://localhost:8080/` in the existing nginx container.
All requests use the same origin and the existing `/api/v1/**` routes. No frontend secrets or
external CDN dependencies are needed. The administration console belongs to M13.

`core.mjs` contains exact decimal-to-cents conversion, the session-scoped API client, immutable
money requests and the single-flight refresh scheduler. `app.mjs` renders customer screens.
User-entered names and notes use `textContent` (SEC-8). The access token and latest money action
are in `sessionStorage`; logout and authenticated 401 responses clear both (UI-7).

Each confirmed new payment generates a UUID key and serializes the body once (UI-4). An uncertain
outcome offers **Retry original payment**, retaining those bytes across navigation and reload.
Business rejection permits **New payment**. An accepted bank operation uses GET detail polling
for current status; a replayed POST remains the original PENDING snapshot (IDM-2). A shared refresh
loop runs two seconds after its previous completion, aborts requests on logout, and discovers
pending operations on sign-in. The frontend leaves amount limits, available funds, daily limits
and self-send enforcement to backend error codes (UI-6).

Run the dependency-free unit checks with Node 20 or later:

```sh
npm --prefix web test
./mvnw -q verify
```

The optional real-browser smoke script needs an installed `playwright-core` (1.50.1 was used)
and Chrome. Start Compose first, then run:

```sh
PLAYWRIGHT_CORE_PATH=/absolute/path/to/playwright-core \
CHROME_PATH='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
node web/test/browser-smoke.cjs
```

`WALLET_URL` defaults to `http://localhost:8080`; `WALLET_SCREENSHOT` optionally saves a mobile
home screenshot. The script creates unique Alice/Bob accounts and real customer transactions.
It temporarily sets the bank settlement delay to two seconds to observe PENDING and reserved
funds, then restores the original configuration in `finally`. It never resets bank transactions.
Run it against a development stack with no concurrent bank configuration changes.

ACC-1 B2–B6 use real services through the customer UI. B7 sends real requests through nginx,
checking unauthenticated wallet access and Bob's access to the deposit Alice created in B3.
For B1, start a fresh Compose project using `.env.example` and verify that all ten containers
report healthy before running the browser script. Additional checks cover cursor pagination,
notification actions, exact cents, lost-response and REQUEST_IN_PROGRESS retries, fresh keys,
reload persistence, mobile layout, OpenAPI routes, failed login and logout. Two controlled
browser response fixtures cover a markup display name and an authenticated 401; they are reported
separately from real backend behavior.
