import { execFileSync } from 'node:child_process';

const standardEnv = {
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
	RUNWAY_IMAGE:
		process.env['RUNWAY_IMAGE'] ?? 'ghcr.io/deftmartian/runway:sha-compose-verification',
	RUNWAY_BIND_ADDRESS: process.env['RUNWAY_BIND_ADDRESS'] ?? '127.0.0.1',
	RUNWAY_PORT: process.env['RUNWAY_PORT'] ?? '4100',
	APP_DATABASE_POOL_MAX: process.env['APP_DATABASE_POOL_MAX'] ?? '7',
	WORKER_DATABASE_POOL_MAX: process.env['WORKER_DATABASE_POOL_MAX'] ?? '3',
	DATABASE_CONNECT_TIMEOUT_SECONDS: process.env['DATABASE_CONNECT_TIMEOUT_SECONDS'] ?? '8',
	DATABASE_IDLE_TIMEOUT_SECONDS: process.env['DATABASE_IDLE_TIMEOUT_SECONDS'] ?? '40',
	DATABASE_MAX_LIFETIME_SECONDS: process.env['DATABASE_MAX_LIFETIME_SECONDS'] ?? '1200',
	DATABASE_STATEMENT_TIMEOUT_MS: process.env['DATABASE_STATEMENT_TIMEOUT_MS'] ?? '25000',
	DATABASE_IDLE_TRANSACTION_TIMEOUT_MS:
		process.env['DATABASE_IDLE_TRANSACTION_TIMEOUT_MS'] ?? '26000',
	VLAN_TAG: '',
	VLAN_TRUNK_INTERFACE: '',
	DNS_SERVER: ''
};

const ipvlanEnv = {
	...standardEnv,
	VLAN_TAG: process.env['VLAN_TAG'] ?? '100',
	VLAN_TRUNK_INTERFACE: process.env['VLAN_TRUNK_INTERFACE'] ?? 'eth0',
	DNS_SERVER: process.env['DNS_SERVER'] ?? '192.168.100.1'
};

function renderCompose(files, env) {
	const args = ['compose', ...files.flatMap((file) => ['-f', file]), 'config', '--format', 'json'];
	return JSON.parse(
		execFileSync('docker', args, { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] })
	);
}

const standard = renderCompose(['compose.yaml', 'deploy/compose.production.yaml'], standardEnv);
const local = renderCompose(['compose.yaml'], standardEnv);
if (
	local.services.app.environment?.ADDRESS_HEADER !== undefined ||
	local.services.app.environment?.XFF_DEPTH !== undefined
) {
	throw new Error('Base Compose must use the socket address when no reverse proxy is configured.');
}
if (
	standard.services.app.environment?.ADDRESS_HEADER !== 'x-forwarded-for' ||
	String(standard.services.app.environment?.XFF_DEPTH) !== '1'
) {
	throw new Error('Production Compose must enforce the one-hop forwarded-address contract.');
}
for (const service of ['app', 'worker', 'migrate']) {
	if (standard.services[service]?.image !== standardEnv.RUNWAY_IMAGE) {
		throw new Error(`Production Compose must use RUNWAY_IMAGE for the ${service} service.`);
	}
	if (standard.services[service]?.build) {
		throw new Error(`Production Compose must not build the ${service} service locally.`);
	}
}
if (standard.services.migrate.command?.join(' ') !== 'node scripts/run-migrations.mjs') {
	throw new Error('The migration service must use the migrator bundled in the runtime image.');
}
const standardPort = standard.services.app.ports?.[0];
if (
	standardPort?.host_ip !== standardEnv.RUNWAY_BIND_ADDRESS ||
	standardPort?.published !== standardEnv.RUNWAY_PORT ||
	standardPort?.target !== 4100
) {
	throw new Error(
		'Standard production Compose must publish the configured app port and bind address.'
	);
}
if (standard.networks.vlan) {
	throw new Error('Standard production Compose must not require an ipvlan network.');
}
if (
	standard.services.app.environment.DATABASE_POOL_MAX !== standardEnv.APP_DATABASE_POOL_MAX ||
	standard.services.worker.environment.DATABASE_POOL_MAX !== standardEnv.WORKER_DATABASE_POOL_MAX
) {
	throw new Error(
		'Production Compose must keep web and worker database pools independently bounded.'
	);
}
for (const setting of [
	'DATABASE_CONNECT_TIMEOUT_SECONDS',
	'DATABASE_IDLE_TIMEOUT_SECONDS',
	'DATABASE_MAX_LIFETIME_SECONDS',
	'DATABASE_STATEMENT_TIMEOUT_MS',
	'DATABASE_IDLE_TRANSACTION_TIMEOUT_MS'
]) {
	if (
		standard.services.app.environment[setting] !== standardEnv[setting] ||
		standard.services.worker.environment[setting] !== standardEnv[setting]
	) {
		throw new Error(`Production Compose must pass ${setting} to web and worker.`);
	}
}

const ipvlan = renderCompose(
	['compose.yaml', 'deploy/compose.production.yaml', 'deploy/compose.ipvlan.yaml'],
	ipvlanEnv
);
if (ipvlan.services.app.ports) {
	throw new Error('The ipvlan overlay must remove the host-published app port.');
}
const ipvlanIpam = ipvlan.networks.vlan?.ipam?.config?.[0];
if (
	ipvlan.services.app.networks?.vlan?.ipv4_address !== `192.168.${ipvlanEnv.VLAN_TAG}.10` ||
	ipvlan.services.app.dns?.[0] !== ipvlanEnv.DNS_SERVER ||
	ipvlan.services.worker.dns?.[0] !== ipvlanEnv.DNS_SERVER ||
	ipvlan.networks.vlan?.name !== `vlan${ipvlanEnv.VLAN_TAG}_runway` ||
	ipvlan.networks.vlan?.driver !== 'ipvlan' ||
	ipvlan.networks.vlan?.driver_opts?.parent !==
		`${ipvlanEnv.VLAN_TRUNK_INTERFACE}.${ipvlanEnv.VLAN_TAG}` ||
	ipvlanIpam?.subnet !== `192.168.${ipvlanEnv.VLAN_TAG}.0/24` ||
	ipvlanIpam?.gateway !== `192.168.${ipvlanEnv.VLAN_TAG}.1`
) {
	throw new Error('The ipvlan overlay does not match the stack VLAN networking convention.');
}

console.log('Standard and optional ipvlan production Compose configurations verified.');
