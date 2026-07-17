import { drizzle } from 'drizzle-orm/postgres-js';
import postgres from 'postgres';
import * as schema from './schema';
import { building } from '$app/environment';
import { env } from '$env/dynamic/private';

const databaseUrl =
	process.env['DATABASE_URL'] ??
	env['DATABASE_URL'] ??
	(building ? 'postgres://runway:runway@127.0.0.1:5432/runway' : undefined);

if (!databaseUrl) throw new Error('DATABASE_URL is not set');
if (
	!building &&
	(process.env['NODE_ENV'] ?? env['NODE_ENV']) === 'production' &&
	databaseUrl.includes('runway_dev_password')
) {
	throw new Error('DATABASE_URL must not use the development database password in production.');
}

const client = postgres(databaseUrl);

export const db = drizzle(client, { schema });
