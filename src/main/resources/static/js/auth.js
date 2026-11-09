// Shared by the login and registration pages; the page's data-mode attribute selects which one.
import { api, safeNext, saveSession } from './api.js';
import { renderShell } from './layout.js';
import { applyFieldErrors, field, h, replace } from './ui.js';

const main = renderShell();
const mode = document.body.dataset.mode;
const isLogin = mode === 'login';
const next = safeNext(new URLSearchParams(location.search).get('next'));

const specs = isLogin
  ? [
    { name: 'email', label: 'Email', type: 'email', required: true, autocomplete: 'username' },
    { name: 'password', label: 'Password', type: 'password', required: true, autocomplete: 'current-password' },
  ]
  : [
    { name: 'fullName', label: 'Full name', required: true, autocomplete: 'name', maxlength: 120 },
    { name: 'email', label: 'Email', type: 'email', required: true, autocomplete: 'username' },
    { name: 'password', label: 'Password', type: 'password', required: true, autocomplete: 'new-password', hint: '8 or more characters, with at least one letter and one number.' },
  ];

const fields = specs.map((spec) => field(spec));
const formError = h('div', { class: 'notice bad', role: 'alert', hidden: true });
const submit = h('button', { type: 'submit', class: 'primary', style: 'width:100%' }, isLogin ? 'Log in' : 'Create account');

async function onSubmit(event) {
  event.preventDefault();
  formError.hidden = true;
  fields.forEach((f) => f.setError(null));
  submit.disabled = true;
  try {
    const body = Object.fromEntries(fields.map((f) => [f.name, f.read()]));
    const auth = await api(`/api/auth/${isLogin ? 'login' : 'register'}`, { method: 'POST', body });
    saveSession(auth);
    location.assign(next);
  } catch (error) {
    if (!applyFieldErrors(fields, error)) {
      formError.textContent = error.message;
      formError.hidden = false;
    }
    submit.disabled = false;
  }
}

const alternate = isLogin
  ? h('p', { class: 'small', style: 'margin-top:14px' }, 'New here? ', h('a', { href: `/register.html?next=${encodeURIComponent(next)}` }, 'Create an account'))
  : h('p', { class: 'small', style: 'margin-top:14px' }, 'Already registered? ', h('a', { href: `/login.html?next=${encodeURIComponent(next)}` }, 'Log in'));

// Each account is one large button, so it fits any screen width and the whole card can be clicked.
const demoAccount = (role, email, password) => h('button', {
  type: 'button',
  class: 'demo-account',
  'aria-label': `Fill in ${role} demo account`,
  onclick: () => {
    fields[0].control.value = email;
    fields[1].control.value = password;
    submit.focus();
  },
},
h('span', { class: 'demo-account-head' }, h('strong', {}, role), h('span', { class: 'demo-use' }, 'Use')),
h('code', {}, email),
h('code', {}, password));

replace(main, h('div', { class: 'auth-card' },
  h('form', { class: 'card', novalidate: true, onsubmit: onSubmit },
    h('h1', {}, isLogin ? 'Log in' : 'Create your account'),
    h('div', { class: 'form-grid' }, formError, fields.map((f) => f.element), submit),
    alternate),
  isLogin ? h('details', { class: 'card demo-box', open: true },
    h('summary', {}, 'Dummy demo accounts'),
    h('p', { class: 'hint' }, 'Click an account to fill in the form, then choose Log in.'),
    h('div', { class: 'demo-accounts' },
      demoAccount('Customer', 'customer@cinema.test', 'Customer@123'),
      demoAccount('Staff', 'staff@cinema.test', 'Staff@123'),
      demoAccount('Admin', 'admin@cinema.test', 'Admin@123'))) : null));
fields[0].control.focus();
