import http from 'node:http';
import https from 'node:https';
import { readFile } from 'node:fs/promises';

const packageMetadata = JSON.parse(
	await readFile(new URL('../package.json', import.meta.url), 'utf8')
);

const siteUrl = process.env.SITE_URL ?? 'http://localhost:4100';
const publicUrl = new URL(siteUrl);

const failures = [];

const home = await request(siteUrl);
const secondHome = await request(siteUrl);

if (home.statusCode < 200 || home.statusCode >= 300) {
	failures.push(`Home returned ${home.statusCode}.`);
}
if (
	home.body.includes('/@vite/client') ||
	home.body.includes('/@fs') ||
	home.body.includes('__sveltekit_dev') ||
	home.body.includes('/node_modules/') ||
	home.body.includes('/src/')
) {
	failures.push('Preview appears to be serving Vite development resources.');
}

const csp = header(home.headers, 'content-security-policy');
const exactStaticCsp = {
	'default-src': ["'self'"],
	'base-uri': ["'self'"],
	'connect-src': ["'self'"],
	'font-src': ["'self'"],
	'form-action': ["'self'"],
	'frame-ancestors': ["'none'"],
	'img-src': ["'self'", 'data:'],
	'manifest-src': ["'self'"],
	'object-src': ["'none'"],
	'style-src': ["'self'"],
	'style-src-attr': ["'unsafe-inline'"],
	'worker-src': ["'self'"],
	'require-trusted-types-for': ["'script'"],
	'trusted-types': ['svelte-trusted-html', 'sveltekit-trusted-url', 'runway-service-worker']
};
for (const [directive, expectedSources] of Object.entries(exactStaticCsp)) {
	assertExactDirective(csp, directive, expectedSources);
}
const cspDirectiveNames = csp
	.split(';')
	.map((part) => part.trim().split(/\s+/, 1)[0])
	.filter(Boolean);
const expectedDirectiveNames = [...Object.keys(exactStaticCsp), 'script-src'];
for (const directive of cspDirectiveNames) {
	if (!expectedDirectiveNames.includes(directive)) {
		failures.push(`CSP contains unexpected directive ${directive}.`);
	}
}
const scriptSrc = directiveValue(csp, 'script-src');
const styleSrc = directiveValue(csp, 'style-src');
const styleSrcAttr = directiveValue(csp, 'style-src-attr');
for (const [directive, requiredValue] of [
	['default-src', "'self'"],
	['base-uri', "'self'"],
	['form-action', "'self'"],
	['frame-ancestors', "'none'"],
	['object-src', "'none'"]
]) {
	if (!directiveValue(csp, directive).split(/\s+/).includes(requiredValue)) {
		failures.push(`CSP ${directive} is missing ${requiredValue}.`);
	}
}
if (!scriptSrc.includes("'strict-dynamic'")) {
	failures.push('CSP script-src is missing strict-dynamic.');
}
if (!scriptSrc.includes("'nonce-")) {
	failures.push('CSP script-src is missing a SvelteKit nonce.');
}
const scriptSources = directiveSources(csp, 'script-src');
const nonceSources = scriptSources.filter((source) =>
	/^'nonce-[A-Za-z0-9+/]+={0,2}'$/.test(source)
);
if (
	nonceSources.length !== 1 ||
	JSON.stringify(scriptSources.filter((source) => !source.startsWith("'nonce-")).sort()) !==
		JSON.stringify(["'self'", "'strict-dynamic'"].sort())
) {
	failures.push(
		'CSP script-src differs from the exact self, strict-dynamic, per-response nonce contract.'
	);
}
if (scriptSrc.includes("'unsafe-inline'")) {
	failures.push('CSP script-src still allows unsafe-inline.');
}
if (styleSrc.includes("'unsafe-inline'")) {
	failures.push('CSP style-src still allows unsafe-inline.');
}
if (!styleSrcAttr.includes("'unsafe-inline'")) {
	failures.push('CSP style-src-attr is missing the explicit style-attribute allowance.');
}
if (!csp.includes("require-trusted-types-for 'script'")) {
	failures.push('CSP is missing Trusted Types enforcement.');
}
if (!csp.includes('runway-service-worker')) {
	failures.push('CSP trusted-types is missing the service worker policy.');
}
const firstNonce = csp.match(/'nonce-([^']+)'/)?.[1];
const secondNonce = header(secondHome.headers, 'content-security-policy').match(
	/'nonce-([^']+)'/
)?.[1];
if (!firstNonce || !secondNonce || firstNonce === secondNonce) {
	failures.push('CSP nonces are missing or reused across responses.');
}

for (const [name, expected] of [
	['cross-origin-opener-policy', 'same-origin'],
	['cross-origin-resource-policy', 'same-origin'],
	['origin-agent-cluster', '?1'],
	['x-permitted-cross-domain-policies', 'none'],
	['x-content-type-options', 'nosniff'],
	['x-frame-options', 'DENY'],
	['referrer-policy', 'strict-origin-when-cross-origin']
]) {
	if (header(home.headers, name).toLowerCase() !== expected.toLowerCase()) {
		failures.push(`${name} is missing or incorrect.`);
	}
}
const permissionsPolicy = header(home.headers, 'permissions-policy');
for (const feature of ['camera=()', 'geolocation=()', 'microphone=()']) {
	if (!permissionsPolicy.includes(feature))
		failures.push(`Permissions-Policy is missing ${feature}.`);
}
if (publicUrl.protocol === 'https:') {
	const hsts = header(home.headers, 'strict-transport-security');
	const maxAge = Number(/(?:^|;)\s*max-age=(\d+)/i.exec(hsts)?.[1]);
	if (!Number.isFinite(maxAge) || maxAge < 31_536_000) {
		failures.push('HTTPS deployment needs HSTS max-age of at least one year.');
	}
}

const app = await request(new URL('/app', siteUrl));
if (header(app.headers, 'cache-control') !== 'private, no-store') {
	failures.push('/app is missing private no-store cache policy.');
}
if (![302, 303, 307, 308].includes(app.statusCode))
	failures.push(`/app returned ${app.statusCode}.`);
const appLocation = header(app.headers, 'location');
if (appLocation) {
	const redirect = new URL(appLocation, publicUrl);
	if (redirect.origin !== publicUrl.origin || !redirect.pathname.startsWith('/login')) {
		failures.push(`/app redirects outside the expected login flow: ${redirect.href}.`);
	}
}

const reset = await request(new URL('/login/reset-password', siteUrl));
if (header(reset.headers, 'referrer-policy') !== 'no-referrer') {
	failures.push('/login/reset-password is missing no-referrer policy.');
}
const resetBearer = await request(
	new URL(`/login/reset-password?token=${'A'.repeat(43)}`, siteUrl)
);
const resetCookie = header(resetBearer.headers, 'set-cookie');
for (const attribute of ['HttpOnly', 'SameSite=Lax', 'Path=/login/reset-password']) {
	if (!resetCookie.toLowerCase().includes(attribute.toLowerCase())) {
		failures.push(`Reset-token cookie is missing ${attribute}.`);
	}
}
if (publicUrl.protocol === 'https:' && !resetCookie.toLowerCase().includes('secure')) {
	failures.push('HTTPS reset-token cookie is missing Secure.');
}

const manifest = await request(new URL('/manifest.webmanifest', siteUrl));
try {
	const parsedManifest = JSON.parse(manifest.body);
	if (parsedManifest.name !== 'runway' || parsedManifest.short_name !== 'runway') {
		failures.push('Manifest is missing the runway app name.');
	}
	if (parsedManifest.start_url !== '/app' || parsedManifest.display !== 'standalone') {
		failures.push('Manifest is missing the expected install surface.');
	}
	if (parsedManifest.id !== '/' || parsedManifest.scope !== '/' || parsedManifest.lang !== 'en') {
		failures.push('Manifest is missing its stable identity, scope, or language.');
	}
	const requiredIcons = [
		['192x192', 'any'],
		['512x512', 'any'],
		['192x192', 'maskable'],
		['512x512', 'maskable']
	];
	for (const [sizes, purpose] of requiredIcons) {
		const icon = parsedManifest.icons?.find(
			(candidate) =>
				candidate.sizes === sizes && candidate.purpose === purpose && candidate.type === 'image/png'
		);
		if (!icon) failures.push(`Manifest is missing the ${sizes} ${purpose} PNG icon.`);
		else await verifyPngAsset(icon.src, `Manifest ${sizes} ${purpose} icon`);
	}
	for (const formFactor of ['narrow', 'wide']) {
		const screenshot = parsedManifest.screenshots?.find(
			(candidate) => candidate.form_factor === formFactor && candidate.type === 'image/png'
		);
		if (!screenshot) failures.push(`Manifest is missing its ${formFactor} install screenshot.`);
		else await verifyPngAsset(screenshot.src, `Manifest ${formFactor} screenshot`);
	}
	const shortcutUrls = parsedManifest.shortcuts?.map((shortcut) => shortcut.url);
	for (const shortcut of ['/app', '/app/import', '/app/stats']) {
		if (!shortcutUrls?.includes(shortcut))
			failures.push(`Manifest is missing shortcut ${shortcut}.`);
	}
	const shareTarget = parsedManifest.share_target;
	if (
		shareTarget?.action !== '/app/import/share' ||
		shareTarget?.method !== 'POST' ||
		shareTarget?.enctype !== 'multipart/form-data' ||
		shareTarget?.params?.files?.[0]?.name !== 'gpx'
	) {
		failures.push('Manifest is missing the authenticated GPX share target.');
	}
} catch {
	failures.push('Manifest is not valid JSON.');
}

const serviceWorker = await request(new URL('/service-worker.js', siteUrl));
if (header(serviceWorker.headers, 'cache-control') !== 'public, max-age=0, must-revalidate') {
	failures.push('/service-worker.js has the wrong cache policy.');
}
const cacheRevision = serviceWorker.body.match(/const CACHE_REVISION = "([^"]+)"/)?.[1];
if (!cacheRevision || cacheRevision === 'runway-static-v1') {
	failures.push('/service-worker.js is missing a deployment-specific cache revision.');
}
if (
	!serviceWorker.body.includes("event.data?.type !== 'ACTIVATE_UPDATE'") ||
	!serviceWorker.body.includes('event.waitUntil(self.skipWaiting())')
) {
	failures.push('/service-worker.js is missing user-controlled update activation.');
}
const installHandler = serviceWorker.body.match(
	/self\.addEventListener\('install',[\s\S]*?\n}\);/
)?.[0];
if (!installHandler || installHandler.includes('skipWaiting')) {
	failures.push('/service-worker.js activates updates during installation instead of waiting.');
}
if (
	!serviceWorker.body.includes('self.registration.navigationPreload?.enable()') ||
	!serviceWorker.body.includes('event.preloadResponse')
) {
	failures.push('/service-worker.js is missing navigation preload.');
}
if (
	!serviceWorker.body.includes("url.pathname === prefix || url.pathname.startsWith(prefix + '/')")
) {
	failures.push('/service-worker.js is missing exact private-route cache boundaries.');
}

const live = await request(new URL('/health/live', siteUrl));
if (live.statusCode !== 200) failures.push(`/health/live returned ${live.statusCode}.`);
try {
	const liveBody = JSON.parse(live.body);
	if (!cacheRevision || liveBody.version !== cacheRevision) {
		failures.push('Service-worker cache revision does not match the running application build.');
	}
	if (liveBody.build !== liveBody.version || liveBody.release !== packageMetadata.version) {
		failures.push('Health identity does not match the application release and build.');
	}
} catch {
	failures.push('/health/live did not return valid JSON.');
}
const ready = await request(new URL('/health/ready', siteUrl));
if (ready.statusCode !== 200) failures.push(`/health/ready returned ${ready.statusCode}.`);
for (const health of [live, ready]) {
	if (header(health.headers, 'cache-control') !== 'private, no-store') {
		failures.push('Health endpoints must use private, no-store.');
		break;
	}
}

const blockedCrossSitePost = await request(new URL('/app/import?/deleteActivity', siteUrl), {
	method: 'POST',
	headers: {
		accept: 'text/html,*/*',
		'content-type': 'application/x-www-form-urlencoded',
		origin: 'https://cross-site-verification.invalid'
	}
});
if (blockedCrossSitePost.statusCode !== 403) {
	failures.push(`Cross-site POST returned ${blockedCrossSitePost.statusCode} instead of 403.`);
}
if (header(blockedCrossSitePost.headers, 'x-frame-options') !== 'DENY') {
	failures.push('Blocked cross-site responses are missing security headers.');
}

const offline = await request(new URL('/offline.html', siteUrl));
if (!offline.body.includes('Reconnect to open your calendar and training data.')) {
	failures.push('/offline.html does not explain the private-data offline boundary.');
}
if (offline.body.includes('<style')) {
	failures.push('/offline.html still contains inline styles.');
}
const offlineCss = await request(new URL('/offline.css', siteUrl));
if (offlineCss.statusCode !== 200) {
	failures.push(`/offline.css returned ${offlineCss.statusCode}.`);
}
if (header(offlineCss.headers, 'cache-control') !== 'public, max-age=0, must-revalidate') {
	failures.push('/offline.css has the wrong cache policy.');
}

const immutableAssetHref = firstImmutableAsset(home);
if (!immutableAssetHref) {
	failures.push('Preview did not expose an immutable client asset.');
} else {
	const asset = await request(new URL(immutableAssetHref, siteUrl));
	const cacheControl = header(asset.headers, 'cache-control');
	if (!cacheControl.includes('max-age=31536000') || !cacheControl.includes('immutable')) {
		failures.push(`Immutable asset has weak cache policy: ${cacheControl || 'missing'}.`);
	}
}

if (failures.length > 0) {
	for (const failure of failures) console.error(failure);
	process.exit(1);
}

console.log(`Preview verified at ${siteUrl}`);

function request(input, options = {}) {
	const url = new URL(input);
	const transport = url.protocol === 'https:' ? https : http;

	return new Promise((resolve, reject) => {
		const req = transport.request(
			url,
			{
				method: options.method ?? 'GET',
				headers: options.headers ?? { accept: 'text/html,*/*' }
			},
			(response) => {
				response.setEncoding('utf8');
				let body = '';
				response.on('data', (chunk) => {
					body += chunk;
				});
				response.on('end', () => {
					resolve({
						statusCode: response.statusCode ?? 0,
						headers: response.headers,
						body
					});
				});
			}
		);
		req.on('error', reject);
		req.setTimeout(10_000, () => req.destroy(new Error(`Timed out requesting ${url.href}`)));
		req.end();
	});
}

function header(headers, name) {
	const value = headers[name];
	if (Array.isArray(value)) return value.join(', ');
	return value ?? '';
}

function directiveValue(policy, directiveName) {
	const directive = policy
		.split(';')
		.map((part) => part.trim())
		.find((part) => part.startsWith(`${directiveName} `));
	return directive ?? '';
}

function directiveSources(policy, directiveName) {
	const directive = directiveValue(policy, directiveName);
	return directive ? directive.split(/\s+/).slice(1) : [];
}

function assertExactDirective(policy, directiveName, expectedSources) {
	const actual = directiveSources(policy, directiveName).sort();
	const expected = [...expectedSources].sort();
	if (JSON.stringify(actual) !== JSON.stringify(expected)) {
		failures.push(
			`CSP ${directiveName} differs from the expected ${expectedSources.join(' ')} contract.`
		);
	}
}

function firstImmutableAsset(response) {
	const linkHeader = header(response.headers, 'link');
	const linkMatch = linkHeader.match(/<([^>]*\/_app\/immutable\/[^>]*)>/);
	if (linkMatch?.[1]) return linkMatch[1];

	const bodyMatch = response.body.match(/(?:href|src)="([^"]*\/_app\/immutable\/[^"]*)"/);
	return bodyMatch?.[1] ?? null;
}

async function verifyPngAsset(pathname, label) {
	if (typeof pathname !== 'string' || !pathname.startsWith('/pwa/') || pathname.includes('?')) {
		failures.push(`${label} has an unsafe or unexpected path.`);
		return;
	}
	const asset = await request(new URL(pathname, siteUrl));
	if (asset.statusCode !== 200) failures.push(`${label} returned ${asset.statusCode}.`);
	if (!header(asset.headers, 'content-type').toLowerCase().startsWith('image/png')) {
		failures.push(`${label} is not served as image/png.`);
	}
}
