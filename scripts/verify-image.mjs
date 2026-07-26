import { spawn } from 'node:child_process';

const [firstArgument, secondArgument] = process.argv.slice(2);
const image = firstArgument === '--' ? secondArgument : firstArgument;
if (!image || image.startsWith('-') || /\s/.test(image)) {
	throw new Error('Usage: node scripts/verify-image.mjs <local-image-reference>');
}

const scannerImage = 'runway-image-scanner:local';

await verifyRuntimeDependencies(image);
await run('docker', ['build', '--pull', '--file', 'Dockerfile.audit', '--tag', scannerImage, '.']);
const report = JSON.parse(
	await capture('docker', [
		'run',
		'--rm',
		'--network',
		'bridge',
		'--volume',
		'/var/run/docker.sock:/var/run/docker.sock:ro',
		scannerImage,
		'image',
		'--quiet',
		'--format',
		'json',
		'--scanners',
		'vuln',
		'--severity',
		'HIGH,CRITICAL',
		'--ignore-unfixed',
		'--no-progress',
		'--skip-version-check',
		image
	])
);

const findings = (report.Results ?? []).flatMap((result) =>
	(result.Vulnerabilities ?? []).map((vulnerability) => ({
		target: result.Target,
		id: vulnerability.VulnerabilityID,
		package: vulnerability.PkgName,
		installed: vulnerability.InstalledVersion,
		fixed: vulnerability.FixedVersion,
		severity: vulnerability.Severity,
		title: vulnerability.Title
	}))
);

if (findings.length > 0) {
	console.table(findings);
	throw new Error(`${image} contains ${findings.length} fixed high or critical advisories.`);
}

console.log(`${image} has no fixed high or critical OS or library advisories.`);

async function verifyRuntimeDependencies(imageReference) {
	const script = String.raw`
		import { closeSync, openSync, readFileSync, readdirSync, readSync } from 'node:fs';
		import { createRequire } from 'node:module';
		import { dirname, join } from 'node:path';

		const rootPackagePath = '/app/package.json';
		const rootPackage = JSON.parse(readFileSync(rootPackagePath, 'utf8'));
		const fromRoot = createRequire(rootPackagePath);

		function installedPackage(requireFrom, name) {
			const entry = requireFrom.resolve(name);
			let directory = dirname(entry);
			while (true) {
				try {
					const packagePath = join(directory, 'package.json');
					const metadata = JSON.parse(readFileSync(packagePath, 'utf8'));
					if (metadata.name === name) {
						return { entry, metadata, require: createRequire(entry) };
					}
				} catch {
					// Keep walking toward the package root.
				}
				const parent = dirname(directory);
				if (parent === directory) throw new Error('Could not locate package metadata for ' + name + '.');
				directory = parent;
			}
		}

		for (const name of ['better-auth', '@better-auth/core', '@better-auth/passkey']) {
			const installed = installedPackage(fromRoot, name);
			const expected = rootPackage.dependencies?.[name];
			if (!expected || installed.metadata.version !== expected) {
				throw new Error(
					name + ' resolved to ' + installed.metadata.version + ', expected exact runtime version ' + expected + '.'
				);
			}
		}

		const passkey = installedPackage(fromRoot, '@better-auth/passkey');
		const core = installedPackage(passkey.require, '@better-auth/core');
		for (const name of ['@better-fetch/fetch', 'better-call']) {
			const required = core.metadata.peerDependencies?.[name];
			const resolved = installedPackage(passkey.require, name);
			if (!required || resolved.metadata.version !== required) {
				throw new Error(
					'@better-auth/passkey resolves ' + name + '@' + resolved.metadata.version +
					', but @better-auth/core requires ' + required + '.'
				);
			}
		}

		const elfArtifacts = [];
		for (const root of ['/app/build', '/app/node_modules']) {
			visit(root);
		}
		if (elfArtifacts.length > 0) {
			throw new Error(
				'Cross-platform build stages require architecture-neutral app artifacts; found ELF files: ' +
				elfArtifacts.slice(0, 10).join(', ')
			);
		}

		function visit(directory) {
			for (const entry of readdirSync(directory, { withFileTypes: true })) {
				const path = join(directory, entry.name);
				if (entry.isDirectory()) {
					visit(path);
					continue;
				}
				if (!entry.isFile()) continue;
				const descriptor = openSync(path, 'r');
				const header = Buffer.alloc(4);
				let bytesRead;
				try {
					bytesRead = readSync(descriptor, header, 0, header.length, 0);
				} finally {
					closeSync(descriptor);
				}
				if (
					bytesRead === header.length &&
					header[0] === 0x7f &&
					header[1] === 0x45 &&
					header[2] === 0x4c &&
					header[3] === 0x46
				) {
					elfArtifacts.push(path);
				}
			}
		}
	`;
	await run('docker', [
		'run',
		'--rm',
		'--entrypoint',
		'node',
		imageReference,
		'--input-type=module',
		'--eval',
		script
	]);
	console.log(
		`${imageReference} has a consistent Better Auth graph and architecture-neutral app artifacts.`
	);
}

function run(command, args) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, { stdio: 'inherit' });
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (code === 0) resolve();
			else reject(new Error(`${command} exited with ${signal ?? code ?? 'unknown status'}.`));
		});
	});
}

function capture(command, args) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'inherit'] });
		let output = '';
		child.stdout.setEncoding('utf8');
		child.stdout.on('data', (chunk) => {
			output += chunk;
		});
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (code === 0) resolve(output);
			else reject(new Error(`${command} exited with ${signal ?? code ?? 'unknown status'}.`));
		});
	});
}
