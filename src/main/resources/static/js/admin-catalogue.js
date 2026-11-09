import { api, query } from './api.js';
import { badge, confirmDialog, emptyState, errorState, field, formDialog, formatMoney, formatRuntime, h, loading, pagination, replace, table, toast } from './ui.js';

// ---- Movies ----

export function renderMovies(container) {
  const state = { q: '', page: 0 };
  const list = h('div', { 'aria-live': 'polite' });
  let genres = [];

  function openForm(movie) {
    formDialog({
      title: movie ? 'Edit movie' : 'Add movie',
      initial: movie ? { ...movie, genreIds: movie.genres.map((g) => g.id) } : { certificate: 'U', language: 'English' },
      fields: [
        { name: 'title', label: 'Title', required: true, maxlength: 200 },
        { name: 'description', label: 'Description', type: 'textarea', maxlength: 2000 },
        { name: 'durationMinutes', label: 'Running time (minutes)', type: 'number', min: 1, max: 600, required: true },
        { name: 'language', label: 'Language', required: true, maxlength: 60 },
        { name: 'certificate', label: 'Certificate', required: true, maxlength: 10 },
        { name: 'releaseDate', label: 'Release date', type: 'date', required: true },
        { name: 'genreIds', label: 'Genres', type: 'checks', options: genres.map((g) => ({ value: g.id, label: g.name })) },
      ],
      onSubmit: async (values) => {
        const body = { ...values, releaseDate: values.releaseDate || null };
        if (movie) await api(`/api/admin/movies/${movie.id}`, { method: 'PUT', body });
        else await api('/api/admin/movies', { method: 'POST', body });
        toast('Movie saved.', 'ok');
        load();
      },
    });
  }

  async function setActive(movie, active) {
    try {
      await api(`/api/admin/movies/${movie.id}/active`, { method: 'PATCH', body: { active } });
      toast(active ? 'Movie restored.' : 'Movie archived.', 'ok');
      load();
    } catch (error) {
      toast(error.message, 'bad');
    }
  }

  async function uploadPoster(movie, file) {
    const form = new FormData();
    form.append('file', file);
    try {
      await api(`/api/admin/movies/${movie.id}/poster`, { method: 'POST', form });
      toast('Poster saved.', 'ok');
      load();
    } catch (error) {
      toast(error.message, 'bad');
    }
  }

  async function removePoster(movie) {
    try {
      await api(`/api/admin/movies/${movie.id}/poster`, { method: 'DELETE' });
      toast('Poster removed.', 'ok');
      load();
    } catch (error) {
      toast(error.message, 'bad');
    }
  }

  function posterControls(movie) {
    const input = h('input', {
      type: 'file',
      accept: 'image/jpeg,image/png,image/webp',
      hidden: true,
      'aria-label': `Poster file for ${movie.title}`,
      onchange: (event) => { if (event.target.files[0]) uploadPoster(movie, event.target.files[0]); },
    });
    return [input,
      h('button', { type: 'button', class: 'small-btn', onclick: () => input.click() }, movie.posterUrl ? 'Change poster' : 'Upload poster'),
      movie.posterUrl ? h('button', { type: 'button', class: 'small-btn', onclick: () => removePoster(movie) }, 'Remove poster') : null];
  }

  async function load() {
    replace(list, loading());
    try {
      const page = await api(`/api/admin/movies${query({ q: state.q, page: state.page, size: 15 })}`);
      if (!page.content.length) {
        replace(list, emptyState('No movies found.'));
        return;
      }
      replace(list, table([
        {
          label: 'Poster',
          render: (m) => (m.posterUrl
            ? h('img', { class: 'poster-thumb', src: m.posterUrl, alt: `Uploaded poster for ${m.title}` })
            : h('div', { class: 'poster-thumb none' }, 'Default')),
        },
        { label: 'Title', render: (m) => m.title },
        { label: 'Language', render: (m) => m.language },
        { label: 'Runtime', render: (m) => formatRuntime(m.durationMinutes) },
        { label: 'Genres', render: (m) => m.genres.map((g) => g.name).join(', ') },
        { label: 'Status', render: (m) => badge(m.active ? 'ACTIVE' : 'ARCHIVED') },
        {
          label: 'Actions',
          render: (m) => h('div', { class: 'actions' },
            h('button', { type: 'button', class: 'small-btn', onclick: () => openForm(m) }, 'Edit'),
            h('button', { type: 'button', class: 'small-btn', onclick: () => setActive(m, !m.active) }, m.active ? 'Archive' : 'Restore'),
            posterControls(m)),
        },
      ], page.content, 'Movies'), pagination(page, (next) => { state.page = next; load(); }));
    } catch (error) {
      replace(list, errorState(error, load));
    }
  }

  const search = h('input', { id: 'movie-search', type: 'search', placeholder: 'Search titles' });
  let debounce;
  search.addEventListener('input', () => {
    clearTimeout(debounce);
    debounce = setTimeout(() => { state.q = search.value.trim(); state.page = 0; load(); }, 280);
  });

  replace(container,
    h('div', { class: 'page-head' }, h('h1', {}, 'Movies'),
      h('button', { type: 'button', class: 'primary', onclick: () => openForm(null) }, 'Add movie')),
    h('div', { style: 'max-width:340px;margin-bottom:16px' }, h('label', { for: 'movie-search' }, 'Search'), search),
    list);
  api('/api/genres').then((g) => { genres = g; }).catch(() => {});
  load();
}

// ---- Seat categories, genres and the cancellation policy ----

export function renderPricing(container) {
  const categoryArea = h('div', {}, loading());
  const genreArea = h('div', {}, loading());
  const policyArea = h('div', {}, loading());

  function categoryForm(category) {
    formDialog({
      title: category ? 'Edit seat category' : 'Add seat category',
      intro: 'A new price applies to shows scheduled from now on. Existing shows keep their prices.',
      initial: category || { sortOrder: 5 },
      fields: [
        { name: 'name', label: 'Name', required: true, maxlength: 40 },
        { name: 'defaultPrice', label: 'Default price (₹)', type: 'number', min: 0, step: '0.01', required: true },
        { name: 'sortOrder', label: 'Display order', type: 'number', min: 0, required: true },
      ],
      onSubmit: async (values) => {
        if (category) await api(`/api/admin/seat-categories/${category.id}`, { method: 'PUT', body: values });
        else await api('/api/admin/seat-categories', { method: 'POST', body: values });
        toast('Category saved.', 'ok');
        loadCategories();
      },
    });
  }

  async function removeCategory(category) {
    const yes = await confirmDialog({ title: `Delete “${category.name}”?`, body: h('p', {}, 'A category used by any seat cannot be deleted.'), confirmLabel: 'Delete', danger: true });
    if (!yes) return;
    try {
      await api(`/api/admin/seat-categories/${category.id}`, { method: 'DELETE' });
      toast('Category deleted.', 'ok');
      loadCategories();
    } catch (error) {
      toast(error.status === 409 ? 'This category is still used by seats, so it cannot be deleted.' : error.message, 'bad');
    }
  }

  async function loadCategories() {
    try {
      const rows = await api('/api/admin/seat-categories');
      replace(categoryArea, table([
        { label: 'Category', render: (c) => c.name },
        { label: 'Default price', num: true, render: (c) => formatMoney(c.defaultPrice) },
        { label: 'Order', num: true, render: (c) => c.sortOrder },
        {
          label: 'Actions',
          render: (c) => h('div', { class: 'actions' },
            h('button', { type: 'button', class: 'small-btn', onclick: () => categoryForm(c) }, 'Edit'),
            h('button', { type: 'button', class: 'small-btn danger', onclick: () => removeCategory(c) }, 'Delete')),
        },
      ], rows, 'Seat categories'));
    } catch (error) {
      replace(categoryArea, errorState(error, loadCategories));
    }
  }

  function genreForm(genre) {
    formDialog({
      title: genre ? 'Rename genre' : 'Add genre',
      initial: genre || {},
      fields: [{ name: 'name', label: 'Name', required: true, maxlength: 60 }],
      onSubmit: async (values) => {
        if (genre) await api(`/api/admin/genres/${genre.id}`, { method: 'PUT', body: values });
        else await api('/api/admin/genres', { method: 'POST', body: values });
        toast('Genre saved.', 'ok');
        loadGenres();
      },
    });
  }

  async function removeGenre(genre) {
    try {
      await api(`/api/admin/genres/${genre.id}`, { method: 'DELETE' });
      toast('Genre deleted.', 'ok');
      loadGenres();
    } catch (error) {
      toast(error.status === 409 ? 'This genre is still used by movies, so it cannot be deleted.' : error.message, 'bad');
    }
  }

  async function loadGenres() {
    try {
      const rows = await api('/api/genres');
      replace(genreArea, h('div', { class: 'row' }, rows.map((g) => h('span', { class: 'chip row', style: 'gap:6px' }, g.name,
        h('button', { type: 'button', class: 'link-btn small', 'aria-label': `Rename ${g.name}`, onclick: () => genreForm(g) }, 'edit'),
        h('button', { type: 'button', class: 'link-btn small', 'aria-label': `Delete ${g.name}`, onclick: () => removeGenre(g) }, 'delete')))));
    } catch (error) {
      replace(genreArea, errorState(error, loadGenres));
    }
  }

  async function loadPolicy() {
    try {
      const rules = await api('/api/cancellation-rules');
      const rowsEl = h('div', { class: 'stack' });
      const addRow = (rule = { minHoursBefore: '', refundPercent: '' }) => {
        const hours = field({ name: 'hours', label: 'Hours before show', type: 'number', min: 0, required: true }, rule.minHoursBefore);
        const percent = field({ name: 'percent', label: 'Refund %', type: 'number', min: 0, max: 100, required: true }, rule.refundPercent);
        const row = h('div', { class: 'row', style: 'align-items:end;flex-wrap:nowrap', 'data-rule': '1' }, hours.element, percent.element,
          h('button', { type: 'button', class: 'small-btn', 'aria-label': 'Remove rule', onclick: () => row.remove() }, 'Remove'));
        row.read = () => ({ minHoursBefore: hours.read(), refundPercent: percent.read() });
        rowsEl.append(row);
      };
      rules.forEach(addRow);
      const message = h('div', { class: 'notice bad', role: 'alert', hidden: true });
      const save = h('button', {
        type: 'button',
        class: 'primary',
        onclick: async () => {
          message.hidden = true;
          save.disabled = true;
          try {
            const body = { rules: [...rowsEl.querySelectorAll('[data-rule]')].map((row) => row.read()) };
            if (body.rules.some((r) => r.minHoursBefore === null || r.refundPercent === null)) {
              throw new Error('Fill in both values for every rule.');
            }
            await api('/api/admin/cancellation-rules', { method: 'PUT', body });
            toast('Cancellation policy saved.', 'ok');
            loadPolicy();
          } catch (error) {
            message.textContent = error.fieldErrors?.[0]?.message || error.message;
            message.hidden = false;
          } finally {
            save.disabled = false;
          }
        },
      }, 'Save policy');
      replace(policyArea, h('div', { class: 'card stack' },
        h('p', { class: 'subtle small' }, 'The most generous rule whose notice period is met applies. Later than every rule, customers cannot cancel. When a show itself is cancelled, every customer gets a full refund.'),
        message, rowsEl,
        h('div', { class: 'row' }, h('button', { type: 'button', onclick: () => addRow() }, 'Add rule'), save)));
    } catch (error) {
      replace(policyArea, errorState(error, loadPolicy));
    }
  }

  replace(container,
    h('div', { class: 'page-head' }, h('h1', {}, 'Prices & policy')),
    h('section', {}, h('div', { class: 'section-head' }, h('h2', {}, 'Seat categories'),
      h('button', { type: 'button', class: 'primary', onclick: () => categoryForm(null) }, 'Add category')), categoryArea),
    h('section', { class: 'section' }, h('h2', { style: 'margin-bottom:12px' }, 'Cancellation policy'), policyArea),
    h('section', { class: 'section' }, h('div', { class: 'section-head' }, h('h2', {}, 'Genres'),
      h('button', { type: 'button', onclick: () => genreForm(null) }, 'Add genre')), genreArea));
  loadCategories();
  loadPolicy();
  loadGenres();
}
