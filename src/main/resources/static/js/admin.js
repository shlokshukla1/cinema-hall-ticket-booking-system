// Admin console: a small hash router over the section modules.
import { renderShell, requireRole } from './layout.js';
import { h, replace } from './ui.js';
import { renderDashboard } from './admin-dashboard.js';
import { renderMovies, renderPricing } from './admin-catalogue.js';
import { renderVenues } from './admin-venues.js';
import { renderShows } from './admin-shows.js';
import { renderAudit, renderBookings } from './admin-records.js';

const main = renderShell();

const SECTIONS = {
  dashboard: { label: 'Dashboard', render: renderDashboard },
  shows: { label: 'Schedules', render: renderShows },
  movies: { label: 'Movies', render: renderMovies },
  venues: { label: 'Theatres & screens', render: renderVenues },
  pricing: { label: 'Prices & policy', render: renderPricing },
  bookings: { label: 'Bookings', render: renderBookings },
  audit: { label: 'Audit trail', render: renderAudit },
};

if (requireRole(main, 'ADMIN')) {
  const tabs = h('nav', { class: 'admin-tabs', 'aria-label': 'Admin sections' });
  const content = h('div', {});
  replace(main, h('div', { class: 'admin-layout' }, tabs, content));

  function route() {
    const key = SECTIONS[location.hash.slice(1)] ? location.hash.slice(1) : 'dashboard';
    replace(tabs, Object.entries(SECTIONS).map(([id, section]) => h('a', {
      href: `#${id}`,
      'aria-current': id === key ? 'page' : null,
    }, section.label)));
    document.title = `${SECTIONS[key].label} · Cinema Hall admin`;
    replace(content);
    SECTIONS[key].render(content);
  }

  addEventListener('hashchange', route);
  route();
}
