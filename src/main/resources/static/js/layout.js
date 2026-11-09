// Page shell: navigation, footer and the convenience guards for role-specific pages.
import { clearSession, getSession, loginUrl } from './api.js';
import { h, replace } from './ui.js';

function navLink(href, label) {
  const current = location.pathname === href || (href === '/' && location.pathname === '/index.html');
  return h('a', { href, 'aria-current': current ? 'page' : null }, label);
}

export function renderShell() {
  const session = getSession();
  const role = session?.user.role;
  const links = [navLink('/', 'Movies')];
  if (session) links.push(navLink('/bookings.html', 'My bookings'));
  if (role === 'STAFF' || role === 'ADMIN') links.push(navLink('/staff.html', 'Entry gate'));
  if (role === 'ADMIN') links.push(navLink('/admin.html', 'Admin'));

  const initials = session
    ? session.user.fullName.trim().split(/\s+/).slice(0, 2).map((word) => word[0].toUpperCase()).join('') : '';
  const user = session
    ? h('div', { class: 'nav-user' },
      h('div', { class: 'user-chip' },
        h('span', { class: 'avatar', 'aria-hidden': 'true' }, initials),
        h('span', { class: 'user-text' },
          h('span', { class: 'user-name' }, session.user.fullName),
          h('span', { class: 'user-role' }, role.toLowerCase()))),
      h('button', {
        type: 'button',
        class: 'logout-btn',
        onclick: () => { clearSession(); location.assign('/'); },
      }, 'Log out'))
    : h('div', { class: 'nav-user' },
      h('a', { class: 'login-link', href: loginUrl() }, 'Log in'),
      h('a', { class: 'btn small-btn primary', href: '/register.html' }, 'Sign up'));

  document.body.prepend(
    h('a', { class: 'skip-link', href: '#main' }, 'Skip to content'),
    h('header', { class: 'topbar' },
      h('div', { class: 'topbar-inner' },
        h('a', { class: 'brand', href: '/' },
          h('img', { src: '/logo.svg', alt: '', width: 32, height: 32 }),
          h('span', {}, 'Cinema', h('span', { class: 'brand-accent' }, 'Hall'))),
        h('nav', { class: 'nav-links', 'aria-label': 'Main' }, links),
        user)),
  );
  document.body.append(h('footer', { class: 'footer' }, 'Demo application. Payments and refunds are simulated; no real money moves.'));
  return document.getElementById('main');
}

/** Redirects anonymous visitors to log in. The API would refuse them anyway; this just saves a confusing page. */
export function requireLogin() {
  const session = getSession();
  if (!session) {
    location.replace(loginUrl());
    return null;
  }
  return session;
}

export function requireRole(main, ...roles) {
  const session = requireLogin();
  if (!session) return null;
  if (!roles.includes(session.user.role)) {
    replace(main, h('div', { class: 'notice bad', role: 'alert' }, 'Your account does not have access to this page.'));
    return null;
  }
  return session;
}
