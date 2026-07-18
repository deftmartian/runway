import { betterAuth } from 'better-auth';
import { APIError } from 'better-auth/api';
import { drizzleAdapter } from 'better-auth/adapters/drizzle';
import { genericOAuth, twoFactor } from 'better-auth/plugins';
import { sveltekitCookies } from 'better-auth/svelte-kit';
import { passkey } from '@better-auth/passkey';
import { building } from '$app/environment';
import { env } from '$env/dynamic/private';
import { getRequestEvent } from '$app/server';
import { db } from '$lib/server/db';
import { authLogger } from '$lib/server/runway/auth-log';
import {
	authFreshSessionSeconds,
	canonicalAppOrigin,
	omitStoredOidcIdToken,
	oauthTokenStorageOptions,
	oidcDiscoveryUrl,
	passkeyRpIdProblem,
	publicOriginMismatchProblem
} from '$lib/server/runway/auth-config';

const isProductionRuntime = env['NODE_ENV'] === 'production' && !building;
const origin = canonicalAppOrigin(readOrigin(), 'ORIGIN');
const configuredPasskeyOrigin = env['PUBLIC_APP_ORIGIN'];
const passkeyOrigin = canonicalAppOrigin(configuredPasskeyOrigin || origin, 'PUBLIC_APP_ORIGIN');
assertProductionHttps('ORIGIN', origin);
assertProductionHttps('PUBLIC_APP_ORIGIN', passkeyOrigin);
const publicOriginConfigurationProblem = publicOriginMismatchProblem(origin, passkeyOrigin);
if (publicOriginConfigurationProblem && isProductionRuntime) {
	throw new Error(publicOriginConfigurationProblem);
}
if (publicOriginConfigurationProblem && !building) {
	console.warn(`[runway auth] ${publicOriginConfigurationProblem}`);
}
const passkeyRpId = env['PASSKEY_RP_ID'] || new URL(passkeyOrigin).hostname;
const passkeyRpIdConfigurationProblem = passkeyRpIdProblem(passkeyOrigin, passkeyRpId);
if (passkeyRpIdConfigurationProblem && isProductionRuntime) {
	throw new Error(passkeyRpIdConfigurationProblem);
}
if (passkeyRpIdConfigurationProblem && !building) {
	console.warn(`[runway auth] ${passkeyRpIdConfigurationProblem}`);
}
const localAuthEnabled = env['LOCAL_AUTH_ENABLED'] !== 'false';
const localSignupsEnabled = env['ALLOW_LOCAL_SIGNUPS'] === 'true';
const oidcIssuer = env['OIDC_ISSUER'];
if (oidcIssuer) assertProductionHttps('OIDC_ISSUER', oidcIssuer);
const oidcClientId = env['OIDC_CLIENT_ID'];
const oidcClientSecret = env['OIDC_CLIENT_SECRET'];
const oidcSignupsEnabled = env['ALLOW_OIDC_SIGNUPS'] === 'true';
const oidcConfigCount = [oidcIssuer, oidcClientId, oidcClientSecret].filter(Boolean).length;
const authSecret =
	env['BETTER_AUTH_SECRET'] ??
	(building ? 'runway-build-time-placeholder-secret-00000000' : undefined);

if (isProductionRuntime && !env['BETTER_AUTH_SECRET']) {
	throw new Error('BETTER_AUTH_SECRET is required in production.');
}
if (isProductionRuntime && oidcConfigCount > 0 && oidcConfigCount < 3) {
	throw new Error('OIDC_ISSUER, OIDC_CLIENT_ID, and OIDC_CLIENT_SECRET must be set together.');
}
if (isProductionRuntime && !localAuthEnabled && oidcConfigCount !== 3) {
	throw new Error('At least one production auth method must be configured.');
}

const developmentTrustedOrigins = isProductionRuntime
	? []
	: ['http://127.0.0.1:4100', 'http://localhost:4100'];

const oidcPlugins =
	oidcIssuer && oidcClientId && oidcClientSecret
		? [
				genericOAuth({
					config: [
						{
							providerId: 'authentik',
							discoveryUrl: oidcDiscoveryUrl(oidcIssuer),
							issuer: oidcIssuer,
							clientId: oidcClientId,
							clientSecret: oidcClientSecret,
							scopes: ['openid', 'profile', 'email'],
							pkce: true,
							disableSignUp: !oidcSignupsEnabled,
							requireIssuerValidation: true
						}
					]
				})
			]
		: [];

export const auth = betterAuth({
	appName: 'runway',
	baseURL: origin,
	secret: authSecret,
	logger: authLogger,
	trustedOrigins: Array.from(new Set([origin, passkeyOrigin, ...developmentTrustedOrigins])),
	session: {
		freshAge: authFreshSessionSeconds
	},
	database: drizzleAdapter(db, { provider: 'pg' }),
	databaseHooks: {
		account: {
			create: {
				before: (account) => Promise.resolve({ data: omitStoredOidcIdToken(account) })
			},
			update: {
				before: (account) => Promise.resolve({ data: omitStoredOidcIdToken(account) })
			}
		}
	},
	account: oauthTokenStorageOptions,
	emailAndPassword: {
		enabled: localAuthEnabled,
		disableSignUp: !localSignupsEnabled,
		minPasswordLength: 12,
		maxPasswordLength: 128
	},
	rateLimit: {
		enabled: true,
		window: 60,
		max: 60,
		customRules: {
			'/sign-in/email': { window: 10 * 60, max: 10 },
			'/sign-up/email': { window: 10 * 60, max: 10 },
			'/two-factor/enable': { window: 10 * 60, max: 5 },
			'/two-factor/disable': { window: 10 * 60, max: 5 },
			'/two-factor/get-totp-uri': { window: 10 * 60, max: 5 },
			'/two-factor/verify-totp': { window: 10 * 60, max: 5 },
			'/two-factor/verify-backup-code': { window: 10 * 60, max: 5 },
			'/two-factor/generate-backup-codes': { window: 10 * 60, max: 5 }
		}
	},
	plugins: [
		twoFactor({
			issuer: env['PASSKEY_RP_NAME'] || 'runway',
			twoFactorCookieMaxAge: 10 * 60,
			trustDeviceMaxAge: 30 * 24 * 60 * 60
		}),
		passkey({
			rpName: env['PASSKEY_RP_NAME'] || 'runway',
			rpID: passkeyRpId,
			origin: passkeyOrigin,
			authenticatorSelection: {
				residentKey: 'preferred',
				userVerification: 'required'
			},
			registration: {
				afterVerification: ({ verification }) => {
					if (!verification.registrationInfo?.userVerified) {
						throw new APIError('BAD_REQUEST', {
							message: 'Passkey user verification is required.'
						});
					}
				}
			},
			authentication: {
				afterVerification: ({ verification }) => {
					if (!verification.authenticationInfo.userVerified) {
						throw new APIError('UNAUTHORIZED', {
							message: 'Passkey user verification is required.'
						});
					}
				}
			}
		}),
		...oidcPlugins,
		sveltekitCookies(getRequestEvent) // make sure this is the last plugin in the array
	]
});

function readOrigin(): string {
	if (env['ORIGIN']) return env['ORIGIN'];
	if (isProductionRuntime) throw new Error('ORIGIN is required in production.');
	return 'http://localhost:4100';
}

function assertProductionHttps(name: string, value: string): void {
	if (!isProductionRuntime) return;
	const parsed = new URL(value);
	if (parsed.protocol !== 'https:') {
		throw new Error(`${name} must be an HTTPS URL in production.`);
	}
}
