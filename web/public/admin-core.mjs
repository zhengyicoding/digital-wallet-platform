// UI-3 / BNK-6: presets always start from the Section 6.6 defaults.
export const bankDefaults = Object.freeze({ down: false, responseDelayMs: 50, hangRate: 0,
  settleDelayMs: 500, declineRate: 0, webhookCopies: 1 });
export const bankPresets = Object.freeze(Object.fromEntries(Object.entries({
  Normal: {}, Slow: { responseDelayMs: 2000, settleDelayMs: 5000 }, Down: { down: true },
  Hang: { hangRate: 1, webhookCopies: 0 }, Decline: { declineRate: 1 },
  'Duplicate webhooks': { webhookCopies: 3 }, 'No webhooks': { webhookCopies: 0 }
}).map(([name, overrides]) => [name, Object.freeze({ ...bankDefaults, ...overrides })])));
export const isAdmin = profile => profile?.roles?.includes('ADMIN') === true;

// UI-3 / API-5: walk fresh cursors through every visible wallet, including the old tail
// when new wallets push it onto another page. Commit only complete refreshes.
export class WalletPages {
  constructor() { this.items = []; this.nextCursor = null; this.target = 20; }
  async refresh(request) {
    const tail = this.items.at(-1)?.walletId;
    const items = new Map();
    let cursor = null;
    do {
      const params = new URLSearchParams({ limit: '20' });
      if (cursor) params.set('cursor', cursor);
      const page = await request(`/api/v1/admin/wallets?${params}`);
      for (const item of page.items) items.set(item.walletId, item);
      cursor = page.nextCursor;
    } while (cursor && (items.size < this.target || (tail && !items.has(tail))));
    this.items = [...items.values()];
    this.nextCursor = cursor;
  }
}

// UI-3 / IDM-2: all requests are launched before any result is awaited. Never make a new key.
export async function replay(client, action, count) {
  if (!action || !['transfers', 'deposits', 'withdrawals'].includes(action.kind)) return [];
  return Promise.all(Array.from({ length: count }, async () => {
    try {
      const response = await client.request(`/api/v1/${action.kind}`, {
        method: 'POST', body: action.body, key: action.key, withStatus: true
      });
      return { status: response.status };
    } catch (error) {
      if (error.name === 'AbortError') throw error;
      return { status: error.status || 0, message: error.message };
    }
  }));
}
