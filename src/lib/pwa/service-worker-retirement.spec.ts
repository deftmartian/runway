import { describe, expect, it, vi } from 'vitest';
import {
	findRootRunwayRegistration,
	updateExistingRunwayWorker,
	type ServiceWorkerRegistrationLike
} from './service-worker-retirement';
import { createServiceWorkerRetirementSource, isRunwayOwnedCache } from './service-worker';

function registration(
	scope: string,
	scriptURL = `${new URL(scope).origin}/service-worker.js`
): ServiceWorkerRegistrationLike & { update: ReturnType<typeof vi.fn> } {
	return { scope, active: { scriptURL }, update: vi.fn().mockResolvedValue(undefined) };
}

describe('one-release service-worker retirement', () => {
	it('does not register or update anything for a first-time visitor', async () => {
		const unrelated = registration('https://runway.example/app/');

		await expect(updateExistingRunwayWorker([unrelated], 'https://runway.example')).resolves.toBe(
			false
		);
		expect(unrelated.update.mock.calls).toHaveLength(0);
	});

	it('updates exactly the old root registration and ignores unrelated scopes', async () => {
		const unrelated = registration('https://runway.example/app/');
		const root = registration('https://runway.example/');

		expect(findRootRunwayRegistration([unrelated, root], 'https://runway.example')).toBe(root);
		await expect(
			updateExistingRunwayWorker([unrelated, root], 'https://runway.example')
		).resolves.toBe(true);
		expect(root.update.mock.calls).toHaveLength(1);
		expect(unrelated.update.mock.calls).toHaveLength(0);
	});

	it('does not replace an unrelated root-scoped service worker', async () => {
		const unrelatedRoot = registration(
			'https://runway.example/',
			'https://runway.example/custom-worker.js'
		);

		await expect(
			updateExistingRunwayWorker([unrelatedRoot], 'https://runway.example')
		).resolves.toBe(false);
		expect(unrelatedRoot.update.mock.calls).toHaveLength(0);
	});

	it('limits cache cleanup to runway-owned cache names', () => {
		expect(isRunwayOwnedCache('runway-public-old')).toBe(true);
		expect(isRunwayOwnedCache('runway-app-assets-old')).toBe(true);
		expect(isRunwayOwnedCache('third-party-cache')).toBe(false);
		expect(isRunwayOwnedCache('Runway-public-old')).toBe(false);
	});

	it('navigates clients only after successful unregistration, preventing a reload loop', () => {
		const source = createServiceWorkerRetirementSource();

		expect(source).toContain('const unregistered = await self.registration.unregister();');
		expect(source).toContain('if (!unregistered) return;');
		expect(source).toContain('clients.map((client) => client.navigate(client.url))');
		expect(source.indexOf('if (!unregistered) return;')).toBeLessThan(
			source.indexOf('clients.map((client) => client.navigate(client.url))')
		);
		expect(source).not.toContain('self.clients.claim()');
		expect(source).not.toContain("self.addEventListener('fetch'");
		expect(source).not.toContain('caches.open(');
	});
});
