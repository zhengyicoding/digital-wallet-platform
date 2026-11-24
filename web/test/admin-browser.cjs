// UI-3 / ADM-1..4 / IDM-2 / SEC-3. Run only against the isolated test project.
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_CORE_PATH || 'playwright-core');
const base = process.env.WALLET_URL || 'http://127.0.0.1:19083';
const admin = process.env.ADMIN_USERNAME;
const password = process.env.ADMIN_PASSWORD;
assert.ok(admin && password, 'Supply the test stack bootstrap credentials');
const defaults = {down:false, responseDelayMs:50, hangRate:0, settleDelayMs:500, declineRate:0, webhookCopies:1};
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const button = (page, name) => page.getByRole('button', {name, exact:true});
async function login(page, username, secret) {
  await page.getByLabel('Username', {exact:true}).fill(username);
  await page.getByLabel('Password', {exact:true}).fill(secret);
  await button(page, 'Log in').click();
  await page.locator('#greeting').waitFor();
}
async function run(browser) {
  const page = await browser.newPage();
  const api = page.request;
  async function json(path, options = {}) {
    const response = await api.fetch(base + path, options);
    assert.ok(response.ok(), path + ' returned ' + response.status());
    return response.json();
  }
  const configPath = '/bank/admin/config';
  const savedConfig = await json(configPath);
  try {
    const prefix = 'm' + Date.now().toString(36);
    const secret = 'browser-test-password';
    async function createWallet(i) {
      const username = prefix + '_' + i;
      await json('/api/v1/auth/register', {method:'POST', data:{username, password:secret, displayName:username}});
      const session = await json('/api/v1/auth/login', {method:'POST', data:{username, password:secret}});
      const headers = {Authorization:'Bearer ' + session.accessToken};
      const wallet = await json('/api/v1/wallets', {method:'POST', headers});
      return {username, headers, wallet};
    }
    const customer = await createWallet(0);
    await page.goto(base + '/#admin');
    await login(page, customer.username, secret);
    await page.waitForURL('**/#home');
    let adminReads = 0;
    page.on('request', req => {if (/\/api\/v1\/admin\/|\/bank\/admin\//.test(req.url())) adminReads++;});
    await page.goto(base + '/admin');
    await page.waitForURL('**/#home');
    assert.equal(await button(page, 'Admin console').count(), 0);
    assert.equal(await page.locator('#admin-bank').count(), 0);
    assert.equal(adminReads, 0);
    for (const path of ['status', 'integrity', 'wallets', 'reconciliation']) {
      const r = await api.fetch(base + '/api/v1/admin/' + path, {method:path === 'reconciliation' ? 'POST' : 'GET', headers:customer.headers});
      assert.equal(r.status(), 403);
    }
    console.log('PASS customer direct navigation and protected APIs');
    await button(page, 'Log out').click();
    await login(page, admin, password);
    const headers = {Authorization:'Bearer ' + await page.evaluate(() => sessionStorage.getItem('wallet.accessToken'))};
    await json('/api/v1/wallets', {method:'POST', headers});
    for (let i = 1; i < 21; i++) await createWallet(i);
    await page.goto(base + '/admin');
    await button(page, 'Check integrity').click();
    await page.locator('[data-invariant="INV-6"]').waitFor();
    assert.deepEqual(await page.locator('[data-invariant]').allTextContents().then(rows => rows.map(text => text.split(' — ')[0])), Array.from({length:6}, (_, i) => 'INV-' + (i+1) + ': PASS'));
    assert.equal(await page.locator('#admin-wallet-rows .row').count(), 20);
    await button(page, 'Load more wallets').click();
    const row = page.locator('[data-wallet-id="' + customer.wallet.id + '"]');
    // The oldest test customer is beyond the initial page.
    await row.waitFor();
    const fields = ['pendingDeposits','pendingWithdrawals','oldestPendingSeconds','outboxUnsent','outboxOldestSeconds','bankCircuit'];
    assert.deepEqual(await page.locator('#status-values [data-field]').evaluateAll(els => els.map(e => e.dataset.field)), fields);
    const post = page.waitForResponse(r => r.url().endsWith('/admin/reconciliation'));
    await button(page, 'Run reconciliation').click();
    assert.equal((await post).status(), 202);
    await page.getByText(/202 — Reconciliation job accepted/).waitFor();
    const put = async (name, expected) => {
      const response = page.waitForResponse(r => r.url().endsWith(configPath) && r.request().method() === 'PUT');
      await button(page, name).click();
      assert.deepEqual(await (await response).json(), expected);
      assert.deepEqual(await json(configPath), expected);
    };
    for (const [name, overrides] of Object.entries({Slow:{responseDelayMs:2000,settleDelayMs:5000},Down:{down:true},Hang:{hangRate:1,webhookCopies:0},Decline:{declineRate:1},'Duplicate webhooks':{webhookCopies:3},'No webhooks':{webhookCopies:0},Normal:{}})) {
      await put(name, {...defaults,...overrides});
    }
    await page.getByLabel('settleDelayMs').fill('6500');
    await put('Apply settings', {...defaults,settleDelayMs:6500});
    const old = await json('/api/v1/admin/status',{headers});
    await json('/api/v1/deposits',{method:'POST',headers:{...customer.headers,'Idempotency-Key':prefix},data:{amount:123}});
    await page.getByText('pendingDeposits: ' + (old.pendingDeposits + 1), {exact:true}).waitFor({timeout:6000});
    await row.getByText('Balance: $1.23 · Reserved: $0.00').waitFor({timeout:15000});
    await put('Normal', defaults);
    console.log('PASS six invariants, six status fields, paginated wallet refresh, reconciliation, seven presets and custom form');
    await button(page, 'Home').click();
    const atExit = adminReads;
    await pause(2400);
    assert.equal(adminReads, atExit);
    const attempts = [];
    page.on('response', response => {
      const r = response.request();
      if (r.method() === 'POST' && r.url().endsWith('/api/v1/deposits')) attempts.push({
        url:r.url(), key:r.headers()['idempotency-key'], body:r.postData(), status:response.status(), replayed:response.headers()['idempotent-replayed']
      });
    });
    await button(page.locator('nav'), 'Add money').click();
    await page.getByLabel('Amount (USD)').fill('2.57');
    await button(page, 'Review payment').click(); await button(page, 'Confirm payment').click();
    await page.locator('#payment-status').getByText('SUCCEEDED').waitFor();
    await button(page, 'New payment').click();
    async function snapshot() {
      const opts = {headers};
      return Promise.all(['/wallets/me','/wallets/me/entries','/deposits'].map(p => json('/api/v1' + p, opts)));
    }
    const before = await snapshot();
    await button(page, 'Admin console').click();
    await page.reload();
    for (const [count, name] of [[1,'Repeat the last money request with the same key'],[10,'Send the last money request 10 times in parallel with the same key']]) {
      await button(page, name).click();
      await page.locator('#diagnostic-results').getByText('Response ' + count + ': 202', {exact:true}).waitFor();
      assert.equal(await page.locator('#diagnostic-results p').count(), count);
      assert.deepEqual(await snapshot(), before);
    }
    assert.equal(attempts.length, 12);
    for (const attempt of attempts.slice(1)) assert.deepEqual(attempt, {...attempts[0],replayed:'true'});
    console.log('PASS original 257-cent deposit, eleven HTTP 202 replays, unchanged balance, statement and deposit list');
    for (const name of ['auth Swagger UI','wallet Swagger UI','bank Swagger UI','notifications Swagger UI','Grafana','Tracing UI']) {
      const opened = page.waitForEvent('popup');
      await page.getByRole('link', {name,exact:true}).click();
      const tab = await opened;
      if (name.includes('Swagger')) await tab.locator('.swagger-ui .opblock').first().waitFor();
      else if (name === 'Grafana') await tab.getByText('Request rate', {exact:false}).first().waitFor();
      else {
        await tab.getByText('Recent traces', {exact:true}).first().waitFor();
        await tab.getByText(/^(auth-service|wallet-service|notification-service|bank-simulator)$/).first().waitFor();
      }
      await tab.close();
      console.log('PASS link ' + name);
    }
    await button(page, 'Log out').click();
    const atLogout = adminReads;
    await pause(2400); assert.equal(adminReads, atLogout);
    await login(page, admin, password);
    await button(page, 'Admin console').click();
    assert.ok(await button(page, 'Repeat the last money request with the same key').isDisabled());
    console.log('PASS polling stops on exit and logout, new session has no replay');
  } finally { await api.put(base + configPath, {data:savedConfig}); }
}
(async () => {
  const browser = await chromium.launch({executablePath:process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});
  try { await run(browser); } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
