// Simulated entry gate. Staff scan or type a ticket code; the server decides, and admits each ticket only once.
import { api } from './api.js';
import { renderShell, requireRole } from './layout.js';
import { formatDateTime, h, replace } from './ui.js';

const main = renderShell();

const HEADINGS = {
  TICKET_ALREADY_USED: 'Already used',
  TICKET_CANCELLED: 'Booking cancelled',
  TICKET_EXPIRED: 'Show finished',
  TICKET_NOT_FOUND: 'Ticket not found',
};

if (requireRole(main, 'STAFF', 'ADMIN')) {
  const input = h('input', { id: 'code', autocomplete: 'off', maxlength: 64, required: true, placeholder: 'CH-XXXXXXXX or scanned code' });
  const submit = h('button', { type: 'submit', class: 'primary' }, 'Validate ticket');
  const result = h('div', { 'aria-live': 'assertive' });
  const history = h('ul', { class: 'small subtle' });

  function log(code, outcome) {
    history.prepend(h('li', {}, `${new Date().toLocaleTimeString()}: ${code.slice(0, 14)}${code.length > 14 ? '…' : ''}, ${outcome}`));
  }

  async function validate(event) {
    event.preventDefault();
    const code = input.value.trim();
    if (!code) {
      replace(result, h('div', { class: 'gate-result bad' }, h('p', {}, 'Enter or scan a ticket code first.')));
      input.focus();
      return;
    }
    submit.disabled = true;
    try {
      const ticket = await api('/api/staff/tickets/validate', { method: 'POST', body: { code } });
      replace(result, h('div', { class: 'gate-result ok' },
        h('h2', {}, '✓ Admit'),
        h('p', {}, h('strong', {}, ticket.movieTitle), ` · ${formatDateTime(ticket.startTime)}`),
        h('p', {}, `${ticket.theatreName}, ${ticket.screenName}`),
        h('p', {}, h('strong', {}, `Seats ${ticket.seats.join(', ')}`), ` · ${ticket.customerName}`),
        h('p', { class: 'small' }, `Reference ${ticket.reference}`)));
      log(code, 'admitted');
    } catch (error) {
      replace(result, h('div', { class: 'gate-result bad' },
        h('h2', {}, `✕ ${HEADINGS[error.code] || 'Could not validate'}`),
        h('p', {}, error.message)));
      log(code, (HEADINGS[error.code] || 'error').toLowerCase());
    } finally {
      submit.disabled = false;
      input.value = '';
      input.focus();
    }
  }

  replace(main, h('div', { class: 'gate' },
    h('h1', {}, 'Entry gate'),
    h('p', { class: 'subtle', style: 'margin:6px 0 16px' }, 'Scan the QR code, or type the booking reference. Each ticket admits once.'),
    h('form', { class: 'card', novalidate: true, onsubmit: validate },
      h('label', { for: 'code' }, 'Ticket code'),
      h('div', { class: 'row', style: 'flex-wrap:nowrap' }, input, h('div', { style: 'flex:none' }, submit))),
    result,
    h('section', { class: 'section' }, h('h2', {}, 'This session'), history)));
  input.focus();
}
