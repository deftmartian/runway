/**
 * One-release handoff for clients controlled by runway's former offline worker.
 * It intentionally caches nothing, deletes only runway-owned cache names, then
 * unregisters so normal web use returns to ordinary browser semantics.
 */
export function isRunwayOwnedCache(name: string): boolean {
	return name.startsWith('runway-');
}

export function createServiceWorkerRetirementSource(): string {
	return `
self.addEventListener('install', (event) => {
	event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', (event) => {
	event.waitUntil((async () => {
		const cacheNames = await caches.keys();
		await Promise.all(cacheNames
			.filter((name) => name.startsWith('runway-'))
			.map((name) => caches.delete(name)));
		const unregistered = await self.registration.unregister();
		if (!unregistered) return;
		const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
		await Promise.all(clients.map((client) => client.navigate(client.url)));
	})());
});
`;
}
