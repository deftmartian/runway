import { cp, mkdir, readFile, rm } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { dirname } from 'node:path';

await loadEnvFile();

process.env.HOST ??= '0.0.0.0';
process.env.PORT ??= '4100';
process.env.ORIGIN ??=
	process.env.PUBLIC_APP_ORIGIN ??
	process.env.RUNWAY_PREVIEW_ORIGIN ??
	`http://localhost:${process.env.PORT}`;
process.env.PUBLIC_APP_ORIGIN ??= process.env.ORIGIN;

const managedPreviewDir = process.env.RUNWAY_PREVIEW_DIR === undefined;
const liveDir = process.env.RUNWAY_PREVIEW_DIR ?? `.runway-live/${process.pid}`;
const buildDir = process.env.RUNWAY_BUILD_DIR ?? 'build';

await mkdir(dirname(liveDir), { recursive: true });
await rm(liveDir, { force: true, recursive: true });
await cp(buildDir, liveDir, { recursive: true });

const child = spawn(process.execPath, [`${liveDir}/index.js`], {
	env: process.env,
	stdio: 'inherit'
});

let shuttingDown = false;

for (const signal of ['SIGINT', 'SIGTERM']) {
	process.on(signal, () => {
		shuttingDown = true;
		child.kill(signal);
	});
}

child.on('exit', async (code, signal) => {
	if (managedPreviewDir) {
		await rm(liveDir, { force: true, recursive: true });
	}

	if (signal) {
		process.exit(signal === 'SIGINT' ? 130 : 143);
		return;
	}
	process.exit(shuttingDown ? 143 : (code ?? 0));
});

async function loadEnvFile() {
	if (!existsSync('.env')) return;
	const text = await readFile('.env', 'utf8');
	for (const rawLine of text.split(/\r?\n/)) {
		const line = rawLine.trim();
		if (!line || line.startsWith('#')) continue;
		const index = line.indexOf('=');
		if (index <= 0) continue;
		const key = line.slice(0, index).trim();
		if (!/^[A-Z_][A-Z0-9_]*$/i.test(key) || process.env[key] !== undefined) continue;
		process.env[key] = parseEnvValue(line.slice(index + 1).trim());
	}
}

function parseEnvValue(value) {
	if (
		(value.startsWith('"') && value.endsWith('"')) ||
		(value.startsWith("'") && value.endsWith("'"))
	) {
		return value.slice(1, -1);
	}
	return value;
}
