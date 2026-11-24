import { cents, money, newAction, retryable, Client, Poller } from './core.mjs';
import { isAdmin } from './admin-core.mjs';
import { AdminConsole } from './admin.mjs';

const app = document.querySelector('#app');
const $ = (selector, root = document) => root.querySelector(selector);
const tokenKey = 'wallet.accessToken';
const actionKey = 'wallet.moneyAction';
const lastActionKey = 'wallet.lastMoneyAction';
let adminConsole, lastMoneyAction;
const labels = { transfers: 'Send money', deposits: 'Add money', withdrawals: 'Withdraw' };
let profile, wallet, view = 'home', recipient, draft, payment, sending = false, pageEpoch = 0;
const pending = new Map();
const lists = {};
const client = new Client(() => logout('Your session has expired. Please log in again.'));
const poller = new Poller(refresh);

// SEC-8: dynamic values only enter the document through textContent, never HTML.
function node(tag, text, className) {
  const result = document.createElement(tag);
  if (text !== undefined) result.textContent = text;
  if (className) result.className = className;
  return result;
}
function button(text, action, className) {
  const result = node('button', text, className);
  result.type = 'button';
  result.onclick = action;
  return result;
}
function errorAt(target, error) {
  if (error?.name === 'AbortError' || !target?.isConnected) return;
  target.textContent = error?.message || String(error);
  target.hidden = false;
}
function status(value) {
  const result = node('span', value, 'status');
  result.dataset.status = value;
  return result;
}
function date(value) { return new Date(value).toLocaleString(); }
function savePayment() {
  if (payment) sessionStorage.setItem(actionKey, JSON.stringify(payment));
  else sessionStorage.removeItem(actionKey);
}
function resetLists() {
  for (const key of ['entries', 'deposits', 'withdrawals', 'notifications']) {
    lists[key] = { items: [], cursor: null, loaded: false, busy: false, revision: 0 };
  }
}
resetLists();

function logout(message = '') {
  adminConsole?.stop(); adminConsole = null; lastMoneyAction = null;
  poller.stop();
  client.reset();
  sessionStorage.removeItem(tokenKey);
  sessionStorage.removeItem(actionKey);
  sessionStorage.removeItem(lastActionKey);
  profile = wallet = recipient = draft = payment = null;
  sending = false;
  entryType = ''; unreadOnly = false;
  pending.clear();
  resetLists();
  history.replaceState(null, '', '#login');
  showAuth(false, message);
}

function showAuth(signup = false, message = '') {
  pageEpoch++;
  app.innerHTML = `<section class="auth card">
    <div class="eyebrow">Your everyday wallet</div><h1></h1>
    <p class="muted">Move money. Keep track. Stay in control.</p>
    <div id="auth-notice" class="notice" hidden></div><div id="auth-error" class="error" role="alert" hidden></div>
    <form id="auth-form">
      <label for="username">Username</label><input id="username" name="username" autocomplete="username" required pattern="[a-z0-9_]{3,20}" title="3–20 lowercase letters, digits or underscores">
      <div id="name-field" hidden><label for="displayName">Display name</label><input id="displayName" name="displayName" autocomplete="name"></div>
      <label for="password">Password</label><input id="password" name="password" type="password" required autocomplete="current-password">
      <button class="primary" type="submit"></button>
    </form><button id="auth-switch" class="link" type="button"></button>
  </section>`;
  $('h1', app).textContent = signup ? 'Create your account' : 'Welcome back';
  $('#name-field').hidden = !signup;
  $('#displayName').required = signup;
  $('#password').autocomplete = signup ? 'new-password' : 'current-password';
  $('#auth-form button').textContent = signup ? 'Sign up' : 'Log in';
  $('#auth-switch').textContent = signup ? 'Already have an account? Log in' : 'New here? Sign up';
  $('#auth-switch').onclick = () => showAuth(!signup);
  if (message) { $('#auth-notice').textContent = message; $('#auth-notice').hidden = false; }
  $('#auth-form').onsubmit = async event => {
    event.preventDefault();
    const form = event.currentTarget;
    const epoch = pageEpoch;
    const submit = $('button', form);
    const target = $('#auth-error');
    target.hidden = true;
    submit.disabled = true;
    const credentials = { username: form.username.value, password: form.password.value };
    try {
      if (signup) {
        await client.request('/api/v1/auth/register', { method: 'POST', authenticated: false,
          body: JSON.stringify({ ...credentials, displayName: form.displayName.value }) });
        if (epoch !== pageEpoch) return;
        showAuth(false, 'Account created. Log in to open your wallet.');
        $('#username').value = credentials.username;
        return;
      }
      const data = await client.request('/api/v1/auth/login', { method: 'POST', authenticated: false, body: JSON.stringify(credentials) });
      if (epoch !== pageEpoch) return;
      sessionStorage.setItem(tokenKey, data.accessToken);
      await startSession(data.accessToken);
    } catch (error) { errorAt(target, error); }
    finally { submit.disabled = false; }
  };
}

async function startSession(token) {
  client.reset(token);
  app.innerHTML = '<p role="status">Loading your wallet…</p>';
  try {
    profile = await client.request('/api/v1/auth/me');
    try {
      const saved = JSON.parse(sessionStorage.getItem(lastActionKey));
      lastMoneyAction = saved?.owner === profile.id ? saved.action : null;
    } catch { lastMoneyAction = null; }
    try { payment = JSON.parse(sessionStorage.getItem(actionKey)); } catch { payment = null; }
    if (payment?.result && payment.action.kind !== 'transfers') track(payment.action.kind, payment.result);
    shell();
    await poller.start();
  } catch (error) {
    if (error.name === 'AbortError') return;
    app.replaceChildren(node('p', error.message, 'error'), button('Try loading again', () => startSession(token)), button('Log out', () => logout()));
  }
}

function shell() {
  app.innerHTML = `<div class="eyebrow">Personal account</div><h1 id="greeting"></h1>
    <nav aria-label="Wallet navigation"></nav>
    <div id="refresh-error" class="error" role="status" hidden></div>
    <div id="resume" class="notice" hidden></div><section id="content"></section>`;
  $('#greeting').textContent = `Hello, ${profile.displayName}`;
  const nav = $('nav', app);
  for (const [key, label] of Object.entries({ home: 'Home', transfers: 'Send', deposits: 'Add money', withdrawals: 'Withdraw', activity: 'Activity', notifications: 'Notifications' })) {
    const item = button(label, () => navigate(key));
    item.dataset.view = key;
    if (key === 'notifications') item.append(' ', node('span', '0', 'badge'));
    nav.append(item);
  }
  if (isAdmin(profile)) {
    const item = button('Admin console', () => navigate('admin'));
    item.dataset.view = 'admin'; nav.append(item);
  }
  nav.append(button('Log out', () => logout(), 'logout'));
  navigate(location.hash === '#admin' || location.pathname === '/admin' ? 'admin' : 'home');
}

function navigate(next) {
  if (!profile) return;
  const denied = next === 'admin' && !isAdmin(profile);
  if (denied || !['home', 'admin', 'activity', 'notifications', ...Object.keys(labels)].includes(next)) next = 'home';
  adminConsole?.stop(); adminConsole = null;
  view = next;
  pageEpoch++;
  history.replaceState(null, '', `/#${view}`);
  for (const item of document.querySelectorAll('nav [data-view]')) {
    if (item.dataset.view === view) item.setAttribute('aria-current', 'page');
    else item.removeAttribute('aria-current');
  }
  const content = $('#content');
  content.replaceChildren();
  if (view === 'home') renderHome();
  else if (view === 'activity') showActivity();
  else if (view === 'notifications') showNotifications();
  else if (view === 'admin') adminConsole = new AdminConsole(content, client.token,
    () => logout('Your session has expired. Please log in again.'), () => lastMoneyAction);
  else showMoney();
  if (denied) content.prepend(node('p', 'Administrator access is required. (FORBIDDEN)', 'error'));
  renderResume();
}

function renderResume() {
  const target = $('#resume');
  if (!target) return;
  target.replaceChildren();
  target.hidden = !payment || Boolean(labels[view]);
  if (!target.hidden) {
    target.append(node('span', payment.result ? 'Your latest payment is available. ' : 'A payment needs your attention. '),
      button('View payment', () => navigate(payment.action.kind)));
  }
}

function renderHome() {
  if (view !== 'home' || !profile) return;
  const content = $('#content');
  content.replaceChildren();
  if (wallet === undefined) { content.append(node('p', 'Loading your wallet…')); return; }
  if (!wallet) {
    const card = node('div', undefined, 'card narrow');
    card.append(node('h2', 'A home for your money'), node('p', 'Open your USD wallet to add money, send to friends and keep track of every payment.'));
    const error = node('div', undefined, 'error'); error.hidden = true; error.setAttribute('role', 'alert');
    const open = button('Open wallet', async () => {
      open.disabled = true;
      try { wallet = await client.request('/api/v1/wallets', { method: 'POST' }); renderHome(); await poller.refresh(); }
      catch (err) { errorAt(error, err); }
      finally { open.disabled = false; }
    }, 'primary');
    card.append(open, error); content.append(card); return;
  }
  const card = node('div', undefined, 'card balance-card');
  const balance = node('div', money(wallet.balance), 'balance'); balance.id = 'balance';
  card.append(node('div', 'Available balance', 'eyebrow'), balance);
  const metrics = node('div', undefined, 'metrics');
  const reserved = node('div', 'Reserved for withdrawals', 'muted');
  const reservedValue = node('strong', money(wallet.reserved)); reservedValue.id = 'reserved'; reserved.append(reservedValue);
  const daily = node('div', 'Sent today / daily limit (UTC)', 'muted');
  const dailyValue = node('strong', `${money(wallet.sentToday)} / ${money(wallet.dailyLimit)}`); dailyValue.id = 'daily'; daily.append(dailyValue);
  metrics.append(reserved, daily); card.append(metrics); content.append(card);
  const actions = node('div', undefined, 'actions');
  for (const kind of Object.keys(labels)) actions.append(button(labels[kind], () => navigate(kind), kind === 'transfers' ? 'primary' : ''));
  content.append(actions);
  const identity = node('p', undefined, 'identity muted');
  identity.append(node('span', `Username: @${wallet.username}`), document.createElement('br'), node('span', `Wallet ID: ${wallet.id}`));
  content.append(identity);
}

function track(kind, operation) {
  const key = `${kind}/${operation.id}`;
  if (operation.status === 'PENDING') pending.set(key, { kind, ...operation });
  else pending.delete(key);
}

async function refresh() {
  const generation = client.generation;
  const errors = [];
  const jobs = [
    (async () => {
      try { wallet = await client.request('/api/v1/wallets/me'); }
      catch (error) { if (error.code === 'WALLET_NOT_FOUND') wallet = null; else throw error; }
      renderHome();
    })(),
    (async () => {
      const count = await client.request('/api/v1/notifications/unread-count');
      const badge = $('nav .badge'); if (badge) badge.textContent = count.count;
      await loadList('notifications', 'head');
    })(),
    ...['deposits', 'withdrawals'].map(async kind => {
      let cursor;
      do {
        const params = new URLSearchParams({ status: 'PENDING', limit: '100' });
        if (cursor) params.set('cursor', cursor);
        const page = await client.request(`/api/v1/${kind}?${params}`);
        for (const operation of page.items) track(kind, operation);
        cursor = page.nextCursor;
      } while (cursor && generation === client.generation);
    })
  ];
  const results = await Promise.allSettled(jobs);
  for (const result of results) if (result.status === 'rejected') errors.push(result.reason);
  if (generation !== client.generation) return;
  const details = await Promise.allSettled([...pending.entries()].map(async ([path, operation]) => {
    const current = await client.request(`/api/v1/${path}`);
    track(operation.kind, current);
    const list = lists[operation.kind];
    list.items = list.items.map(item => item.id === current.id ? current : item);
    if (payment?.result?.id === current.id && payment.action.kind === operation.kind) {
      payment.result = current; savePayment(); renderPayment();
    }
    renderList(operation.kind);
  }));
  for (const result of details) if (result.status === 'rejected') errors.push(result.reason);
  if (generation !== client.generation) return;
  const target = $('#refresh-error');
  if (target) {
    target.hidden = errors.length === 0;
    if (errors.length) target.textContent = `Live updates interrupted. Retrying automatically. ${errors[0].message}`;
  }
}

function showMoney() {
  const content = $('#content');
  const card = node('div', undefined, 'card narrow'); card.id = 'money-card';
  content.append(card);
  if (payment) { renderPayment(); return; }
  card.innerHTML = `<h2></h2><div id="money-error" class="error" role="alert" hidden></div>
    <form id="money-form">
      <div id="recipient-fields" hidden><label for="recipient">Recipient username</label><input id="recipient" autocomplete="off" pattern="[a-z0-9_]{3,20}" title="3–20 lowercase letters, digits or underscores">
      <button id="lookup" type="button">Look up recipient</button><p id="recipient-name" class="notice" hidden></p></div>
      <label for="amount">Amount (USD)</label><input id="amount" name="amount" type="text" inputmode="decimal" placeholder="0.00" required>
      <div id="note-field" hidden><label for="note">Note (optional)</label><textarea id="note" maxlength="140" placeholder="What is it for?"></textarea></div>
      <button type="submit" class="primary">Review payment</button>
    </form>`;
  $('h2', card).textContent = labels[view];
  recipient = null; draft = null;
  const isTransfer = view === 'transfers';
  $('#recipient-fields').hidden = !isTransfer; $('#recipient').required = isTransfer;
  $('#note-field').hidden = !isTransfer;
  $('#recipient').oninput = () => { recipient = null; $('#recipient-name').hidden = true; };
  $('#lookup').onclick = async () => {
    const input = $('#recipient'); if (!input.reportValidity() || !input.value) return;
    const username = input.value;
    const target = $('#money-error'); target.hidden = true;
    const lookup = $('#lookup'); lookup.disabled = true;
    try {
      const result = await client.request(`/api/v1/wallets/lookup?${new URLSearchParams({ username })}`);
      if (!input.isConnected || input.value !== username) return;
      recipient = result;
      $('#recipient-name').textContent = `${result.displayName} (@${result.username})`;
      $('#recipient-name').hidden = false;
    } catch (error) { errorAt(target, error); }
    finally { lookup.disabled = false; }
  };
  $('#money-form').onsubmit = event => {
    event.preventDefault();
    const target = $('#money-error'); target.hidden = true;
    try {
      const amount = cents($('#amount').value);
      if (isTransfer && !recipient) throw new Error('Look up the recipient before reviewing the payment.');
      const body = isTransfer ? { toWalletId: recipient.walletId, amount, note: $('#note').value } : { amount };
      draft = { kind: view, body, recipient };
      showConfirmation();
    } catch (error) { errorAt(target, error); }
  };
}

function summary(kind, body, recipientInfo) {
  const result = node('div', undefined, 'summary');
  result.append(node('p', `${labels[kind]} · ${money(body.amount)}`));
  if (kind === 'transfers') {
    result.append(node('p', recipientInfo ? `To ${recipientInfo.displayName} (@${recipientInfo.username})` : `To wallet ${body.toWalletId}`));
    if (body.note) result.append(node('p', body.note));
  }
  return result;
}

function showConfirmation() {
  const card = $('#money-card');
  card.replaceChildren(node('h2', 'Confirm your payment'), summary(draft.kind, draft.body, draft.recipient));
  card.append(node('p', 'Check the details before continuing.'));
  const actions = node('div', undefined, 'actions');
  actions.append(button('Confirm payment', () => {
    payment = { action: newAction(draft.kind, draft.body), recipient: draft.recipient, result: null, uncertain: true };
    savePayment(); submitPayment();
  }, 'primary'), button('Edit details', () => {
    const previous = draft; showMoney();
    $('#amount').value = money(previous.body.amount).replace(/[$,]/g, '');
    if (previous.recipient) {
      recipient = previous.recipient; $('#recipient').value = recipient.username;
      $('#recipient-name').textContent = `${recipient.displayName} (@${recipient.username})`; $('#recipient-name').hidden = false;
      $('#note').value = previous.body.note;
    }
  }));
  card.append(actions);
}

async function submitPayment() {
  if (sending || !payment) return;
  sending = true;
  payment.error = null;
  renderPayment();
  const current = payment;
  lastMoneyAction = current.action;
  sessionStorage.setItem(lastActionKey, JSON.stringify({ owner: profile.id, action: lastMoneyAction }));
  try {
    const result = await client.send(current.action);
    if (payment !== current) return;
    current.result = result; current.uncertain = false;
    if (current.action.kind !== 'transfers') track(current.action.kind, result);
    savePayment();
    // Accepted operation response is an idempotency snapshot; always use its GET for current status.
    await poller.refresh();
  } catch (error) {
    if (payment !== current || error.name === 'AbortError') return;
    current.error = error.message;
    current.uncertain = retryable(error);
    savePayment();
  } finally {
    if (payment === current) { sending = false; renderPayment(); renderResume(); }
  }
}

function renderPayment() {
  const card = $('#money-card');
  if (!card || !payment) return;
  const { action, result } = payment;
  card.replaceChildren(node('h2', result ? 'Payment details' : 'Your payment'), summary(action.kind, JSON.parse(action.body), payment.recipient));
  if (result) {
    const state = status(result.status); state.id = 'payment-status'; state.setAttribute('role', 'status');
    card.append(state, node('p', `Reference: ${result.id}`, 'identity muted'));
    if (result.failureReason) card.append(node('p', `Reason: ${result.failureReason}`, 'error'));
    if (result.status === 'PENDING') card.append(node('p', 'Waiting for the bank. This status updates automatically.', 'muted'));
    else card.append(node('p', result.status === 'FAILED' ? 'The bank declined this payment.' : 'Your payment is complete.'));
  }
  if (payment.error) { const error = node('div', payment.error, 'error'); error.setAttribute('role', 'alert'); card.append(error); }
  if (sending) card.append(node('p', 'Submitting your payment…', 'muted'));
  else if (payment.uncertain) {
    card.append(node('p', 'The outcome is not confirmed. Retry to safely check the original payment. Its details are preserved, including after a page reload.', 'muted'));
    card.append(button('Retry original payment', submitPayment, 'primary'));
  } else card.append(button('New payment', () => { payment = null; savePayment(); showMoney(); renderResume(); }, 'primary'));
}

const paths = { entries: '/api/v1/wallets/me/entries', deposits: '/api/v1/deposits', withdrawals: '/api/v1/withdrawals', notifications: '/api/v1/notifications' };
let entryType = '', unreadOnly = false;
function paramsFor(kind) {
  const params = new URLSearchParams({ limit: '20' });
  if (kind === 'entries' && entryType) params.set('type', entryType);
  if (kind === 'notifications' && unreadOnly) params.set('unreadOnly', 'true');
  return params;
}
async function loadList(kind, mode = 'replace') {
  const list = lists[kind];
  if (list.busy) return;
  if (mode === 'more' && !list.cursor) return;
  list.busy = true;
  const revision = list.revision;
  const params = paramsFor(kind);
  if (mode === 'more') params.set('cursor', list.cursor);
  renderList(kind);
  try {
    const page = await client.request(`${paths[kind]}?${params}`);
    if (list !== lists[kind] || revision !== list.revision) return;
    const first = mode === 'replace' || !list.loaded;
    if (first) list.items = page.items;
    else {
      const merged = new Map(list.items.map(item => [item.id, item]));
      for (const item of page.items) merged.set(item.id, item);
      list.items = [...merged.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id));
      if (kind === 'notifications' && unreadOnly) list.items = list.items.filter(item => !item.read);
    }
    if (first || mode === 'more') list.cursor = page.nextCursor;
    list.loaded = true;
    if (kind === 'deposits' || kind === 'withdrawals') for (const operation of page.items) track(kind, operation);
  } finally {
    list.busy = false;
    if (list === lists[kind]) renderList(kind);
  }
}
function listSection(kind, title) {
  const card = node('section', undefined, 'card'); card.dataset.list = kind;
  card.append(node('h2', title));
  const error = node('div', undefined, 'error'); error.hidden = true; error.dataset.listError = kind; error.setAttribute('role', 'alert');
  const rows = node('div', undefined, 'rows'); rows.id = `${kind}-rows`;
  const more = button('Load more', () => fetchList(kind, 'more')); more.dataset.more = kind; more.hidden = true;
  card.append(error, rows, more);
  return card;
}
function fetchList(kind, mode) {
  const target = $(`[data-list-error="${kind}"]`);
  if (target) target.hidden = true;
  return loadList(kind, mode).catch(error => errorAt(target, error));
}
function showActivity() {
  const content = $('#content');
  content.append(node('h2', 'Activity'));
  const entries = listSection('entries', 'Statement');
  const filter = node('select'); filter.id = 'entry-type';
  for (const value of ['', 'DEPOSIT', 'TRANSFER_OUT', 'TRANSFER_IN', 'WITHDRAWAL', 'WITHDRAWAL_REVERSAL']) {
    const option = node('option', value || 'All entry types'); option.value = value; filter.append(option);
  }
  filter.value = entryType;
  const label = node('label', 'Entry type'); label.htmlFor = filter.id;
  entries.insertBefore(label, $('#entries-rows', entries)); entries.insertBefore(filter, $('#entries-rows', entries));
  filter.onchange = () => { entryType = filter.value; lists.entries = { items: [], cursor: null, loaded: false, busy: false, revision: 0 }; fetchList('entries'); };
  const refreshButton = button('Refresh statement', () => fetchList('entries'));
  entries.append(refreshButton);
  content.append(entries);
  const columns = node('div', undefined, 'columns');
  columns.append(listSection('deposits', 'Deposits'), listSection('withdrawals', 'Withdrawals')); content.append(columns);
  for (const kind of ['entries', 'deposits', 'withdrawals']) { renderList(kind); fetchList(kind); }
}
function showNotifications() {
  const content = $('#content');
  const card = listSection('notifications', 'Notifications');
  const controls = node('div', undefined, 'actions');
  controls.append(button('Mark all as read', () => markRead()), button(unreadOnly ? 'Show all notifications' : 'Show unread only', () => {
    unreadOnly = !unreadOnly;
    lists.notifications = { items: [], cursor: null, loaded: false, busy: false, revision: 0 };
    showNotifications();
  }));
  card.insertBefore(controls, $('#notifications-rows', card));
  content.replaceChildren(card); renderList('notifications'); fetchList('notifications', 'head');
}
async function markRead(id) {
  const target = $('[data-list-error="notifications"]');
  try {
    await client.request(`/api/v1/notifications/${id ? `${id}/read` : 'read-all'}`, { method: 'POST' });
    for (const item of lists.notifications.items) if (!id || item.id === id) item.read = true;
    if (unreadOnly) lists.notifications.items = lists.notifications.items.filter(item => !item.read);
    renderList('notifications'); await poller.refresh();
  } catch (error) { errorAt(target, error); }
}
function renderList(kind) {
  const rows = $(`#${kind}-rows`);
  if (!rows || !profile) return;
  const list = lists[kind]; rows.replaceChildren();
  if (!list.items.length) rows.append(node('p', list.loaded ? 'Nothing here yet.' : 'Loading…', 'muted'));
  for (const item of list.items) {
    const row = node('article', undefined, `row${kind === 'notifications' && !item.read ? ' unread' : ''}`);
    row.dataset.id = item.id;
    if (kind === 'notifications') {
      row.append(node('p', item.text), node('small', date(item.createdAt)));
      if (!item.read) row.append(button('Mark as read', () => markRead(item.id), 'link'));
    } else if (kind === 'entries') {
      const top = node('div', undefined, 'row-top');
      top.append(node('strong', item.type), node('strong', `${item.amount > 0 ? '+' : ''}${money(item.amount)}`));
      row.append(top);
      if (item.counterparty) row.append(node('p', item.counterparty.displayName));
      if (item.note) row.append(node('p', item.note));
      row.append(node('small', `Balance after: ${money(item.balanceAfter)}`), node('small', date(item.createdAt)), node('small', `Reference: ${item.referenceId}`));
    } else {
      const top = node('div', undefined, 'row-top'); top.append(node('strong', money(item.amount)), status(item.status));
      row.append(top, node('small', date(item.createdAt)), node('small', `Reference: ${item.id}`));
      if (item.failureReason) row.append(node('p', `Reason: ${item.failureReason}`));
    }
    rows.append(row);
  }
  const more = $(`[data-more="${kind}"]`);
  more.hidden = !list.cursor; more.disabled = list.busy;
  more.textContent = list.busy ? 'Loading…' : 'Load more';
}

document.querySelector('.brand').onclick = event => { event.preventDefault(); if (profile) navigate('home'); };
window.addEventListener('hashchange', () => { if (profile) navigate(location.hash.slice(1)); });
const token = sessionStorage.getItem(tokenKey);
if (token) startSession(token);
else showAuth();
