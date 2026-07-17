import { chromium } from '@playwright/test';
import { mkdir, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const assetDirectory = resolve(root, 'static/pwa');
const screenshotDirectory = resolve(assetDirectory, 'screenshots');
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

	await cropScreenshot(
		page,
		resolve(root, 'tests/visual/runway.visual.ts-snapshots/calendar-mobile-linux.png'),
		{ width: 390, height: 844 },
		resolve(screenshotDirectory, 'calendar-mobile.png')
	);
	await cropScreenshot(
		page,
		resolve(root, 'tests/visual/runway.visual.ts-snapshots/calendar-wide-linux.png'),
		{ width: 1440, height: 900 },
		resolve(screenshotDirectory, 'calendar-desktop.png')
	);
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
