// Smoke test for the MongoDB connection.
//
// Run with: npm run test:db
// Requires MONGODB_URI (and optionally MONGODB_DB_NAME) in .env.local.
//
// Ensures the indexes, then inserts a dummy media record, reads it back,
// deletes it, and reports.

import { randomUUID } from 'node:crypto';

import { loadEnv } from '../lib/env.js';
import { closeClient, getDb } from '../lib/db.js';
import { createIndexes } from '../lib/migrations/001-indexes.js';
import { MediaStatus, mediaCollection } from '../lib/models/media.js';

async function main() {
  loadEnv();

  const db = await getDb();
  console.log(`connected to database: ${db.databaseName}`);

  await createIndexes(db);
  console.log('indexes ensured');

  const media = await mediaCollection();
  const filename = `smoketest-${randomUUID()}.mkv`;

  const { insertedId } = await media.insertOne({
    type: 'movie',
    title: 'Smoke Test',
    year: null,
    driveFileId: `smoketest-${randomUUID()}`,
    size: 1234,
    filename,
    tmdbId: null,
    posterPath: null,
    description: '',
    status: MediaStatus.UNMATCHED,
    createdAt: new Date(),
  });
  console.log(`inserted media doc: ${insertedId}`);

  const readBack = await media.findOne({ _id: insertedId });
  if (!readBack) throw new Error('inserted doc did not read back');
  console.log(`read back: ${readBack.title} (${readBack.status})`);

  await media.deleteOne({ _id: insertedId });
  console.log('deleted smoke-test doc');

  console.log('OK');
  await closeClient();
}

main().catch(async (err) => {
  console.error(err);
  await closeClient().catch(() => {});
  process.exit(1);
});
