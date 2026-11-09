// HTTP client and session storage. Role checks here are for navigation only; the server enforces every rule.
const SESSION_KEY = 'cinema.session';

export class ApiError extends Error {
  constructor(status, code, message, fieldErrors = []) {
    super(message);
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

function readStoredSession() {
  try {
    return JSON.parse(localStorage.getItem(SESSION_KEY));
  } catch {
    return null;
  }
}

export function getSession() {
  const session = readStoredSession();
  if (!session) return null;
  if (new Date(session.expiresAt) <= new Date()) {
    clearSession();
    return null;
  }
  return session;
}

export function saveSession(auth) {
  localStorage.setItem(SESSION_KEY, JSON.stringify({
    token: auth.token,
    expiresAt: auth.expiresAt,
    user: auth.user,
  }));
}

export function clearSession() {
  try {
    localStorage.removeItem(SESSION_KEY);
  } catch {
    // Storage unavailable: nothing to clear.
  }
}

export function loginUrl() {
  const next = location.pathname + location.search + location.hash;
  return `/login.html?next=${encodeURIComponent(next)}`;
}

/** Only same-site paths are accepted as a post-login destination, which prevents open redirects. */
export function safeNext(value) {
  return value && value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/\\') ? value : '/';
}

async function request(path, { method = 'GET', body, form, headers = {} } = {}) {
  const session = getSession();
  const allHeaders = { ...headers };
  if (session) allHeaders.Authorization = `Bearer ${session.token}`;
  if (body !== undefined) allHeaders['Content-Type'] = 'application/json';

  let response;
  try {
    response = await fetch(path, {
      method,
      headers: allHeaders,
      // A FormData body sets its own multipart Content-Type.
      body: form || (body === undefined ? undefined : JSON.stringify(body)),
    });
  } catch {
    throw new ApiError(0, 'NETWORK', 'Could not reach the server. Check your connection and try again.');
  }

  if (response.status === 401 && session) {
    clearSession();
    location.assign(loginUrl());
    throw new ApiError(401, 'UNAUTHENTICATED', 'Your session has ended. Please log in again.');
  }
  return response;
}

async function toError(response) {
  let payload = {};
  try {
    payload = await response.json();
  } catch {
    // A non-JSON failure falls through to the generic message.
  }
  return new ApiError(response.status, payload.code || 'ERROR',
    payload.message || 'Something went wrong. Please try again.', payload.fieldErrors || []);
}

export async function api(path, options) {
  const response = await request(path, options);
  if (!response.ok) throw await toError(response);
  if (response.status === 204) return null;
  return response.json();
}

export async function apiBlob(path) {
  const response = await request(path);
  if (!response.ok) throw await toError(response);
  return response.blob();
}

export function query(params) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, value);
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

export function uuid() {
  if (crypto.randomUUID) return crypto.randomUUID();
  // randomUUID needs a secure context; plain http on a LAN address falls back to this.
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');
}
