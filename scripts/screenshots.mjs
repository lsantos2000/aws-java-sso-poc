/**
 * Regenerates the screenshots in docs/screenshots/ from the running mock flow.
 *
 * Playwright is deliberately not a project dependency — it would add a large install to
 * every `npm ci` in CI, which never takes screenshots. Install it on demand:
 *
 *   npm install --no-save playwright && npx playwright install chromium
 *
 * Then, with both servers running on the mock profile:
 *
 *   node scripts/screenshots.mjs
 *
 * Pass a different app URL as the first argument, and a different output directory as the
 * second. Everything captured here comes from the local simulator, so no screenshot can
 * contain a real identity — keep it that way.
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const APP = process.argv[2] ?? 'http://localhost:5173';
const OUT = process.argv[3] ?? resolve(repoRoot, 'docs/screenshots');

mkdirSync(OUT, { recursive: true });

const wrote = (name) => console.log('wrote', `${name}.png`);

const browser = await chromium.launch();

try {
  // ── Desktop ──────────────────────────────────────────────────────────────
  const desktop = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 });
  const page = await desktop.newPage();

  await page.goto(APP, { waitUntil: 'networkidle' });
  await page.getByText('Local simulator').waitFor();
  await page.getByText('GET /api/auth/status').first().waitFor();
  await page.waitForTimeout(600); // let the entry animation settle

  const height = await page.evaluate(() => document.documentElement.scrollHeight);
  console.log(`signed-out page height: ${height}px against a ${900}px viewport`);

  await page.screenshot({ path: `${OUT}/signed-out.png`, fullPage: true });
  wrote('signed-out');

  // The BACKEND tab, streaming real Spring Boot output.
  await page.getByRole('tab', { name: 'BACKEND' }).click();
  await page.waitForFunction(() => document.querySelectorAll('.term-body .line').length > 3, null, { timeout: 15000 });
  await page.locator('.term-body').evaluate((el) => { el.scrollTop = el.scrollHeight; });
  await page.waitForTimeout(400);
  // An element screenshot scrolls the section into view; a viewport clip would cut it off.
  await page.locator('.console').screenshot({ path: `${OUT}/console-backend.png` });
  wrote('console-backend');

  // Sign in. The app reloads, so wait for the new document before asserting.
  await page.getByRole('tab', { name: 'SESSION' }).click();
  await Promise.all([
    page.waitForLoadState('networkidle'),
    page.getByRole('button', { name: /Sign in as demo user/ }).click(),
  ]);
  await page.getByText('Demo User').waitFor();
  await page.getByText('GET /api/me').first().waitFor();
  await page.waitForTimeout(600);

  const signedInHeight = await page.evaluate(() => document.documentElement.scrollHeight);
  console.log(`signed-in page height: ${signedInHeight}px against a ${900}px viewport`);

  await page.screenshot({ path: `${OUT}/signed-in.png`, fullPage: true });
  wrote('signed-in');

  // ── Mobile ───────────────────────────────────────────────────────────────
  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  const small = await mobile.newPage();
  await small.goto(APP, { waitUntil: 'networkidle' });
  await small.getByText('Local simulator').waitFor();
  await small.waitForTimeout(600);
  await small.screenshot({ path: `${OUT}/signed-out-mobile.png`, fullPage: true });
  wrote('signed-out-mobile');
} finally {
  await browser.close();
}

console.log('done');
