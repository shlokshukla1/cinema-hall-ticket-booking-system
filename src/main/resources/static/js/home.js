import { api, query } from './api.js';
import { renderShell } from './layout.js';
import { emptyState, errorState, field, formatRuntime, h, loading, pagination, poster, replace } from './ui.js';

const main = renderShell();
const state = { q: '', genreId: '', language: '', page: 0, paused: false };
const CARD_WIDTH = 230;
const SECONDS_PER_CARD = 6;
const featured = h('div', {});
const results = h('div', { 'aria-live': 'polite' }, loading('Loading movies…'));

const search = field({ name: 'q', label: 'Search by title', type: 'search', placeholder: 'Try “lantern”', required: true });
search.element.querySelector('.subtle')?.remove();
const genre = field({ name: 'genreId', label: 'Genre', type: 'select', options: [], placeholderOption: 'All genres', required: true });
const language = field({ name: 'language', label: 'Language', type: 'select', options: [], placeholderOption: 'All languages', required: true });

let debounce;
search.control.addEventListener('input', () => {
  clearTimeout(debounce);
  debounce = setTimeout(() => { state.q = search.control.value.trim(); state.page = 0; load(); }, 280);
});
genre.control.addEventListener('change', () => { state.genreId = genre.control.value; state.page = 0; load(); });
language.control.addEventListener('change', () => { state.language = language.control.value; state.page = 0; load(); });

replace(main,
  h('div', { class: 'page-head' }, h('div', {},
    h('h1', {}, 'Now showing'),
    h('p', { class: 'subtle' }, 'Pick a film, choose a showtime, and hold your seats while you pay.'))),
  h('form', { class: 'filters', role: 'search', onsubmit: (e) => e.preventDefault() }, search.element, genre.element, language.element),
  featured,
  results);

function movieCard(movie) {
  return h('a', { class: 'movie-card', href: `/movie.html?id=${movie.id}` },
    poster(movie),
    h('div', {},
      h('h3', {}, movie.title),
      h('p', { class: 'subtle small' }, `${movie.language} · ${formatRuntime(movie.durationMinutes)}`),
      h('p', { class: 'subtle small' }, movie.genres.map((g) => g.name).join(', '))));
}

/**
 * Left-to-right poster row. Two identical groups let the animation loop seamlessly. Every visible poster can be
 * clicked, but this decorative row is hidden from assistive technology and the tab order (not `inert`, which would
 * also block the mouse). Keyboard and screen-reader users get the same movies from the All movies section below.
 */
function carousel(movies) {
  const copies = Math.max(1, Math.ceil(Math.max(screen.width, 1280) / (movies.length * CARD_WIDTH)));
  const repeatCard = (movie) => {
    const card = movieCard(movie);
    card.tabIndex = -1;
    return card;
  };
  const group = () => h('div', { class: 'marquee-group' }, Array.from({ length: copies }, () => movies.map(repeatCard)));
  const marquee = h('div', { class: `marquee ${state.paused ? 'paused' : ''}`, 'aria-hidden': 'true' },
    h('div', { class: 'marquee-track' }, group(), group()));
  marquee.firstChild.style.setProperty('--marquee-duration', `${movies.length * copies * SECONDS_PER_CARD}s`);

  const pause = h('button', {
    type: 'button',
    class: 'small-btn marquee-pause',
    onclick: () => {
      state.paused = !state.paused;
      marquee.classList.toggle('paused', state.paused);
      pause.textContent = state.paused ? 'Play poster row' : 'Pause poster row';
    },
  }, state.paused ? 'Play poster row' : 'Pause poster row');

  return h('section', { 'aria-label': 'Featured movies', class: 'featured' },
    h('div', { class: 'marquee-bar' }, h('h2', {}, 'In cinemas'), pause),
    marquee);
}

const hasFilters = () => Boolean(state.q || state.genreId || state.language);

async function loadFeatured() {
  try {
    const page = await api('/api/movies?size=50');
    replace(featured, page.content.length ? carousel(page.content) : null);
  } catch {
    replace(featured); // the All movies section reports the outage
  }
}

async function load() {
  replace(results, loading('Loading movies…'));
  // While searching, only the matches matter, so the moving row steps aside.
  if (hasFilters()) replace(featured);
  else if (!featured.firstChild) loadFeatured();
  try {
    const page = await api(`/api/movies${query({ q: state.q, genreId: state.genreId, language: state.language, page: state.page, size: 12 })}`);
    const heading = h('h2', { class: 'section-title' }, hasFilters() ? `Results (${page.totalElements})` : 'All movies');
    if (!page.content.length) {
      replace(results, emptyState(hasFilters() ? 'No movies match those filters.' : 'No movies yet.', hasFilters() ? h('button', {
        type: 'button',
        onclick: () => {
          search.control.value = ''; genre.control.value = ''; language.control.value = '';
          Object.assign(state, { q: '', genreId: '', language: '', page: 0 });
          load();
        },
      }, 'Clear filters') : null));
      return;
    }
    replace(results, heading,
      h('div', { class: 'movie-grid' }, page.content.map(movieCard)),
      pagination(page, (next) => { state.page = next; load(); results.scrollIntoView(); }));
  } catch (error) {
    replace(results, errorState(error, load));
  }
}

async function loadFilters() {
  try {
    const [genres, languages] = await Promise.all([api('/api/genres'), api('/api/movies/languages')]);
    genres.forEach((g) => genre.control.append(h('option', { value: g.id }, g.name)));
    languages.forEach((l) => language.control.append(h('option', { value: l }, l)));
  } catch {
    // The movie list reports any outage; the filters can stay empty.
  }
}

loadFilters();
load();
