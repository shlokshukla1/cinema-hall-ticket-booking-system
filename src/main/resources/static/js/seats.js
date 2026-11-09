// Seat selection, temporary hold with countdown, simulated payment and confirmation.
import { api, getSession, loginUrl, query, uuid } from './api.js';
import { renderShell } from './layout.js';
import { errorState, formatDateTime, formatMoney, h, loading, replace, toast } from './ui.js';

const POLL_MS = 10000;
const main = renderShell();
const showId = new URLSearchParams(location.search).get('show');

const state = {
  map: null,
  selected: new Set(), // seat ids chosen but not yet held
  hold: null,
  holdOffsetMs: 0,
  busy: false,
  focusSeatId: null,
};

const seatArea = h('div', { class: 'auditorium' });
const summary = h('aside', { class: 'card summary', 'aria-label': 'Your selection' });
const announcer = h('div', { class: 'sr-only', 'aria-live': 'assertive' });
let countdownTimer;
let pollTimer;

const seatLabel = (seat) => `${seat.row}${seat.number}`;
const heldIds = () => new Set(state.hold ? state.hold.seatIds : []);
const seatsById = () => new Map(state.map.seats.map((seat) => [seat.seatId, seat]));

function seatButton(seat) {
  const mine = heldIds().has(seat.seatId);
  const selected = state.selected.has(seat.seatId);
  const taken = seat.status !== 'AVAILABLE' && !mine;
  const stateName = mine ? 'held for you' : selected ? 'selected'
    : seat.status === 'BOOKED' ? 'booked' : seat.status === 'HELD' ? 'held by another customer' : 'available';
  const classes = ['seat'];
  if (mine) classes.push('selected', 'mine');
  else if (selected) classes.push('selected');
  else if (seat.status === 'BOOKED') classes.push('booked');
  else if (seat.status === 'HELD') classes.push('held');

  const button = h('button', {
    type: 'button',
    class: classes.join(' '),
    'data-seat': seat.seatId,
    tabindex: '-1',
    disabled: taken || Boolean(state.hold) || !state.map.show.bookable,
    'aria-pressed': String(selected || mine),
    'aria-label': `Row ${seat.row} seat ${seat.number}, ${seat.category}, ${formatMoney(seat.price)}, ${stateName}`,
    title: `${seatLabel(seat)} · ${seat.category} · ${formatMoney(seat.price)} · ${stateName}`,
    onclick: () => toggleSeat(seat),
  }, seat.status === 'BOOKED' && !mine ? '×' : String(seat.number));
  return button;
}

function toggleSeat(seat) {
  if (state.selected.has(seat.seatId)) state.selected.delete(seat.seatId);
  else if (state.selected.size >= 10) {
    toast('You can select up to 10 seats at a time.', 'bad');
    return;
  } else state.selected.add(seat.seatId);
  state.focusSeatId = seat.seatId;
  render();
}

function renderSeats() {
  const rows = new Map();
  state.map.seats.forEach((seat) => {
    if (!rows.has(seat.row)) rows.set(seat.row, []);
    rows.get(seat.row).push(seat);
  });
  let previousCategory = null;
  const rowEls = [];
  rows.forEach((seats, row) => {
    if (previousCategory && previousCategory !== seats[0].category) rowEls.push(h('div', { class: 'category-gap' }));
    previousCategory = seats[0].category;
    rowEls.push(h('div', { class: 'seat-row', role: 'group', 'aria-label': `Row ${row}, ${seats[0].category}` },
      h('span', { class: 'row-label', 'aria-hidden': 'true' }, row),
      seats.map(seatButton),
      h('span', { class: 'row-label', 'aria-hidden': 'true' }, row)));
  });
  const legendSeat = (cls, text) => h('span', { class: `seat ${cls}`, 'aria-hidden': 'true' }, text);
  replace(seatArea,
    h('div', { class: 'screen-curve', 'aria-hidden': 'true' }, 'SCREEN'),
    h('div', { class: 'seat-rows', role: 'group', 'aria-label': 'Seat map. Use arrow keys to move between seats.', onkeydown: seatKeys }, rowEls),
    h('div', { class: 'legend' },
      h('span', {}, legendSeat('', ''), 'Available'),
      h('span', {}, legendSeat('selected', '✓'), 'Your seats'),
      h('span', {}, legendSeat('held', ''), 'On hold'),
      h('span', {}, legendSeat('booked', '×'), 'Booked')));

  // One tab stop for the whole map: the last touched seat or the first usable one.
  const buttons = [...seatArea.querySelectorAll('.seat-rows .seat:not(:disabled)')];
  const target = buttons.find((b) => Number(b.dataset.seat) === state.focusSeatId) || buttons[0];
  if (target) target.tabIndex = 0;
  if (state.focusSeatId && document.activeElement === document.body && target) target.focus({ preventScroll: true });
}

function seatKeys(event) {
  const moves = { ArrowLeft: [0, -1], ArrowRight: [0, 1], ArrowUp: [-1, 0], ArrowDown: [1, 0] };
  const move = moves[event.key];
  const current = event.target.closest('.seat');
  if (!move || !current) return;
  event.preventDefault();
  const rows = [...seatArea.querySelectorAll('.seat-row')].map((r) => [...r.querySelectorAll('.seat')]);
  let r = rows.findIndex((row) => row.includes(current));
  let c = rows[r].indexOf(current);
  for (let step = 0; step < 60; step++) {
    if (move[0]) {
      r += move[0];
      if (!rows[r]) return;
      c = Math.min(c, rows[r].length - 1);
    } else {
      c += move[1];
      if (!rows[r][c]) return;
    }
    if (!rows[r][c].disabled) {
      current.tabIndex = -1;
      rows[r][c].tabIndex = 0;
      rows[r][c].focus();
      return;
    }
  }
}

function lineItems(items) {
  return items.map((item) => h('div', { class: 'summary-line' },
    h('span', {}, `${item.label} `, h('span', { class: 'subtle small' }, item.category)),
    h('span', {}, formatMoney(item.price))));
}

function renderSelectionSummary() {
  const seats = seatsById();
  const items = [...state.selected].map((id) => seats.get(id)).filter(Boolean)
    .map((seat) => ({ label: seatLabel(seat), category: seat.category, price: seat.price }));
  const total = items.reduce((sum, item) => sum + Number(item.price), 0);
  const show = state.map.show;
  if (!show.bookable) {
    replace(summary, h('h2', {}, 'Booking closed'),
      h('p', { class: 'subtle', style: 'margin-top:8px' },
        show.status === 'CANCELLED' ? 'This show has been cancelled.' : 'This show has already started.'));
    return;
  }
  const session = getSession();
  replace(summary,
    h('h2', {}, 'Price summary'),
    items.length
      ? h('div', { style: 'margin-top:10px' }, lineItems(items),
        h('div', { class: 'summary-total' }, h('span', {}, 'Total'), h('span', {}, formatMoney(total))))
      : h('p', { class: 'subtle', style: 'margin-top:8px' }, 'Select seats on the map. Prices by category:'),
    !items.length ? h('div', { style: 'margin-top:8px' }, state.map.categories.map((c) => h('div', { class: 'summary-line' },
      h('span', {}, c.category, ' ', h('span', { class: 'subtle small' }, `${c.available} free`)),
      h('span', {}, formatMoney(c.price))))) : null,
    h('p', { style: 'margin-top:14px' }, session
      ? h('button', { type: 'button', class: 'primary', style: 'width:100%', disabled: !items.length || state.busy, onclick: createHold },
        state.busy ? 'Holding seats…' : `Hold ${items.length || ''} seat${items.length === 1 ? '' : 's'}`)
      : h('a', { class: 'btn primary', style: 'display:block;text-align:center', href: loginUrl() }, 'Log in to book')),
    h('p', { class: 'hint' }, 'Holding reserves your seats for a few minutes while you pay. A timer shows the time left.'));
}

function renderHoldSummary() {
  const hold = state.hold;
  const method = h('select', { id: 'pay-method' },
    h('option', { value: 'UPI' }, 'UPI'), h('option', { value: 'CARD' }, 'Card'), h('option', { value: 'WALLET' }, 'Wallet'));
  const outcome = h('select', { id: 'pay-outcome' },
    h('option', { value: 'SUCCESS' }, 'Payment succeeds'), h('option', { value: 'DECLINE' }, 'Payment is declined'));
  const countdown = h('span', { class: 'countdown', role: 'timer', id: 'countdown' });
  const pay = h('button', { type: 'button', class: 'primary', style: 'width:100%', disabled: state.busy, onclick: () => confirmBooking(method.value, outcome.value) },
    state.busy ? 'Processing…' : `Pay ${formatMoney(hold.totalAmount)} (simulated)`);

  replace(summary,
    h('div', { class: 'row spread' }, h('h2', {}, 'Checkout'), countdown),
    h('p', { class: 'subtle small' }, 'Your seats are held until the timer ends.'),
    h('div', { style: 'margin-top:10px' }, lineItems(hold.seats),
      h('div', { class: 'summary-total' }, h('span', {}, 'Total'), h('span', {}, formatMoney(hold.totalAmount)))),
    h('div', { class: 'stack', style: 'margin-top:14px' },
      h('div', {}, h('label', { for: 'pay-method' }, 'Payment method'), method),
      h('div', {}, h('label', { for: 'pay-outcome' }, 'Simulate result'), outcome,
        h('div', { class: 'hint' }, 'Demo only. No payment details are collected.')),
      pay,
      h('button', { type: 'button', disabled: state.busy, onclick: releaseHold }, 'Change seats')));
  tickCountdown();
}

function render() {
  renderSeats();
  if (state.hold) renderHoldSummary();
  else renderSelectionSummary();
}

function tickCountdown() {
  if (!state.hold) return;
  const remaining = Math.max(0, new Date(state.hold.expiresAt).getTime() - (Date.now() + state.holdOffsetMs));
  const el = document.getElementById('countdown');
  if (el) {
    const seconds = Math.ceil(remaining / 1000);
    el.textContent = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
    el.classList.toggle('urgent', seconds <= 60);
    el.setAttribute('aria-label', `${Math.floor(seconds / 60)} minutes ${seconds % 60} seconds left`);
    if (seconds === 60) announcer.textContent = 'One minute left to complete your booking.';
  }
  if (remaining <= 0) holdExpired();
}

function holdExpired() {
  clearInterval(countdownTimer);
  setHold(null);
  announcer.textContent = 'Your hold expired and the seats were released.';
  toast('Your hold expired and the seats were released. Please select again.', 'bad');
  refreshMap();
}

function setHold(hold) {
  state.hold = hold;
  clearInterval(countdownTimer);
  if (hold) {
    state.holdOffsetMs = new Date(hold.serverTime).getTime() - Date.now();
    state.selected.clear();
    countdownTimer = setInterval(tickCountdown, 1000);
  }
}

/** One key per hold, kept across page reloads, so a retried payment can never book twice. */
function idempotencyKey(holdReference) {
  const storageKey = `cinema.idem.${holdReference}`;
  try {
    let key = sessionStorage.getItem(storageKey);
    if (!key) {
      key = uuid();
      sessionStorage.setItem(storageKey, key);
    }
    return key;
  } catch {
    return `hold-${holdReference}`;
  }
}

async function createHold() {
  state.busy = true;
  render();
  try {
    const hold = await api('/api/holds', { method: 'POST', body: { showId: Number(showId), seatIds: [...state.selected] } });
    setHold(hold);
    announcer.textContent = 'Seats held. Checkout is now available.';
    await refreshMap();
  } catch (error) {
    toast(error.message, 'bad');
    if (error.status === 409) {
      state.selected.clear();
      await refreshMap();
    }
  } finally {
    state.busy = false;
    render();
  }
}

async function releaseHold() {
  const hold = state.hold;
  state.busy = true;
  try {
    await api(`/api/holds/${hold.reference}`, { method: 'DELETE' });
  } catch (error) {
    if (error.code !== 'HOLD_NOT_ACTIVE') toast(error.message, 'bad');
  }
  state.busy = false;
  setHold(null);
  await refreshMap();
}

async function confirmBooking(paymentMethod, simulatedOutcome) {
  const hold = state.hold;
  state.busy = true;
  render();
  try {
    const booking = await api('/api/bookings', {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey(hold.reference) },
      body: { holdReference: hold.reference, paymentMethod, simulatedOutcome },
    });
    clearInterval(countdownTimer);
    clearInterval(pollTimer);
    location.assign(`/booking.html?ref=${encodeURIComponent(booking.reference)}&new=1`);
  } catch (error) {
    state.busy = false;
    toast(error.message, 'bad');
    if (error.status === 409) {
      setHold(null);
      await refreshMap();
    } else {
      render();
    }
  }
}

/** Drops selected seats that someone else has just taken, and tells the customer. */
async function refreshMap() {
  const map = await api(`/api/shows/${encodeURIComponent(showId)}/seats`);
  state.map = map;
  const lost = [];
  const seats = seatsById();
  [...state.selected].forEach((id) => {
    const seat = seats.get(id);
    if (!seat || seat.status !== 'AVAILABLE') {
      state.selected.delete(id);
      if (seat) lost.push(seatLabel(seat));
    }
  });
  if (lost.length) toast(`Seat ${lost.join(', ')} was just taken by someone else.`, 'bad');
  if (document.activeElement && document.activeElement.classList?.contains('seat')) {
    state.focusSeatId = Number(document.activeElement.dataset.seat) || state.focusSeatId;
  }
  render();
}

async function init() {
  replace(main, loading('Loading seats…'));
  try {
    state.map = await api(`/api/shows/${encodeURIComponent(showId)}/seats`);
    if (getSession()) {
      const live = await api(`/api/holds/active${query({ showId })}`);
      if (live) setHold(live);
    }
    const show = state.map.show;
    document.title = `${show.movieTitle} seats · Cinema Hall`;
    replace(main,
      announcer,
      h('div', { class: 'page-head' }, h('div', {},
        h('p', { class: 'small' }, h('a', { href: `/movie.html?id=${show.movieId}` }, `← ${show.movieTitle}`)),
        h('h1', {}, 'Choose your seats'),
        h('p', { class: 'subtle' }, `${formatDateTime(show.startTime)} · ${show.theatreName}, ${show.screenName} · ${show.format} · ${show.language}`))),
      h('div', { class: 'seat-layout' }, h('div', { class: 'card' }, seatArea), summary));
    render();
    pollTimer = setInterval(() => {
      if (!document.hidden && !state.busy) refreshMap().catch(() => { /* a later poll retries */ });
    }, POLL_MS);
  } catch (error) {
    replace(main, errorState(error.status === 404 || error.status === 400 ? { message: 'We could not find that show.' } : error,
      error.status === 404 ? null : init), h('p', { style: 'margin-top:14px' }, h('a', { href: '/' }, 'Back to all movies')));
  }
}

init();
