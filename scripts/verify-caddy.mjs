import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const config = resolve(root, 'deploy/Caddyfile.example');
const caddyImage = 'caddy@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648';
const trustedProxyRanges = ['203.0.113.0/24', '2001:db8::/32'];
const baseDockerArgs = [
	'run',
	'--rm',
	'--volume',
	`${config}:/etc/caddy/Caddyfile:ro`,
	'--env',
	'RUNWAY_HOST=runway.example.test',
	'--env',
	'RUNWAY_UPSTREAM=127.0.0.1:4100'
];

const adapted = JSON.parse(
	execFileSync(
		'docker',
		[
			...baseDockerArgs,
			'--env',
			`RUNWAY_TRUSTED_PROXY_CIDRS=${trustedProxyRanges.join(' ')}`,
			caddyImage,
			'caddy',
			'adapt',
			'--config',
			'/etc/caddy/Caddyfile',
			'--validate'
		],
		{ cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] }
	)
);

const servers = Object.values(adapted.apps?.http?.servers ?? {});
if (servers.length !== 1) throw new Error('Caddy contract must adapt to one HTTP server.');
const server = servers[0];
if (!server.listen?.includes(':443')) {
	throw new Error('Caddy contract must terminate the public HTTPS listener.');
}
if (
	server.trusted_proxies?.source !== 'static' ||
	JSON.stringify(server.trusted_proxies.ranges) !== JSON.stringify(trustedProxyRanges) ||
	server.trusted_proxies_strict !== 1
) {
	throw new Error('Caddy contract must preserve the exact strict trusted-proxy ranges.');
}

const handlers = collectHandlers(server.routes ?? []);
const proxy = handlers.find((handler) => handler.handler === 'reverse_proxy');
if (
	!proxy ||
	JSON.stringify(proxy.upstreams) !== JSON.stringify([{ dial: '127.0.0.1:4100' }]) ||
	JSON.stringify(proxy.headers?.request?.set?.['X-Forwarded-For']) !==
		JSON.stringify(['{http.vars.client_ip}'])
) {
	throw new Error('Caddy contract must replace X-Forwarded-For with one derived client address.');
}
const hsts = handlers.find(
	(handler) =>
		handler.handler === 'headers' &&
		JSON.stringify(handler.response?.set?.['Strict-Transport-Security']) ===
			JSON.stringify(['max-age=31536000'])
);
if (!hsts) throw new Error('Caddy contract must set the exact one-year HSTS baseline.');

const loggers = Object.values(adapted.logging?.logs ?? {});
const resetTokenFilter = loggers
	.flatMap((logger) => logger.encoder?.fields?.['request>uri']?.actions ?? [])
	.find(
		(action) =>
			action.parameter === 'token' && action.type === 'replace' && action.value === 'REDACTED'
	);
if (!resetTokenFilter) {
	throw new Error('Caddy access logging must redact the password-reset token query value.');
}

const activeCaddyfile = readFileSync(config, 'utf8')
	.split(/\r?\n/)
	.filter((line) => !line.trimStart().startsWith('#'))
	.join('\n');
if (/\bforward_auth\b/.test(activeCaddyfile)) {
	throw new Error('Authentik must be used through runway OIDC, not as a second forward_auth gate.');
}
if (/Content-Security-Policy/i.test(JSON.stringify(adapted))) {
	throw new Error("The edge must preserve runway's nonce-bearing CSP instead of replacing it.");
}

for (const [label, proxyValue] of [
	['missing', undefined],
	['malformed', '203.0.113.0/24 not-a-cidr']
]) {
	const args = [...baseDockerArgs];
	if (proxyValue) args.push('--env', `RUNWAY_TRUSTED_PROXY_CIDRS=${proxyValue}`);
	args.push(caddyImage, 'caddy', 'adapt', '--config', '/etc/caddy/Caddyfile', '--validate');
	const result = spawnSync('docker', args, { cwd: root, encoding: 'utf8' });
	if (result.status === 0) {
		throw new Error(`Caddy contract accepted ${label} trusted-proxy configuration.`);
	}
}

console.log('Caddy edge contract semantics adapted and validated.');

function collectHandlers(value) {
	const handlers = [];
	for (const candidate of value) {
		for (const handler of candidate.handle ?? []) {
			handlers.push(handler);
			if (handler.routes) handlers.push(...collectHandlers(handler.routes));
		}
		if (candidate.routes) handlers.push(...collectHandlers(candidate.routes));
	}
	return handlers;
}
