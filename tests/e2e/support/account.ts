import { createHmac } from 'node:crypto';
import { expect, type Page, type Route } from '@playwright/test';
import { clearPasswordResetRateLimits } from './db';

export async function createAccount(page: Page) {
	await clearPasswordResetRateLimits();
	const email = `runner-${Date.now()}-${Math.random().toString(36).slice(2)}@example.test`;

	await page.goto('/login');
	await page.waitForLoadState('networkidle');
	await page.getByRole('button', { name: 'Create account', exact: true }).click();
	const signup = page.locator('#create-account');
	await expect(signup).toBeVisible();
	await signup.getByLabel('Email').fill(email);
	await signup.getByLabel('Password').fill('correct horse battery staple 2026');
	await signup.getByLabel('Name').fill('Runway Tester');
	await signup.getByRole('button', { name: 'Create account' }).click();
	await expect(page).toHaveURL(/\/app\/onboarding$/);
	return email;
}

export function localSignInForm(page: Page) {
	return page.locator('form').filter({ has: page.getByRole('heading', { name: 'Local sign in' }) });
}

export async function holdSettingsAction(page: Page, action: string) {
	return holdPageAction(page, '/app/settings', action);
}

export async function holdPageAction(page: Page, pathname: string, action: string) {
	let releaseRequest!: () => void;
	let markObserved!: () => void;
	const gate = new Promise<void>((resolve) => {
		releaseRequest = resolve;
	});
	const observed = new Promise<void>((resolve) => {
		markObserved = resolve;
	});
	const matcher = (url: URL) => url.pathname === pathname && url.searchParams.has(`/${action}`);
	const handler = async (route: Route) => {
		markObserved();
		await gate;
		await route.continue();
	};
	await page.route(matcher, handler);
	return {
		observed,
		release: releaseRequest,
		stop: () => page.unroute(matcher, handler)
	};
}

export function totpForSecret(secret: string): string {
	const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
	let bits = '';
	for (const character of secret.toUpperCase().replace(/=+$/, '')) {
		const value = alphabet.indexOf(character);
		if (value < 0) throw new Error('Authenticator secret was not valid base32.');
		bits += value.toString(2).padStart(5, '0');
	}
	const bytes: number[] = [];
	for (let offset = 0; offset + 8 <= bits.length; offset += 8) {
		bytes.push(Number.parseInt(bits.slice(offset, offset + 8), 2));
	}
	const counter = Buffer.alloc(8);
	counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000)));
	const digest = createHmac('sha1', Buffer.from(bytes)).update(counter).digest();
	const offset = (digest.at(-1) ?? 0) & 0x0f;
	const value =
		(((digest[offset] ?? 0) & 0x7f) << 24) |
		((digest[offset + 1] ?? 0) << 16) |
		((digest[offset + 2] ?? 0) << 8) |
		(digest[offset + 3] ?? 0);
	return String(value % 1_000_000).padStart(6, '0');
}
