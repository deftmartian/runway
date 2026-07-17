import { describe, expect, it } from 'vitest';
import { createServiceWorkerSource } from './service-worker';

describe('service worker build revisions', () => {
	it('gives each build independent caches and removes superseded runway caches on activation', () => {
		const first = createServiceWorkerSource('build-a');
		const second = createServiceWorkerSource('build-b');

		expect(first).toContain('const CACHE_REVISION = "build-a"');
		expect(second).toContain('const CACHE_REVISION = "build-b"');
		expect(first).not.toBe(second);
		expect(second).toContain("name.startsWith('runway-') && !ACTIVE_CACHES.has(name)");
	});

	it('activates a waiting build only after an explicit client request', () => {
		const source = createServiceWorkerSource('safe-update');

		expect(source).toContain("event.data?.type !== 'ACTIVATE_UPDATE'");
		expect(source).toContain('event.waitUntil(self.skipWaiting())');
		expect(source).toContain('self.clients.claim()');
	});

	it('uses navigation preload without caching navigation responses', () => {
		const source = createServiceWorkerSource('navigation-preload');

		expect(source).toContain('self.registration.navigationPreload?.enable()');
		expect(source).toContain('(await event.preloadResponse) ?? (await fetch(event.request))');
		expect(source).not.toContain(
			"cache.put(event.request, response.clone());\n\t\t\t\treturn response;\n\t\t\t} catch {\n\t\t\t\treturn (await caches.match('/offline.html'))"
		);
	});

	it('never intercepts private routes for runtime caching', () => {
		const source = createServiceWorkerSource('private-boundary');

		expect(source).toContain("PRIVATE_PREFIXES = ['/app', '/api/auth', '/login', '/logout']");
		expect(source).toContain("url.pathname === prefix || url.pathname.startsWith(prefix + '/')");
	});
});
