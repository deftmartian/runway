import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:net';
import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';
import {
	assertFinalMigrationState,
	migrationIntegrity,
	releasedV001Final,
	releasedV001ViaV012Final
} from './migration-state.mjs';

const sourceImage = process.env['RUNWAY_COMPOSE_TEST_IMAGE'] ?? 'runway:latest';
const suffix = `${process.pid}-${randomBytes(4).toString('hex')}`;
const project = `runway-lifecycle-${suffix}`;
const beforeImage = `runway-compose-lifecycle:${suffix}-before`;
const afterImage = `runway-compose-lifecycle:${suffix}-after`;
const port = await availablePort();
let databasePort = await availablePort();
while (databasePort === port) databasePort = await availablePort();
const databasePassword = randomBytes(24).toString('hex');
const runtimeDatabasePassword = randomBytes(24).toString('hex');
const authSecret = `runway-secret-v1_${randomBytes(32).toString('base64url')}`;
const releasedV001ViaV012Entries = [
	...migrationIntegrity.releasedV001.entries,
	...migrationIntegrity.releasedV012.entries.slice(migrationIntegrity.releasedV001.entries.length)
];
const releasedUpgradeScenarios = [
	{
		label: 'exact released v0.0.1',
		fixtureFolders: ['tests/fixtures/migrations/v0.0.1/drizzle'],
		initialEntries: migrationIntegrity.releasedV001.entries,
		finalEntries: releasedV001Final,
		probeId: 'compose-v001-upgrade-probe',
		probeEmail: 'compose-v001-probe@example.invalid'
	},
	{
		label: 'v0.0.1 database upgraded by released v0.1.2',
		fixtureFolders: [
			'tests/fixtures/migrations/v0.0.1/drizzle',
			'tests/fixtures/migrations/v0.1.2/drizzle'
		],
		initialEntries: releasedV001ViaV012Entries,
		finalEntries: releasedV001ViaV012Final,
		probeId: 'compose-v012-upgrade-probe',
		probeEmail: 'compose-v012-probe@example.invalid'
	}
];
const composeArguments = [
	'compose',
	'-p',
	project,
	'-f',
	'compose.yaml',
	'-f',
	'deploy/compose.production.yaml',
	'-f',
	'deploy/compose.lifecycle-test.yaml'
];
const baseEnvironment = {
	...process.env,
	POSTGRES_PASSWORD: databasePassword,
	APP_DATABASE_URL: `postgres://runway_runtime:${runtimeDatabasePassword}@db:5432/runway`,
	MIGRATION_DATABASE_URL: `postgres://runway:${databasePassword}@db:5432/runway`,
	BETTER_AUTH_SECRET: authSecret,
	ORIGIN: 'https://runway-compose-lifecycle.example.test',
	PUBLIC_APP_ORIGIN: 'https://runway-compose-lifecycle.example.test',
	RUNWAY_PORT: String(port),
	RUNWAY_TEST_DATABASE_PORT: String(databasePort)
};
let activeEnvironment = { ...baseEnvironment, RUNWAY_IMAGE: beforeImage };

try {
	runDocker(['image', 'inspect', sourceImage], process.env, true);
	runDocker(['tag', sourceImage, beforeImage]);
	runDocker(['tag', sourceImage, afterImage]);

	console.log('Verifying a fresh whole-project Compose deployment.');
	runCompose(['up', '-d', '--wait', 'db'], activeEnvironment);
	provisionRuntimeDatabaseRole(activeEnvironment, runtimeDatabasePassword);
	runCompose(['up', '-d', '--wait'], activeEnvironment);
	const fresh = inspectProject(activeEnvironment, beforeImage);
	await assertReady(port);

	console.log('Verifying a whole-project image update.');
	activeEnvironment = { ...baseEnvironment, RUNWAY_IMAGE: afterImage };
	runCompose(['up', '-d', '--wait'], activeEnvironment);
	const updated = inspectProject(activeEnvironment, afterImage);
	await assertReady(port);
	assertReplaced(fresh, updated);

	console.log('Verifying an idempotent same-image redeploy.');
	runCompose(['up', '-d', '--wait'], activeEnvironment);
	const rerun = inspectProject(activeEnvironment, afterImage);
	await assertReady(port);
	assertStable(updated, rerun);

	for (const scenario of releasedUpgradeScenarios) {
		console.log(`Verifying a whole-project upgrade from the ${scenario.label} database.`);
		runCompose(['down', '--volumes', '--remove-orphans'], activeEnvironment);
		runCompose(['up', '-d', '--wait', 'db'], activeEnvironment);
		await seedReleasedDatabase(activeEnvironment, scenario);
		provisionRuntimeDatabaseRole(activeEnvironment, runtimeDatabasePassword);
		runCompose(['up', '-d', '--wait'], activeEnvironment);
		const releasedUpgrade = inspectProject(activeEnvironment, afterImage);
		await assertReady(port);
		await assertReleasedUpgrade(activeEnvironment, scenario);

		console.log(`Verifying an idempotent ${scenario.label} upgrade redeploy.`);
		runCompose(['up', '-d', '--wait'], activeEnvironment);
		const releasedRerun = inspectProject(activeEnvironment, afterImage);
		await assertReady(port);
		await assertReleasedUpgrade(activeEnvironment, scenario);
		assertStable(releasedUpgrade, releasedRerun);
	}

	console.log(
		'Fresh deployment, image update, released v0.0.1 and v0.1.2 upgrades, and idempotent Compose redeploy verified.'
	);
} catch (error) {
	console.error(`Compose lifecycle verification failed: ${error.message}`);
	diagnose();
	process.exitCode = 1;
} finally {
	cleanup();
}

function inspectProject(environment, expectedImage) {
	const state = Object.fromEntries(
		['app', 'worker', 'migrate', 'db'].map((service) => [
			service,
			inspectService(service, environment)
		])
	);

	for (const service of ['app', 'worker']) {
		const container = state[service];
		if (!container.state.Running || container.state.Health?.Status !== 'healthy') {
			throw new Error(`${service} is not running and healthy.`);
		}
		if (container.image !== expectedImage) {
			throw new Error(`${service} is not using the selected image.`);
		}
	}

	if (state.migrate.state.Running || state.migrate.state.ExitCode !== 0) {
		throw new Error('The one-shot migration service did not complete successfully.');
	}
	if (state.migrate.image !== expectedImage) {
		throw new Error('The migration service is not using the selected image.');
	}
	if (!state.db.state.Running || state.db.state.Health?.Status !== 'healthy') {
		throw new Error('The lifecycle database is not running and healthy.');
	}

	return state;
}

function inspectService(service, environment) {
	const id = runCompose(['ps', '-aq', service], environment, true).trim();
	if (!id) throw new Error(`Compose did not create the ${service} service.`);
	const state = JSON.parse(
		runDocker(['inspect', '--format', '{{json .State}}', id], process.env, true)
	);
	const image = runDocker(
		['inspect', '--format', '{{.Config.Image}}', id],
		process.env,
		true
	).trim();
	return { id, image, state };
}

function assertReplaced(before, after) {
	for (const service of ['app', 'worker', 'migrate']) {
		if (before[service].id === after[service].id) {
			throw new Error(`${service} was not recreated for the selected image update.`);
		}
	}
	if (before.db.id !== after.db.id) {
		throw new Error('The database was unnecessarily recreated during the image update.');
	}
}

function assertStable(before, after) {
	for (const service of ['app', 'worker', 'migrate', 'db']) {
		if (before[service].id !== after[service].id) {
			throw new Error(`${service} was unnecessarily recreated by a same-image redeploy.`);
		}
	}
	if (before.migrate.state.FinishedAt === after.migrate.state.FinishedAt) {
		throw new Error('The same-image redeploy did not rerun the idempotent migration job.');
	}
}

async function assertReady(hostPort) {
	const response = await fetch(`http://127.0.0.1:${hostPort}/health/ready`);
	if (!response.ok) {
		throw new Error(`The web readiness endpoint returned HTTP ${response.status}.`);
	}
}

function provisionRuntimeDatabaseRole(environment, password) {
	const sql = `
CREATE ROLE runway_runtime LOGIN PASSWORD '${password}';
REVOKE CONNECT, TEMP ON DATABASE runway FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS drizzle AUTHORIZATION runway;
GRANT CONNECT ON DATABASE runway TO runway_runtime;
GRANT USAGE ON SCHEMA public, drizzle TO runway_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO runway_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA drizzle TO runway_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO runway_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE runway IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO runway_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE runway IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO runway_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE runway IN SCHEMA drizzle
  GRANT SELECT ON TABLES TO runway_runtime;
`;
	runCompose(
		[
			'exec',
			'-T',
			'db',
			'psql',
			'--set',
			'ON_ERROR_STOP=1',
			'--username',
			'runway',
			'--dbname',
			'runway'
		],
		environment,
		true,
		sql
	);
}

async function seedReleasedDatabase(environment, scenario) {
	const databaseUrl = projectDatabaseUrl(environment);
	const sql = postgres(databaseUrl, { max: 1, onnotice: () => undefined });
	try {
		for (const migrationsFolder of scenario.fixtureFolders) {
			await migrate(drizzle(sql), { migrationsFolder });
		}
		await assertLedger(sql, scenario.initialEntries, `${scenario.label} seed`);
		await sql`
			insert into "user" ("id", "name", "email", "email_verified", "created_at", "updated_at")
			values (
				${scenario.probeId},
				'Compose upgrade probe',
				${scenario.probeEmail},
				false,
				now(),
				now()
			)
		`;
	} finally {
		await sql.end();
	}
}

async function assertReleasedUpgrade(environment, scenario) {
	const databaseUrl = projectDatabaseUrl(environment);
	const sql = postgres(databaseUrl, { max: 1, onnotice: () => undefined });
	try {
		await assertLedger(sql, scenario.finalEntries, `${scenario.label} upgrade`);
		await assertFinalMigrationState(sql);
		const [probe] = await sql`
			select "id", "email"
			from "user"
			where "id" = ${scenario.probeId}
		`;
		if (probe?.id !== scenario.probeId || probe.email !== scenario.probeEmail) {
			throw new Error(`The ${scenario.label} Compose upgrade did not preserve existing data.`);
		}
	} finally {
		await sql.end();
	}
}

async function assertLedger(sql, expectedEntries, label) {
	const rows = await sql`
		select "hash", "created_at"::text as "createdAt"
		from drizzle.__drizzle_migrations
		order by "created_at", "id"
	`;
	if (
		rows.length !== expectedEntries.length ||
		rows.some(
			(row, index) =>
				row.hash !== expectedEntries[index]?.hash ||
				row.createdAt !== expectedEntries[index]?.createdAt
		)
	) {
		throw new Error(`${label} does not have its exact ordered migration ledger.`);
	}
}

function projectDatabaseUrl(environment) {
	const configuredPort = environment['RUNWAY_TEST_DATABASE_PORT'];
	if (!configuredPort) throw new Error('Lifecycle database test port is not configured.');
	return `postgres://runway:${encodeURIComponent(databasePassword)}@127.0.0.1:${configuredPort}/runway`;
}

function runCompose(arguments_, environment, capture = false, input) {
	return runDocker([...composeArguments, ...arguments_], environment, capture, input);
}

function runDocker(arguments_, environment = process.env, capture = false, input) {
	return execFileSync('docker', arguments_, {
		cwd: process.cwd(),
		env: environment,
		encoding: 'utf8',
		input,
		stdio: capture ? [input === undefined ? 'ignore' : 'pipe', 'pipe', 'pipe'] : 'inherit',
		timeout: 180_000,
		maxBuffer: 8 * 1024 * 1024
	});
}

function diagnose() {
	try {
		runCompose(['ps', '--all'], activeEnvironment);
		runCompose(
			['logs', '--no-color', '--tail', '120', 'app', 'worker', 'migrate', 'db'],
			activeEnvironment
		);
	} catch (error) {
		console.error(`Could not collect Compose diagnostics: ${error.message}`);
	}
}

function cleanup() {
	try {
		runCompose(['down', '--volumes', '--remove-orphans'], activeEnvironment);
	} catch (error) {
		console.error(`Could not remove the lifecycle Compose project: ${error.message}`);
		process.exitCode = 1;
	}
	try {
		runDocker(['image', 'rm', beforeImage, afterImage], process.env, true);
	} catch (error) {
		console.error(`Could not remove lifecycle image aliases: ${error.message}`);
		process.exitCode = 1;
	}
}

function availablePort() {
	return new Promise((resolve, reject) => {
		const server = createServer();
		server.once('error', reject);
		server.listen(0, '127.0.0.1', () => {
			const address = server.address();
			if (typeof address !== 'object' || address === null) {
				server.close();
				reject(new Error('Could not reserve a loopback port for lifecycle verification.'));
				return;
			}
			server.close((error) => {
				if (error) reject(error);
				else resolve(address.port);
			});
		});
	});
}
