import type { Handle } from '@sveltejs/kit';
import { building } from '$app/environment';
import { env } from '$env/dynamic/private';
import { auth } from '$lib/server/auth';
import {
	isAllowedBetterAuthHttpRequest,
	isPasskeyRegistrationRequest,
	passkeyAuthenticationAction
} from '$lib/server/runway/auth-http-boundary';
import { startImportSourceWorker } from '$lib/server/runway/import-worker';
import {
	hasExactRequestOrigin,
	isAndroidNativeApiRequest,
	isMutationRequest
} from '$lib/server/runway/request-security';
import {
	consumeSecurityRateLimit,
	passkeyAuthenticationRateLimitBuckets,
	passkeyRegistrationRateLimitBuckets,
	signInRateLimitBuckets,
	signUpRateLimitBuckets,
	twoFactorChallengeFromHeaders,
	twoFactorRateLimitBuckets
} from '$lib/server/runway/security-rate-limit';
import { authEmailSchema } from '$lib/server/runway/validation';
import { startStagedExportReaper } from '$lib/server/runway/staged-json-export';
import { svelteKitHandler } from 'better-auth/svelte-kit';
import { sequence } from '@sveltejs/kit/hooks';

const baselineCsp = [
	"default-src 'self'",
	"base-uri 'self'",
	"connect-src 'self'",
	"font-src 'self'",
	"form-action 'self'",
	"frame-ancestors 'none'",
	"img-src 'self' data:",
	"object-src 'none'",
	"script-src 'self'",
	"style-src 'self'",
	"style-src-attr 'unsafe-inline'",
	"require-trusted-types-for 'script'",
	'trusted-types svelte-trusted-html sveltekit-trusted-url'
].join('; ');

if (!building && env['IMPORT_WORKER_ENABLED'] === 'true') startImportSourceWorker();
if (!building && env['IMPORT_WORKER_ENABLED'] !== 'true') startStagedExportReaper();

const handleSecurityHeaders: Handle = async ({ event, resolve }) => {
	if (isMutationRequest(event.request.method)) {
		const origin = event.request.headers.get('origin');
		if (
			!hasExactRequestOrigin(origin, event.url.origin) &&
			!isAndroidNativeApiRequest(event.request, event.url.pathname)
		) {
			return applySecurityHeaders(
				new Response('Cross-site requests are forbidden', { status: 403 }),
				event.url.pathname
			);
		}
	}

	const response = await resolve(event);

	return applySecurityHeaders(response, event.url.pathname);
};

function applySecurityHeaders(response: Response, pathname: string): Response {
	if (!response.headers.has('Content-Security-Policy')) {
		response.headers.set('Content-Security-Policy', baselineCsp);
	}
	response.headers.set('Permissions-Policy', 'camera=(), geolocation=(), microphone=()');
	response.headers.set('Referrer-Policy', 'strict-origin-when-cross-origin');
	response.headers.set('Cross-Origin-Opener-Policy', 'same-origin');
	response.headers.set('Cross-Origin-Resource-Policy', 'same-origin');
	response.headers.set('Origin-Agent-Cluster', '?1');
	response.headers.set('X-Permitted-Cross-Domain-Policies', 'none');
	response.headers.set('X-Content-Type-Options', 'nosniff');
	response.headers.set('X-Frame-Options', 'DENY');

	if (response.status >= 400) {
		response.headers.set('Cache-Control', 'private, no-store');
	}

	if (pathname.startsWith('/_app/immutable/')) {
		response.headers.set('Cache-Control', 'public, max-age=31536000, immutable');
	}

	if (response.status < 400 && pathname.endsWith('.svg')) {
		response.headers.set('Cache-Control', 'public, max-age=0, must-revalidate');
	}

	if (
		pathname.startsWith('/health/') ||
		pathname === '/' ||
		pathname.startsWith('/app') ||
		pathname.startsWith('/device') ||
		pathname.startsWith('/login') ||
		pathname.startsWith('/logout') ||
		pathname.startsWith('/api/android/') ||
		pathname.startsWith('/api/auth')
	) {
		response.headers.set('Cache-Control', 'private, no-store');
	}

	if (pathname === '/login/reset-password') {
		response.headers.set('Referrer-Policy', 'no-referrer');
	}

	return response;
}

const handleBetterAuth: Handle = async ({ event, resolve }) => {
	if (event.url.pathname.startsWith('/health/')) return resolve(event);
	const androidNativeRequest = isAndroidNativeApiRequest(event.request, event.url.pathname);
	if (
		!isAllowedBetterAuthHttpRequest(event.url.pathname, event.request.method, androidNativeRequest)
	) {
		return new Response('Not found', {
			status: 404,
			headers: { 'Content-Type': 'text/plain; charset=utf-8' }
		});
	}

	if (androidNativeRequest) {
		const nativeRateLimit = await rateLimitNativeAuthentication(event);
		if (nativeRateLimit) return nativeRateLimit;
	}

	const passkeyAction = passkeyAuthenticationAction(
		event.url.pathname,
		event.request.method.toUpperCase()
	);
	if (passkeyAction) {
		const rateLimit = await consumeSecurityRateLimit(
			passkeyAuthenticationRateLimitBuckets(passkeyAction, event.getClientAddress())
		);
		if (!rateLimit.allowed) return rateLimitedAuthResponse(rateLimit.retryAfterSeconds);
	}

	const session = await auth.api.getSession({ headers: event.request.headers });

	if (session) {
		event.locals.session = session.session;
		event.locals.user = session.user;
	}

	if (
		session &&
		isPasskeyRegistrationRequest(event.url.pathname, event.request.method.toUpperCase())
	) {
		const rateLimit = await consumeSecurityRateLimit(
			passkeyRegistrationRateLimitBuckets(session.user.id, event.getClientAddress())
		);
		if (!rateLimit.allowed) return rateLimitedAuthResponse(rateLimit.retryAfterSeconds);
	}

	return svelteKitHandler({ event, resolve, auth, building });
};

async function rateLimitNativeAuthentication(
	event: Parameters<Handle>[0]['event']
): Promise<Response | null> {
	const pathname = event.url.pathname;
	if (pathname === '/api/auth/sign-in/email' || pathname === '/api/auth/sign-up/email') {
		const body = await event.request
			.clone()
			.json()
			.catch(() => null);
		const email = authEmailSchema.safeParse(
			typeof body === 'object' && body !== null && 'email' in body ? body.email : null
		);
		if (!email.success) {
			return new Response('Email or password is not correct.', {
				status: 400,
				headers: { 'Content-Type': 'text/plain; charset=utf-8' }
			});
		}
		const rateLimit = await consumeSecurityRateLimit(
			pathname === '/api/auth/sign-in/email'
				? signInRateLimitBuckets(email.data, event.getClientAddress())
				: signUpRateLimitBuckets(email.data, event.getClientAddress())
		);
		return rateLimit.allowed ? null : rateLimitedAuthResponse(rateLimit.retryAfterSeconds);
	}
	const method =
		pathname === '/api/auth/two-factor/verify-totp'
			? 'totp'
			: pathname === '/api/auth/two-factor/verify-backup-code'
				? 'backup'
				: null;
	if (!method) return null;
	const rateLimit = await consumeSecurityRateLimit(
		twoFactorRateLimitBuckets(
			method,
			twoFactorChallengeFromHeaders(event.request.headers),
			event.getClientAddress()
		)
	);
	return rateLimit.allowed ? null : rateLimitedAuthResponse(rateLimit.retryAfterSeconds);
}

function rateLimitedAuthResponse(retryAfterSeconds: number): Response {
	return new Response('Too many authentication attempts. Try again later.', {
		status: 429,
		headers: {
			'Content-Type': 'text/plain; charset=utf-8',
			'Retry-After': String(retryAfterSeconds)
		}
	});
}

export const handle: Handle = sequence(handleSecurityHeaders, handleBetterAuth);
