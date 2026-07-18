import { chromium } from '@playwright/test';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const assetDirectory = resolve(root, 'static/pwa');
const screenshotDirectory = resolve(assetDirectory, 'screenshots');
const provenancePath = resolve(screenshotDirectory, 'provenance.json');
const screenshotSpecs = [
	{
		source: 'tests/visual/runway.visual.ts-snapshots/calendar-mobile-linux.png',
		viewport: { width: 390, height: 844 },
		output: 'static/pwa/screenshots/calendar-mobile.png'
	},
	{
		source: 'tests/visual/runway.visual.ts-snapshots/calendar-wide-linux.png',
		viewport: { width: 1440, height: 900 },
		output: 'static/pwa/screenshots/calendar-desktop.png'
	}
];

if (process.argv.includes('--check')) {
	await checkScreenshotProvenance();
	process.exit(0);
}

const browser = await chromium.launch({ headless: true });

try {
	await mkdir(screenshotDirectory, { recursive: true });
	const source = await readFile(resolve(root, 'src/lib/assets/favicon.svg'), 'utf8');
	const maskableSource = source.replace('rx="20"', 'rx="0"');
	const page = await browser.newPage();

	await renderIcon(page, source, 192, resolve(assetDirectory, 'icon-192.png'));
	await renderIcon(page, source, 512, resolve(assetDirectory, 'icon-512.png'));
	await renderIcon(page, maskableSource, 192, resolve(assetDirectory, 'maskable-icon-192.png'));
	await renderIcon(page, maskableSource, 512, resolve(assetDirectory, 'maskable-icon-512.png'));
	await renderIcon(page, maskableSource, 180, resolve(assetDirectory, 'apple-touch-icon.png'));

	for (const screenshot of screenshotSpecs) {
		await cropScreenshot(
			page,
			resolve(root, screenshot.source),
			screenshot.viewport,
			resolve(root, screenshot.output)
		);
	}
	await writeScreenshotProvenance();
} finally {
	await browser.close();
}

async function renderIcon(page, source, size, output) {
	await page.setViewportSize({ width: size, height: size });
	await page.setContent(
		`<style>html,body{margin:0;width:${size}px;height:${size}px;overflow:hidden}svg{display:block;width:100%;height:100%}</style>${source}`
	);
	await page.locator('svg').screenshot({ path: output, omitBackground: true });
}

async function cropScreenshot(page, source, viewport, output) {
	await page.setViewportSize(viewport);
	await page.goto(pathToFileURL(source).href);
	await page.addStyleTag({
		content: `html,body{margin:0;min-height:0;background:transparent;overflow:hidden}img{display:block;width:${viewport.width}px!important;height:auto!important;margin:0;max-width:none!important;max-height:none!important}`
	});
	await page.screenshot({ path: output });
}

async function writeScreenshotProvenance() {
	const screenshots = await Promise.all(
		screenshotSpecs.map(async ({ source, output }) => ({
			source,
			sourceSha256: await fileSha256(resolve(root, source)),
			output,
			outputSha256: await fileSha256(resolve(root, output))
		}))
	);
	await writeFile(
		provenancePath,
		`${JSON.stringify({ version: 1, screenshots }, null, 2)}\n`,
		'utf8'
	);
}

async function checkScreenshotProvenance() {
	let provenance;
	try {
		provenance = JSON.parse(await readFile(provenancePath, 'utf8'));
	} catch {
		throw new Error(
			'PWA screenshot provenance is missing or invalid. Run `corepack pnpm assets:pwa` after updating visual snapshots.'
		);
	}
	if (
		provenance?.version !== 1 ||
		!Array.isArray(provenance.screenshots) ||
		provenance.screenshots.length !== screenshotSpecs.length
	) {
		throw new Error(
			'PWA screenshot provenance has an unsupported shape. Run `corepack pnpm assets:pwa`.'
		);
	}

	for (let index = 0; index < screenshotSpecs.length; index += 1) {
		const expected = screenshotSpecs[index];
		const recorded = provenance.screenshots[index];
		if (recorded?.source !== expected.source || recorded?.output !== expected.output) {
			throw new Error(
				'PWA screenshot provenance does not match the configured assets. Run `corepack pnpm assets:pwa`.'
			);
		}
		const [sourceSha256, outputSha256] = await Promise.all([
			fileSha256(resolve(root, expected.source)),
			fileSha256(resolve(root, expected.output))
		]);
		if (recorded.sourceSha256 !== sourceSha256 || recorded.outputSha256 !== outputSha256) {
			throw new Error(
				`PWA install screenshot ${expected.output} is stale. Run \`corepack pnpm assets:pwa\` after the visual snapshot update.`
			);
		}
	}
}

async function fileSha256(path) {
	return createHash('sha256')
		.update(await readFile(path))
		.digest('hex');
}
