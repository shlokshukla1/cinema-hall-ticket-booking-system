// Live hold expiry against a server started with APP_HOLD_TTL_SECONDS=8 and the real sweeper running.
import { chromium } from 'playwright-core';

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const page = await (await browser.newContext({ viewport: { width: 1280, height: 900 } })).newPage();

const shows = await (await fetch(`${BASE}/api/shows?size=1`)).json();
const showId = shows.content[0].id;
await page.goto(`${BASE}/login.html?next=${encodeURIComponent(`/seats.html?show=${showId}`)}`);
await page.getByLabel('Email').fill('customer@cinema.test');
await page.getByLabel('Password').fill('Customer@123');
await page.locator('form').getByRole('button', { name: 'Log in' }).click();
await page.waitForSelector('.seat-rows .seat:not(:disabled)');
const seat = await page.locator('.seat-rows .seat:not(:disabled)').first().getAttribute('data-seat');
await page.locator(`[data-seat="${seat}"]`).click();
await page.getByRole('button', { name: /^Hold/ }).click();
await page.getByRole('timer').waitFor();

const status = async () => (await (await fetch(`${BASE}/api/shows/${showId}/seats`)).json()).seats.find((s) => String(s.seatId) === seat).status;
console.log('during hold, API seat status:', await status());
await page.locator('.toast.bad', { hasText: 'Your hold expired' }).waitFor({ timeout: 20000 });
console.log('UI announced expiry; checkout closed:', !(await page.getByRole('timer').isVisible()));
console.log('after expiry, API seat status:', await status());
console.log('seat selectable again in UI:', !(await page.locator(`[data-seat="${seat}"]`).isDisabled()));
await new Promise((r) => setTimeout(r, 17000)); // allow one sweep
await browser.close();
