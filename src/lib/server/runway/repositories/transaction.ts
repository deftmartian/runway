import { db } from '$lib/server/db';

export type RunwayTransaction = Parameters<Parameters<typeof db.transaction>[0]>[0];
