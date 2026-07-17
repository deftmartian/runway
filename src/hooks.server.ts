import type { Handle } from '@sveltejs/kit';
import { building } from '$app/environment';
import { env } from '$env/dynamic/private';
import { auth } from '$lib/server/auth';
import { startImportSourceWorker } from '$lib/server/runway/import-worker';
import {
	hasExactRequestOrigin,
	isMutationRequest,
	isWebShareTargetNavigation
} from '$lib/server/runway/request-security';
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
	"manifest-src 'self'",
	"object-src 'none'",
	"script-src 'self'",
	"style-src 'self'",
	"style-src-attr 'unsafe-inline'",
	"worker-src 'self'",
	"require-trusted-types-for 'script'",
	'trusted-types svelte-trusted-html sveltekit-trusted-url runway-service-worker'
].join('; ');

if (!building && env['IMPORT_WORKER_ENABLED'] === 'true') startImportSourceWorker();

const handleSecurityHeaders: Handle = async ({ event, resolve }) => {
	if (isMutationRequest(event.request.method)) {
		const origin = event.request.headers.get('origin');
		if (
			!hasExactRequestOrigin(origin, event.url.origin) &&
			!isWebShareTargetNavigation(event.request, event.url.pathname)
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
	response.headers.set('X-Content-Type-Options', 'nosniff');
	response.headers.set('X-Frame-Options', 'DENY');

	if (pathname.startsWith('/_app/immutable/')) {
		response.headers.set('Cache-Control', 'public, max-age=31536000, immutable');
	}

	if (pathname === '/service-worker.js') {
		response.headers.set('Cache-Control', 'public, max-age=0, must-revalidate');
	}

	if (
		response.status < 400 &&
		(pathname === '/manifest.webmanifest' ||
			pathname === '/offline.html' ||
			pathname === '/offline.css' ||
			pathname.endsWith('.svg'))
	) {
		response.headers.set('Cache-Control', 'public, max-age=86400');
	}

	if (
		pathname.startsWith('/health/') ||
		pathname === '/' ||
		pathname.startsWith('/app') ||
		pathname.startsWith('/login') ||
		pathname.startsWith('/logout') ||
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

	const session = await auth.api.getSession({ headers: event.request.headers });

	if (session) {
		event.locals.session = session.session;
		event.locals.user = session.user;
	}

	return svelteKitHandler({ event, resolve, auth, building });
};

export const handle: Handle = sequence(handleSecurityHeaders, handleBetterAuth);
