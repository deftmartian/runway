import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const packageJson = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8'));
const errors = [];

const requiredFiles = [
	'README.md',
	'CONTRIBUTING.md',
	'LICENSE',
	'docs/PRODUCT.md',
	'docs/DESIGN_SYSTEM.md',
	'docs/ARCHITECTURE.md',
	'docs/SECURITY.md',
	'docs/DEPLOYMENT.md',
	'docs/TRAINING_SOURCES.md',
	'deploy/Caddyfile.example'
];

for (const file of requiredFiles) {
	if (!existsSync(resolve(root, file))) errors.push(`missing required publication file: ${file}`);
}

if (packageJson.license !== 'AGPL-3.0-only') {
	errors.push('package.json must declare license AGPL-3.0-only');
}

if (typeof packageJson.description !== 'string' || packageJson.description.trim().length < 20) {
	errors.push('package.json must include a useful description');
}

if (packageJson.repository?.url !== 'git+https://github.com/deftmartian/runway.git') {
	errors.push('package.json must identify the canonical source repository');
}

const caddyfilePath = resolve(root, 'deploy/Caddyfile.example');
if (existsSync(caddyfilePath)) {
	const caddyfile = readFileSync(caddyfilePath, 'utf8');
	for (const required of [
		'trusted_proxies static {$RUNWAY_TRUSTED_PROXY_CIDRS}',
		'trusted_proxies_strict',
		'header_up X-Forwarded-For {client_ip}',
		'replace token REDACTED',
		'Strict-Transport-Security "max-age=31536000"'
	]) {
		if (!caddyfile.includes(required)) {
			errors.push(`deploy/Caddyfile.example is missing the edge contract: ${required}`);
		}
	}
	if (/Content-Security-Policy/i.test(caddyfile)) {
		errors.push('deploy/Caddyfile.example must preserve the application nonce CSP, not replace it');
	}
}

const licensePath = resolve(root, 'LICENSE');
if (
	existsSync(licensePath) &&
	!readFileSync(licensePath, 'utf8').includes(
		'GNU AFFERO GENERAL PUBLIC LICENSE\n                       Version 3'
	)
) {
	errors.push('LICENSE is not the canonical GNU Affero General Public License version 3 text');
}

const trackedMarkdownFiles = execFileSync('git', ['ls-files', '-z', '*.md'], {
	cwd: root,
	encoding: 'utf8'
})
	.split('\0')
	.filter(Boolean);
const markdownFiles = [
	...new Set([...trackedMarkdownFiles, ...requiredFiles.filter((file) => file.endsWith('.md'))])
].sort();

const privateMachinePatterns = [
	{ pattern: /\/home\/[A-Za-z0-9._-]+\//g, label: 'absolute Linux home path' },
	{ pattern: /\/Users\/[A-Za-z0-9._-]+\//g, label: 'absolute macOS home path' },
	{ pattern: /[A-Za-z]:\\Users\\[^\\\s]+\\/g, label: 'absolute Windows home path' },
	{ pattern: /file:\/\//g, label: 'local file URL' },
	{
		pattern:
			/\b(?:10(?:\.\d{1,3}){3}|192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})\b/g,
		label: 'machine-specific private IPv4 address'
	}
];

function headingSlugs(markdown) {
	const counts = new Map();
	const slugs = new Set();

	for (const line of markdown.split('\n')) {
		const match = /^(?:#{1,6})\s+(.+?)\s*$/.exec(line);
		if (!match) continue;

		const base = match[1]
			.toLowerCase()
			.replace(/<[^>]*>/g, '')
			.replace(/[^\p{L}\p{N}\s-]/gu, '')
			.trim()
			.replace(/\s+/g, '-');
		const count = counts.get(base) ?? 0;
		counts.set(base, count + 1);
		slugs.add(count === 0 ? base : `${base}-${count}`);
	}

	return slugs;
}

for (const file of markdownFiles) {
	const absoluteFile = resolve(root, file);
	const markdown = readFileSync(absoluteFile, 'utf8');
	const topLevelHeadings = markdown.match(/^#\s+.+$/gm) ?? [];
	if (topLevelHeadings.length !== 1) {
		errors.push(`${file} must contain exactly one top-level heading`);
	}

	for (const { pattern, label } of privateMachinePatterns) {
		pattern.lastIndex = 0;
		if (pattern.test(markdown)) errors.push(`${file} contains an ${label}`);
	}

	for (const match of markdown.matchAll(/\[[^\]]+\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)) {
		const target = match[1];
		if (/^(?:https?:|mailto:)/i.test(target) || target.startsWith('#')) continue;

		const [encodedPath, anchor] = target.split('#', 2);
		let linkedPath;
		try {
			linkedPath = decodeURIComponent(encodedPath);
		} catch {
			errors.push(`${file} contains an invalid encoded link: ${target}`);
			continue;
		}

		const resolvedTarget = resolve(dirname(absoluteFile), linkedPath);
		if (isAbsolute(linkedPath) || relative(root, resolvedTarget).startsWith('..')) {
			errors.push(`${file} links outside the repository: ${target}`);
			continue;
		}

		if (!existsSync(resolvedTarget)) {
			errors.push(`${file} contains a broken local link: ${target}`);
			continue;
		}

		if (anchor && statSync(resolvedTarget).isFile()) {
			const slugs = headingSlugs(readFileSync(resolvedTarget, 'utf8'));
			if (!slugs.has(anchor.toLowerCase())) {
				errors.push(`${file} contains a broken heading link: ${target}`);
			}
		}
	}

	for (const match of markdown.matchAll(/corepack pnpm(?: run)? ([A-Za-z0-9:_-]+)/g)) {
		const command = match[1];
		if (command !== 'install' && !Object.hasOwn(packageJson.scripts, command)) {
			errors.push(`${file} documents an unknown pnpm script: ${command}`);
		}
	}
}

if (errors.length > 0) {
	console.error(`Documentation verification failed:\n- ${errors.join('\n- ')}`);
	process.exit(1);
}

console.log(
	`Documentation verification passed for ${markdownFiles.length} publication Markdown files.`
);
