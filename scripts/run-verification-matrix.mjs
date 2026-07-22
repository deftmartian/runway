import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

const groups = [
	{
		id: 'web',
		name: 'web quality',
		steps: [
			['verify:docs'],
			['verify:dependencies'],
			['verify'],
			['verify:pwa'],
			['verify:pwa:assets']
		]
	},
	{
		id: 'browser',
		name: 'browser',
		steps: [['test:e2e'], ['test:visual']]
	},
	{
		id: 'deployment',
		name: 'data and deployment',
		steps: [
			['verify:migrations'],
			['verify:compose'],
			['verify:compose:production'],
			['verify:caddy']
		]
	},
	{
		id: 'android',
		name: 'Android',
		steps: [['verify:android'], ['verify:android:build'], ['verify:android:release']]
	},
	{
		id: 'container',
		name: 'container',
		steps: [['verify:docker'], ['verify:image', '--', 'runway:latest']]
	}
];

if (process.argv.includes('--list')) {
	for (const group of groups) {
		console.log(`${group.name}: ${group.steps.map(formatStep).join(', ')}`);
	}
	console.log(`production preview: ${formatStep(['verify:preview:local'])}`);
	process.exit(0);
}

const concurrency = process.argv.includes('--serial')
	? 1
	: parseConcurrency(process.env['RUNWAY_VERIFY_CONCURRENCY']);
const activeChildren = new Set();
const useProcessGroups = process.platform !== 'win32';
let interruptedSignal;

for (const signal of ['SIGINT', 'SIGTERM']) {
	process.once(signal, () => {
		interruptedSignal ??= signal;
		for (const child of activeChildren) terminate(child, signal);
	});
}

console.log(
	`Running ${groups.length} verification groups with up to ${concurrency} groups in parallel.`
);

const startedAt = Date.now();
const results = await runPool(groups, concurrency, runGroup);

const webPassed = results.find((result) => result.name === 'web quality')?.ok;
if (!interruptedSignal && webPassed) {
	console.log(
		'\nParallel groups finished. Verifying the production preview from the completed build.'
	);
	results.push(
		await runGroup({
			id: 'preview',
			name: 'production preview',
			steps: [['verify:preview:local']]
		})
	);
} else if (!interruptedSignal) {
	console.log('\nSkipping production preview because the web build did not complete successfully.');
}

console.log('\nVerification summary');
for (const result of results) {
	const mark = result.ok ? 'PASS' : 'FAIL';
	console.log(`${mark.padEnd(4)}  ${result.name.padEnd(20)} ${formatDuration(result.duration)}`);
}
console.log(`Total elapsed: ${formatDuration(Date.now() - startedAt)}`);

if (interruptedSignal) {
	process.exitCode = interruptedSignal === 'SIGINT' ? 130 : 143;
} else if (results.some((result) => !result.ok)) {
	process.exitCode = 1;
}

async function runGroup(group) {
	const groupStartedAt = Date.now();
	console.log(`\n[${group.id}] Starting ${group.name}.`);
	for (const step of group.steps) {
		if (interruptedSignal) break;
		const ok = await runPnpm(group.id, step);
		if (!ok) {
			console.error(`[${group.id}] Failed at ${formatStep(step)}.`);
			return { name: group.name, ok: false, duration: Date.now() - groupStartedAt };
		}
	}
	const ok = !interruptedSignal;
	if (ok) console.log(`[${group.id}] Completed ${group.name}.`);
	return { name: group.name, ok, duration: Date.now() - groupStartedAt };
}

function runPnpm(prefix, args) {
	console.log(`[${prefix}] > corepack pnpm ${args.join(' ')}`);
	return new Promise((resolve) => {
		const child = spawn('corepack', ['pnpm', ...args], {
			detached: useProcessGroups,
			stdio: ['ignore', 'pipe', 'pipe']
		});
		activeChildren.add(child);
		prefixLines(child.stdout, prefix, console.log);
		prefixLines(child.stderr, prefix, console.error);

		let settled = false;
		const settle = (ok) => {
			if (settled) return;
			settled = true;
			activeChildren.delete(child);
			resolve(ok);
		};
		child.once('error', (error) => {
			console.error(`[${prefix}] Could not start corepack: ${error.message}`);
			settle(false);
		});
		child.once('exit', (code) => settle(code === 0));
	});
}

function terminate(child, signal) {
	try {
		if (useProcessGroups && child.pid !== undefined) process.kill(-child.pid, signal);
		else child.kill(signal);
	} catch (error) {
		if (error?.code !== 'ESRCH') {
			console.error(
				`Could not stop verification process ${child.pid ?? 'unknown'}: ${error.message}`
			);
		}
	}
}

function prefixLines(stream, prefix, write) {
	const lines = createInterface({ input: stream, crlfDelay: Infinity });
	lines.on('line', (line) => write(`[${prefix}] ${line}`));
}

async function runPool(items, limit, worker) {
	const results = new Array(items.length);
	let nextIndex = 0;
	const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
		while (nextIndex < items.length && !interruptedSignal) {
			const index = nextIndex++;
			results[index] = await worker(items[index]);
		}
	});
	await Promise.all(runners);
	return results.filter(Boolean);
}

function parseConcurrency(input) {
	if (input === undefined) return 3;
	const value = Number(input);
	if (!Number.isSafeInteger(value) || value < 1 || value > groups.length) {
		throw new Error(`RUNWAY_VERIFY_CONCURRENCY must be an integer from 1 to ${groups.length}.`);
	}
	return value;
}

function formatStep(step) {
	return `pnpm ${step.join(' ')}`;
}

function formatDuration(milliseconds) {
	const totalSeconds = Math.round(milliseconds / 1_000);
	const minutes = Math.floor(totalSeconds / 60);
	const seconds = totalSeconds % 60;
	return minutes > 0 ? `${minutes}m ${seconds}s` : `${seconds}s`;
}
