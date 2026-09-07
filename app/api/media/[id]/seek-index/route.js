// PUT /api/media/[id]/seek-index — stores a cue index built somewhere else.
//
// Matroska carries its cues in a place a player cannot reach with the range
// requests it uses for playback, so seeking needs an index built by reading
// the file. Reading the file is exactly what this deployment must never do:
// the bytes would cross a serverless function and the data transfer that the
// direct-play design removes would come straight back.
//
// So the index is built by scripts/index-mkv.mjs on a machine with bandwidth
// to spare, and posted here once. Playback reads it from the catalog.

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function PUT(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;

  let _id;
  try {
    _id = new ObjectId(id);
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }

  let body;
  try {
    body = await request.json();
  } catch (err) {
    return Response.json({ error: 'Expected a JSON body' }, { status: 400 });
  }

  const { seekIndex } = body ?? {};
  if (!Array.isArray(seekIndex) || seekIndex.length === 0) {
    return Response.json({ error: 'seekIndex must be a non-empty array' }, { status: 400 });
  }

  // A title is a movie or an episode and the caller does not know which, so
  // try both rather than making the local script resolve it first.
  const media = await mediaCollection();
  const updated = await media.updateOne({ _id }, { $set: { seekIndex } });
  if (updated.matchedCount === 1) return Response.json({ ok: true });

  const episodes = await episodeCollection();
  const updatedEpisode = await episodes.updateOne({ _id }, { $set: { seekIndex } });
  if (updatedEpisode.matchedCount === 1) return Response.json({ ok: true });

  return Response.json({ error: 'Not found' }, { status: 404 });
}
