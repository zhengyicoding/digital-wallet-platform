import test from 'node:test';
import assert from 'node:assert/strict';
import { Client } from '../public/core.mjs';
import { replay, WalletPages } from '../public/admin-core.mjs';

test('UI-3 / IDM-2: ten concurrent replays retain the exact wire request and each HTTP outcome', async () => {
  const replies = [];
  const client = new Client(() => assert.fail(), async (path, init) => {
    assert.equal(path, '/api/v1/withdrawals');
    assert.equal(init.body, '{ "amount": 101 }');
    assert.equal(init.headers['Idempotency-Key'], 'original');
    return new Promise(resolve => replies.push(resolve));
  });
  const pending = replay(client, { kind: 'withdrawals', key: 'original', body: '{ "amount": 101 }' }, 10);
  assert.equal(replies.length, 10);
  replies.forEach((reply, i) => reply(Response.json({}, { status: i ? 202 : 409 })));
  assert.deepEqual((await pending).map(r => r.status), [409, ...Array(9).fill(202)]);
});

test('UI-3 / API-5: wallet refresh keeps the old tail, updates older balances and recovers from a page failure', async () => {
  const wallets = new WalletPages();
  let seed = Array.from({length: 41}, (_, i) => ({ walletId: String(i), balance: 0 }));
  const request = async path => {
    const cursor = new URL(path, 'http://localhost').searchParams.get('cursor');
    const start = cursor ? seed.findIndex(w => w.walletId === cursor) + 1 : 0;
    const items = seed.slice(start, start + 20);
    return { items, nextCursor: start + 20 < seed.length ? items.at(-1).walletId : null };
  };
  await wallets.refresh(request);
  assert.equal(wallets.items.length, 20);
  wallets.target = 40;
  await wallets.refresh(request);
  seed = [{walletId: 'new', balance: 100}, ...seed];
  seed[40] = {walletId: '39', balance: 55};
  await assert.rejects(wallets.refresh(path => path.includes('cursor=') ? Promise.reject(Error('offline')) : request(path)));
  assert.equal(wallets.items.length, 40);
  await wallets.refresh(request);
  assert.equal(new Set(wallets.items.map(w => w.walletId)).size, 42);
  assert.equal(wallets.items.find(w => w.walletId === '39').balance, 55);
});
