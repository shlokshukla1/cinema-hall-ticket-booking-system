import { api, query } from './api.js';
import { renderShell, requireLogin } from './layout.js';
import { badge, emptyState, errorState, formatDateTime, formatMoney, h, loading, pagination, replace } from './ui.js';

const main = renderShell();
const state = { status: '', page: 0 };
const list = h('div', { 'aria-live': 'polite' });

function bookingRow(booking) {
  return h('article', { class: 'card booking-item' },
    h('div', {},
      h('div', { class: 'row' },
        h('h2', {}, booking.movieTitle),
        booking.status === 'CANCELLED' ? badge('CANCELLED')
          : booking.checkedInAt ? badge('USED', 'used') : badge('CONFIRMED')),
      h('p', { class: 'subtle' }, `${formatDateTime(booking.startTime)} · ${booking.theatreName}, ${booking.screenName}`),
      h('p', { class: 'small' }, `Seats ${booking.seats.map((s) => s.label).join(', ')} · ${formatMoney(booking.totalAmount)} · `,
        h('span', { class: 'subtle' }, booking.reference)),
      booking.status === 'CANCELLED' && booking.payment
        ? h('p', { class: 'small subtle' }, `Refund ${formatMoney(booking.payment.refundAmount)}: ${booking.payment.refundStatus.replaceAll('_', ' ').toLowerCase()}`)
        : null),
    h('a', { class: 'btn', href: `/booking.html?ref=${encodeURIComponent(booking.reference)}` }, booking.status === 'CONFIRMED' ? 'View ticket' : 'View details'));
}

async function load() {
  replace(list, loading('Loading your bookings…'));
  try {
    const page = await api(`/api/bookings${query({ status: state.status, page: state.page, size: 8 })}`);
    if (!page.content.length) {
      replace(list, emptyState(state.status ? 'No bookings with this status.' : 'You have no bookings yet.',
        h('a', { class: 'btn primary', href: '/' }, 'Browse movies')));
      return;
    }
    replace(list, h('div', { class: 'stack' }, page.content.map(bookingRow)),
      pagination(page, (next) => { state.page = next; load(); scrollTo(0, 0); }));
  } catch (error) {
    replace(list, errorState(error, load));
  }
}

if (requireLogin()) {
  const status = h('select', {
    id: 'status-filter',
    onchange: (event) => { state.status = event.target.value; state.page = 0; load(); },
  }, h('option', { value: '' }, 'All bookings'), h('option', { value: 'CONFIRMED' }, 'Confirmed'), h('option', { value: 'CANCELLED' }, 'Cancelled'));
  replace(main,
    h('div', { class: 'page-head' },
      h('h1', {}, 'My bookings'),
      h('div', { style: 'min-width:200px' }, h('label', { for: 'status-filter' }, 'Status'), status)),
    list);
  load();
}
