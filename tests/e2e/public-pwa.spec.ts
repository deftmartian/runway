import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript } from '../support/test-clock';
import { createAccount, expectNoCriticalAxeViolations } from './support/runway';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('public pages are accessible at desktop and mobile widths', async ({ page }) => {
	const cspErrors: string[] = [];
	page.on('console', (message) => {
		if (message.type() === 'error' && message.text().includes('Content Security Policy')) {
			cspErrors.push(message.text());
		}
	});

	await page.setViewportSize({ width: 390, height: 844 });
	await page.goto('/');
	await expect(page.getByRole('heading', { name: 'runway' })).toBeVisible();
	await expectNoCriticalAxeViolations(page);

	await page.setViewportSize({ width: 1280, height: 900 });
	await page.goto('/login');
	await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();
	await expect(page.getByRole('button', { name: 'Sign in with passkey' })).toBeVisible();
	await expectNoCriticalAxeViolations(page);
	expect(cspErrors).toEqual([]);
});

test('PWA files and private routes carry the expected cache boundaries', async ({ page }) => {
	const manifest = await page.request.get('/manifest.webmanifest');
	expect(manifest.ok()).toBe(true);
	await expect(manifest.json()).resolves.toMatchObject({
		id: '/',
		name: 'runway',
		short_name: 'runway',
		start_url: '/app',
		scope: '/',
		display: 'standalone',
		share_target: {
			action: '/app/import/share',
			method: 'POST',
			enctype: 'multipart/form-data',
			params: { files: [{ name: 'gpx' }] }
		}
	});

	const serviceWorker = await page.request.get('/service-worker.js');
	expect(serviceWorker.ok()).toBe(true);
	expect(serviceWorker.headers()['cache-control']).toBe('public, max-age=0, must-revalidate');
	const serviceWorkerBody = await serviceWorker.text();
	expect(serviceWorkerBody).toContain(
		"PRIVATE_PREFIXES = ['/app', '/api/auth', '/login', '/logout']"
	);
	expect(serviceWorkerBody).toContain("'/offline.css'");
	expect(serviceWorkerBody).toContain("event.data?.type !== 'ACTIVATE_UPDATE'");
	expect(serviceWorkerBody).toContain('event.waitUntil(self.skipWaiting())');
	expect(serviceWorkerBody).toContain('self.registration.navigationPreload?.enable()');
	expect(serviceWorkerBody).toContain('event.preloadResponse');
	const cacheRevision = /const CACHE_REVISION = "([^"]+)"/.exec(serviceWorkerBody)?.[1];
	const live = await page.request.get('/health/live');
	expect(live.ok()).toBe(true);
	const liveBody: unknown = await live.json();
	expect(liveBody).toEqual(expect.objectContaining({ version: cacheRevision }));

	const offline = await page.request.get('/offline.html');
	expect(offline.ok()).toBe(true);
	const offlineBody = await offline.text();
	expect(offlineBody).toContain('Reconnect to open your calendar and training data.');
	expect(offlineBody).toContain('href="/app">Try again</a>');
	expect(offlineBody).not.toContain('<style');
	const offlineCss = await page.request.get('/offline.css');
	expect(offlineCss.ok()).toBe(true);
	expect(offlineCss.headers()['cache-control']).toBe('public, max-age=86400');
	await page.setViewportSize({ width: 390, height: 844 });
	await page.goto('/offline.html');
	expect(
		await page.evaluate(() => ({
			scrollWidth: document.documentElement.scrollWidth,
			clientWidth: document.documentElement.clientWidth
		}))
	).toMatchObject({ scrollWidth: 390, clientWidth: 390 });

	const app = await page.request.get('/app', { maxRedirects: 0 });
	expect([302, 303, 307, 308]).toContain(app.status());
	expect(app.headers()['cache-control']).toBe('private, no-store');

	const assetLinks = await page.request.get('/.well-known/assetlinks.json');
	expect(assetLinks.status()).toBe(404);
	expect(assetLinks.headers()['cache-control']).toBe('private, no-store');
	const unknownRoute = await page.request.get('/not-a-runway-route');
	expect(unknownRoute.status()).toBe(404);
	expect(unknownRoute.headers()['cache-control']).toBe('private, no-store');
});

test('PWA lifecycle shows connection state and a quiet install shortcut', async ({
	context,
	page
}) => {
	await createAccount(page);
	await page.goto('/app/settings');

	const installNotice = page.getByRole('region', { name: 'Install runway' });
	const installButton = installNotice.getByRole('button', { name: 'Install', exact: true });
	await expect(
		installNotice.getByText('Use the browser menu and choose Install app or Add to Home screen.')
	).toBeVisible();
	await expect
		.poll(async () => {
			await page.evaluate(() => {
				const installEvent = new Event('beforeinstallprompt', { cancelable: true });
				Object.defineProperties(installEvent, {
					prompt: {
						value: () => {
							(
								globalThis as typeof globalThis & { runwayInstallPrompted?: boolean }
							).runwayInstallPrompted = true;
							return Promise.resolve();
						}
					},
					userChoice: {
						value: Promise.resolve({ outcome: 'accepted', platform: 'test' })
					}
				});
				globalThis.dispatchEvent(installEvent);
			});
			return installButton.isVisible();
		})
		.toBe(true);
	const installShortcut = page.getByRole('button', { name: 'Install runway', exact: true });
	await expect(installShortcut).toBeVisible();
	await page.getByRole('link', { name: 'Calendar' }).click();
	await expect(page).toHaveURL(/\/app\/onboarding$/);
	await expect(installNotice).not.toBeVisible();
	await expect(installShortcut).toBeVisible();
	await page.getByRole('link', { name: 'Settings' }).click();
	await expect(page).toHaveURL(/\/app\/settings$/);
	await expect(installNotice).toBeVisible();
	await installButton.click();
	await expect
		.poll(() =>
			page.evaluate(
				() =>
					(globalThis as typeof globalThis & { runwayInstallPrompted?: boolean })
						.runwayInstallPrompted
			)
		)
		.toBe(true);
	await expect(installNotice).not.toBeVisible();
	await expect(installShortcut).not.toBeVisible();

	await context.setOffline(true);
	try {
		await expect(
			page.getByText('Reconnect to view or change private training data.')
		).toBeVisible();
	} finally {
		await context.setOffline(false);
	}
	await expect(page.getByText('Back online')).toBeVisible();
});

test('service worker serves the offline fallback without caching private app pages', async ({
	context,
	page
}) => {
	await page.goto('/');
	await page.evaluate(async () => {
		if (!('serviceWorker' in navigator)) throw new Error('Service workers are unavailable.');
		await navigator.serviceWorker.ready;
	});
	await page.reload();
	await page.waitForFunction(() => navigator.serviceWorker.controller !== null);

	const cachedPaths = await page.evaluate(async () => {
		const paths: string[] = [];
		for (const cacheName of await caches.keys()) {
			if (!cacheName.startsWith('runway-')) continue;
			const cache = await caches.open(cacheName);
			for (const request of await cache.keys()) {
				paths.push(new URL(request.url).pathname);
			}
		}
		return paths;
	});
	expect(cachedPaths).toContain('/offline.html');
	expect(cachedPaths).toContain('/offline.css');
	expect(cachedPaths.some((path) => path.startsWith('/app'))).toBe(false);

	await context.setOffline(true);
	try {
		await page.goto('/app');
		await expect(page.getByRole('heading', { name: 'Offline' })).toBeVisible();
		await expect(
			page.getByText('Reconnect to open your calendar and training data.')
		).toBeVisible();
	} finally {
		await context.setOffline(false);
	}
});
