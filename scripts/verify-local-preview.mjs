import { spawn } from 'node:child_process';
import { createServer } from 'node:net';

const siteUrl = process.env['SITE_URL'] ?? 'http://127.0.0.1:4100';
const publicUrl = new URL(siteUrl);
await assertPortAvailable(
	publicUrl.hostname,
	Number(publicUrl.port || (publicUrl.protocol === 'https:' ? 443 : 80))
);
const preview = spawn(process.execPath, ['scripts/run-preview.mjs'], {
	env: {
		...process.env,
		HOST: process.env['HOST'] ?? publicUrl.hostname,
		PORT:
			process.env['PORT'] ?? (publicUrl.port || (publicUrl.protocol === 'https:' ? '443' : '80')),
		ORIGIN: process.env['ORIGIN'] ?? publicUrl.origin,
		PUBLIC_APP_ORIGIN: process.env['PUBLIC_APP_ORIGIN'] ?? publicUrl.origin
	},
	stdio: 'inherit'
});

let previewExit;
const previewExited = new Promise((resolve) => {
	preview.once('exit', (code, signal) => {
		previewExit = { code, signal };
		resolve(previewExit);
	});
});

try {
	await waitForReady(new URL('/health/live', publicUrl), previewExited);
	await run(process.execPath, ['scripts/verify-preview.mjs'], {
		...process.env,
		SITE_URL: publicUrl.origin
	});
} finally {
	if (!previewExit) {
		preview.kill('SIGTERM');
		await previewExited;
	}
}

async function waitForReady(url, exited) {
	const deadline = Date.now() + 30_000;
	while (Date.now() < deadline) {
		const outcome = await Promise.race([
			exited.then((status) => ({ type: 'exit', status })),
			fetch(url, { signal: AbortSignal.timeout(2_000) })
				.then((response) => ({ type: 'response', response }))
				.catch(() => ({ type: 'retry' })),
			delay(250).then(() => ({ type: 'retry' }))
		]);
		if (outcome.type === 'exit') {
			throw new Error(
				`Preview exited before becoming ready (${outcome.status.signal ?? outcome.status.code ?? 'unknown status'}).`
			);
		}
		if (outcome.type === 'response' && outcome.response.ok) return;
		await delay(250);
	}
	throw new Error(`Preview did not become ready at ${url.href} within 30 seconds.`);
}

function run(command, args, env) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, { env, stdio: 'inherit' });
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (code === 0) resolve();
			else reject(new Error(`${command} exited with ${signal ?? code ?? 'unknown status'}.`));
		});
	});
}

function delay(milliseconds) {
	return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function assertPortAvailable(host, port) {
	return new Promise((resolve, reject) => {
		const server = createServer();
		server.unref();
		server.once('error', () => {
			reject(new Error(`Preview verification port ${host}:${port} is already in use.`));
		});
		server.listen(port, host, () => {
			server.close((error) => (error ? reject(error) : resolve()));
		});
	});
}
