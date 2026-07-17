import { fileURLToPath } from 'node:url';
import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';

const databaseUrl = process.env['DATABASE_URL'];
if (!databaseUrl) throw new Error('DATABASE_URL is not set');
if (process.env['NODE_ENV'] === 'production' && databaseUrl.includes('runway_dev_password')) {
	throw new Error('DATABASE_URL must not use the development database password in production.');
}

const migrationsFolder = fileURLToPath(new URL('../drizzle', import.meta.url));
const client = postgres(databaseUrl, { max: 1, onnotice: () => undefined });

try {
	await migrate(drizzle(client), { migrationsFolder });
	console.log('Database migrations applied.');
} finally {
	await client.end();
}
