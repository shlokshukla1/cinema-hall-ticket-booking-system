import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const SHOTS = fileURLToPath(new URL('./shots/', import.meta.url));
mkdirSync(SHOTS, { recursive: true });
const axeSource = readFileSync(new URL('./node_modules/axe-core/axe.min.js', import.meta.url), 'utf8');

const until = async (page, fn) => { for (let i = 0; i < 100; i++) { if (await page.evaluate(fn).catch(() => false)) return; await new Promise((r) => setTimeout(r, 100)); } throw new Error('until timed out: ' + fn); };
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const problems = [];
const passed = [];
const check = (name, ok, detail = '') => { (ok ? passed : problems).push(`${name}${detail ? ` (${detail})` : ''}`); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ` -- ${detail}` : ''}`); };

async function newUser(viewport = { width: 1280, height: 900 }) {
  const context = await browser.newContext({ viewport, bypassCSP: false });
  const page = await context.newPage();
  page.on('console', (m) => {
    if (m.type() === 'error' && !/status of 4\d\d/.test(m.text())) problems.push(`console error on ${page.url()}: ${m.text()}`);
  });
  page.on('pageerror', (e) => problems.push(`uncaught error on ${page.url()}: ${e.message}`));
  page.on('response', (r) => { if (r.status() >= 500) problems.push(`HTTP ${r.status()} ${r.url()}`); });
  return { context, page };
}
const shot = (page, name) => page.screenshot({ path: `${SHOTS}${name}.png`, fullPage: true });

async function login(page, email, password, next = '/') {
  await page.goto(`${BASE}/login.html?next=${encodeURIComponent(next)}`);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.locator('form').getByRole('button', { name: 'Log in' }).click();
  await until(page, () => !location.pathname.includes('login'));
}

async function axe(name, page) {
  const context = page.context();
  const url = page.url();
  // CSP blocks injected scripts by design, so the scan runs in a CSP-bypassing clone of the session.
  const state = await context.storageState();
  const scanContext = await browser.newContext({ bypassCSP: true, storageState: state, viewport: page.viewportSize() });
  const scan = await scanContext.newPage();
  await scan.goto(url);
  await scan.waitForTimeout(900);
  await scan.addScriptTag({ content: axeSource });
  const result = await scan.evaluate(async () => {
    const r = await window.axe.run(document, { runOnly: ['wcag2a', 'wcag2aa', 'wcag21aa'] });
    return r.violations.map((v) => `${v.id} [${v.impact}] x${v.nodes.length}: ${v.nodes[0].target.join(' ')}`);
  });
  check(`axe: ${name}`, result.length === 0, result.join(' | '));
  await scanContext.close();
}

async function noOverflow(name, page) {
  const over = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  check(`no horizontal page scroll: ${name}`, over <= 0, over > 0 ? `${over}px over` : '');
}

// ---- Customer browse ----
const a = await newUser();
await a.page.goto(BASE);
await a.page.waitForSelector('.movie-card');
const liveCards = '.movie-grid .movie-card';
await a.page.locator(liveCards).first().waitFor();
await a.page.locator('.marquee').waitFor();
check('All movies section lists each movie once', (await a.page.locator(liveCards).count()) === 8);
check('every seeded movie has poster art', (await a.page.locator(`${liveCards} .poster img`).count()) === 8);
await until(a.page, () => [...document.querySelectorAll('.poster img')].every((i) => i.complete && i.naturalWidth > 0));
const trackX = () => a.page.locator('.marquee-track').evaluate((el) => new DOMMatrix(getComputedStyle(el).transform).m41);
await a.page.mouse.move(5, 5);
const x1 = await trackX();
await a.page.waitForTimeout(700);
const x2 = await trackX();
check('poster row moves left to right', x2 > x1, `${x1.toFixed(1)} to ${x2.toFixed(1)}`);
await a.page.getByRole('button', { name: 'Pause poster row' }).click();
const x3 = await trackX();
await a.page.waitForTimeout(400);
check('Pause stops the row', x3 === (await trackX()));
await shot(a.page, '01-home');
await axe('home', a.page);
await a.page.getByLabel('Search by title').fill('lantern');
await until(a.page, () => document.querySelectorAll('.movie-card').length === 1);
check('search filters movies', true);
await a.page.getByLabel('Search by title').fill('zzzzzz');
await a.page.getByText('No movies match those filters.').waitFor();
check('empty state shows', true);
await a.page.getByRole('button', { name: 'Clear filters' }).click();
await a.page.locator('.marquee').waitFor();

// Click whichever poster is really on screen, as a visitor would: it may be a repeat in the moving row.
const target = await a.page.locator('.marquee .movie-card').evaluateAll((cards) => {
  const visible = cards.find((el) => { const r = el.getBoundingClientRect(); return r.left > 100 && r.right < innerWidth - 100; });
  const r = visible.getBoundingClientRect();
  return { x: r.left + r.width / 2, y: r.top + 120, repeat: Boolean(visible.closest('[aria-hidden]')) };
});
await a.page.mouse.move(target.x, target.y);
await a.page.waitForTimeout(150);
await a.page.mouse.down();
await a.page.mouse.up();
await a.page.waitForURL(/movie\.html/);
check('a visible poster in the row opens its movie', true, target.repeat ? 'clicked a repeat' : 'clicked an original');
await a.page.waitForSelector('.showtime');
await shot(a.page, '02-movie');
await axe('movie', a.page);
const showHref = await a.page.locator('.showtime').first().getAttribute('href');
const showId = new URLSearchParams(showHref.split('?')[1]).get('show');

// Anonymous visitors see the map but must log in to book.
await a.page.goto(`${BASE}${showHref}`);
await a.page.waitForSelector('.seat-rows .seat');
check('anonymous visitor is asked to log in', await a.page.getByRole('link', { name: 'Log in to book' }).isVisible());

await login(a.page, 'customer@cinema.test', 'Customer@123', showHref);
await a.page.waitForSelector('.seat-rows .seat:not(:disabled)');
const free = a.page.locator('.seat-rows .seat:not(:disabled)');
const firstSeat = await free.nth(0).getAttribute('data-seat');
const secondSeat = await free.nth(1).getAttribute('data-seat');
await a.page.locator(`[data-seat="${firstSeat}"]`).click();
await a.page.locator(`[data-seat="${secondSeat}"]`).click();
check('price summary totals seats', await a.page.locator('.summary-total').isVisible());
await shot(a.page, '03-seats-selected');
await axe('seat map', a.page);

// Keyboard: arrow keys move focus along the map.
await a.page.locator(`[data-seat="${firstSeat}"]`).focus();
await a.page.keyboard.press('ArrowRight');
const focused = await a.page.evaluate(() => document.activeElement.dataset.seat);
check('arrow keys move between seats', focused === secondSeat, `focus on ${focused}`);

await a.page.getByRole('button', { name: /^Hold/ }).click();
await a.page.getByRole('timer').waitFor();
check('hold starts a countdown', /^\d:\d\d$/.test(await a.page.getByRole('timer').textContent()));
await shot(a.page, '04-checkout');

// A second customer sees the seat as taken, and a stale selection is refused.
const b = await newUser();
await login(b.page, 'customer2@cinema.test', 'Customer@123', showHref);
await b.page.waitForSelector('.seat-rows .seat');
check('other customer sees held seat disabled', await b.page.locator(`[data-seat="${firstSeat}"]`).isDisabled());
const raceSeat = await b.page.locator('.seat-rows .seat:not(:disabled)').first().getAttribute('data-seat');
await b.page.locator(`[data-seat="${raceSeat}"]`).click();

// Refresh keeps the hold and its timer.
await a.page.reload();
await a.page.getByRole('timer').waitFor();
check('hold survives page refresh', true);

// Declined payment keeps the hold.
await a.page.getByLabel('Simulate result').selectOption('DECLINE');
await a.page.getByRole('button', { name: /^Pay/ }).click();
await a.page.locator('.toast.bad').waitFor();
check('declined payment shows an error and keeps checkout', await a.page.getByRole('timer').isVisible());

await a.page.getByLabel('Simulate result').selectOption('SUCCESS');
await a.page.getByRole('button', { name: /^Pay/ }).click();
await a.page.waitForURL(/booking\.html/);
await a.page.locator('.ticket-stub img').waitFor();
await until(a.page, () => document.querySelector('.ticket-stub img')?.naturalWidth > 0);
check('ticket shows a rendered QR code', true);
const reference = (await a.page.locator('.reference').textContent()).trim();
await shot(a.page, '05-ticket');
await axe('ticket', a.page);

// Race: the other customer takes the seat first, then this seat is confirmed booked for them to see.
await b.page.reload();
await b.page.waitForSelector('.seat-rows .seat');
check('booked seat shows as unavailable to others', await b.page.locator(`[data-seat="${firstSeat}"]`).isDisabled());

// Conflict in the UI: both select the same free seat, the slower one is told.
const contested = await b.page.locator('.seat-rows .seat:not(:disabled)').nth(3).getAttribute('data-seat');
await b.page.locator(`[data-seat="${contested}"]`).click();
const c = await newUser();
await login(c.page, 'admin@cinema.test', 'Admin@123', showHref);
await c.page.waitForSelector(`[data-seat="${contested}"]`);
await c.page.locator(`[data-seat="${contested}"]`).click();
await c.page.getByRole('button', { name: /^Hold/ }).click();
await c.page.getByRole('timer').waitFor();
await b.page.getByRole('button', { name: /^Hold/ }).click();
await b.page.locator('.toast.bad').waitFor();
check('losing a seat race explains itself', /taken/i.test(await b.page.locator('.toast.bad').first().textContent()));
await shot(b.page, '06-seat-conflict');
await c.page.getByRole('button', { name: 'Change seats' }).click();

// ---- History and cancellation ----
await a.page.goto(`${BASE}/bookings.html`);
await a.page.waitForSelector('.booking-item');
await shot(a.page, '07-bookings');
await axe('bookings', a.page);
check('history lists the new booking', await a.page.getByText(reference).isVisible());

// ---- Entry gate ----
const s = await newUser();
await login(s.page, 'staff@cinema.test', 'Staff@123', '/staff.html');
await s.page.waitForSelector('#code');
await s.page.locator('#code').fill(reference);
await s.page.getByRole('button', { name: 'Validate ticket' }).click();
await s.page.locator('.gate-result.ok').waitFor();
check('gate admits a valid ticket', true);
await shot(s.page, '08-gate-admit');
await s.page.locator('#code').fill(reference);
await s.page.getByRole('button', { name: 'Validate ticket' }).click();
await s.page.locator('.gate-result.bad').waitFor();
check('gate refuses second use', /Already used/.test(await s.page.locator('.gate-result').textContent()));
await shot(s.page, '09-gate-reject');
await axe('entry gate', s.page);
await s.page.goto(`${BASE}/admin.html`);
await s.page.getByText('does not have access').waitFor();
check('staff cannot open admin UI', true);

await a.page.goto(`${BASE}/booking.html?ref=${reference}`);
await a.page.getByText('This ticket has been used').waitFor();
check('used ticket cannot be cancelled in UI', true);

// Cancel a different, seeded booking and confirm the refund.
await b.page.goto(`${BASE}/bookings.html?x=1`);
await b.page.getByLabel('Status').selectOption('CONFIRMED');
await b.page.waitForSelector('.booking-item');
await b.page.locator('.booking-item a').first().click();
await b.page.getByRole('button', { name: 'Cancel this booking' }).click();
await b.page.getByRole('button', { name: 'Yes, cancel booking' }).click();
await b.page.getByText('Refund status').waitFor();
check('cancellation shows simulated refund', true);
await shot(b.page, '10-cancelled');

// ---- Admin ----
const ad = c.page;
await ad.goto(`${BASE}/admin.html`);
await ad.waitForSelector('.chart-plot .chart-bar');
await ad.locator('.chart-col').nth(5).hover();
check('chart tooltip appears on hover', await ad.locator('.chart-tip').isVisible());
await shot(ad, '11-admin-dashboard');
await axe('admin dashboard', ad);
check('occupancy rows reconcile', (await ad.getByText('mismatch').count()) === 0 && (await ad.getByText('matches').count()) > 0);

await ad.goto(`${BASE}/admin.html#movies`);
await ad.waitForSelector('table');
await ad.getByRole('button', { name: 'Add movie' }).click();
await ad.locator('dialog').getByRole('button', { name: 'Save' }).click();
await ad.locator('dialog .field-error:not([hidden])').first().waitFor();
check('server validation errors appear on form fields', true);
await shot(ad, '12-admin-movie-validation');
await ad.locator('dialog').getByLabel('Title').fill('E2E Test Picture');
await ad.locator('dialog').getByLabel('Running time (minutes)').fill('100');
await ad.locator('dialog').getByLabel('Release date').fill('2026-10-01');
await ad.locator('dialog').getByLabel('Comedy').check();
await ad.locator('dialog').getByRole('button', { name: 'Save' }).click();
await ad.getByText('Movie saved.').waitFor();
check('admin can add a movie', true);

// Upload a tiny generated image as this movie's poster: it appears in the admin column and on the home page.
const tinyPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
await ad.getByLabel('Poster file for E2E Test Picture').setInputFiles({ name: 'test.png', mimeType: 'image/png', buffer: tinyPng });
await ad.getByText('Poster saved.').waitFor();
await ad.getByAltText('Uploaded poster for E2E Test Picture').waitFor();
check('admin can upload a poster, shown in the Poster column', true);
await ad.getByLabel('Poster file for E2E Test Picture').setInputFiles({ name: 'fake.png', mimeType: 'image/png', buffer: Buffer.from('not an image') });
await ad.locator('.toast.bad', { hasText: 'JPEG, PNG or WebP' }).waitFor();
check('a fake image is refused with a clear message', true);
await shot(ad, '12b-admin-movies');
await axe('admin movies', ad);

await ad.goto(`${BASE}/admin.html#venues`);
await ad.waitForSelector('.card h2');
await ad.getByRole('button', { name: 'Edit seats' }).first().click();
await ad.locator('dialog .seat').first().waitFor();
await shot(ad, '13-admin-seat-editor');
await ad.locator('dialog').getByRole('button', { name: 'Done' }).click();
await ad.getByRole('button', { name: 'Add screen' }).first().click();
await ad.locator('dialog').getByLabel('Screen name').fill('E2E Screen');
await ad.locator('dialog').getByRole('button', { name: 'Save' }).click();
await ad.getByText('Screen created.').waitFor();
check('admin can create a screen with a layout', true);
await axe('admin venues', ad);

await ad.goto(`${BASE}/admin.html#shows`);
await ad.waitForSelector('table');
await ad.waitForTimeout(400);
await ad.getByRole('button', { name: 'Schedule a show' }).click();
const dlg = ad.locator('dialog');
await dlg.getByLabel('Movie', { exact: true }).selectOption({ label: 'E2E Test Picture' });
await dlg.getByLabel('Theatre', { exact: true }).selectOption({ index: 1 });
await dlg.getByLabel('Screen', { exact: true }).locator('option', { hasText: 'E2E Screen' }).waitFor({ state: 'attached' });
await dlg.getByLabel('Screen', { exact: true }).selectOption({ label: 'E2E Screen (20 seats)' });
await dlg.getByLabel('Start time').fill('2031-06-15T18:00');
await dlg.getByRole('button', { name: 'Schedule show' }).click();
await ad.getByText('Show scheduled.').waitFor();
check('admin can schedule a show', true);
await shot(ad, '14-admin-shows');
await axe('admin shows', ad);

// Same slot again: overlap explained inside the open dialog.
await ad.getByRole('button', { name: 'Schedule a show' }).click();
await dlg.getByLabel('Movie', { exact: true }).selectOption({ label: 'E2E Test Picture' });
await dlg.getByLabel('Theatre', { exact: true }).selectOption({ index: 1 });
await dlg.getByLabel('Screen', { exact: true }).locator('option', { hasText: 'E2E Screen' }).waitFor({ state: 'attached' });
await dlg.getByLabel('Screen', { exact: true }).selectOption({ label: 'E2E Screen (20 seats)' });
await dlg.getByLabel('Start time').fill('2031-06-15T19:00');
await dlg.getByRole('button', { name: 'Schedule show' }).click();
await dlg.getByText(/overlaps another show/).waitFor();
check('overlap error stays in the form', true);
await dlg.getByRole('button', { name: 'Cancel' }).click();

await ad.goto(`${BASE}/admin.html#pricing`);
await ad.getByRole('button', { name: 'Save policy' }).waitFor();
await shot(ad, '15-admin-pricing');
await axe('admin pricing', ad);
await ad.goto(`${BASE}/admin.html#bookings`);
await ad.waitForSelector('table');
await shot(ad, '16-admin-bookings');
await axe('admin bookings', ad);
await ad.goto(`${BASE}/admin.html#audit`);
await ad.waitForSelector('table');
await shot(ad, '17-admin-audit');
await axe('admin audit', ad);

// ---- Sign-up ----
const n = await newUser();
await n.page.goto(`${BASE}/register.html`);
await n.page.getByLabel('Full name').fill('New Person');
await n.page.getByLabel('Email').fill('not-an-email');
await n.page.getByLabel('Password').fill('short');
await n.page.getByRole('button', { name: 'Create account' }).click();
await n.page.locator('.field-error:not([hidden])').first().waitFor();
check('registration shows field errors', true);
await axe('register', n.page);
await n.page.getByLabel('Email').fill(`new${Date.now()}@test.example`);
await n.page.getByLabel('Password').fill('Password123');
await n.page.getByRole('button', { name: 'Create account' }).click();
await n.page.getByRole('button', { name: 'Log out' }).waitFor();
check('registration logs the new customer in', true);
await n.page.goto(`${BASE}/login.html`);
await axe('login', n.page);

// Demo accounts: nothing to scroll sideways, and one click fills the form, on desktop and phone.
for (const [label, viewport] of [['desktop', { width: 1280, height: 900 }], ['phone', { width: 375, height: 760 }]]) {
  const fresh = await newUser(viewport);
  await fresh.page.goto(`${BASE}/login.html`);
  await fresh.page.locator('.demo-account').first().waitFor();
  const sideways = await fresh.page.locator('.demo-box').evaluate((box) => [box, ...box.querySelectorAll('*')].some((el) => el.scrollWidth > el.clientWidth + 1 && el.clientWidth > 0));
  check(`demo accounts need no sideways scroll: ${label}`, !sideways);
  await fresh.page.getByRole('button', { name: 'Fill in Staff demo account' }).click();
  check(`clicking a demo account fills the form: ${label}`, (await fresh.page.getByLabel('Email').inputValue()) === 'staff@cinema.test');
  if (label === 'phone') await noOverflow('login', fresh.page);
  await shot(fresh.page, `24-login-${label}`);
  await fresh.context.close();
}

// ---- Phone width ----
const phone = await newUser({ width: 375, height: 760 });
await phone.page.goto(BASE);
await phone.page.waitForSelector('.movie-card');
await noOverflow('home', phone.page);
await shot(phone.page, '20-phone-home');
await phone.page.goto(`${BASE}/movie.html?id=1`);
await phone.page.waitForSelector('.showtime');
await noOverflow('movie', phone.page);
await login(phone.page, 'customer2@cinema.test', 'Customer@123', `/seats.html?show=${Number(showId) + 1}`);
await phone.page.waitForSelector('.seat-rows .seat');
await noOverflow('seats', phone.page);
await shot(phone.page, '21-phone-seats');
await phone.page.goto(`${BASE}/bookings.html`);
await phone.page.waitForSelector('.booking-item');
await noOverflow('bookings', phone.page);
await phone.page.locator('.booking-item a').first().click();
await phone.page.waitForSelector('.ticket');
await noOverflow('ticket', phone.page);
await shot(phone.page, '22-phone-ticket');
await login(phone.page, 'admin@cinema.test', 'Admin@123', '/admin.html');
await phone.page.waitForSelector('.chart-plot');
await noOverflow('admin dashboard', phone.page);
await shot(phone.page, '23-phone-admin');

// ---- API docs under the CSP ----
const d = await newUser();
await d.page.goto(`${BASE}/swagger-ui/index.html`);
await d.page.waitForSelector('.opblock', { timeout: 15000 });
check('Swagger UI renders under the CSP', (await d.page.locator('.opblock').count()) > 20, `${await d.page.locator('.opblock').count()} operations`);
await shot(d.page, '30-swagger');

await browser.close();
console.log(`\n${passed.length} passed, ${problems.length} problems`);
problems.forEach((p) => console.log(`PROBLEM: ${p}`));
process.exit(problems.length ? 1 : 0);
