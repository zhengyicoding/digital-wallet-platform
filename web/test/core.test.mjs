import { test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { cents, money, newAction, retryable, ApiError, Client, Poller } from '../public/core.mjs';

globalThis.crypto ??= webcrypto;

test('UI-1: the default browser transport invokes fetch without a Client receiver', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = function () {
    assert.equal(this, undefined);
    return Promise.resolve(Response.json({ id: 'profile' }));
  };
  try { assert.equal((await new Client(() => {}).request('/api/v1/auth/me')).id, 'profile'); }
  finally { globalThis.fetch = original; }
});

test('UI-6 / MNY-1: decimal conversion is exact and leaves business validation to the server', () => {
  for (const [input, expected] of [['12.50', 1250], ['0.29', 29], ['1.01', 101], ['100', 10000], ['0', 0], ['-1.25', -125], ['2000.01', 200001], ['90071992547409.91', Number.MAX_SAFE_INTEGER]]) {
    assert.equal(cents(input), expected);
  }
  for (const input of ['1e2', '1.001', 'NaN', '', '$1', '1,000', '90071992547409.92']) assert.throws(() => cents(input));
  assert.equal(money(-1250), '-$12.50');
  assert.equal(money(125000), '$1,250.00');
});

test('UI-4 / IDM-2: lost response and REQUEST_IN_PROGRESS preserve wire body and key; new action is fresh', async () => {
  const attempts = [];
  const client = new Client(() => assert.fail('Unexpected session expiry'), async (path, request) => {
    attempts.push({ path, body: request.body, key: request.headers['Idempotency-Key'] });
    if (attempts.length === 1) throw new TypeError('Lost response');
    if (attempts.length === 2) return Response.json({ code: 'REQUEST_IN_PROGRESS' }, { status: 409 });
    return Response.json({ id: 'one-payment', status: 'PENDING' }, { status: 202 });
  });
  const body = { amount: 1250, note: '<img src=x>' };
  const action = newAction('transfers', body);
  body.amount = 9999;
  await assert.rejects(client.send(action), error => retryable(error));
  await assert.rejects(client.send(action), error => retryable(error));
  await client.send(action);
  assert.deepEqual(attempts[0], attempts[1]);
  assert.deepEqual(attempts[1], attempts[2]);
  assert.equal(JSON.parse(attempts[2].body).amount, 1250);
  assert.notEqual(newAction('transfers', body).key, action.key);
  assert.equal(retryable(new ApiError(422, { code: 'INSUFFICIENT_FUNDS' })), false);
});

test('UI-7: authenticated 401 clears the session while failed login retains its feedback', async () => {
  let expired = 0;
  const client = new Client(() => { expired++; client.reset(); }, async () => Response.json({ code: 'INVALID_CREDENTIALS' }, { status: 401 }));
  client.reset('token');
  await assert.rejects(client.request('/api/v1/auth/login', { authenticated: false }), /INVALID_CREDENTIALS/);
  assert.equal(expired, 0);
  await assert.rejects(client.request('/api/v1/wallets/me'), { name: 'AbortError' });
  assert.equal(expired, 1);
  assert.equal(client.token, null);
});

test('UI-7: logout aborts outstanding requests and rejects late responses', async () => {
  let release, signal;
  const client = new Client(() => {}, async (_path, request) => {
    signal = request.signal;
    await new Promise(resolve => { release = resolve; });
    return Response.json({ username: 'previous-user' });
  });
  client.reset('old');
  const request = client.request('/api/v1/auth/me');
  client.reset('new');
  assert.equal(signal.aborted, true);
  release();
  await assert.rejects(request, { name: 'AbortError' });
});

test('UI-5: refresh calls share one flight, and logout prevents another scheduled poll', async () => {
  let calls = 0, release;
  const poller = new Poller(async () => { calls++; await new Promise(resolve => { release = resolve; }); }, 10);
  const first = poller.start();
  const second = poller.refresh();
  assert.equal(first, second);
  await Promise.resolve();
  assert.equal(calls, 1);
  poller.stop();
  release();
  await first;
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(calls, 1);
});

test('UI-5/7: logout before a queued poll starts prevents the task from running', async () => {
  const poller = new Poller(() => assert.fail('Stopped task ran'));
  const queued = poller.start();
  poller.stop();
  await queued;
});
