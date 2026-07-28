import { and, eq, gt, isNull, sql } from 'drizzle-orm';
import { APIError } from 'better-auth/api';
import { auth } from '$lib/server/auth';
import { db } from '$lib/server/db';
import { account, passkey, session, user } from '$lib/server/db/auth.schema';
import { androidDevice } from '$lib/server/db/schema';
import { isFreshAuthSession } from './auth-config';
import { androidNativeAuthClientHeader, isAndroidMobileSession } from './mobile-session-scope';

type MobileAccountSession = {
	id: string;
	client: 'Android app' | 'Web browser';
	current: boolean;
	createdAt: Date;
	updatedAt: Date;
	expiresAt: Date;
};

type AccountSessionSource = {
	id: string;
	token?: unknown;
	ipAddress?: unknown;
	userAgent?: unknown;
	mobileClientId?: unknown;
	createdAt: Date;
	updatedAt: Date;
	expiresAt: Date;
};

/**
 * Deliberately summary-only account state for an already device-authorized mobile session.
 * It never exposes credential material, session tokens, addresses, user agents, passkey public
 * keys, or provider tokens.
 */
export async function getMobileAccountSecurity(userId: string, request: Request) {
	const now = new Date();
	const [identity, providers, passkeys, activeSessions, devices, sessionList] = await Promise.all([
		db
			.select({ twoFactorEnabled: user.twoFactorEnabled })
			.from(user)
			.where(eq(user.id, userId))
			.limit(1),
		db
			.select({ providerId: account.providerId })
			.from(account)
			.where(eq(account.userId, userId))
			.limit(20),
		db
			.select({ count: sql<number>`count(*)::int` })
			.from(passkey)
			.where(eq(passkey.userId, userId)),
		db
			.select({ count: sql<number>`count(*)::int` })
			.from(session)
			.where(and(eq(session.userId, userId), gt(session.expiresAt, now))),
		db
			.select({
				id: androidDevice.id,
				label: androidDevice.label,
				lastSeenAt: androidDevice.lastSeenAt,
				lastImportedAt: androidDevice.lastImportedAt,
				expiresAt: androidDevice.expiresAt
			})
			.from(androidDevice)
			.where(
				and(
					eq(androidDevice.userId, userId),
					isNull(androidDevice.revokedAt),
					gt(androidDevice.expiresAt, now)
				)
			)
			.orderBy(androidDevice.createdAt)
			.limit(20),
		listMobileAccountSessions(request)
	]);
	const providerIds = new Set(providers.map((provider) => provider.providerId));
	return {
		authentication: {
			localPassword: providerIds.has('credential'),
			oidc: providerIds.has('authentik'),
			twoFactor: Boolean(identity[0]?.twoFactorEnabled),
			passkeyCount: passkeys[0]?.count ?? 0
		},
		sessions: {
			activeCount: activeSessions[0]?.count ?? 0,
			currentIsNative: true,
			requiresFreshSession: sessionList.requiresFreshSession,
			items: sessionList.items
		},
		importDevices: devices.map((device) => ({
			id: device.id,
			label: device.label,
			lastSeenAt: device.lastSeenAt,
			lastImportedAt: device.lastImportedAt,
			expiresAt: device.expiresAt
		}))
	};
}

async function listMobileAccountSessions(
	request: Request
): Promise<{ requiresFreshSession: boolean; items: MobileAccountSession[] }> {
	const current = await auth.api.getSession({ headers: request.headers });
	if (!current?.session || !isAndroidMobileSession(current.session)) {
		return { requiresFreshSession: true, items: [] };
	}
	if (!isFreshAuthSession(current.session.createdAt)) {
		return { requiresFreshSession: true, items: [] };
	}
	try {
		const sessions = await auth.api.listSessions({ headers: request.headers });
		return {
			requiresFreshSession: false,
			items: sanitizeMobileAccountSessions(sessions, current.session.id)
		};
	} catch (error) {
		if (error instanceof APIError && (error.statusCode === 401 || error.statusCode === 403)) {
			return { requiresFreshSession: true, items: [] };
		}
		throw error;
	}
}

export function sanitizeMobileAccountSessions(
	sessions: AccountSessionSource[],
	currentSessionId: string
): MobileAccountSession[] {
	return sessions.slice(0, 50).map((item) => ({
		id: item.id,
		client: isAndroidMobileSession(item) ? 'Android app' : 'Web browser',
		current: item.id === currentSessionId,
		createdAt: item.createdAt,
		updatedAt: item.updatedAt,
		expiresAt: item.expiresAt
	}));
}

/**
 * Better Auth's bearer plugin exposes a replacement session through
 * `set-auth-token`. Treat that header as the only token source, then resolve it
 * back through Better Auth before allowing it to cross the native JSON
 * boundary. This prevents response bodies, request fields, or a cross-user
 * replacement from promoting an unmarked session.
 */
export async function validateMobileReplacementToken(
	responseHeaders: Headers,
	expectedUserId: string,
	previousSessionId: string
): Promise<string | null> {
	const token = responseHeaders.get('set-auth-token')?.trim();
	if (!token || token.length > 4_096 || /\s/.test(token)) return null;

	try {
		const replacement = await auth.api.getSession({
			headers: new Headers({
				authorization: `Bearer ${token}`,
				'x-runway-client': androidNativeAuthClientHeader
			})
		});
		if (
			replacement?.user.id !== expectedUserId ||
			replacement.session.id === previousSessionId ||
			!isAndroidMobileSession(replacement.session)
		) {
			return null;
		}
		return token;
	} catch {
		return null;
	}
}
