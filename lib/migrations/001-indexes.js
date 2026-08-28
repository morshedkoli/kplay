// MongoDB index migration.
//
// Run with: npm run migrate
//
// Verify afterwards in mongosh:
//   use kdrive
//   db.media.getIndexes()
//   db.episode.getIndexes()
//   db.progress.getIndexes()

import { pathToFileURL } from 'node:url';

import { loadEnv } from '../env.js';
import { closeClient, getDb } from '../db.js';

export async function createIndexes(db) {
  const created = [];

  created.push(
    await db.collection('media').createIndex({ status: 1 }, { name: 'status_1' })
  );
  created.push(
    await db.collection('media').createIndex({ type: 1, title: 1 }, { name: 'type_1_title_1' })
  );
  created.push(
    await db
      .collection('media')
      .createIndex({ driveFileId: 1 }, { unique: true, sparse: true, name: 'driveFileId_unique' })
  );
  created.push(
    await db
      .collection('episode')
      .createIndex({ mediaId: 1, season: 1, episode: 1 }, { name: 'mediaId_1_season_1_episode_1' })
  );
  created.push(
    await db
      .collection('episode')
      .createIndex({ driveFileId: 1 }, { unique: true, sparse: true, name: 'driveFileId_unique' })
  );
  created.push(
    await db
      .collection('progress')
      .createIndex({ itemId: 1 }, { unique: true, name: 'itemId_unique' })
  );
  // /api/media/watching sorts the whole collection by recency on every visit
  // to the TV home screen, so that sort gets its own index rather than a
  // collection scan that grows with every title ever opened.
  created.push(
    await db.collection('progress').createIndex({ updatedAt: -1 }, { name: 'updatedAt_desc' })
  );
  // One seek table per library item, looked up on every playback.
  created.push(
    await db
      .collection('seekIndex')
      .createIndex({ itemId: 1 }, { unique: true, name: 'itemId_unique' })
  );

  return created;
}

async function run() {
  loadEnv();
  const db = await getDb();
  const created = await createIndexes(db);
  console.log(`Indexes ensured on ${db.databaseName}: ${created.join(', ')}`);
  await closeClient();
}

// Only self-execute when invoked directly, not when imported.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  run().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
