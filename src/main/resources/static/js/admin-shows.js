import { api, query } from './api.js';
import { badge, confirmDialog, emptyState, errorState, field, formDialog, formatDateTime, h, loading, pagination, replace, table, toast, toLocalInput } from './ui.js';

export function renderShows(container) {
  const state = { movieId: '', theatreId: '', status: 'SCHEDULED', scope: 'upcoming', page: 0 };
  const list = h('div', { 'aria-live': 'polite' }, loading());
  const options = { movies: [], theatres: [], categories: [] };

  /** The screen picker depends on the chosen theatre, so it is a small custom control. */
  function screenPicker() {
    return {
      build: () => {
        const theatre = h('select', { 'aria-label': 'Theatre', required: true },
          h('option', { value: '' }, 'Choose a theatre'),
          options.theatres.filter((t) => t.active).map((t) => h('option', { value: t.id }, `${t.name}, ${t.city}`)));
        const screen = h('select', { 'aria-label': 'Screen', required: true, disabled: true }, h('option', { value: '' }, 'Choose a theatre first'));
        theatre.addEventListener('change', async () => {
          screen.disabled = true;
          replace(screen, h('option', { value: '' }, 'Loading…'));
          if (!theatre.value) {
            replace(screen, h('option', { value: '' }, 'Choose a theatre first'));
            return;
          }
          const screens = await api(`/api/admin/theatres/${theatre.value}/screens`);
          replace(screen, h('option', { value: '' }, 'Choose a screen'),
            screens.filter((s) => s.active).map((s) => h('option', { value: s.id }, `${s.name} (${s.seatCount} seats)`)));
          screen.disabled = false;
        });
        return { element: h('div', { class: 'row', style: 'flex-wrap:nowrap' }, theatre, screen), read: () => (screen.value ? Number(screen.value) : null) };
      },
    };
  }

  function priceOverrides() {
    return {
      build: () => {
        const inputs = options.categories.map((c) => ({
          id: c.id,
          control: field({ name: `price-${c.id}`, label: `${c.name} (default ₹${c.defaultPrice})`, type: 'number', min: 0, step: '0.01' }),
        }));
        return {
          element: h('div', { class: 'stack' }, inputs.map((i) => i.control.element)),
          read: () => Object.fromEntries(inputs.filter((i) => i.control.read() !== null).map((i) => [i.id, i.control.read()])),
        };
      },
    };
  }

  const sharedFields = [
    { name: 'startTime', label: 'Start time', type: 'datetime-local', required: true, hint: 'In your local time. Rounded down to the minute.' },
    { name: 'language', label: 'Language', required: true, maxlength: 60 },
    { name: 'format', label: 'Format', required: true, maxlength: 20, hint: 'For example 2D, 3D or IMAX.' },
  ];
  const startTimeToIso = (value) => (value ? new Date(value).toISOString() : null);

  function createForm() {
    formDialog({
      title: 'Schedule a show',
      submitLabel: 'Schedule show',
      initial: { format: '2D', language: 'English' },
      fields: [
        { name: 'movieId', label: 'Movie', type: 'select', numeric: true, required: true, placeholderOption: 'Choose a movie', options: options.movies.filter((m) => m.active).map((m) => ({ value: m.id, label: m.title })) },
        { name: 'screenId', label: 'Theatre and screen', type: 'custom', ...screenPicker() },
        ...sharedFields,
        { name: 'categoryPrices', label: 'Price overrides for this show (optional)', type: 'custom', ...priceOverrides() },
      ],
      onSubmit: async (values) => {
        await api('/api/admin/shows', { method: 'POST', body: { ...values, startTime: startTimeToIso(values.startTime) } });
        toast('Show scheduled.', 'ok');
        load();
      },
    });
  }

  function editForm(show) {
    formDialog({
      title: `Edit show: ${show.movieTitle}`,
      intro: 'The time can only change while the show has no bookings.',
      initial: { startTime: toLocalInput(show.startTime), language: show.language, format: show.format },
      fields: sharedFields,
      onSubmit: async (values) => {
        await api(`/api/admin/shows/${show.id}`, { method: 'PUT', body: { ...values, startTime: startTimeToIso(values.startTime) } });
        toast('Show updated.', 'ok');
        load();
      },
    });
  }

  async function cancelShow(show) {
    const booked = show.seatsTotal - show.seatsAvailable;
    const yes = await confirmDialog({
      title: 'Cancel this show?',
      body: h('p', {}, `${show.movieTitle}, ${formatDateTime(show.startTime)}. Every booking will be cancelled and refunded in full, and any holds released (${booked} seats are currently taken). This cannot be undone.`),
      confirmLabel: 'Cancel show and refund',
      cancelLabel: 'Keep show',
      danger: true,
    });
    if (!yes) return;
    try {
      const result = await api(`/api/admin/shows/${show.id}/cancel`, { method: 'POST' });
      toast(`Show cancelled. ${result.refundedBookings} booking(s) refunded.`, 'ok');
      load();
    } catch (error) {
      toast(error.message, 'bad');
      load();
    }
  }

  async function load() {
    replace(list, loading());
    try {
      const from = state.scope === 'upcoming' ? new Date().toISOString() : null;
      const page = await api(`/api/admin/shows${query({ movieId: state.movieId, theatreId: state.theatreId, status: state.status, from, page: state.page, size: 15 })}`);
      if (!page.content.length) {
        replace(list, emptyState('No shows match these filters.'));
        return;
      }
      replace(list, table([
        { label: 'Movie', render: (s) => s.movieTitle },
        { label: 'Theatre', render: (s) => `${s.theatreName}, ${s.screenName}` },
        { label: 'Starts', nowrap: true, render: (s) => formatDateTime(s.startTime) },
        { label: 'Format', render: (s) => `${s.format} · ${s.language}` },
        { label: 'Free / total', num: true, render: (s) => `${s.seatsAvailable} / ${s.seatsTotal}` },
        { label: 'Status', render: (s) => badge(s.status) },
        {
          label: 'Actions',
          render: (s) => (s.bookable ? h('div', { class: 'actions' },
            h('button', { type: 'button', class: 'small-btn', onclick: () => editForm(s) }, 'Edit'),
            h('button', { type: 'button', class: 'small-btn danger', onclick: () => cancelShow(s) }, 'Cancel')) : h('span', { class: 'subtle small' }, 'Closed')),
        },
      ], page.content, 'Shows'), pagination(page, (next) => { state.page = next; load(); }));
    } catch (error) {
      replace(list, errorState(error, load));
    }
  }

  const movieFilter = h('select', { id: 'show-movie', onchange: (e) => { state.movieId = e.target.value; state.page = 0; load(); } }, h('option', { value: '' }, 'All movies'));
  const theatreFilter = h('select', { id: 'show-theatre', onchange: (e) => { state.theatreId = e.target.value; state.page = 0; load(); } }, h('option', { value: '' }, 'All theatres'));
  const statusFilter = h('select', {
    id: 'show-status',
    onchange: (e) => {
      const [status, scope] = e.target.value.split('|');
      Object.assign(state, { status, scope, page: 0 });
      load();
    },
  },
  h('option', { value: 'SCHEDULED|upcoming' }, 'Upcoming'),
  h('option', { value: 'SCHEDULED|all' }, 'All scheduled'),
  h('option', { value: 'CANCELLED|all' }, 'Cancelled'));

  replace(container,
    h('div', { class: 'page-head' }, h('h1', {}, 'Schedules'),
      h('button', { type: 'button', class: 'primary', onclick: createForm }, 'Schedule a show')),
    h('div', { class: 'filters' },
      h('div', {}, h('label', { for: 'show-movie' }, 'Movie'), movieFilter),
      h('div', {}, h('label', { for: 'show-theatre' }, 'Theatre'), theatreFilter),
      h('div', {}, h('label', { for: 'show-status' }, 'Shows'), statusFilter)),
    list);

  Promise.all([api('/api/admin/movies?size=100'), api('/api/admin/theatres'), api('/api/admin/seat-categories')])
    .then(([movies, theatres, categories]) => {
      options.movies = movies.content;
      options.theatres = theatres;
      options.categories = categories;
      movies.content.forEach((m) => movieFilter.append(h('option', { value: m.id }, m.title)));
      theatres.forEach((t) => theatreFilter.append(h('option', { value: t.id }, t.name)));
    })
    .catch((error) => toast(error.message, 'bad'));
  load();
}
