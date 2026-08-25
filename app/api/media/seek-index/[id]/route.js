// GET /api/media/seek-index/[id] — the time-to-byte table a client needs to
// seek in a file the player cannot seek by itself.
//
// ExoPlayer's MatroskaExtractor gives up on a .mkv whose Cues it cannot reach
// through the first SeekHead, and reports the whole file as unseekable — fast
// forward then does nothing, or restarts the film. lib/library/mkv-index.js
// recovers the table over a few Range reads; this route caches the result so
// the work happens once per file rather than once per playback.
//
// `id` may be a media _id (movie) or an episode _id, the same as the stream
// route. A non-Matroska file answers { seekable: false } quickly and harmlessly
// — MP4 and friends carry an index the player already reads.

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { getCollection } from '@/lib/db.js';
import { getFileMetadata, readRange } from '@/lib/gdrive.js';
import { buildMkvIndex } from '@/lib/library/mkv-index.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

// The cluster-scan fallback costs one Drive round trip per cluster, so it is
// the only part of this that can run long. Bounded well under a serverless
// function's limit; what it does not reach is simply left out of the table.
const CLUSTER_SCAN_BUDGET_MS = Number(process.env.SEEK_INDEX_SCAN_BUDGET_MS || 45000);

async function resolveDoc(id) {
  const _id = new ObjectId(id);
  const media = await mediaCollection();
  const mediaDoc = await media.findOne({ _id });
  if (mediaDoc?.driveFileId) return mediaDoc;

  const episodes = await episodeCollection();
  return episodes.findOne({ _id });
}

export async function GET(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;
  let doc;
  try {
    doc = await resolveDoc(id);
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }
  if (!doc?.driveFileId) return Response.json({ error: 'Not found' }, { status: 404 });

  const cache = await getCollection('seekIndex');

  // Keyed on the file as well as the item: replacing a title's file in Drive
  // gives it a new id, and a table built for the old bytes would seek the new
  // ones to the wrong place.
  const cached = await cache.findOne({ itemId: doc._id, driveFileId: doc.driveFileId });
  if (cached && !cached.partial) {
    return Response.json({
      seekable: cached.seekable,
      method: cached.method,
      durationMs: cached.durationMs ?? null,
      cues: cached.cues || [],
      cached: true,
    });
  }

  let meta;
  try {
    meta = await getFileMetadata(doc.driveFileId);
  } catch (err) {
    console.error('[api/media/seek-index] Drive metadata failed', err);
    return Response.json({ error: 'Storage read failed' }, { status: 502 });
  }
  if (!meta.size) {
    // No size means no Range reads, so there is nothing to index against.
    return Response.json({ seekable: false, method: 'no-size', cues: [] });
  }

  let index;
  try {
    index = await buildMkvIndex({
      size: meta.size,
      read: (start, end) => readRange(doc.driveFileId, start, end),
      clusterScanBudgetMs: CLUSTER_SCAN_BUDGET_MS,
    });
  } catch (err) {
    // A file this cannot parse is not a broken request — the player keeps
    // whatever seekability it worked out for itself.
    console.error(`[api/media/seek-index] index build failed for ${doc.driveFileId}`, err);
    return Response.json({ seekable: false, method: 'error', cues: [] });
  }

  await cache.updateOne(
    { itemId: doc._id },
    {
      $set: {
        itemId: doc._id,
        driveFileId: doc.driveFileId,
        seekable: index.seekable,
        method: index.method,
        partial: Boolean(index.partial),
        durationMs: index.durationMs ?? null,
        cues: index.cues,
        builtAt: new Date(),
      },
    },
    { upsert: true }
  );

  return Response.json({
    seekable: index.seekable,
    method: index.method,
    durationMs: index.durationMs ?? null,
    cues: index.cues,
    cached: false,
  });
}
