export function previousCompleteCacheRevision(
	names: readonly string[],
	currentRevision: string,
	recordedPreviousRevision: string | null
): string | null {
	const publicPrefix = 'runway-public-';
	const appPrefix = 'runway-app-assets-';
	const complete = (revision: string) =>
		names.includes(publicPrefix + revision) && names.includes(appPrefix + revision);
	if (
		recordedPreviousRevision &&
		recordedPreviousRevision !== currentRevision &&
		complete(recordedPreviousRevision)
	) {
		return recordedPreviousRevision;
	}
	for (const name of [...names].reverse()) {
		if (!name.startsWith(publicPrefix)) continue;
		const revision = name.slice(publicPrefix.length);
		if (revision !== currentRevision && complete(revision)) return revision;
	}
	return null;
}

export function immutableAssetCacheOrder(
	names: readonly string[],
	currentRevision: string,
	recordedPreviousRevision: string | null
): string[] {
	const current = `runway-app-assets-${currentRevision}`;
	const previous = previousCompleteCacheRevision(names, currentRevision, recordedPreviousRevision);
	return previous ? [current, `runway-app-assets-${previous}`] : [current];
}

export function createServiceWorkerSource(cacheRevision: string): string {
	if (!cacheRevision.trim()) throw new Error('Service worker cache revision must not be empty.');

	return `
const CACHE_REVISION = ${JSON.stringify(cacheRevision)};
const CACHE_NAME = 'runway-public-' + CACHE_REVISION;
const APP_CACHE_NAME = 'runway-app-assets-' + CACHE_REVISION;
const PUBLIC_CACHE_PREFIX = 'runway-public-';
const APP_CACHE_PREFIX = 'runway-app-assets-';
const CACHE_METADATA_NAME = 'runway-cache-metadata';
const CACHE_METADATA_KEY = '/__runway-cache-metadata';
const PUBLIC_ASSETS = ['/manifest.webmanifest', '/offline.html', '/offline.css'];
const PUBLIC_ASSET_SET = new Set(PUBLIC_ASSETS);
const PRIVATE_PREFIXES = ['/app', '/api/auth', '/login', '/logout'];

self.addEventListener('install', (event) => {
	event.waitUntil(
		caches.open(CACHE_NAME).then((cache) =>
			Promise.all(
				PUBLIC_ASSETS.map(async (asset) => {
					const request = new Request(asset, { cache: 'reload', credentials: 'same-origin' });
					const response = await fetch(request);
					if (!response.ok) throw new Error('Public offline asset could not be cached: ' + asset);
					await cache.put(asset, response);
				})
			)
		)
	);
});

self.addEventListener('message', (event) => {
	if (event.data?.type !== 'ACTIVATE_UPDATE') return;
	event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', (event) => {
	event.waitUntil(
	Promise.all([
		self.registration.navigationPreload?.enable(),
		pruneCaches(),
		self.clients.claim()
	])
);
});

async function pruneCaches() {
	const names = await caches.keys();
	const retained = new Set([CACHE_NAME, APP_CACHE_NAME, CACHE_METADATA_NAME]);
	const previousRevision = await previousCompleteCacheRevision(names);
	if (previousRevision) {
		retained.add(PUBLIC_CACHE_PREFIX + previousRevision);
		retained.add(APP_CACHE_PREFIX + previousRevision);
	}
	await Promise.all(
		names
			.filter((name) => name.startsWith('runway-') && !retained.has(name))
			.map((name) => caches.delete(name))
	);
	await caches.open(CACHE_METADATA_NAME).then((cache) =>
		cache.put(CACHE_METADATA_KEY, new Response(CACHE_REVISION))
	);
}

async function previousCompleteCacheRevision(names) {
	const recorded = await caches.open(CACHE_METADATA_NAME).then(async (cache) => {
		const response = await cache.match(CACHE_METADATA_KEY);
		return response ? response.text() : null;
	});
	if (recorded && recorded !== CACHE_REVISION && isCompleteCacheRevision(names, recorded)) {
		return recorded;
	}
	for (const name of [...names].reverse()) {
		if (!name.startsWith(PUBLIC_CACHE_PREFIX) || name === CACHE_NAME) continue;
		const revision = name.slice(PUBLIC_CACHE_PREFIX.length);
		if (isCompleteCacheRevision(names, revision)) return revision;
	}
	return null;
}

function isCompleteCacheRevision(names, revision) {
	return names.includes(PUBLIC_CACHE_PREFIX + revision) && names.includes(APP_CACHE_PREFIX + revision);
}

self.addEventListener('fetch', (event) => {
	const url = new URL(event.request.url);
	if (event.request.method !== 'GET') return;
	if (url.origin !== self.location.origin) return;

	if (event.request.mode === 'navigate') {
		event.respondWith(
			(async () => {
				try {
					return (await event.preloadResponse) ?? (await fetch(event.request));
				} catch {
					return (await caches.match('/offline.html')) ?? Response.error();
				}
			})()
		);
		return;
	}

	if (PRIVATE_PREFIXES.some((prefix) => url.pathname === prefix || url.pathname.startsWith(prefix + '/'))) return;
	if (url.pathname.startsWith('/_app/immutable/')) {
		event.respondWith(immutableAsset(event.request));
		return;
	}
	if (!PUBLIC_ASSET_SET.has(url.pathname)) return;

	event.respondWith(
		caches.open(CACHE_NAME).then(async (cache) => {
			try {
				const response = await fetch(event.request);
				if (response.ok) await cache.put(event.request, response.clone());
				return response;
			} catch {
				return (await cache.match(event.request)) ?? Response.error();
			}
		})
	);
});

async function immutableAsset(request) {
	const cache = await caches.open(APP_CACHE_NAME);
	const cached = await cache.match(request);
	if (cached) return cached;
	try {
		const response = await fetch(request);
		if (response.ok) {
			await cache.put(request, response.clone());
			return response;
		}
		return (await previousImmutableAsset(request)) ?? response;
	} catch (error) {
		const fallback = await previousImmutableAsset(request);
		if (fallback) return fallback;
		throw error;
	}
}

async function previousImmutableAsset(request) {
	const names = await caches.keys();
	const revision = await previousCompleteCacheRevision(names);
	if (!revision) return undefined;
	return caches.open(APP_CACHE_PREFIX + revision).then((cache) => cache.match(request));
}
`.trimStart();
}
