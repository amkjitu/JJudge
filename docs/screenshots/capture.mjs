/**
 * Captures the README screenshots from the running compose stack.
 *
 *   docker compose up -d --build          # the stack must be up and seeded
 *   npm install --no-save puppeteer-core  # ~80 packages, no browser download
 *   node docs/screenshots/capture.mjs
 *
 * puppeteer-core rather than puppeteer: it drives the Chrome already installed on the machine
 * instead of downloading its own, which keeps a Java repository from acquiring a 150 MB browser
 * as a dev dependency. Set CHROME if yours lives somewhere else.
 *
 * It logs in through the real form rather than injecting a session, so every shot is of the
 * application as a visitor actually sees it.
 */
import puppeteer from 'puppeteer-core';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const CHROME = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const BASE = process.env.BASE || 'http://localhost:8080';
// Defaults to this directory, which is where the README expects the images.
//
// fileURLToPath, not `new URL(...).pathname`: a file URL percent-encodes its path, so a checkout
// under "Project Development" yields ".../Project%20Development/..." - a directory that does not
// exist, which mkdirSync then cheerfully creates. The capture reports success, writes eight PNGs
// into a phantom tree, and the real ones never change.
const OUT = process.env.OUT || dirname(fileURLToPath(import.meta.url));

const VIEWPORT = { width: 1440, height: 900 };

mkdirSync(OUT, { recursive: true });

const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    defaultViewport: VIEWPORT,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--force-color-profile=srgb'],
});

const page = await browser.newPage();
page.setDefaultTimeout(150000);
page.setDefaultNavigationTimeout(150000);

async function go(path) {
    await page.goto(BASE + path, { waitUntil: 'domcontentloaded' });
    // Chart.js animates on first paint; give it a beat so the charts are drawn.
    await new Promise(r => setTimeout(r, 900));
}

async function shot(name, opts = {}) {
    await page.screenshot({ path: `${OUT}/${name}`, ...opts });
    console.log('  captured ' + name);
}

async function login(username, password) {
    await go('/login');
    await page.type('#usernameOrEmail, input[name="usernameOrEmail"]', username);
    await page.type('#password, input[name="password"]', password);
    await Promise.all([
        page.waitForNavigation({ waitUntil: 'domcontentloaded' }),
        page.click('button[type="submit"]'),
    ]);
    console.log('  logged in as ' + username);
}

console.log('Capturing from ' + BASE);

// ---- signed out -------------------------------------------------------------------------
await go('/swagger-ui.html');
await new Promise(r => setTimeout(r, 2500));
await shot('swagger.png');

// ---- signed in as bob -------------------------------------------------------------------
await login('bob', 'Password123!');

await go('/');
await shot('hero.png');

await go('/problems?tag=dp&difficulty=MEDIUM');
await shot('problem-list.png');

await go('/problems/maximum-subarray-sum');
await shot('problem-detail.png');

// The hint loads on demand. Wait for a *successful* one - the provenance line is only populated
// on success - rather than for any text at all: the error message is also text, so the looser
// condition happily screenshots the failure state and calls it a captured hint.
//
// The first request after arena-ai starts pays the model probe before falling back, so this can
// legitimately take half a minute. Clicking again on failure covers that first slow attempt.
try {
    for (let attempt = 1; attempt <= 3; attempt++) {
        await page.click('#hint-button');
        try {
            await page.waitForFunction(
                () => {
                    const meta = document.getElementById('hint-meta');
                    const text = document.getElementById('hint-text');
                    return meta && !meta.classList.contains('d-none')
                        && text && text.textContent.trim().length > 0;
                },
                { timeout: 60000 });
            break;
        } catch (e) {
            if (attempt === 3) {
                throw e;
            }
            console.log('  hint attempt ' + attempt + ' did not succeed; retrying');
            await new Promise(r => setTimeout(r, 2000));
        }
    }
    await new Promise(r => setTimeout(r, 400));
    await shot('ai-hint.png');
} catch (e) {
    console.log('  ai-hint skipped: ' + e.message);
}

await go('/users/bob');
await shot('profile-charts.png');

await go('/leaderboard');
await shot('leaderboard.png');

// Recommendations live on the dashboard. Find the panel by its heading rather than adding an
// id to the template purely so a screenshot script can grab it.
await go('/');
const handle = await page.evaluateHandle(() => {
    const headings = [...document.querySelectorAll('h1, h2, h3, .h4, .h5, .h6')];
    const heading = headings.find(e => /recommend/i.test(e.textContent || ''));
    return heading ? heading.closest('section') || heading.parentElement : null;
});
const panel = handle.asElement();
if (panel) {
    await panel.screenshot({ path: `${OUT}/recommendations.png` });
    console.log('  captured recommendations.png (panel)');
} else {
    console.log('  recommendations panel not found; capturing the dashboard instead');
    await shot('recommendations.png');
}

await browser.close();
console.log('done');
