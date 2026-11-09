import { api, query } from './api.js';
import { badge, emptyState, errorState, formatDateTime, formatMoney, formatMoneyShort, h, loading, pagination, replace, table } from './ui.js';

function isoDay(date) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Rounds up to 1, 2 or 5 times a power of ten, so the axis shows tidy numbers. */
function niceMax(value) {
  if (value <= 0) return 100;
  const power = 10 ** Math.floor(Math.log10(value));
  const leading = value / power;
  return (leading <= 1 ? 1 : leading <= 2 ? 2 : leading <= 5 ? 5 : 10) * power;
}

const dayLabel = (date) => new Date(`${date}T12:00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });

/** Single-series column chart of net sales per day, with hover and keyboard tooltips. */
function salesChart(daily) {
  const max = niceMax(Math.max(...daily.map((d) => Number(d.netRevenue))));
  const ticks = [0, 0.5, 1];
  const tip = h('div', { class: 'chart-tip', role: 'status', hidden: true });
  const plot = h('div', { class: 'chart-plot' },
    ticks.slice(1).map((t) => {
      const line = h('div', { class: 'chart-grid' });
      line.style.bottom = `${t * 100}%`;
      return line;
    }));

  daily.forEach((day) => {
    const bar = h('span', { class: 'chart-bar' });
    const net = Number(day.netRevenue);
    bar.style.height = `${(net / max) * 100}%`;
    const text = `${dayLabel(day.date)}: ${formatMoney(day.netRevenue)} net, ${day.bookings} booking${day.bookings === 1 ? '' : 's'}`;
    const show = () => {
      tip.textContent = text;
      tip.hidden = false;
      const left = column.offsetLeft + column.offsetWidth / 2;
      tip.style.left = `${Math.min(Math.max(left, 110), Math.max(plot.offsetWidth - 110, 110))}px`;
    };
    const hide = () => { tip.hidden = true; };
    const column = h('button', {
      type: 'button',
      class: 'chart-col',
      'aria-label': text,
      onmouseenter: show,
      onfocus: show,
      onmouseleave: hide,
      onblur: hide,
    }, bar);
    plot.append(column);
  });
  plot.append(tip);

  // Fewer date labels on narrow screens, so that they never run into each other.
  const step = Math.ceil(daily.length / (matchMedia('(max-width: 620px)').matches ? 4 : 7));
  const axis = h('div', { class: 'chart-y', 'aria-hidden': 'true' }, ticks.map((t) => {
    const label = h('span', {}, formatMoneyShort(max * t));
    label.style.bottom = `${t * 100}%`;
    return label;
  }));
  return h('div', {},
    h('div', { class: 'chart' }, axis, plot,
      h('div', { class: 'chart-x', 'aria-hidden': 'true' }, daily.map((d, i) => h('span', {}, i % step === 0 ? dayLabel(d.date) : '')))),
    h('details', { style: 'margin-top:12px' }, h('summary', { class: 'small' }, 'View as table'),
      h('div', { style: 'margin-top:8px' }, table([
        { label: 'Date', render: (d) => dayLabel(d.date) },
        { label: 'Bookings', num: true, render: (d) => d.bookings },
        { label: 'Gross', num: true, render: (d) => formatMoney(d.grossSales) },
        { label: 'Refunds', num: true, render: (d) => formatMoney(d.refunds) },
        { label: 'Net', num: true, render: (d) => formatMoney(d.netRevenue) },
      ], daily, 'Daily sales'))));
}

function meter(percent) {
  const fill = h('div', { class: 'meter-fill' });
  fill.style.width = `${Math.min(100, Number(percent))}%`;
  return h('div', { class: 'meter' }, h('div', { class: 'meter-track', 'aria-hidden': 'true' }, fill), h('span', {}, `${percent}%`));
}

const kpi = (label, value, note) => h('div', { class: 'kpi' }, h('span', { class: 'subtle small' }, label), h('b', {}, value),
  note ? h('span', { class: 'subtle small' }, note) : null);

export function renderDashboard(container) {
  const today = new Date();
  const start = new Date();
  start.setDate(today.getDate() - 13);
  const fromInput = h('input', { id: 'from-date', type: 'date', value: isoDay(start) });
  const toInput = h('input', { id: 'to-date', type: 'date', value: isoDay(today) });
  const salesArea = h('div', { 'aria-live': 'polite' }, loading('Loading sales…'));
  const occupancyArea = h('div', {}, loading('Loading occupancy…'));
  const occupancyState = { page: 0, status: 'SCHEDULED', scope: 'upcoming' };

  async function loadSales() {
    replace(salesArea, loading('Loading sales…'));
    try {
      const data = await api(`/api/admin/reports/sales${query({ fromDate: fromInput.value, toDate: toInput.value })}`);
      replace(salesArea,
        h('div', { class: 'kpis' },
          kpi('Net revenue', formatMoney(data.netRevenue)),
          kpi('Gross sales', formatMoney(data.grossSales)),
          kpi('Refunds', formatMoney(data.refunds)),
          kpi('Bookings', data.confirmedBookings, `${data.cancelledBookings} cancelled`),
          kpi('Seats sold', data.seatsSold),
          kpi('Upcoming occupancy', `${data.upcomingOccupancyPercent}%`, 'all future shows')),
        h('section', { class: 'card' },
          h('h2', { style: 'margin-bottom:14px' }, 'Net sales per day'),
          data.daily.some((d) => d.bookings > 0) ? salesChart(data.daily) : emptyState('No sales in this period.')),
        h('section', { class: 'section' },
          h('h2', { style: 'margin-bottom:12px' }, 'Sales by movie'),
          data.movies.length ? table([
            { label: 'Movie', render: (m) => m.movieTitle },
            { label: 'Bookings', num: true, render: (m) => m.bookings },
            { label: 'Seats sold', num: true, render: (m) => m.seatsSold },
            { label: 'Net revenue', num: true, render: (m) => formatMoney(m.netRevenue) },
          ], data.movies, 'Sales by movie') : emptyState('No sales in this period.')));
    } catch (error) {
      replace(salesArea, errorState(error, loadSales));
    }
  }

  async function loadOccupancy() {
    replace(occupancyArea, loading('Loading occupancy…'));
    try {
      const from = occupancyState.scope === 'upcoming' ? new Date().toISOString() : null;
      const page = await api(`/api/admin/reports/occupancy${query({ status: occupancyState.status, from, page: occupancyState.page, size: 10 })}`);
      if (!page.content.length) {
        replace(occupancyArea, emptyState('No shows match this filter.'));
        return;
      }
      replace(occupancyArea, table([
        { label: 'Show', render: (r) => h('div', {}, r.movieTitle, h('div', { class: 'subtle small' }, `${r.theatreName}, ${r.screenName}`)) },
        { label: 'Starts', nowrap: true, render: (r) => formatDateTime(r.startTime) },
        { label: 'Status', render: (r) => badge(r.status) },
        { label: 'Occupancy', render: (r) => meter(r.occupancyPercent) },
        { label: 'Booked / seats', num: true, render: (r) => `${r.bookedSeats} / ${r.totalSeats}` },
        { label: 'Held', num: true, render: (r) => r.heldSeats },
        { label: 'Bookings', num: true, render: (r) => r.confirmedBookings },
        { label: 'Cancelled', num: true, render: (r) => r.cancelledBookings },
        { label: 'Net', num: true, render: (r) => formatMoney(r.netRevenue) },
        { label: 'Reconciled', render: (r) => r.reconciled ? badge('CONFIRMED', 'matches') : badge('CANCELLED', 'mismatch') },
      ], page.content, 'Occupancy by show'),
      pagination(page, (next) => { occupancyState.page = next; loadOccupancy(); }));
    } catch (error) {
      replace(occupancyArea, errorState(error, loadOccupancy));
    }
  }

  [fromInput, toInput].forEach((input) => input.addEventListener('change', loadSales));
  const scope = h('select', {
    id: 'occupancy-scope',
    onchange: (event) => {
      const [status, when] = event.target.value.split('|');
      Object.assign(occupancyState, { status, scope: when, page: 0 });
      loadOccupancy();
    },
  },
  h('option', { value: 'SCHEDULED|upcoming' }, 'Upcoming shows'),
  h('option', { value: 'SCHEDULED|all' }, 'All scheduled shows'),
  h('option', { value: 'CANCELLED|all' }, 'Cancelled shows'));

  replace(container,
    h('div', { class: 'page-head' },
      h('h1', {}, 'Dashboard'),
      h('div', { class: 'row', style: 'flex-wrap:wrap' },
        h('div', {}, h('label', { for: 'from-date' }, 'Sales from'), fromInput),
        h('div', {}, h('label', { for: 'to-date' }, 'To'), toInput))),
    salesArea,
    h('section', { class: 'section' },
      h('div', { class: 'section-head' },
        h('div', {}, h('h2', {}, 'Occupancy report'),
          h('p', { class: 'subtle small' }, '“Matches” confirms that seat records and sold tickets agree for that show.')),
        h('div', { style: 'min-width:190px' }, h('label', { for: 'occupancy-scope' }, 'Shows'), scope)),
      occupancyArea));
  loadSales();
  loadOccupancy();
}
