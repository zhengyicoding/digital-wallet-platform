import { Client, Poller, money } from './core.mjs';
import { bankDefaults, bankPresets, WalletPages, replay } from './admin-core.mjs';

const element = (tag, text, className) => {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
};
export class AdminConsole {
  constructor(root, token, onUnauthorized, lastAction) {
    this.root = root;
    this.client = new Client(onUnauthorized);
    this.client.reset(token);
    this.lastAction = lastAction;
    this.wallets = new WalletPages();
    this.active = true;
    this.poller = new Poller(() => this.refresh(), 2000);
    this.render();
    this.poller.start();
    this.loadBank();
    this.loadLinks();
  }
  stop() { this.active = false; this.poller.stop(); this.client.reset(); }
  section(title, id) {
    const card = element('section', undefined, 'card'); card.id = id;
    card.append(element('h2', title)); this.root.append(card); return card;
  }
  output(parent, id) {
    const output = element('div'); output.id = id; output.setAttribute('role', 'status'); parent.append(output); return output;
  }
  control(parent, label, task) {
    const button = element('button', label); button.type = 'button';
    button.onclick = async () => {
      button.disabled = true;
      try { await task(); }
      catch (error) { if (this.active && error.name !== 'AbortError') this.error.textContent = error.message; }
      finally { if (this.active) button.disabled = false; }
    };
    parent.append(button); return button;
  }
  render() {
    this.root.append(element('h2', 'Admin console'));
    this.error = this.output(this.root, 'admin-error'); this.error.className = 'error';
    const integrity = this.section('Integrity', 'admin-integrity');
    this.control(integrity, 'Check integrity', async () => {
      const report = await this.client.request('/api/v1/admin/integrity');
      this.integrity.replaceChildren(element('p', `${report.ok ? 'PASS' : 'FAIL'} · ${report.checkedAt}`));
      for (const check of report.checks) {
        const row = element('p', `${check.id}: ${check.ok ? 'PASS' : 'FAIL'} — ${check.detail}`);
        row.dataset.invariant = check.id; this.integrity.append(row);
      }
      this.integrity.append(element('pre', JSON.stringify(report.totals, null, 2)));
    });
    this.integrity = this.output(integrity, 'integrity-results');
    const status = this.section('Status', 'admin-status');
    status.append(element('p', 'Status and visible wallets refresh every two seconds after the previous refresh completes.', 'muted'));
    this.refreshError = this.output(status, 'admin-refresh-error');
    this.status = this.output(status, 'status-values');
    this.control(status, 'Run reconciliation', async () => {
      const response = await this.client.request('/api/v1/admin/reconciliation', { method: 'POST', withStatus: true });
      this.reconciliation.textContent = `${response.status} — Reconciliation job accepted. Completion is asynchronous; follow the status above.`;
    });
    this.reconciliation = this.output(status, 'reconciliation-result');
    const wallets = this.section('Wallets', 'admin-wallets');
    this.walletRows = this.output(wallets, 'admin-wallet-rows');
    this.more = this.control(wallets, 'Load more wallets', async () => {
      if (!this.wallets.nextCursor) return;
      this.wallets.target = this.wallets.items.length + 20;
      await this.poller.refresh();
    }); this.more.hidden = true;
    const bank = this.section('Bank failure settings', 'admin-bank');
    this.bankControls = element('div', undefined, 'actions'); bank.append(this.bankControls);
    for (const [label, config] of Object.entries(bankPresets)) this.control(this.bankControls, label, () => this.saveBank(config));
    this.form = element('form'); this.form.id = 'bank-settings';
    for (const [field, value] of Object.entries(bankDefaults)) {
      const label = element('label', field); label.htmlFor = `bank-${field}`;
      const input = element('input'); input.id = label.htmlFor; input.name = field;
      input.type = typeof value === 'boolean' ? 'checkbox' : 'number';
      if (input.type === 'number') {
        input.required = true; input.min = '0'; input.step = field.endsWith('Rate') ? 'any' : '1';
        if (field.endsWith('Rate')) input.max = '1';
      }
      this.form.append(label, input);
    }
    const save = element('button', 'Apply settings'); save.type = 'submit'; this.form.append(save);
    this.form.onsubmit = event => {
      event.preventDefault();
      const config = {};
      for (const field of Object.keys(bankDefaults)) {
        const input = this.form.elements.namedItem(field);
        config[field] = input.type === 'checkbox' ? input.checked : Number(input.value);
      }
      this.saveBank(config).catch(error => { if (this.active && error.name !== 'AbortError') this.error.textContent = error.message; });
    };
    bank.append(this.form); this.bankResult = this.output(bank, 'bank-result');
    // Disable edits until the live settings arrive; do not overwrite server settings on entry.
    this.setBankBusy(true);
    const diagnostics = this.section('Diagnostics', 'admin-diagnostics');
    const action = this.lastAction();
    diagnostics.append(element('p', action ? `Last request: POST /api/v1/${action.kind} · Idempotency-Key: ${action.key} · Body: ${action.body}` : 'Submit a money request in this signed-in session to enable diagnostics.', 'identity'));
    const controls = element('div', undefined, 'actions'); diagnostics.append(controls);
    this.diagnosticButtons = [
      this.control(controls, 'Repeat the last money request with the same key', () => this.diagnose(1)),
      this.control(controls, 'Send the last money request 10 times in parallel with the same key', () => this.diagnose(10))
    ];
    for (const button of this.diagnosticButtons) button.disabled = !action;
    this.responses = this.output(diagnostics, 'diagnostic-results');
    this.links = this.section('Tools', 'admin-tools');
    for (const name of ['auth', 'wallet', 'bank', 'notifications']) this.link(`${name} Swagger UI`, `/docs/${name}/`);
  }
  link(label, url) {
    const link = element('a', label); link.href = url; link.target = '_blank'; link.rel = 'noopener noreferrer'; this.links.append(link);
  }
  async loadLinks() {
    try {
      const config = await this.client.request('/config.json', { authenticated: false });
      const base = new URL(location.origin); base.port = String(config.grafanaPort);
      this.link('Grafana', new URL('/d/digital-wallet', base).href);
      this.link('Tracing UI', new URL('/d/wallet-traces/digital-wallet-traces', base).href);
    } catch (error) { if (this.active && error.name !== 'AbortError') this.error.textContent = error.message; }
  }
  async refresh() {
    const results = await Promise.allSettled([
      (async () => {
        const values = await this.client.request('/api/v1/admin/status');
        this.status.replaceChildren();
        for (const [field, value] of Object.entries(values)) {
          const row = element('p', `${field}: ${value}`); row.dataset.field = field; this.status.append(row);
        }
      })(),
      (async () => {
        await this.wallets.refresh(path => this.client.request(path));
        if (!this.active) return;
        this.walletRows.replaceChildren();
        for (const wallet of this.wallets.items) {
          const row = element('div', undefined, 'row'); row.dataset.walletId = wallet.walletId;
          row.append(element('strong', `${wallet.displayName} (@${wallet.username})`),
            element('p', `Balance: ${money(wallet.balance)} · Reserved: ${money(wallet.reserved)}`), element('small', wallet.walletId));
          this.walletRows.append(row);
        }
        if (!this.wallets.items.length) this.walletRows.append(element('p', 'No wallets yet.'));
        this.more.hidden = !this.wallets.nextCursor;
      })()
    ]);
    if (!this.active) return;
    const failed = results.find(result => result.status === 'rejected');
    this.refreshError.textContent = failed ? `Live updates interrupted. Retrying automatically. ${failed.reason.message}` : '';
  }
  setBankBusy(busy) {
    for (const input of this.form.elements) input.disabled = busy;
    for (const button of this.bankControls.children) button.disabled = busy;
  }
  showBank(config) {
    for (const [field, value] of Object.entries(config)) {
      const input = this.form.elements.namedItem(field);
      if (input?.type === 'checkbox') input.checked = value;
      else if (input) input.value = value;
    }
  }
  async loadBank() {
    try { this.showBank(await this.client.request('/bank/admin/config', { authenticated: false })); }
    catch (error) { if (this.active && error.name !== 'AbortError') this.error.textContent = error.message; }
    finally { if (this.active) this.setBankBusy(false); }
  }
  async saveBank(config) {
    if (this.bankBusy) return;
    this.bankBusy = true; this.setBankBusy(true);
    try {
      const result = await this.client.request('/bank/admin/config', { method: 'PUT', authenticated: false, body: JSON.stringify(config) });
      this.showBank(result); this.bankResult.textContent = 'Settings applied.';
    } finally { this.bankBusy = false; if (this.active) this.setBankBusy(false); }
  }
  async diagnose(count) {
    for (const button of this.diagnosticButtons) button.disabled = true;
    this.responses.replaceChildren(element('p', `Sending ${count} request${count === 1 ? '' : 's'}…`));
    try {
      const results = await replay(this.client, this.lastAction(), count);
      this.responses.replaceChildren(...results.map((result, index) => element('p', `Response ${index + 1}: ${result.status || 'Network error'}${result.message ? ` — ${result.message}` : ''}`)));
    } finally { if (this.active) for (const button of this.diagnosticButtons) button.disabled = false; }
  }
}
