import { execFileSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const config = resolve(root, 'deploy/Caddyfile.example');
const caddyImage = 'caddy@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648';

execFileSync(
	'docker',
	[
		'run',
		'--rm',
		'--volume',
		`${config}:/etc/caddy/Caddyfile:ro`,
		'--env',
		'RUNWAY_HOST=runway.example.test',
		'--env',
		'RUNWAY_UPSTREAM=127.0.0.1:4100',
		'--env',
		'RUNWAY_TRUSTED_PROXY_CIDRS=203.0.113.0/24 2001:db8::/32',
		caddyImage,
		'caddy',
		'adapt',
		'--config',
		'/etc/caddy/Caddyfile',
		'--validate'
	],
	{ cwd: root, stdio: 'inherit' }
);

console.log('Caddy edge contract adapted and validated.');
