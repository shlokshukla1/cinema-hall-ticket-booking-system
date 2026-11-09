import { api, query } from './api.js';
import { badge, confirmDialog, emptyState, errorState, formatDateTime, formatMoney, h, loading, pagination, replace, table, toast } from './ui.js';

export function renderBookings(container) {
  const state = { q: '', status: '', page: 0 };
  const list = h('div', { 'aria-live': 'polite' }, loading());

  async function cancel(booking) {
    let quote;
    try {
      quote = await api(`/api/bookings/${booking.reference}/cancellation-quote`);
    } catch (error) {
      toast(error.message, 'bad');
      return;
    }
    if (!quote.cancellable) {
      toast(`Cannot cancel: ${quote.reason}.`, 'bad');
      return;
    }
    const yes = await confirmDialog({
      title: `Cancel ${booking.reference}?`,
      body: h('p', {}, `${booking.customerEmail} will be refunded ${formatMoney(quote.refundAmount)} (${quote.refundPercent}%) under the current policy.`),
      confirmLabel: 'Cancel booking',
      cancelLabel: 'Keep booking',
      danger: true,
    });
    if (!yes) return;
    try {
      await api(`/api/bookings/${booking.reference}/cancel`, { method: 'POST' });
      toast('Booking cancelled.', 'ok');
    } catch (error) {
      toast(error.message, 'bad');
    }
    load();
  }

  async function load() {
    replace(list, loading());
    try {
      const page = await api(`/api/admin/bookings${query({ q: state.q, status: state.status, page: state.page, size: 15 })}`);
      if (!page.content.length) {
        replace(list, emptyState('No bookings match these filters.'));
        return;
      }
      replace(list, table([
        { label: 'Reference', render: (b) => h('a', { href: `/booking.html?ref=${encodeURIComponent(b.reference)}` }, b.reference) },
        { label: 'Customer', render: (b) => b.customerEmail },
        { label: 'Show', render: (b) => h('div', {}, b.movieTitle, h('div', { class: 'subtle small' }, formatDateTime(b.startTime))) },
        { label: 'Seats', render: (b) => b.seats.map((s) => s.label).join(', ') },
        { label: 'Total', num: true, render: (b) => formatMoney(b.totalAmount) },
        { label: 'Refund', num: true, render: (b) => (b.status === 'CANCELLED' && b.payment ? formatMoney(b.payment.refundAmount) : '') },
        { label: 'Status', render: (b) => (b.checkedInAt ? badge('USED', 'used') : badge(b.status)) },
        {
          label: 'Actions',
          render: (b) => (b.status === 'CONFIRMED' && !b.checkedInAt
            ? h('button', { type: 'button', class: 'small-btn danger', onclick: () => cancel(b) }, 'Cancel') : ''),
        },
      ], page.content, 'Bookings'), pagination(page, (next) => { state.page = next; load(); }));
    } catch (error) {
      replace(list, errorState(error, load));
    }
  }

  const search = h('input', { id: 'booking-search', type: 'search', placeholder: 'Reference or email' });
  let debounce;
  search.addEventListener('input', () => {
    clearTimeout(debounce);
    debounce = setTimeout(() => { state.q = search.value.trim(); state.page = 0; load(); }, 280);
  });
  const status = h('select', { id: 'booking-status', onchange: (e) => { state.status = e.target.value; state.page = 0; load(); } },
    h('option', { value: '' }, 'All'), h('option', { value: 'CONFIRMED' }, 'Confirmed'), h('option', { value: 'CANCELLED' }, 'Cancelled'));

  replace(container,
    h('div', { class: 'page-head' }, h('h1', {}, 'Bookings')),
    h('div', { class: 'filters' },
      h('div', {}, h('label', { for: 'booking-search' }, 'Search'), search),
      h('div', {}, h('label', { for: 'booking-status' }, 'Status'), status)),
    list);
  load();
}

export function renderAudit(container) {
  const state = { action: '', actor: '', page: 0 };
  const list = h('div', { 'aria-live': 'polite' }, loading());
  const ACTIONS = ['BOOKING_CONFIRMED', 'BOOKING_CANCELLED', 'SEATS_HELD', 'HOLD_RELEASED', 'HOLDS_EXPIRED', 'PAYMENT_DECLINED',
    'TICKET_VALIDATED', 'TICKET_REJECTED', 'SHOW_CREATED', 'SHOW_UPDATED', 'SHOW_CANCELLED', 'MOVIE_CREATED', 'MOVIE_UPDATED',
    'THEATRE_CREATED', 'SCREEN_CREATED', 'SEAT_UPDATED', 'SEAT_CATEGORY_UPDATED', 'CANCELLATION_POLICY_UPDATED', 'USER_REGISTERED'];

  async function load() {
    replace(list, loading());
    try {
      const page = await api(`/api/admin/audit-logs${query({ action: state.action, actor: state.actor, page: state.page, size: 25 })}`);
      if (!page.content.length) {
        replace(list, emptyState('No audit entries match these filters.'));
        return;
      }
      replace(list, table([
        { label: 'When', nowrap: true, render: (a) => formatDateTime(a.createdAt) },
        { label: 'Who', render: (a) => a.actorEmail },
        { label: 'Action', render: (a) => a.action.replaceAll('_', ' ').toLowerCase() },
        { label: 'Record', render: (a) => `${a.entityType.toLowerCase()} ${a.entityId.slice(0, 13)}` },
        { label: 'Details', render: (a) => a.details || '' },
      ], page.content, 'Audit trail'), pagination(page, (next) => { state.page = next; load(); }));
    } catch (error) {
      replace(list, errorState(error, load));
    }
  }

  const action = h('select', { id: 'audit-action', onchange: (e) => { state.action = e.target.value; state.page = 0; load(); } },
    h('option', { value: '' }, 'All actions'), ACTIONS.map((a) => h('option', { value: a }, a.replaceAll('_', ' ').toLowerCase())));
  const actor = h('input', { id: 'audit-actor', type: 'search', placeholder: 'Email contains…' });
  let debounce;
  actor.addEventListener('input', () => {
    clearTimeout(debounce);
    debounce = setTimeout(() => { state.actor = actor.value.trim(); state.page = 0; load(); }, 280);
  });

  replace(container,
    h('div', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Audit trail'),
      h('p', { class: 'subtle' }, 'An append-only record of material business actions, including rejected attempts.'))),
    h('div', { class: 'filters' },
      h('div', {}, h('label', { for: 'audit-action' }, 'Action'), action),
      h('div', {}, h('label', { for: 'audit-actor' }, 'Who'), actor)),
    list);
  load();
}
