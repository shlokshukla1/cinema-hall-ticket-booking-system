import { api, query } from './api.js';
import { renderShell } from './layout.js';
import { emptyState, errorState, formatDate, formatRuntime, formatTime, h, loading, poster, replace } from './ui.js';

const main = renderShell();
const movieId = new URLSearchParams(location.search).get('id');
const state = { dayOffset: null, theatreId: '' };
const showtimes = h('div', { 'aria-live': 'polite' }, loading('Loading showtimes…'));
const dayChips = h('div', { class: 'day-chips', role: 'group', 'aria-label': 'Filter by date' });
const theatreSelect = h('select', { id: 'theatre-filter' }, h('option', { value: '' }, 'All theatres'));

function startOfDay(offset) {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() + offset);
  return d;
}

function renderDayChips() {
  const chip = (label, offset) => h('button', {
    type: 'button',
    'aria-pressed': String(state.dayOffset === offset),
    onclick: () => { state.dayOffset = offset; renderDayChips(); loadShows(); },
  }, label);
  replace(dayChips, chip('All dates', null),
    Array.from({ length: 7 }, (_, i) => chip(i === 0 ? 'Today' : startOfDay(i).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric' }), i)));
}

function showtimeLink(show) {
  const soldOut = show.seatsAvailable === 0;
  const low = show.seatsAvailable <= show.seatsTotal * 0.2;
  const availability = soldOut ? 'Sold out' : low ? `Only ${show.seatsAvailable} left` : `${show.seatsAvailable} seats`;
  return h('a', { class: `showtime ${soldOut ? 'sold-out' : ''}`, href: `/seats.html?show=${show.id}` },
    h('strong', {}, formatTime(show.startTime)),
    h('span', { class: 'small' }, `${show.format} · ${show.language}`),
    h('br'),
    h('span', { class: 'subtle small' }, availability));
}

function groupShows(shows) {
  const days = new Map();
  shows.forEach((show) => {
    const day = formatDate(show.startTime);
    if (!days.has(day)) days.set(day, new Map());
    const theatres = days.get(day);
    const key = `${show.theatreName}, ${show.city}`;
    if (!theatres.has(key)) theatres.set(key, []);
    theatres.get(key).push(show);
  });
  return days;
}

async function loadShows() {
  replace(showtimes, loading('Loading showtimes…'));
  const from = state.dayOffset === null ? null : startOfDay(state.dayOffset).toISOString();
  const to = state.dayOffset === null ? null : startOfDay(state.dayOffset + 1).toISOString();
  try {
    const page = await api(`/api/shows${query({ movieId, from, to, theatreId: state.theatreId, size: 100 })}`);
    if (!page.content.length) {
      replace(showtimes, emptyState('No showtimes for this selection. Try another date or theatre.'));
      return;
    }
    const days = groupShows(page.content);
    replace(showtimes, h('div', { class: 'stack' }, [...days].map(([day, theatres]) => h('section', { class: 'card' },
      h('h2', {}, day),
      [...theatres].map(([theatre, shows]) => h('div', { style: 'margin-top:14px' },
        h('h3', {}, theatre, h('span', { class: 'subtle small' }, ` · ${shows[0].screenName}`)),
        h('div', { class: 'showtimes' }, shows.map(showtimeLink))))))));
  } catch (error) {
    replace(showtimes, errorState(error, loadShows));
  }
}

async function init() {
  replace(main, loading('Loading movie…'));
  try {
    const [movie, theatres] = await Promise.all([api(`/api/movies/${encodeURIComponent(movieId)}`), api('/api/theatres')]);
    document.title = `${movie.title} · Cinema Hall`;
    theatres.forEach((t) => theatreSelect.append(h('option', { value: t.id }, `${t.name}, ${t.city}`)));
    theatreSelect.addEventListener('change', () => { state.theatreId = theatreSelect.value; loadShows(); });
    renderDayChips();
    replace(main,
      h('div', { class: 'movie-hero' },
        poster(movie),
        h('div', { class: 'stack' },
          h('h1', {}, movie.title),
          h('div', { class: 'chips' },
            [movie.certificate, formatRuntime(movie.durationMinutes), movie.language, ...movie.genres.map((g) => g.name)]
              .map((text) => h('span', { class: 'chip' }, text))),
          h('p', {}, movie.description || ''))),
      h('h2', { style: 'margin-bottom:12px' }, 'Showtimes'),
      dayChips,
      h('div', { style: 'max-width:340px;margin:8px 0 18px' }, h('label', { for: 'theatre-filter' }, 'Theatre'), theatreSelect),
      showtimes);
    loadShows();
  } catch (error) {
    replace(main, errorState(error.status === 404 || !movieId
      ? { message: 'We could not find that movie.' } : error, error.status === 404 ? null : init),
    h('p', { style: 'margin-top:14px' }, h('a', { href: '/' }, 'Back to all movies')));
  }
}

init();
