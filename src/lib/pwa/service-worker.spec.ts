import { describe, expect, it } from 'vitest';
import {
	createServiceWorkerSource,
	immutableAssetCacheOrder,
	previousCompleteCacheRevision
} from './service-worker';

describe('service worker build revisions', () => {
	it('keeps one complete predecessor cache generation while bounding superseded caches', () => {
		const first = createServiceWorkerSource('build-a');
		const second = createServiceWorkerSource('build-b');

		expect(first).toContain('const CACHE_REVISION = "build-a"');
		expect(second).toContain('const CACHE_REVISION = "build-b"');
		expect(first).not.toBe(second);
		expect(second).toContain("const PUBLIC_CACHE_PREFIX = 'runway-public-'");
		expect(second).toContain("const APP_CACHE_PREFIX = 'runway-app-assets-'");
		expect(second).toContain('const previousRevision = await previousCompleteCacheRevision(names)');
		expect(second).toContain('retained.add(PUBLIC_CACHE_PREFIX + previousRevision)');
		expect(second).toContain('retained.add(APP_CACHE_PREFIX + previousRevision)');
		expect(second).toContain('function previousCompleteCacheRevision(names)');
		expect(second).toContain('names.includes(APP_CACHE_PREFIX + revision)');
		expect(second).toContain("name.startsWith('runway-') && !retained.has(name)");
	});

	it('uses the recorded predecessor, not cache enumeration order, and ignores incomplete generations', () => {
		const names = [
			'runway-public-oldest',
			'runway-app-assets-oldest',
			'runway-public-previous',
			'runway-app-assets-previous',
			'runway-public-current',
			'runway-public-incomplete'
		];

		expect(previousCompleteCacheRevision(names, 'current', 'previous')).toBe('previous');
		expect(previousCompleteCacheRevision(names, 'current', 'incomplete')).toBe('previous');
		expect(immutableAssetCacheOrder(names, 'current', 'previous')).toEqual([
			'runway-app-assets-current',
			'runway-app-assets-previous'
		]);
	});

	it('bypasses the HTTP cache while installing every unversioned offline asset', () => {
		const source = createServiceWorkerSource('fresh-install');

		expect(source).toContain("new Request(asset, { cache: 'reload', credentials: 'same-origin' })");
		expect(source).toContain(
			"if (!response.ok) throw new Error('Public offline asset could not be cached: '"
		);
		expect(source).not.toContain('cache.addAll(PUBLIC_ASSETS)');
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

	it('falls back to the retained predecessor asset only after the current asset cache and network', () => {
		const source = createServiceWorkerSource('safe-fallback');

		expect(source).toContain('event.respondWith(immutableAsset(event.request))');
		expect(source).toContain('const response = await fetch(request)');
		expect(source).toContain('return (await previousImmutableAsset(request)) ?? response');
		expect(source).toContain('const fallback = await previousImmutableAsset(request)');
	});
});
