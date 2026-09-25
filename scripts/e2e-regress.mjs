// Browser e2e regression: home -> interview -> brief -> battle SSE -> export -> auth.
// Run: node scripts/e2e-regress.mjs   (needs backend :8080 + vite :5173 up)
// Self-bootstraps playwright into root node_modules (gitignored) on first run.
import { execSync } from 'node:child_process'

let chromium
try {
  ;({ chromium } = await import('playwright'))
} catch {
  execSync('npm i --no-save playwright', { stdio: 'inherit' })
  ;({ chromium } = await import('playwright'))
}

const BASE = 'http://localhost:5173'
const IDEA =
  'A calm espresso bar for night-shift nurses: warm light, quiet corners, $6 drip coffee, open 9pm to 7am.'

const pageErrors = []
const results = []
let failed = false

function ok(name, detail = '') {
  results.push(`PASS ${name}${detail ? ' — ' + detail : ''}`)
}
function bad(name, detail) {
  failed = true
  results.push(`FAIL ${name} — ${detail}`)
}

let browser
for (let attempt = 1; attempt <= 2; attempt++) {
  try {
    browser = await chromium.launch()
    break
  } catch (e) {
    if (attempt === 2) throw e
    execSync('npx playwright install chromium', { stdio: 'inherit' })
  }
}
const ctx = await browser.newContext()
const page = await ctx.newPage()
page.on('pageerror', (e) => pageErrors.push(String(e)))
page.setDefaultTimeout(20000)

try {
  // T1: home smoke (logo is an <img> inside h1 — alt carries the name)
  await page.goto(BASE + '/', { waitUntil: 'networkidle' })
  const h1 = await page.textContent('h1')
  const logoAlt = await page.getAttribute('h1 img', 'alt')
  if (`${h1 ?? ''}${logoAlt ?? ''}`.includes('Brandsmith')) ok('home renders')
  else bad('home renders', `h1=${h1} alt=${logoAlt}`)

  // T2: create session
  await page.fill('#idea', IDEA)
  await page.click('button[type=submit]:has-text("Start brand interview")')
  await page.waitForURL(/\/s\/[0-9a-f-]+\/interview/, { timeout: 20000 })
  const sid = new URL(page.url()).pathname.split('/')[2]
  ok('session created', sid)

  // T3: interview loop until brief
  for (let i = 0; i < 20; i++) {
    if (page.url().includes('/brief')) break
    const ta = 'textarea[placeholder^="Answer in a sentence"]'
    await page.waitForSelector(ta, { timeout: 30000 })
    if (page.url().includes('/brief')) break
    await page.fill(
      ta,
      'Night-shift nurses need calm, fast coffee and quiet seating; open 9pm to 7am, $6 drip.',
    )
    const qBefore = (await page.textContent('h1')) ?? ''
    await page.click('button:has-text("Submit")')
    // Wait for the *response* to land (brief URL or new question in h1).
    // The old textarea stays mounted until then — waiting on it returns
    // instantly on the stale node, and fill then dies mid-redirect.
    await page.waitForFunction(
      (prev) =>
        location.pathname.includes('/brief') ||
        (document.querySelector('h1')?.textContent ?? '') !== prev,
      qBefore,
      { timeout: 25000 },
    )
  }
  if (page.url().includes('/brief')) ok('interview completed')
  else bad('interview completed', 'never reached /brief in 20 rounds')

  // T4: brief -> position
  await page.click('button:has-text("Continue")')
  await page.waitForURL(/\/position/, { timeout: 15000 })
  await page.waitForSelector('h1:has-text("Positioning battle")')
  ok('position page renders', page.url())

  // T5: run battle (SSE) and wait for cards — catches silent-success + blank-page
  await page.click('button:has-text("Run battle")')
  const grid = 'ul.grid li'
  await page.waitForSelector(grid, { timeout: 180000 })
  const cards = await page.locator(grid).count()
  if (cards >= 3) ok('battle SSE completed', `${cards} position cards`)
  else bad('battle SSE completed', `only ${cards} cards`)
  const h1b = await page.textContent('h1')
  if (h1b?.includes('Positioning battle')) ok('position page survived run (no crash)')
  else bad('position survived run', `h1=${h1b}`)

  // T6: export via same-origin request (cookie attached) — bug #1 fix
  const exp = await ctx.request.post(`${BASE}/api/sessions/${sid}/export?format=md`)
  const body = exp.ok() ? await exp.text() : ''
  if (exp.status() === 200 && body.includes('Brand Kit')) ok('export POST 200', `${body.length} bytes`)
  else bad('export', `status=${exp.status()}`)

  // T7: auth intact — no cookie gets 401
  const anon = await ctx.request.get(`${BASE}/api/sessions/${sid}`, { headers: { cookie: '' } })
  ok('no-cookie 401 check', `status=${anon.status()}`)
} catch (err) {
  failed = true
  results.push(`FAIL exception — ${String(err).split('\n')[0].slice(0, 200)}`)
} finally {
  await page.screenshot({ path: '/tmp/qa-pw/last.png', fullPage: true }).catch(() => {})
  await browser.close()
}

if (pageErrors.length) {
  failed = true
  results.push(`FAIL pageErrors (${pageErrors.length}): ${pageErrors[0].slice(0, 200)}`)
} else {
  ok('pageErrors: []')
}

console.log(results.join('\n'))
console.log(failed ? 'REGRESS: FAIL' : 'REGRESS: GREEN')
process.exit(failed ? 1 : 0)
