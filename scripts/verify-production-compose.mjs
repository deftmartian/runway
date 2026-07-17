import { spawn } from 'node:child_process';

const env = {
	...process.env,
	APP_DATABASE_URL:
		process.env['APP_DATABASE_URL'] ??
		'postgres://runway:compose_verification_password@db:5432/runway',
	POSTGRES_PASSWORD: process.env['POSTGRES_PASSWORD'] ?? 'compose_verification_password',
	BETTER_AUTH_SECRET:
		process.env['BETTER_AUTH_SECRET'] ??
		'compose-verification-auth-secret-with-at-least-32-characters',
	ORIGIN: process.env['ORIGIN'] ?? 'https://runway.example.test',
	PUBLIC_APP_ORIGIN: process.env['PUBLIC_APP_ORIGIN'] ?? 'https://runway.example.test',
	RUNWAY_BUILD_ID: process.env['RUNWAY_BUILD_ID'] ?? 'compose-verification-build',
	RUNWAY_IPV4_ADDRESS: process.env['RUNWAY_IPV4_ADDRESS'] ?? '192.0.2.10',
	IPVLAN_SUBNET: process.env['IPVLAN_SUBNET'] ?? '192.0.2.0/24',
	IPVLAN_GATEWAY: process.env['IPVLAN_GATEWAY'] ?? '192.0.2.1',
	IPVLAN_PARENT: process.env['IPVLAN_PARENT'] ?? 'eth0.100',
	IPVLAN_NETWORK_NAME: process.env['IPVLAN_NETWORK_NAME'] ?? 'runway-verification-edge',
	DNS_SERVER: process.env['DNS_SERVER'] ?? '192.0.2.53'
};

await new Promise((resolve, reject) => {
	const child = spawn(
		'docker',
		['compose', '-f', 'compose.yaml', '-f', 'deploy/compose.production.yaml', 'config', '-q'],
		{ env, stdio: 'inherit' }
	);
	child.once('error', reject);
	child.once('exit', (code, signal) => {
		if (code === 0) resolve();
		else reject(new Error(`Production Compose validation exited with ${signal ?? code}.`));
	});
});

console.log('Production Compose configuration verified.');
