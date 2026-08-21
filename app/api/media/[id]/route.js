// GET    /api/media/[id] — item detail. For a series, includes its episodes.
// DELETE /api/media/[id] — removes the catalog doc(s) and the underlying
//                          Drive file(s). Movie: one doc, one file. Series:
//                          the parent doc plus every episode and its file.

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { deleteFile } from '@/lib/gdrive.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export async function GET(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;
  let media;
  let doc;
  try {
    media = await mediaCollection();
    doc = await media.findOne({ _id: new ObjectId(id) });
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }
  if (!doc) return Response.json({ error: 'Not found' }, { status: 404 });

  if (doc.type === 'series') {
    const episodes = await episodeCollection();
    const eps = await episodes
      .find({ mediaId: doc._id })
      .sort({ season: 1, episode: 1 })
      .toArray();
    return Response.json({ ...doc, episodes: eps });
  }

  return Response.json(doc);
}

export async function DELETE(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;
  let media;
  let doc;
  try {
    media = await mediaCollection();
    doc = await media.findOne({ _id: new ObjectId(id) });
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }
  if (!doc) return Response.json({ error: 'Not found' }, { status: 404 });

  // Drive first, catalog second. A swallowed Drive failure would strand the
  // bytes: the doc is the only record of driveFileId, so once it is gone the
  // file is unreachable and un-deletable, silently eating quota forever.
  // Leaving the catalog row in place keeps the delete retryable instead.
  // deleteFile() already treats a missing Drive file as success, so an
  // already-deleted file never blocks the catalog cleanup.
  if (doc.type === 'series') {
    const episodes = await episodeCollection();
    const eps = await episodes.find({ mediaId: doc._id }).toArray();

    const deletedEpisodeIds = [];
    let failed = 0;
    for (const ep of eps) {
      if (!ep.driveFileId) {
        deletedEpisodeIds.push(ep._id);
        continue;
      }
      try {
        await deleteFile(ep.driveFileId);
        deletedEpisodeIds.push(ep._id);
      } catch (err) {
        console.error(`[api/media/${id}] Drive delete failed for episode ${ep._id}`, err);
        failed += 1;
      }
    }

    // Drop the episodes whose bytes really are gone, so a retry only reworks
    // the ones that failed.
    if (deletedEpisodeIds.length) {
      await episodes.deleteMany({ _id: { $in: deletedEpisodeIds } });
    }
    if (failed) {
      return Response.json(
        { error: 'Storage delete failed', failedEpisodes: failed },
        { status: 502 }
      );
    }
  } else if (doc.driveFileId) {
    try {
      await deleteFile(doc.driveFileId);
    } catch (err) {
      console.error(`[api/media/${id}] Drive delete failed`, err);
      return Response.json({ error: 'Storage delete failed' }, { status: 502 });
    }
  }

  await media.deleteOne({ _id: doc._id });
  return Response.json({ ok: true });
}
