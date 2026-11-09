import { api, apiBlob } from './api.js';
import { renderShell, requireLogin } from './layout.js';
import { badge, confirmDialog, errorState, formatDateTime, formatMoney, h, loading, replace, toast } from './ui.js';

const main = renderShell();
const params = new URLSearchParams(location.search);
const reference = params.get('ref');
const justBooked = params.get('new') === '1';

function ticketStatus(booking) {
  if (booking.status === 'CANCELLED') return badge('CANCELLED');
  if (booking.checkedInAt) return badge('USED', 'used');
  return badge('CONFIRMED', 'valid');
}

async function qrImage(booking) {
  const image = h('img', { alt: `QR code for ticket ${booking.reference}`, width: 190, height: 190 });
  try {
    image.src = URL.createObjectURL(await apiBlob(`/api/bookings/${booking.reference}/qr`));
  } catch {
    return h('p', { class: 'small' }, 'The QR code could not be loaded. The reference below still works.');
  }
  return image;
}

function paymentPanel(booking) {
  const payment = booking.payment;
  if (!payment) return null;
  return h('section', { class: 'card', style: 'max-width:720px' },
    h('h2', {}, 'Payment'),
    h('div', { style: 'margin-top:8px' },
      h('div', { class: 'summary-line' }, h('span', {}, 'Paid (simulated)'), h('span', {}, formatMoney(payment.amount))),
      h('div', { class: 'summary-line' }, h('span', {}, 'Method'), h('span', {}, payment.method)),
      h('div', { class: 'summary-line' }, h('span', {}, 'Payment reference'), h('span', { class: 'small', style: 'overflow-wrap:anywhere;text-align:right' }, payment.providerRef)),
      booking.status === 'CANCELLED' ? [
        h('div', { class: 'summary-line' }, h('span', {}, 'Refund'), h('span', {}, `${formatMoney(payment.refundAmount)} (${payment.refundPercent}%)`)),
        h('div', { class: 'summary-line' }, h('span', {}, 'Refund status'), badge(payment.refundStatus)),
      ] : null));
}

async function cancelPanel(booking) {
  if (booking.status !== 'CONFIRMED') return null;
  let quote;
  try {
    quote = await api(`/api/bookings/${booking.reference}/cancellation-quote`);
  } catch {
    return null;
  }
  if (!quote.cancellable) {
    return h('p', { class: 'subtle small no-print' }, `Cancellation unavailable: ${quote.reason}.`);
  }
  return h('section', { class: 'card no-print', style: 'max-width:720px' },
    h('h2', {}, 'Change of plans?'),
    h('p', { class: 'subtle', style: 'margin:6px 0 12px' },
      `Cancelling now refunds ${formatMoney(quote.refundAmount)} (${quote.refundPercent}%). The refund is simulated.`),
    h('button', { type: 'button', class: 'danger', onclick: (event) => cancelBooking(event.currentTarget, booking, quote) }, 'Cancel this booking'));
}

async function cancelBooking(button, booking, quote) {
  const yes = await confirmDialog({
    title: 'Cancel this booking?',
    body: h('p', {}, `Your seats will be released and ${formatMoney(quote.refundAmount)} (${quote.refundPercent}%) refunded. This cannot be undone.`),
    confirmLabel: 'Yes, cancel booking',
    cancelLabel: 'Keep booking',
    danger: true,
  });
  if (!yes) return;
  button.disabled = true;
  try {
    await api(`/api/bookings/${booking.reference}/cancel`, { method: 'POST' });
    toast('Your booking was cancelled.', 'ok');
    params.delete('new');
    history.replaceState(null, '', `?${params}`);
    await load(false);
  } catch (error) {
    toast(error.message, 'bad');
    button.disabled = false;
    await load(false);
  }
}

async function load(showSuccess = justBooked) {
  replace(main, loading('Loading your ticket…'));
  try {
    const booking = await api(`/api/bookings/${encodeURIComponent(reference)}`);
    document.title = `Ticket ${booking.reference} · Cinema Hall`;
    const valid = booking.status === 'CONFIRMED' && !booking.checkedInAt;
    const [qr, cancel] = await Promise.all([valid ? qrImage(booking) : null, cancelPanel(booking)]);
    replace(main, h('div', { class: 'stack' },
      showSuccess ? h('div', { class: 'notice ok', role: 'status' }, 'Booking confirmed. Show this ticket at the entrance.') : null,
      h('div', { class: 'row spread no-print', style: 'max-width:720px' },
        h('h1', {}, 'Your ticket'),
        h('div', { class: 'row' },
          h('a', { href: '/bookings.html' }, 'All bookings'),
          valid ? h('button', { type: 'button', class: 'small-btn', onclick: () => print() }, 'Print') : null)),
      h('article', { class: `ticket ${booking.status === 'CANCELLED' ? 'void' : ''}` },
        h('div', { class: 'ticket-main' },
          h('h2', {}, booking.movieTitle),
          h('p', {}, `${booking.format} · ${booking.language}`),
          h('dl', {},
            h('div', {}, h('dt', {}, 'When'), h('dd', {}, formatDateTime(booking.startTime))),
            h('div', {}, h('dt', {}, 'Where'), h('dd', {}, `${booking.theatreName}, ${booking.city}`)),
            h('div', {}, h('dt', {}, 'Screen'), h('dd', {}, booking.screenName)),
            h('div', {}, h('dt', {}, 'Seats'), h('dd', {}, booking.seats.map((s) => s.label).join(', '))),
            h('div', {}, h('dt', {}, 'Total'), h('dd', {}, formatMoney(booking.totalAmount))),
            h('div', {}, h('dt', {}, 'Status'), h('dd', {}, ticketStatus(booking))))),
        h('div', { class: 'ticket-stub' },
          qr,
          !valid ? h('p', {}, booking.status === 'CANCELLED' ? 'This ticket was cancelled.' : `Used ${formatDateTime(booking.checkedInAt)}`) : null,
          h('div', { class: 'reference' }, booking.reference),
          booking.ticketToken && valid ? h('details', {}, h('summary', { class: 'small', style: 'color:#7a4a00' }, 'Show entry code'), h('p', { class: 'token' }, booking.ticketToken)) : null)),
      booking.status === 'CANCELLED' ? h('div', { class: 'notice', style: 'max-width:720px' },
        `Cancelled ${formatDateTime(booking.cancelledAt)}.`,
        booking.cancellationReason === 'SHOW_CANCELLED' ? ' The show was cancelled by the cinema, so you are refunded in full.' : '') : null,
      paymentPanel(booking),
      cancel));
  } catch (error) {
    replace(main, errorState(error.status === 404 ? { message: 'We could not find that booking.' } : error, error.status === 404 ? null : () => load(showSuccess)),
      h('p', { style: 'margin-top:14px' }, h('a', { href: '/bookings.html' }, 'Back to my bookings')));
  }
}

if (requireLogin()) load();
