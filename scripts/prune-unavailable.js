// Removes catalog rows for movies that have no file behind them.
//
//   node scripts/prune-unavailable.js          # report only, deletes nothing
//   node scripts/prune-unavailable.js --delete # actually remove them
//
// A movie row with no driveFileId is the one thing the library itself already
// calls unavailable: MediaDetail renders its play button disabled and labelled
// "Unavailable", because there is nothing to stream. Such a row can only come
// from a partial write — the doc was created and the file never landed, or the
// file was deleted through a path that left the doc behind.
//
// Deliberately narrow. A row whose driveFileId points at a file since removed
// from Drive is NOT touched here: confirming that requires a Drive listing, and
// a Drive outage or a wrong folder id would otherwise read as "every file is
// gone" and delete a whole library. Series parents are left alone too — a
// series holds its files on its episodes, so a parent with no driveFileId is
// normal rather than broken.
//
// Report-only unless --delete is passed, and it prints what it will remove
// either way. Deleting a row is not reversible.

import { pathToFileURL } from 'node:url';

import { loadEnv } from '../lib/env.js';
import { closeClient, getDb } from '../lib/db.js';

/** Movie rows with nothing to play: no driveFileId, or an empty one. */
export function unavailableMoviesQuery() {
  return {
    type: 'movie',
    $or: [{ driveFileId: { $exists: false } }, { driveFileId: null }, { driveFileId: '' }],
  };
}

export async function findUnavailableMovies(db) {
  return db
    .collection('media')
    .find(unavailableMoviesQuery(), {
      projection: { _id: 1, title: 1, year: 1, filename: 1, status: 1, createdAt: 1 },
    })
    .sort({ createdAt: 1 })
    .toArray();
}

async function run() {
  loadEnv();
  const shouldDelete = process.argv.includes('--delete');

  const db = await getDb();
  const doomed = await findUnavailableMovies(db);
  const total = await db.collection('media').countDocuments({ type: 'movie' });

  if (!doomed.length) {
    console.log(`Nothing to remove — all ${total} movies have a file behind them.`);
    await closeClient();
    return;
  }

  console.log(`${doomed.length} of ${total} movies have no file behind them:\n`);
  for (const doc of doomed) {
    const year = doc.year ? ` (${doc.year})` : '';
    console.log(`  ${doc.title}${year}  [${doc.status}]`);
    if (doc.filename) console.log(`    ${doc.filename}`);
  }

  if (!shouldDelete) {
    console.log(`\nReport only. Re-run with --delete to remove these ${doomed.length} rows.`);
    await closeClient();
    return;
  }

  // Delete by id rather than re-running the query: what gets removed is then
  // exactly what was listed above, even if something else wrote to the
  // collection in between.
  const result = await db
    .collection('media')
    .deleteMany({ _id: { $in: doomed.map((d) => d._id) } });
  console.log(`\nRemoved ${result.deletedCount} rows.`);

  // Their watch positions are now orphaned, and would be handed to whatever
  // future row happened to reuse the id.
  const progress = await db
    .collection('progress')
    .deleteMany({ itemId: { $in: doomed.map((d) => d._id) } });
  if (progress.deletedCount) console.log(`Removed ${progress.deletedCount} orphaned progress rows.`);

  await closeClient();
}

// Only self-execute when invoked directly, not when imported.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  run().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
