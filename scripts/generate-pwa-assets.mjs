import { chromium } from '@playwright/test';
import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const assetDirectory = resolve(root, 'static/pwa');
const screenshotDirectory = resolve(assetDirectory, 'screenshots');
const provenancePath = resolve(screenshotDirectory, 'provenance.json');
const iconSourcePath = 'src/lib/assets/favicon.svg';
const iconOutputs = [
	'static/pwa/icon-192.png',
	'static/pwa/icon-512.png',
	'static/pwa/maskable-icon-192.png',
	'static/pwa/maskable-icon-512.png',
	'static/pwa/apple-touch-icon.png'
];
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
const documentationScreenshotSpecs = [
	{
		source: 'tests/visual/runway.visual.ts-snapshots/calendar-desktop-linux.png',
		output: 'docs/images/runway-calendar-desktop.png'
	},
	{
		source: 'tests/visual/runway.visual.ts-snapshots/calendar-mobile-linux.png',
		output: 'docs/images/runway-calendar-mobile.png'
	}
];

if (process.argv.includes('--check')) {
	await checkScreenshotProvenance();
	process.exit(0);
}

const browser = await chromium.launch({ headless: true });

try {
	await mkdir(screenshotDirectory, { recursive: true });
	const source = await readFile(resolve(root, iconSourcePath), 'utf8');
	const maskableSource = paddedMaskableSource(source);
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
	for (const screenshot of documentationScreenshotSpecs) {
		await copyFile(resolve(root, screenshot.source), resolve(root, screenshot.output));
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

function paddedMaskableSource(source) {
	const square = source.replace('rx="20"', 'rx="0"');
	if (!square.includes('<path') || !square.includes('</svg>')) {
		throw new Error('The runway icon source does not contain the expected vector artwork.');
	}
	return square
		.replace('<path', '<g transform="translate(17.92 17.92) scale(0.72)"><path')
		.replace('</svg>', '</g></svg>');
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
	const icons = await Promise.all(
		iconOutputs.map(async (output) => ({
			output,
			outputSha256: await fileSha256(resolve(root, output))
		}))
	);
	const screenshots = await Promise.all(
		screenshotSpecs.map(async ({ source, output }) => ({
			source,
			sourceSha256: await fileSha256(resolve(root, source)),
			output,
			outputSha256: await fileSha256(resolve(root, output))
		}))
	);
	const documentationScreenshots = await Promise.all(
		documentationScreenshotSpecs.map(async ({ source, output }) => ({
			source,
			sourceSha256: await fileSha256(resolve(root, source)),
			output,
			outputSha256: await fileSha256(resolve(root, output))
		}))
	);
	await writeFile(
		provenancePath,
		`${JSON.stringify(
			{
				version: 3,
				iconSource: iconSourcePath,
				iconSourceSha256: await fileSha256(resolve(root, iconSourcePath)),
				icons,
				screenshots,
				documentationScreenshots
			},
			null,
			2
		)}\n`,
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
		provenance?.version !== 3 ||
		provenance.iconSource !== iconSourcePath ||
		!Array.isArray(provenance.icons) ||
		provenance.icons.length !== iconOutputs.length ||
		!Array.isArray(provenance.screenshots) ||
		provenance.screenshots.length !== screenshotSpecs.length ||
		!Array.isArray(provenance.documentationScreenshots) ||
		provenance.documentationScreenshots.length !== documentationScreenshotSpecs.length
	) {
		throw new Error(
			'PWA screenshot provenance has an unsupported shape. Run `corepack pnpm assets:pwa`.'
		);
	}
	if (provenance.iconSourceSha256 !== (await fileSha256(resolve(root, iconSourcePath)))) {
		throw new Error('PWA icon source changed. Run `corepack pnpm assets:pwa`.');
	}
	for (let index = 0; index < iconOutputs.length; index += 1) {
		const output = iconOutputs[index];
		const recorded = provenance.icons[index];
		if (
			recorded?.output !== output ||
			recorded?.outputSha256 !== (await fileSha256(resolve(root, output)))
		) {
			throw new Error(`PWA icon ${output} is stale. Run \`corepack pnpm assets:pwa\`.`);
		}
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

	for (let index = 0; index < documentationScreenshotSpecs.length; index += 1) {
		const expected = documentationScreenshotSpecs[index];
		const recorded = provenance.documentationScreenshots[index];
		if (recorded?.source !== expected.source || recorded?.output !== expected.output) {
			throw new Error(
				'Documentation screenshot provenance does not match the configured assets. Run `corepack pnpm assets:pwa`.'
			);
		}
		const [sourceSha256, outputSha256] = await Promise.all([
			fileSha256(resolve(root, expected.source)),
			fileSha256(resolve(root, expected.output))
		]);
		if (recorded.sourceSha256 !== sourceSha256 || recorded.outputSha256 !== outputSha256) {
			throw new Error(
				`README screenshot ${expected.output} is stale. Run \`corepack pnpm assets:pwa\` after the visual snapshot update.`
			);
		}
	}
}

async function fileSha256(path) {
	return createHash('sha256')
		.update(await readFile(path))
		.digest('hex');
}
