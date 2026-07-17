import { spawn } from 'node:child_process';

const [firstArgument, secondArgument] = process.argv.slice(2);
const image = firstArgument === '--' ? secondArgument : firstArgument;
if (!image || image.startsWith('-') || /\s/.test(image)) {
	throw new Error('Usage: node scripts/verify-image.mjs <local-image-reference>');
}

const scannerImage = 'runway-image-scanner:local';

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
