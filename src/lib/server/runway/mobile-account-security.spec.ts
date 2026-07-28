import { beforeEach, describe, expect, test, vi } from 'vitest';

const authApi = vi.hoisted(() => ({
	getSession: vi.fn(),
	listSessions: vi.fn()
}));

const database = vi.hoisted(() => ({
	transaction: vi.fn()
}));

vi.mock('$lib/server/auth', () => ({
	auth: { api: authApi }
}));
vi.mock('$lib/server/db', () => ({ db: database }));

import {
	deleteMobilePasskeyWithoutLockout,
	sanitizeMobilePasskeys,
	sanitizeMobileAccountSessions,
	validateMobileReplacementToken
} from './mobile-account-security';

describe('native account session summary', () => {
	beforeEach(() => {
		vi.clearAllMocks();
	});

	test('drops bearer tokens, addresses, and exact user agents from the mobile payload', () => {
		const createdAt = new Date('2026-07-28T12:00:00.000Z');
		const updatedAt = new Date('2026-07-28T13:00:00.000Z');
		const expiresAt = new Date('2026-08-04T12:00:00.000Z');

		const result = sanitizeMobileAccountSessions(
			[
				{
					id: 'native-session-id',
					token: 'must-not-cross-the-mobile-json-boundary',
					ipAddress: '192.0.2.10',
					userAgent: 'Exact Browser and Device Fingerprint',
					mobileClientId: 'runway-android',
					createdAt,
					updatedAt,
					expiresAt
				},
				{
					id: 'browser-session-id',
					token: 'also-secret',
					ipAddress: '198.51.100.20',
					userAgent: 'Another Exact Fingerprint',
					mobileClientId: null,
					createdAt,
					updatedAt,
					expiresAt
				}
			],
			'native-session-id'
		);

		expect(result).toEqual([
			{
				id: 'native-session-id',
				client: 'Android app',
				current: true,
				createdAt,
				updatedAt,
				expiresAt
			},
			{
				id: 'browser-session-id',
				client: 'Web browser',
				current: false,
				createdAt,
				updatedAt,
				expiresAt
			}
		]);
		expect(JSON.stringify(result)).not.toContain('must-not-cross');
		expect(JSON.stringify(result)).not.toContain('192.0.2.10');
		expect(JSON.stringify(result)).not.toContain('Fingerprint');
	});

	test('keeps the session list bounded', () => {
		const timestamp = new Date('2026-07-28T12:00:00.000Z');
		const sessions = Array.from({ length: 75 }, (_, index) => ({
			id: `session-${index}`,
			createdAt: timestamp,
			updatedAt: timestamp,
			expiresAt: timestamp
		}));

		expect(sanitizeMobileAccountSessions(sessions, 'session-0')).toHaveLength(50);
	});

	test('whitelists and bounds mobile passkey summaries', () => {
		const createdAt = new Date('2026-07-28T12:00:00.000Z');
		const passkeys = Array.from({ length: 25 }, (_, index) => ({
			id: `passkey-${index}`,
			name: `Key ${index}`,
			deviceType: 'singleDevice',
			backedUp: false,
			createdAt,
			publicKey: 'must-not-cross-the-mobile-json-boundary',
			credentialID: 'credential-id',
			transports: 'usb',
			aaguid: 'aaguid',
			counter: 4,
			userId: 'runner-1'
		}));

		const result = sanitizeMobilePasskeys(passkeys);

		expect(result).toHaveLength(20);
		expect(result[0]).toEqual({
			id: 'passkey-0',
			name: 'Key 0',
			deviceType: 'singleDevice',
			backedUp: false,
			createdAt
		});
		expect(JSON.stringify(result)).not.toContain('must-not-cross');
		expect(JSON.stringify(result)).not.toContain('credential-id');
	});

	test('serializes concurrent passkey removals through the deletion callback', async () => {
		let passkeyCount = 2;
		let releaseFirstDeletion: (() => void) | undefined;
		let firstDeletionStarted: (() => void) | undefined;
		const firstDeletionStartedPromise = new Promise<void>((resolve) => {
			firstDeletionStarted = resolve;
		});
		let transactionTail = Promise.resolve();
		database.transaction.mockImplementation((callback: (tx: unknown) => Promise<unknown>) => {
			const transaction = transactionTail.then(async () =>
				callback(passkeyRemovalTransaction(passkeyCount))
			);
			transactionTail = transaction.then(
				() => undefined,
				() => undefined
			);
			return transaction;
		});

		const first = deleteMobilePasskeyWithoutLockout('runner-1', async () => {
			firstDeletionStarted?.();
			await new Promise<void>((resolve) => {
				releaseFirstDeletion = resolve;
			});
			passkeyCount -= 1;
		});
		await firstDeletionStartedPromise;
		const secondDelete = vi.fn();
		const second = deleteMobilePasskeyWithoutLockout('runner-1', secondDelete);

		expect(secondDelete).not.toHaveBeenCalled();
		releaseFirstDeletion?.();

		await expect(first).resolves.toMatchObject({ removed: true });
		await expect(second).resolves.toEqual({ removed: false });
		expect(secondDelete).not.toHaveBeenCalled();
	});

	test('accepts only the authoritative header token after marked same-user rotation', async () => {
		authApi.getSession.mockResolvedValueOnce({
			user: { id: 'runner-1' },
			session: {
				id: 'replacement-session',
				mobileClientId: 'runway-android'
			}
		});
		const responseHeaders = new Headers({ 'set-auth-token': 'signed-header-token' });

		await expect(
			validateMobileReplacementToken(responseHeaders, 'runner-1', 'previous-session')
		).resolves.toBe('signed-header-token');
		const validationCall = authApi.getSession.mock.calls[0]?.[0] as
			{ headers: Headers } | undefined;
		expect(validationCall?.headers.get('authorization')).toBe('Bearer signed-header-token');
		expect(validationCall?.headers.get('x-runway-client')).toBe('runway-android/2');
	});

	test.each([
		{
			name: 'cross-user session',
			session: {
				user: { id: 'runner-2' },
				session: { id: 'replacement-session', mobileClientId: 'runway-android' }
			}
		},
		{
			name: 'unmarked session',
			session: {
				user: { id: 'runner-1' },
				session: { id: 'replacement-session', mobileClientId: null }
			}
		},
		{
			name: 'non-rotated session',
			session: {
				user: { id: 'runner-1' },
				session: { id: 'previous-session', mobileClientId: 'runway-android' }
			}
		}
	])('rejects $name before returning a replacement token', async ({ session }) => {
		authApi.getSession.mockResolvedValueOnce(session);

		await expect(
			validateMobileReplacementToken(
				new Headers({ 'set-auth-token': 'must-not-return' }),
				'runner-1',
				'previous-session'
			)
		).resolves.toBeNull();
	});

	test('does not consult response bodies or attempt validation without set-auth-token', async () => {
		await expect(
			validateMobileReplacementToken(new Headers(), 'runner-1', 'previous-session')
		).resolves.toBeNull();
		expect(authApi.getSession).not.toHaveBeenCalled();
	});
});

function passkeyRemovalTransaction(passkeyCount: number) {
	let selectCall = 0;
	return {
		select: () => {
			selectCall += 1;
			if (selectCall === 1) {
				const ownerQuery = {
					from: () => ownerQuery,
					where: () => ownerQuery,
					limit: () => ownerQuery,
					for: () => Promise.resolve([{ id: 'runner-1' }])
				};
				return ownerQuery;
			}
			const count = selectCall === 2 ? 0 : passkeyCount;
			const countQuery = {
				from: () => countQuery,
				where: () => Promise.resolve([{ count }])
			};
			return countQuery;
		}
	};
}
