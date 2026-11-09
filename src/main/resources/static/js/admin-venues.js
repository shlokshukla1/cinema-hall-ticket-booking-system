import { api } from './api.js';
import { badge, emptyState, errorState, formDialog, h, loading, replace, toast } from './ui.js';

/** Editor for a screen layout: one line per row, with a label, a seat count and a category. */
function rowsEditor(categories) {
  return {
    build: () => {
      const rowsEl = h('div', { class: 'rows-editor' });
      const addRow = () => {
        const nextLabel = String.fromCharCode(65 + rowsEl.children.length);
        const label = h('input', { 'aria-label': 'Row label', value: nextLabel, maxlength: 4 });
        const seats = h('input', { 'aria-label': 'Seats in row', type: 'number', min: 1, max: 50, value: 10 });
        const category = h('select', { 'aria-label': 'Seat category' },
          categories.map((c) => h('option', { value: c.id }, c.name)));
        const row = h('div', { class: 'layout-row' }, label, seats, category,
          h('button', { type: 'button', class: 'small-btn', 'aria-label': 'Remove row', onclick: () => row.remove() }, '✕'));
        row.read = () => ({ label: label.value.trim(), seats: Number(seats.value), categoryId: Number(category.value) });
        rowsEl.append(row);
      };
      addRow();
      addRow();
      return {
        element: h('div', { class: 'stack' }, h('div', { class: 'hint' }, 'Row label, number of seats, seat category. The first row listed is nearest the screen.'),
          rowsEl, h('div', {}, h('button', { type: 'button', class: 'small-btn', onclick: addRow }, 'Add row'))),
        read: () => [...rowsEl.children].map((row) => row.read()),
      };
    },
  };
}

/** Takes seats out of service or recategorises them. Changes apply to shows scheduled afterwards. */
async function openSeatEditor(screen, categories) {
  const area = h('div', { class: 'seat-editor' }, loading());
  const mode = h('select', { id: 'seat-mode' },
    h('option', { value: 'toggle' }, 'Click a seat to put it in or out of service'),
    categories.map((c) => h('option', { value: c.id }, `Click a seat to set category: ${c.name}`)));
  const dialog = h('dialog', { style: 'width:min(820px, calc(100vw - 32px))' },
    h('h2', {}, `${screen.name}: seats`),
    h('p', { class: 'subtle small', style: 'margin:6px 0 12px' }, 'Changes apply to shows scheduled from now on. Existing shows keep their seats.'),
    h('label', { for: 'seat-mode' }, 'Action'), mode, area,
    h('div', { class: 'dialog-actions' }, h('button', { type: 'button', onclick: () => dialog.close() }, 'Done')));
  dialog.addEventListener('close', () => dialog.remove());
  document.body.append(dialog);
  dialog.showModal();

  let seats = [];
  const shortName = new Map(categories.map((c) => [c.id, c.name]));

  function draw() {
    const rows = new Map();
    seats.forEach((seat) => {
      if (!rows.has(seat.rowLabel)) rows.set(seat.rowLabel, []);
      rows.get(seat.rowLabel).push(seat);
    });
    replace(area, h('div', { class: 'seat-rows', style: 'margin-top:14px' }, [...rows].map(([row, list]) => h('div', { class: 'seat-row' },
      h('span', { class: 'row-label' }, row),
      list.map((seat) => h('button', {
        type: 'button',
        class: `seat ${seat.active ? '' : 'off'}`,
        'aria-label': `${seat.rowLabel}${seat.seatNumber}, ${seat.categoryName}, ${seat.active ? 'in service' : 'out of service'}`,
        title: `${seat.rowLabel}${seat.seatNumber} · ${seat.categoryName}${seat.active ? '' : ' · out of service'}`,
        onclick: () => updateSeat(seat),
      }, seat.seatNumber)),
      h('span', { class: 'subtle small', style: 'margin-left:8px;white-space:nowrap' }, shortName.get(list[0].categoryId))))));
  }

  async function updateSeat(seat) {
    const toggling = mode.value === 'toggle';
    const body = { categoryId: toggling ? seat.categoryId : Number(mode.value), active: toggling ? !seat.active : seat.active };
    try {
      const updated = await api(`/api/admin/seats/${seat.id}`, { method: 'PUT', body });
      seats = seats.map((s) => (s.id === updated.id ? updated : s));
      draw();
    } catch (error) {
      toast(error.message, 'bad');
    }
  }

  try {
    seats = await api(`/api/admin/screens/${screen.id}/seats`);
    draw();
  } catch (error) {
    replace(area, errorState(error));
  }
}

export function renderVenues(container) {
  const list = h('div', { 'aria-live': 'polite' }, loading());
  let categories = [];

  function theatreForm(theatre) {
    formDialog({
      title: theatre ? 'Edit theatre' : 'Add theatre',
      initial: theatre || {},
      fields: [
        { name: 'name', label: 'Name', required: true, maxlength: 120 },
        { name: 'city', label: 'City', required: true, maxlength: 80 },
        { name: 'address', label: 'Address', required: true, maxlength: 255 },
      ],
      onSubmit: async (values) => {
        if (theatre) await api(`/api/admin/theatres/${theatre.id}`, { method: 'PUT', body: values });
        else await api('/api/admin/theatres', { method: 'POST', body: values });
        toast('Theatre saved.', 'ok');
        load();
      },
    });
  }

  function screenForm(theatre) {
    formDialog({
      title: `Add screen to ${theatre.name}`,
      fields: [
        { name: 'name', label: 'Screen name', required: true, maxlength: 60 },
        { name: 'rows', label: 'Seat layout', type: 'custom', ...rowsEditor(categories) },
      ],
      onSubmit: async (values) => {
        await api('/api/admin/screens', { method: 'POST', body: { ...values, theatreId: theatre.id } });
        toast('Screen created.', 'ok');
        load();
      },
    });
  }

  function layoutForm(screen) {
    formDialog({
      title: `Replace layout: ${screen.name}`,
      intro: 'A layout can only be replaced before the screen has any shows.',
      fields: [{ name: 'rows', label: 'Seat layout', type: 'custom', ...rowsEditor(categories) }],
      onSubmit: async (values) => {
        await api(`/api/admin/screens/${screen.id}/layout`, { method: 'PUT', body: values });
        toast('Layout replaced.', 'ok');
        load();
      },
    });
  }

  async function setActive(path, active, done) {
    try {
      await api(path, { method: 'PATCH', body: { active } });
      toast(done, 'ok');
      load();
    } catch (error) {
      toast(error.message, 'bad');
    }
  }

  async function load() {
    try {
      const [theatres, cats] = await Promise.all([api('/api/admin/theatres'), api('/api/admin/seat-categories')]);
      categories = cats;
      if (!theatres.length) {
        replace(list, emptyState('No theatres yet. Add your first one.'));
        return;
      }
      const withScreens = await Promise.all(theatres.map(async (theatre) => ({
        theatre,
        screens: await api(`/api/admin/theatres/${theatre.id}/screens`),
      })));
      replace(list, h('div', { class: 'stack' }, withScreens.map(({ theatre, screens }) => h('section', { class: 'card' },
        h('div', { class: 'section-head' },
          h('div', {},
            h('div', { class: 'row' }, h('h2', {}, theatre.name), badge(theatre.active ? 'ACTIVE' : 'INACTIVE')),
            h('p', { class: 'subtle small' }, `${theatre.address}, ${theatre.city}`)),
          h('div', { class: 'actions' },
            h('button', { type: 'button', class: 'small-btn', onclick: () => theatreForm(theatre) }, 'Edit'),
            h('button', { type: 'button', class: 'small-btn', onclick: () => screenForm(theatre) }, 'Add screen'),
            h('button', {
              type: 'button',
              class: 'small-btn',
              onclick: () => setActive(`/api/admin/theatres/${theatre.id}/active`, !theatre.active, theatre.active ? 'Theatre deactivated.' : 'Theatre activated.'),
            }, theatre.active ? 'Deactivate' : 'Activate'))),
        screens.length ? h('div', { class: 'stack' }, screens.map((screen) => h('div', { class: 'row spread', style: 'border-top:1px solid var(--line);padding-top:10px' },
          h('div', { class: 'row' }, h('strong', {}, screen.name), h('span', { class: 'subtle small' }, `${screen.seatCount} seats in service`),
            !screen.active ? badge('INACTIVE') : null),
          h('div', { class: 'actions' },
            h('button', { type: 'button', class: 'small-btn', onclick: () => openSeatEditor(screen, categories) }, 'Edit seats'),
            h('button', { type: 'button', class: 'small-btn', onclick: () => layoutForm(screen) }, 'Replace layout'),
            h('button', {
              type: 'button',
              class: 'small-btn',
              onclick: () => setActive(`/api/admin/screens/${screen.id}/active`, !screen.active, screen.active ? 'Screen deactivated.' : 'Screen activated.'),
            }, screen.active ? 'Deactivate' : 'Activate')))))
          : h('p', { class: 'subtle small' }, 'No screens yet.')))));
    } catch (error) {
      replace(list, errorState(error, load));
    }
  }

  replace(container,
    h('div', { class: 'page-head' }, h('h1', {}, 'Theatres & screens'),
      h('button', { type: 'button', class: 'primary', onclick: () => theatreForm(null) }, 'Add theatre')),
    list);
  load();
}
