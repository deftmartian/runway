export function createServiceWorkerSource(cacheRevision: string): string {
	if (!cacheRevision.trim()) throw new Error('Service worker cache revision must not be empty.');

	return `
const CACHE_REVISION = ${JSON.stringify(cacheRevision)};
const CACHE_NAME = 'runway-public-' + CACHE_REVISION;
const APP_CACHE_NAME = 'runway-app-assets-' + CACHE_REVISION;
const ACTIVE_CACHES = new Set([CACHE_NAME, APP_CACHE_NAME]);
const PUBLIC_ASSETS = ['/manifest.webmanifest', '/offline.html', '/offline.css'];
const PUBLIC_ASSET_SET = new Set(PUBLIC_ASSETS);
const PRIVATE_PREFIXES = ['/app', '/api/auth', '/login', '/logout'];

self.addEventListener('install', (event) => {
	event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(PUBLIC_ASSETS)));
});

self.addEventListener('message', (event) => {
	if (event.data?.type !== 'ACTIVATE_UPDATE') return;
	event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', (event) => {
	event.waitUntil(
		Promise.all([
			self.registration.navigationPreload?.enable(),
			caches
				.keys()
				.then((names) =>
					Promise.all(
						names
							.filter((name) => name.startsWith('runway-') && !ACTIVE_CACHES.has(name))
							.map((name) => caches.delete(name))
					)
				),
			self.clients.claim()
		])
	);
});

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
		event.respondWith(
			caches.open(APP_CACHE_NAME).then(async (cache) => {
				const cached = await cache.match(event.request);
				if (cached) return cached;
				const response = await fetch(event.request);
				if (response.ok) await cache.put(event.request, response.clone());
				return response;
			})
		);
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
`.trimStart();
}
