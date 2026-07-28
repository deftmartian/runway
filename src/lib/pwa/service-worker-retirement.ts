export type ServiceWorkerRegistrationLike = {
	scope: string;
	active: { scriptURL: string } | null;
	update(): Promise<unknown>;
};

/**
 * The old runway worker was registered at the site root. Do not create a new
 * registration merely to retire it: first-time visitors should never receive
 * a worker as part of this compatibility handoff.
 */
export function findRootRunwayRegistration<T extends ServiceWorkerRegistrationLike>(
	registrations: readonly T[],
	origin: string
): T | undefined {
	return registrations.find(
		(registration) =>
			registration.scope === `${origin}/` &&
			registration.active?.scriptURL === `${origin}/service-worker.js`
	);
}

export async function updateExistingRunwayWorker(
	registrations: readonly ServiceWorkerRegistrationLike[],
	origin: string
): Promise<boolean> {
	const registration = findRootRunwayRegistration(registrations, origin);
	if (!registration) return false;
	await registration.update();
	return true;
}
