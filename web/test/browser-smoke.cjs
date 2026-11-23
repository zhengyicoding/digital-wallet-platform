// UI-1/2/4..7, SEC-8; ACC-1 B2–B7 through the Compose edge.
// Requires an installed playwright-core and Chrome; see web/README.md.
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH || 'playwright-core');
const assert = require('node:assert/strict');
const base = process.env.WALLET_URL || 'http://localhost:8080';
const suffix = Date.now().toString(36);
const users = { alice: `alice_${suffix}`, bob: `bob_${suffix}` };
const password = 'm8-browser-password';
const note = 'Lunch <b>for two</b>';
let browser;
const evidence = [];
function record(message) { evidence.push(message); console.log(`PASS ${message}`); }
async function text(page, selector, expected) {
  await page.waitForFunction(({ selector, expected }) => document.querySelector(selector)?.textContent.includes(expected), { selector, expected });
}
async function nav(page, name) {
  if (name === 'Notifications') await page.locator('nav [data-view="notifications"]').click();
  else await page.locator('nav').getByRole('button', { name, exact: true }).click();
}
async function signup(page, username, displayName) {
  await page.goto(base);
  await page.getByRole('button', { name: 'New here? Sign up' }).click();
  await page.getByLabel('Username', { exact: true }).fill(username);
  await page.getByLabel('Display name').fill(displayName);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign up', exact: true }).click();
  await text(page, '#auth-notice', 'Account created');
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in', exact: true }).click();
  await page.getByRole('button', { name: 'Open wallet', exact: true }).click();
  await text(page, '#balance', '$0.00');
}
async function login(page, username) {
  await page.getByLabel('Username', { exact: true }).fill(username);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in', exact: true }).click();
  await page.locator('#balance').waitFor();
}
async function review(page, amount) {
  await page.getByLabel('Amount (USD)').fill(amount);
  await page.getByRole('button', { name: 'Review payment' }).click();
  await page.getByRole('button', { name: 'Confirm payment' }).click();
}
async function recipient(page, username) {
  await page.getByLabel('Recipient username').fill(username);
  await page.getByRole('button', { name: 'Look up recipient' }).click();
}
async function notificationCount(page, count) {
  await page.waitForFunction(count => document.querySelector('nav .badge')?.textContent === String(count), count, { timeout: 5000 });
}
async function walletToken(page) {
  const token = await page.evaluate(() => sessionStorage.getItem('wallet.accessToken'));
  assert.ok(token, 'Expected a token from the browser login');
  return { Authorization: `Bearer ${token}` };
}
async function assertProblem(response, status, code) {
  assert.equal(response.status(), status);
  assert.match(response.headers()['content-type'], /^application\/problem\+json\b/);
  assert.equal((await response.json()).code, code);
}
async function run() {
  browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true });
  const aliceContext = await browser.newContext();
  const bobContext = await browser.newContext();
  const alice = await aliceContext.newPage(), bob = await bobContext.newPage();
  alice.setDefaultTimeout(15000); bob.setDefaultTimeout(15000);
  const pageErrors = [];
  for (const page of [alice, bob]) {
    page.on('pageerror', error => pageErrors.push(error.message));
    page.on('requestfailed', request => console.log('Request failure:', request.method(), new URL(request.url()).pathname, request.failure()?.errorText));
  }
  let originalConfig;
  try {
    const configResponse = await aliceContext.request.get(`${base}/bank/admin/config`);
    assert.ok(configResponse.ok());
    originalConfig = await configResponse.json();
    // A two-second settlement makes pending and reserve observable without changing customer APIs.
    assert.ok((await aliceContext.request.put(`${base}/bank/admin/config`, { data: { settleDelayMs: 2000 } })).ok());
    await signup(alice, users.alice, 'Alice Chen');
    await signup(bob, users.bob, 'Bob Lee');
    const aliceWallet = await alice.locator('.identity span').last().textContent();
    assert.notEqual(await bob.locator('.identity span').last().textContent(), aliceWallet);
    record('ACC-1 B2: Alice and Bob sign up, log in and open distinct zero-balance wallets');

    await nav(alice, 'Add money');
    const depositResponse = alice.waitForResponse(response =>
      new URL(response.url()).pathname === '/api/v1/deposits' && response.request().method() === 'POST');
    await review(alice, '100.00');
    const acceptedDeposit = await depositResponse;
    assert.equal(acceptedDeposit.status(), 202);
    const deposit = await acceptedDeposit.json();
    assert.equal(deposit.status, 'PENDING');
    assert.equal(deposit.amount, 10000);
    assert.ok(deposit.id);
    await text(alice, '#payment-status', 'PENDING');
    const depositPendingAt = Date.now();
    await text(alice, '#payment-status', 'SUCCEEDED');
    assert.ok(Date.now() - depositPendingAt < 7000, 'Deposit refresh exceeded 2s settlement + 5s UI allowance');
    await notificationCount(alice, 1);
    await nav(alice, 'Home'); await text(alice, '#balance', '$100.00');
    await nav(alice, 'Activity'); await text(alice, '#entries-rows .row', 'DEPOSIT');
    await text(alice, '#entries-rows .row', 'Balance after: $100.00');
    await text(alice, '#entries-rows .row', `Reference: ${deposit.id}`);
    await nav(alice, 'Notifications');
    await text(alice, '#notifications-rows', 'Your deposit of $100.00 is complete');
    record('ACC-1 B3 / UI-5: $100 deposit PENDING → SUCCEEDED, $100 balance, DEPOSIT statement and one notification');

    await nav(alice, 'Send');
    // Latest accepted action is retained until the customer explicitly starts another action.
    await alice.getByRole('button', { name: 'New payment', exact: true }).click();
    await recipient(alice, users.bob);
    await text(alice, '#recipient-name', 'Bob Lee');
    await alice.getByLabel('Note (optional)').fill(note);
    await review(alice, '12.50');
    await text(alice, '#payment-status', 'COMPLETED');
    await nav(alice, 'Home'); await text(alice, '#balance', '$87.50');
    await text(bob, '#balance', '$12.50');
    await notificationCount(alice, 2); await notificationCount(bob, 1);
    for (const [page, type, amount, balance, notification] of [
      [alice, 'TRANSFER_OUT', '-$12.50', '$87.50', 'You sent $12.50 to Bob Lee'],
      [bob, 'TRANSFER_IN', '+$12.50', '$12.50', 'You received $12.50 from Alice Chen']
    ]) {
      await nav(page, 'Activity');
      const row = page.locator('#entries-rows .row').filter({ hasText: type });
      await row.getByText(note, { exact: true }).waitFor();
      await row.getByText(amount, { exact: true }).waitFor();
      await row.getByText(`Balance after: ${balance}`, { exact: true }).waitFor();
      assert.equal(await row.locator('b').count(), 0);
      await nav(page, 'Notifications');
      await text(page, '#notifications-rows', notification);
    }
    record('ACC-1 B4 / SEC-8: recipient display name, $12.50 transfer, both running balances, literal markup note and both notifications');

    await nav(alice, 'Withdraw');
    await alice.getByRole('button', { name: 'New payment', exact: true }).click();
    await review(alice, '20');
    await text(alice, '#payment-status', 'PENDING');
    const withdrawalPendingAt = Date.now();
    await nav(alice, 'Home'); await text(alice, '#reserved', '$20.00');
    await text(alice, '#balance', '$67.50');
    await text(alice, '#daily', '$32.50 / $5,000.00');
    await nav(alice, 'Withdraw'); await text(alice, '#payment-status', 'SUCCEEDED');
    assert.ok(Date.now() - withdrawalPendingAt < 7000, 'Withdrawal refresh exceeded 2s settlement + 5s UI allowance');
    await nav(alice, 'Home'); await text(alice, '#reserved', '$0.00');
    await notificationCount(alice, 4);
    await nav(alice, 'Activity');
    const withdrawalEntry = alice.locator('#entries-rows .row').filter({ hasText: 'WITHDRAWAL' });
    await withdrawalEntry.getByText('-$20.00', { exact: true }).waitFor();
    await withdrawalEntry.getByText('Balance after: $67.50', { exact: true }).waitFor();
    await text(alice, '#withdrawals-rows', 'SUCCEEDED');
    await nav(alice, 'Notifications');
    await text(alice, '#notifications-rows', 'Your withdrawal of $20.00 is in progress');
    await text(alice, '#notifications-rows', 'Your withdrawal of $20.00 is complete');
    record('ACC-1 B5 / UI-5: $20 withdrawal reserve observed, PENDING → SUCCEEDED, $67.50 available, $0 reserved, daily spend $32.50 and two new notifications');

    for (const [username, amount, message] of [
      [users.bob, '100', 'Your wallet has insufficient funds for this payment. (INSUFFICIENT_FUNDS)'],
      [users.alice, '1', 'You cannot send money to your own wallet. Choose another recipient. (SAME_WALLET)']
    ]) {
      await nav(alice, 'Send');
      await alice.getByRole('button', { name: 'New payment', exact: true }).click();
      await recipient(alice, username); await alice.locator('#recipient-name').waitFor({ state: 'visible' });
      await review(alice, amount); await text(alice, '#money-card', message);
    }
    await alice.getByRole('button', { name: 'New payment', exact: true }).click();
    await recipient(alice, `none_${suffix}`);
    await text(alice, '#money-error', 'No wallet was found for this username. The user may need to open a wallet. (WALLET_NOT_FOUND)');
    for (const [page, balance] of [[alice, '$67.50'], [bob, '$12.50']]) {
      await page.reload();
      await page.locator('#balance').getByText(balance, { exact: true }).waitFor();
    }
    record('ACC-1 B6 / UI-6: INSUFFICIENT_FUNDS, SAME_WALLET and WALLET_NOT_FOUND shown; both balances unchanged');

    const walletUrl = `${base}/api/v1/wallets/me`;
    await assertProblem(await bobContext.request.get(walletUrl), 401, 'UNAUTHENTICATED');
    const bobHeaders = await walletToken(bob);
    const ownWallet = await bobContext.request.get(walletUrl, { headers: bobHeaders });
    assert.equal(ownWallet.status(), 200);
    assert.equal((await ownWallet.json()).username, users.bob);
    const depositUrl = `${base}/api/v1/deposits/${deposit.id}`;
    const ownerDeposit = await aliceContext.request.get(depositUrl, { headers: await walletToken(alice) });
    assert.equal(ownerDeposit.status(), 200);
    assert.equal((await ownerDeposit.json()).status, 'SUCCEEDED');
    await assertProblem(await bobContext.request.get(depositUrl, { headers: bobHeaders }), 404, 'NOT_FOUND');
    record(`ACC-1 B7 / API-4: missing token returns 401 UNAUTHENTICATED; Bob receives 404 NOT_FOUND for Alice's B3 deposit ${deposit.id}`);

    await nav(alice, 'Notifications');
    await text(alice, '#notifications-rows', 'Your withdrawal of $20.00 is complete');
    await alice.getByRole('button', { name: 'Mark as read', exact: true }).first().click();
    await notificationCount(alice, 3);
    await alice.getByRole('button', { name: 'Mark all as read' }).click();
    await notificationCount(alice, 0);
    await alice.getByRole('button', { name: 'Show unread only' }).click();
    await text(alice, '#notifications-rows', 'Nothing here yet.');
    record('UI-2 / NTF-3: notification list, unread count, mark one, mark all and unread filter');

    // UI-4: first response lost AFTER the real server commits; second attempt returns controlled 409.
    const attempts = [];
    let replayed = false;
    await alice.route('**/api/v1/deposits', async route => {
      if (route.request().method() !== 'POST') return route.continue();
      attempts.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() });
      if (attempts.length === 1) { const response = await route.fetch(); assert.equal(response.status(), 202); await route.abort('failed'); }
      else if (attempts.length === 2) await route.fulfill({ status: 409, contentType: 'application/problem+json', headers: { 'Retry-After': '1' }, body: JSON.stringify({ code: 'REQUEST_IN_PROGRESS' }) });
      else {
        const response = await route.fetch();
        replayed = response.headers()['idempotent-replayed'] === 'true';
        await route.fulfill({ response });
      }
    });
    await nav(alice, 'Add money'); await review(alice, '0.29');
    await text(alice, '#money-card', 'NETWORK_ERROR');
    assert.equal(await alice.locator('#amount').count(), 0);
    await alice.getByRole('button', { name: 'Retry original payment' }).click();
    await text(alice, '#money-card', 'REQUEST_IN_PROGRESS');
    await alice.reload();
    await alice.getByRole('button', { name: 'View payment' }).click();
    await alice.getByRole('button', { name: 'Retry original payment' }).click();
    await text(alice, '#payment-status', 'SUCCEEDED');
    assert.equal(attempts.length, 3); assert.deepEqual(attempts[0], attempts[1]); assert.deepEqual(attempts[1], attempts[2]);
    assert.equal(attempts[0].body, '{"amount":29}'); assert.ok(replayed);
    await alice.getByRole('button', { name: 'New payment', exact: true }).click();
    await review(alice, '0.01'); await text(alice, '#payment-status', 'SUCCEEDED');
    assert.notEqual(attempts[3].key, attempts[2].key);
    await nav(alice, 'Home'); await text(alice, '#balance', '$67.80');
    await alice.unroute('**/api/v1/deposits');
    record('UI-4 / IDM-2: committed deposit response lost, 409 retry, reload and successful replay reuse exact original key/body; subsequent one-cent action uses a fresh key; credited once');

    // Real cursor pagination: add enough one-cent deposits through the actual customer UI.
    for (let i = 0; i < 18; i++) {
      await nav(alice, 'Add money'); await alice.getByRole('button', { name: 'New payment', exact: true }).click();
      await review(alice, '0.01'); await text(alice, '#payment-status', 'SUCCEEDED');
    }
    const cursorRequests = [];
    alice.on('request', request => { if (request.url().includes('/wallets/me/entries?') && request.url().includes('cursor=')) cursorRequests.push(request.url()); });
    await nav(alice, 'Activity');
    await alice.locator('[data-more="entries"]').waitFor({ state: 'visible' });
    assert.equal(await alice.locator('#entries-rows .row').count(), 20);
    const initialIds = await alice.locator('#entries-rows .row').evaluateAll(rows => rows.map(row => row.dataset.id));
    await alice.locator('[data-more="entries"]').click();
    await alice.waitForFunction(() => document.querySelectorAll('#entries-rows .row').length > 20);
    const allIds = await alice.locator('#entries-rows .row').evaluateAll(rows => rows.map(row => row.dataset.id));
    assert.equal(new Set(allIds).size, allIds.length); assert.deepEqual(allIds.slice(0, 20), initialIds); assert.ok(cursorRequests.length);
    await alice.getByLabel('Entry type').selectOption('TRANSFER_OUT');
    await text(alice, '#entries-rows', note);
    assert.equal(await alice.locator('#entries-rows .row').count(), 1);
    record('UI-2 / API-5: real cursor Load more appends older statement rows without duplicates; entry-type filter works');

    // Read-only OpenAPI routes must continue to reach their respective services.
    for (const service of ['auth', 'wallet', 'notifications', 'bank']) {
      const response = await aliceContext.request.get(`${base}/docs/${service}/v3/api-docs`);
      assert.equal(response.status(), 200); assert.ok((await response.json()).openapi);
      assert.equal((await aliceContext.request.get(`${base}/docs/${service}/`)).status(), 200);
    }
    record('UI-1 / API-8: root SPA, all four OpenAPI JSON documents and Swagger UI routes remain available');

    // SEC-8: controlled display-name fixture is rendered by the same profile path as real names.
    await bob.route('**/api/v1/auth/me', async route => {
      const response = await route.fetch(); const data = await response.json();
      await route.fulfill({ response, json: { ...data, displayName: '<img src=x onerror="window.injected=1">' } });
    });
    await bob.reload(); await text(bob, '#greeting', '<img src=x');
    assert.equal(await bob.locator('#greeting img').count(), 0);
    assert.equal(await bob.evaluate(() => window.injected), undefined);
    await bob.unroute('**/api/v1/auth/me');
    record('SEC-8: controlled markup display name renders literally without creating an element or executing code');

    await alice.setViewportSize({ width: 390, height: 844 });
    await nav(alice, 'Home');
    assert.equal(await alice.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    if (process.env.WALLET_SCREENSHOT) await alice.screenshot({ path: process.env.WALLET_SCREENSHOT, fullPage: true });
    record('UI-2: customer home fits a 390px mobile viewport');

    // Controlled authenticated 401 verifies immediate session cleanup, including pending timers.
    await alice.route('**/api/v1/wallets/me', route => route.fulfill({ status: 401, contentType: 'application/problem+json', body: '{"code":"UNAUTHENTICATED"}' }));
    await alice.getByRole('button', { name: 'Log in', exact: true }).waitFor();
    assert.equal(await alice.evaluate(() => sessionStorage.getItem('wallet.accessToken')), null);
    assert.equal(await alice.evaluate(() => sessionStorage.getItem('wallet.moneyAction')), null);
    await alice.unroute('**/api/v1/wallets/me');
    await alice.getByLabel('Username', { exact: true }).fill(users.alice);
    await alice.getByLabel('Password', { exact: true }).fill('incorrect-password');
    await alice.getByRole('button', { name: 'Log in', exact: true }).click();
    await text(alice, '#auth-error', 'INVALID_CREDENTIALS');
    record('UI-7: authenticated 401 returns to login and clears token/action; real failed login displays INVALID_CREDENTIALS');
    await login(alice, users.alice);
    let activePolls = 0, maxActivePolls = 0, slowPolls = 0;
    await alice.route('**/api/v1/notifications/unread-count', async route => {
      activePolls++; slowPolls++; maxActivePolls = Math.max(maxActivePolls, activePolls);
      try {
        const response = await route.fetch();
        await new Promise(resolve => setTimeout(resolve, 2500));
        await route.fulfill({ response });
      } finally { activePolls--; }
    });
    await alice.waitForTimeout(8000);
    assert.ok(slowPolls >= 2); assert.equal(maxActivePolls, 1);
    await alice.unrouteAll({ behavior: 'wait' });
    record('UI-5: delayed notification-count responses do not overlap refresh polls');
    await alice.getByRole('button', { name: 'Log out', exact: true }).click();
    let afterLogout = 0;
    alice.on('request', request => { if (request.url().includes('/api/v1/')) afterLogout++; });
    await alice.waitForTimeout(5200);
    assert.equal(afterLogout, 0);
    assert.equal(await alice.evaluate(() => sessionStorage.getItem('wallet.accessToken')), null);
    assert.deepEqual(pageErrors, []);
    record('UI-5/7: logout stops polling for more than five seconds; no browser JavaScript errors');
    console.log(JSON.stringify({ users, scenarios: evidence.length, evidence }, null, 2));
  } catch (error) {
    console.error(error);
    console.error('Alice page:', await alice.locator('body').innerText());
    console.error('Bob page:', await bob.locator('body').innerText());
    console.error('Browser errors:', pageErrors);
    throw error;
  } finally {
    if (originalConfig) await aliceContext.request.put(`${base}/bank/admin/config`, { data: originalConfig });
    await browser.close();
  }
}
run().catch(async error => { console.error(error); if (browser) await browser.close(); process.exitCode = 1; });
