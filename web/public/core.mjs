// UI-6 / MNY-1: parse decimal text exactly; only format/representation checks here.
export function cents(value) {
  const match = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(value.trim());
  if (!match) throw new Error('Enter a dollar amount with at most two decimal places.');
  const result = (match[1] ? -1n : 1n) * (BigInt(match[2]) * 100n + BigInt((match[3] || '').padEnd(2, '0')));
  if (result > BigInt(Number.MAX_SAFE_INTEGER) || result < BigInt(Number.MIN_SAFE_INTEGER)) {
    throw new Error('That amount cannot be represented exactly.');
  }
  return Number(result);
}

export function money(value) {
  const n = BigInt(value);
  const absolute = n < 0n ? -n : n;
  return `${n < 0n ? '-' : ''}$${(absolute / 100n).toLocaleString('en-US')}.${String(absolute % 100n).padStart(2, '0')}`;
}

export const messages = {
  VALIDATION_FAILED: 'Check your input. The server could not accept these values.',
  USERNAME_TAKEN: 'That username is already taken. Choose another username.',
  INVALID_CREDENTIALS: 'The username or password is incorrect.',
  RATE_LIMITED: 'Too many login attempts. Please wait before trying again.',
  WALLET_NOT_FOUND: 'No wallet was found for this username. The user may need to open a wallet.',
  RECIPIENT_NOT_FOUND: 'The recipient wallet could not be found.',
  SAME_WALLET: 'You cannot send money to your own wallet. Choose another recipient.',
  INSUFFICIENT_FUNDS: 'Your wallet has insufficient funds for this payment.',
  DAILY_LIMIT_EXCEEDED: 'This payment would exceed your daily spending limit.',
  REQUEST_IN_PROGRESS: 'This payment is still being processed. Retry the original request.',
  IDEMPOTENCY_KEY_REQUIRED: 'The payment request is missing its reference. Please sign in again.',
  IDEMPOTENCY_KEY_REUSED: 'This payment reference was used with different details. Please contact support.',
  UNAUTHENTICATED: 'Your session has expired. Please log in again.',
  FORBIDDEN: 'You do not have permission to do that.',
  NOT_FOUND: 'The requested item could not be found.',
  INTERNAL_ERROR: 'The service could not complete the request. Please try again.',
  NETWORK_ERROR: 'The connection was interrupted. Please try again.'
};

export class ApiError extends Error {
  constructor(status, problem = {}) {
    const code = problem.code || (status ? 'INTERNAL_ERROR' : 'NETWORK_ERROR');
    super(`${messages[code] || 'The request could not be completed.'} (${code})`);
    this.status = status;
    this.code = code;
    this.problem = problem;
  }
}

// UI-4 / IDM-2: freeze both the key and the exact wire body before the first attempt.
export function newAction(kind, body) {
  return Object.freeze({ kind, key: crypto.randomUUID(), body: JSON.stringify(body) });
}

export function retryable(error) {
  return error instanceof ApiError && (!error.status || error.status >= 500 ||
    (error.status === 409 && error.code === 'REQUEST_IN_PROGRESS'));
}

// UI-7: every request belongs to one session. Late responses cannot enter a later session.
export class Client {
  constructor(onUnauthorized, transport = (...args) => fetch(...args)) {
    this.onUnauthorized = onUnauthorized;
    this.transport = transport;
    this.token = null;
    this.generation = 0;
    this.controllers = new Set();
  }
  reset(token = null) {
    this.generation++;
    for (const controller of this.controllers) controller.abort();
    this.controllers.clear();
    this.token = token;
  }
  async request(path, { method = 'GET', body, key, authenticated = true, withStatus = false } = {}) {
    const generation = this.generation;
    const controller = new AbortController();
    this.controllers.add(controller);
    const timeout = setTimeout(() => controller.abort(), 10000);
    try {
      const headers = { Accept: 'application/json' };
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      if (key) headers['Idempotency-Key'] = key;
      if (authenticated && this.token) headers.Authorization = `Bearer ${this.token}`;
      const response = await this.transport(path, { method, body, headers, signal: controller.signal, cache: 'no-store' });
      if (generation !== this.generation) throw new DOMException('Session changed', 'AbortError');
      if (response.status === 401 && authenticated) {
        this.onUnauthorized();
        throw new DOMException('Session expired', 'AbortError');
      }
      const data = response.status === 204 ? null : await response.json().catch(() => ({}));
      if (generation !== this.generation) throw new DOMException('Session changed', 'AbortError');
      if (!response.ok) throw new ApiError(response.status, data);
      return withStatus ? { status: response.status, data } : data;
    } catch (error) {
      if (generation !== this.generation) throw new DOMException('Session changed', 'AbortError');
      if (error instanceof ApiError) throw error;
      throw new ApiError(0);
    } finally {
      clearTimeout(timeout);
      this.controllers.delete(controller);
    }
  }
  send(action) {
    return this.request(`/api/v1/${action.kind}`, { method: 'POST', key: action.key, body: action.body });
  }
}

// UI-5: all callers join a running refresh. Completion schedules the next tick.
export class Poller {
  constructor(task, delay = 2000) {
    this.task = task;
    this.delay = delay;
    this.enabled = false;
    this.running = null;
    this.epoch = 0;
  }
  start() {
    this.stop();
    this.enabled = true;
    return this.refresh();
  }
  stop() {
    this.enabled = false;
    this.epoch++;
    clearTimeout(this.timer);
    this.running = null;
  }
  refresh() {
    if (!this.enabled) return Promise.resolve();
    if (this.running) return this.running;
    clearTimeout(this.timer);
    const epoch = this.epoch;
    this.running = Promise.resolve().then(() => {
      if (epoch === this.epoch && this.enabled) return this.task();
    }).finally(() => {
      if (epoch !== this.epoch) return;
      this.running = null;
      if (this.enabled) this.timer = setTimeout(() => this.refresh(), this.delay);
    });
    return this.running;
  }
}
