// POST /api/media/scan — imports files you dropped into the Drive folder
// yourself.
//
// This replaces the old upload route. Nothing here touches file bytes: it
// lists the folder, skips anything already in the library, and runs the same
// filename -> TMDb -> Mongo pipeline an upload used to trigger. Metadata only,
// so it finishes in seconds even for a large folder and fits well inside a
// serverless function's time limit.

import { requireDeviceOrSession } from '@/lib/auth.js';
import { listFolderFiles } from '@/lib/gdrive.js';
import { isVideoFile } from '@/lib/library/video-types.js';
import { matchAndStore, rematchUnmatchedMovies } from '@/lib/library/match.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

/** Every driveFileId already represented in the library, movies and episodes. */
async function knownDriveFileIds() {
  const [media, episodes] = [await mediaCollection(), await episodeCollection()];
  const [mediaIds, episodeIds] = await Promise.all([
    media.distinct('driveFileId'),
    episodes.distinct('driveFileId'),
  ]);
  return new Set([...mediaIds, ...episodeIds].filter(Boolean));
}

export async function POST(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  let files;
  try {
    files = await listFolderFiles();
  } catch (err) {
    console.error('[api/media/scan] Drive list failed', err);
    return Response.json({ error: 'Could not read the Drive folder' }, { status: 502 });
  }

  const known = await knownDriveFileIds();
  const pending = files.filter((f) => isVideoFile(f) && !known.has(f.driveFileId));

  const imported = [];
  const failed = [];

  // Sequential on purpose: TMDb rate-limits, and series episodes must be
  // matched one at a time or two episodes of the same show race to create
  // duplicate parent docs.
  for (const file of pending) {
    try {
      const result = await matchAndStore({
        filename: file.name,
        driveFileId: file.driveFileId,
        size: file.size,
      });
      imported.push({ filename: file.name, status: result.status, type: result.type });
    } catch (err) {
      // One bad file shouldn't abort the whole scan — report it and continue.
      console.error(`[api/media/scan] failed to import "${file.name}"`, err);
      failed.push({ filename: file.name, error: err.message });
    }
  }

  // Items already in the library are skipped above, so anything that missed
  // TMDb on an earlier scan needs an explicit second chance here.
  let rematched = [];
  try {
    rematched = await rematchUnmatchedMovies();
  } catch (err) {
    // A rematch failure is not a scan failure — the import above still stands.
    console.error('[api/media/scan] rematch pass failed', err);
  }

  return Response.json({
    scanned: files.length,
    skipped: files.length - pending.length,
    imported,
    rematched,
    failed,
  });
}
