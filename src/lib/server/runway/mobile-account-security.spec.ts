import { beforeEach, describe, expect, test, vi } from 'vitest';

const authApi = vi.hoisted(() => ({
	getSession: vi.fn(),
	listSessions: vi.fn()
}));

vi.mock('$lib/server/auth', () => ({
	auth: { api: authApi }
}));
vi.mock('$lib/server/db', () => ({ db: {} }));

import {
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
