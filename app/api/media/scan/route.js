// POST /api/media/scan — imports whatever is new from every configured source.
//
// Two sources, one pipeline. Google Drive contributes the files you dropped in
// your own folder; DhakaFlix (the ISP's h5ai index at 172.16.50.x) contributes
// its crawled catalog. Neither pass touches file bytes: it lists, skips what
// the library already holds, and runs the same filename -> TMDb -> Mongo
// pipeline. Metadata only, so it stays well inside a function time limit.

import { requireDeviceOrSession } from '@/lib/auth.js';
import { crawl, isDhakaFlixEnabled, isFeatureSized } from '@/lib/dhakaflix.js';
import { isDriveConfigured, listFolderFiles } from '@/lib/gdrive.js';
import { isVideoFile, isVideoFilename } from '@/lib/library/video-types.js';
import { matchAndStore, rematchUnmatchedMovies } from '@/lib/library/match.js';
import { MediaSource, episodeCollection, mediaCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

// DhakaFlix holds far more than one sync can match: TMDb is rate-limited and
// every new title costs a lookup. Importing a bounded slice per run keeps a
// sync to seconds and makes the work resumable — press Sync again for the next
// slice, and `remaining` says how many are still queued.
const MAX_IMPORTS_PER_RUN = Number(process.env.DHAKAFLIX_MAX_IMPORTS || 300);

/** Every file address already represented in the library, movies and episodes. */
async function knownAddresses() {
  const [media, episodes] = [await mediaCollection(), await episodeCollection()];
  const [mediaDriveIds, episodeDriveIds, mediaUrls, episodeUrls] = await Promise.all([
    media.distinct('driveFileId'),
    episodes.distinct('driveFileId'),
    media.distinct('sourceUrl'),
    episodes.distinct('sourceUrl'),
  ]);
  return {
    driveFileIds: new Set([...mediaDriveIds, ...episodeDriveIds].filter(Boolean)),
    sourceUrls: new Set([...mediaUrls, ...episodeUrls].filter(Boolean)),
  };
}

/**
 * Runs the match pipeline over a list of pending files, one at a time.
 *
 * Sequential on purpose: TMDb rate-limits, and series episodes must be matched
 * one at a time or two episodes of the same show race to create duplicate
 * parent docs.
 */
async function importFiles(pending, imported, failed) {
  for (const file of pending) {
    try {
      const result = await matchAndStore(file);
      imported.push({ filename: file.filename, status: result.status, type: result.type });
    } catch (err) {
      // One bad file shouldn't abort the whole scan — report it and continue.
      console.error(`[api/media/scan] failed to import "${file.filename}"`, err);
      failed.push({ filename: file.filename, error: err.message });
    }
  }
}

export async function POST(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const known = await knownAddresses();
  const imported = [];
  const failed = [];
  const sources = {};

  // --- Google Drive -------------------------------------------------------
  if (isDriveConfigured()) {
    let files = [];
    let error = null;
    try {
      files = await listFolderFiles();
    } catch (err) {
      console.error('[api/media/scan] Drive list failed', err);
      error = 'Could not read the Drive folder';
    }

    const pending = files
      .filter((f) => isVideoFile(f) && !known.driveFileIds.has(f.driveFileId))
      .map((f) => ({
        filename: f.name,
        driveFileId: f.driveFileId,
        size: f.size,
        source: MediaSource.GDRIVE,
      }));

    await importFiles(pending, imported, failed);
    sources.gdrive = { scanned: files.length, pending: pending.length, error };
  }

  // --- DhakaFlix ----------------------------------------------------------
  if (isDhakaFlixEnabled()) {
    const pending = [];
    let seen = 0;
    const crawlErrors = [];

    try {
      await crawl({
        onError: (e) => crawlErrors.push(e),
        onFile: (file) => {
          if (!isVideoFilename(file.name)) return;
          seen += 1;
          if (known.sourceUrls.has(file.url)) return;
          if (!isFeatureSized(file)) return;
          pending.push({
            filename: file.name,
            sourceUrl: file.url,
            size: file.size,
            source: MediaSource.DHAKAFLIX,
          });
        },
      });
    } catch (err) {
      // A crawl that dies partway still leaves a usable list — import it.
      console.error('[api/media/scan] DhakaFlix crawl failed', err);
      crawlErrors.push({ url: 'crawl', error: err.message });
    }

    // Oldest-listed first is arbitrary; what matters is that the slice is
    // stable between runs so repeated syncs walk forward instead of
    // re-attempting the same head of the list.
    const slice = pending.slice(0, MAX_IMPORTS_PER_RUN);
    await importFiles(slice, imported, failed);

    sources.dhakaflix = {
      scanned: seen,
      pending: pending.length,
      remaining: Math.max(0, pending.length - slice.length),
      errors: crawlErrors.length,
    };
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

  const scanned = Object.values(sources).reduce((total, s) => total + (s.scanned || 0), 0);
  const remaining = sources.dhakaflix?.remaining || 0;

  return Response.json({
    scanned,
    skipped: scanned - imported.length - failed.length,
    imported,
    rematched,
    failed,
    remaining,
    sources,
  });
}
