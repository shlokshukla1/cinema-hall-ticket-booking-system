// DOM helpers. All text goes through textContent, so data from the server is never parsed as HTML.

export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  Object.entries(attrs || {}).forEach(([key, value]) => {
    if (value === undefined || value === null || value === false) return;
    if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'class') el.className = value;
    else if (key === 'value') el.value = value;
    else if (key === 'checked' || key === 'disabled' || key === 'selected') el[key] = Boolean(value);
    else el.setAttribute(key, value === true ? '' : value);
  });
  append(el, children);
  return el;
}

function append(el, children) {
  children.flat(Infinity).forEach((child) => {
    if (child === undefined || child === null || child === false) return;
    el.append(child instanceof Node ? child : String(child));
  });
}

export function replace(el, ...children) {
  el.replaceChildren();
  append(el, children);
}

const money = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });
export const formatMoney = (value) => money.format(Number(value || 0));
export const formatMoneyShort = (value) => `₹${Math.round(Number(value || 0)).toLocaleString('en-IN')}`;

export const formatDateTime = (iso) => iso ? new Date(iso).toLocaleString(undefined, {
  weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit',
}) : '';
export const formatTime = (iso) => new Date(iso).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
export const formatDate = (iso) => new Date(iso).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' });
export const formatRuntime = (minutes) => `${Math.floor(minutes / 60)}h ${minutes % 60}m`;

/** Value for an <input type="datetime-local">, in the browser's own timezone. */
export function toLocalInput(iso) {
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export const loading = (text = 'Loading…') => h('div', { class: 'state', role: 'status' }, h('div', { class: 'spinner' }), text);
export const emptyState = (text, action) => h('div', { class: 'state' }, h('p', {}, text), action ? h('p', { style: 'margin-top:12px' }, action) : null);

export function errorState(error, retry) {
  return h('div', { class: 'notice bad', role: 'alert' },
    h('p', {}, error.message || 'Something went wrong.'),
    retry ? h('p', { style: 'margin-top:8px' }, h('button', { type: 'button', onclick: retry }, 'Try again')) : null);
}

export function toast(message, kind = '') {
  let region = document.querySelector('.toasts');
  if (!region) region = h('div', { class: 'toasts', role: 'status', 'aria-live': 'polite' });
  // A modal dialog sits in the top layer, so toasts must live inside it to stay visible.
  (document.querySelector('dialog[open]') || document.body).append(region);
  const item = h('div', { class: `toast ${kind}` }, message);
  region.append(item);
  setTimeout(() => {
    item.remove();
    if (!region.children.length) region.remove();
  }, 5000);
}

/** Removes a closed dialog, first moving any toast inside it back to the page so the message is not lost. */
function discardDialog(dialog) {
  const region = dialog.querySelector('.toasts');
  if (region) document.body.append(region);
  dialog.remove();
}

const STATUS_KIND = {
  CONFIRMED: 'ok', SCHEDULED: 'ok', ACTIVE: 'ok', SIMULATED_REFUNDED: 'ok', CAPTURED: 'ok',
  CANCELLED: 'bad', ARCHIVED: 'bad', INACTIVE: 'bad',
  USED: 'warn', NOT_ELIGIBLE: 'warn',
};
export const badge = (status, label) => h('span', { class: `badge ${STATUS_KIND[status] || ''}` }, label || status.replaceAll('_', ' ').toLowerCase());

// Original artwork in /posters, named after the movie title. Admins can upload a poster for any movie;
// a movie with neither gets a generated cover.
const POSTER_FILES = {
  'kingdom-of-salt': 'kingdom-of-salt.svg',
  'last-bench-heroes': 'last-bench-heroes.svg',
  'monsoon-protocol': 'monsoon-protocol.svg',
  'orbit-of-small-things': 'orbit-of-small-things.svg',
  'paper-comets': 'paper-comets.svg',
  'saffron-street': 'saffron-street.svg',
  'the-lantern-keeper': 'the-lantern-keeper.svg',
  'the-ninth-junction': 'the-ninth-junction.svg',
};

export function poster(movie) {
  const slug = movie.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  // An admin-uploaded poster wins over the built-in artwork.
  const source = movie.posterUrl || (POSTER_FILES[slug] ? `/posters/${POSTER_FILES[slug]}` : null);
  if (source) {
    return h('div', { class: 'poster has-art', 'aria-hidden': 'true' },
      h('img', { src: source, alt: '', width: 400, height: 600, draggable: 'false' }),
      h('span', { class: 'cert' }, movie.certificate));
  }
  const palettes = [
    ['#5b2a86', '#e0568a'], ['#14505c', '#38b28f'], ['#7a2e1d', '#e88f3b'], ['#1d3a78', '#4c9fe0'],
    ['#4d1f3d', '#b5456f'], ['#23483a', '#a2b84a'], ['#3a2c6b', '#7f7fe0'], ['#6a4413', '#d9b23c'],
  ];
  let hash = 0;
  for (const c of movie.title) hash = (hash * 31 + c.charCodeAt(0)) >>> 0;
  const [from, to] = palettes[hash % palettes.length];
  const el = h('div', { class: 'poster', 'aria-hidden': 'true' }, h('span', { class: 'cert' }, movie.certificate), movie.title);
  el.style.background = `linear-gradient(${(hash % 90) + 120}deg, ${from}, ${to})`;
  return el;
}

export function pagination(page, onPage) {
  if (!page || page.totalPages <= 1) return null;
  return h('nav', { class: 'pagination', 'aria-label': 'Pages' },
    h('button', { type: 'button', disabled: page.page === 0, onclick: () => onPage(page.page - 1) }, 'Previous'),
    h('span', { class: 'subtle small' }, `Page ${page.page + 1} of ${page.totalPages} (${page.totalElements} total)`),
    h('button', { type: 'button', disabled: page.page >= page.totalPages - 1, onclick: () => onPage(page.page + 1) }, 'Next'));
}

/** columns: [{ label, num?, render(row) }] */
export function table(columns, rows, caption) {
  return h('div', { class: 'table-wrap', tabindex: '0', role: 'region', 'aria-label': caption },
    h('table', {},
      h('caption', { class: 'sr-only' }, caption),
      h('thead', {}, h('tr', {}, columns.map((c) => h('th', { scope: 'col', class: c.num ? 'num' : '' }, c.label)))),
      h('tbody', {}, rows.map((row) => h('tr', {}, columns.map((c) => h('td', { class: c.num ? 'num' : c.nowrap ? 'nowrap' : '' }, c.render(row))))))));
}

let fieldCounter = 0;

/** A labelled control. Supports text-like inputs, select, textarea, check groups and custom controls. */
export function field(spec, initial) {
  const id = `f${++fieldCounter}`;
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const error = h('div', { class: 'field-error', id: errorId, hidden: true });
  const hint = spec.hint ? h('div', { class: 'hint', id: hintId }, spec.hint) : null;
  const describedBy = [spec.hint ? hintId : null, errorId].filter(Boolean).join(' ');
  const value = initial ?? spec.value ?? '';
  let control;
  let read;
  let wrap;

  if (spec.type === 'checks') {
    const boxes = spec.options.map((o) => h('input', { type: 'checkbox', value: o.value, checked: (value || []).includes(o.value) }));
    wrap = h('fieldset', { 'aria-describedby': describedBy }, h('legend', {}, spec.label),
      h('div', { class: 'check-group' }, spec.options.map((o, i) => h('label', { class: 'check' }, boxes[i], o.label))));
    read = () => boxes.filter((b) => b.checked).map((b) => Number(b.value));
    control = boxes[0] || wrap;
  } else if (spec.type === 'custom') {
    const custom = spec.build(value);
    wrap = h('fieldset', {}, h('legend', {}, spec.label), custom.element);
    read = custom.read;
    control = custom.element;
  } else {
    const common = {
      id, name: spec.name, required: spec.required, 'aria-describedby': describedBy,
      autocomplete: spec.autocomplete, min: spec.min, max: spec.max, step: spec.step,
      maxlength: spec.maxlength, placeholder: spec.placeholder, inputmode: spec.inputmode,
    };
    if (spec.type === 'select') {
      control = h('select', common,
        spec.placeholderOption !== undefined ? h('option', { value: '' }, spec.placeholderOption) : null,
        spec.options.map((o) => h('option', { value: o.value }, o.label)));
      control.value = value;
    } else if (spec.type === 'textarea') {
      control = h('textarea', { ...common, rows: 3 });
      control.value = value;
    } else {
      control = h('input', { ...common, type: spec.type || 'text' });
      control.value = value;
    }
    if (spec.onChange) control.addEventListener('change', () => spec.onChange(control.value));
    wrap = h('div', {}, h('label', { for: id }, spec.label, spec.required ? null : h('span', { class: 'subtle' }, ' (optional)')), control);
    read = () => {
      const raw = control.value;
      if (spec.type === 'number' || spec.numeric) return raw === '' ? null : Number(raw);
      return raw;
    };
  }

  const element = h('div', { class: 'field' }, wrap, hint, error);
  return {
    name: spec.name,
    element,
    control,
    read,
    setError(message) {
      error.textContent = message || '';
      error.hidden = !message;
      if (control.setAttribute) {
        if (message) control.setAttribute('aria-invalid', 'true');
        else control.removeAttribute('aria-invalid');
      }
    },
  };
}

/** Maps a server validation error onto its fields. Returns true if at least one field took a message. */
export function applyFieldErrors(fields, error) {
  fields.forEach((f) => f.setError(null));
  let matched = false;
  (error.fieldErrors || []).forEach(({ field: name, message }) => {
    const target = fields.find((f) => f.name === name || name.startsWith(`${f.name}.`) || name.startsWith(`${f.name}[`));
    if (target) {
      target.setError(message);
      matched = true;
    }
  });
  const first = fields.find((f) => f.control?.getAttribute?.('aria-invalid') === 'true');
  first?.control.focus();
  return matched;
}

/** Modal form. onSubmit(values) may throw an ApiError, which is shown in place and keeps the dialog open. */
export function formDialog({ title, fields: specs, initial = {}, submitLabel = 'Save', intro, onSubmit }) {
  const fields = specs.map((spec) => field(spec, initial[spec.name]));
  const formError = h('div', { class: 'notice bad', role: 'alert', hidden: true });
  const submit = h('button', { type: 'submit', class: 'primary' }, submitLabel);
  const cancel = h('button', { type: 'button', onclick: () => dialog.close() }, 'Cancel');
  const form = h('form', { novalidate: true },
    h('h2', {}, title),
    intro ? h('p', { class: 'subtle small', style: 'margin-top:6px' }, intro) : null,
    h('div', { class: 'form-grid' }, formError, fields.map((f) => f.element)),
    h('div', { class: 'dialog-actions' }, cancel, submit));
  const dialog = h('dialog', {}, form);
  dialog.addEventListener('close', () => discardDialog(dialog));
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    formError.hidden = true;
    fields.forEach((f) => f.setError(null));
    submit.disabled = true;
    try {
      const values = Object.fromEntries(fields.map((f) => [f.name, f.read()]));
      await onSubmit(values);
      dialog.close();
    } catch (error) {
      const matched = applyFieldErrors(fields, error);
      if (!matched) {
        formError.textContent = error.message;
        formError.hidden = false;
      }
    } finally {
      submit.disabled = false;
    }
  });
  document.body.append(dialog);
  dialog.showModal();
  return dialog;
}

export function confirmDialog({ title, body, confirmLabel = 'Confirm', cancelLabel = 'Go back', danger = false }) {
  return new Promise((resolve) => {
    let answer = false;
    const dialog = h('dialog', { 'aria-labelledby': 'confirm-title' },
      h('h2', { id: 'confirm-title' }, title),
      h('div', { style: 'margin-top:10px' }, body),
      h('div', { class: 'dialog-actions' },
        h('button', { type: 'button', onclick: () => dialog.close() }, cancelLabel),
        h('button', {
          type: 'button',
          class: danger ? 'danger' : 'primary',
          onclick: () => { answer = true; dialog.close(); },
        }, confirmLabel)));
    dialog.addEventListener('close', () => { discardDialog(dialog); resolve(answer); });
    document.body.append(dialog);
    dialog.showModal();
  });
}

/** Runs an async action with its button disabled, which blocks accidental double submits. */
export async function withBusy(button, action) {
  button.disabled = true;
  try {
    return await action();
  } finally {
    button.disabled = false;
  }
}
