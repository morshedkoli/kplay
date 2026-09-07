// PATCH and DELETE /api/episode/[id] — corrections to one episode.
//
// Season and episode numbers are the part filename parsing gets wrong most
// often, and an episode filed against the wrong show has to be removable
// without touching the file in Drive.

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { episodeCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

function parseId(id) {
  try {
    return new ObjectId(id);
  } catch (err) {
    return null;
  }
}

export async function PATCH(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;
  const _id = parseId(id);
  if (!_id) return Response.json({ error: 'Invalid id' }, { status: 400 });

  let body;
  try {
    body = await request.json();
  } catch (err) {
    return Response.json({ error: 'Expected a JSON body' }, { status: 400 });
  }

  const update = {};
  if ('season' in body) {
    if (!Number.isInteger(body.season) || body.season < 0) {
      return Response.json({ error: 'season must be a whole number' }, { status: 400 });
    }
    update.season = body.season;
  }
  if ('episode' in body) {
    if (!Number.isInteger(body.episode) || body.episode < 0) {
      return Response.json({ error: 'episode must be a whole number' }, { status: 400 });
    }
    update.episode = body.episode;
  }
  if ('title' in body) update.title = body.title;

  if (Object.keys(update).length === 0) {
    return Response.json({ error: 'Nothing to change' }, { status: 400 });
  }

  const episodes = await episodeCollection();
  const result = await episodes.updateOne({ _id }, { $set: update });
  if (result.matchedCount === 0) return Response.json({ error: 'Not found' }, { status: 404 });

  return Response.json({ ok: true, ...update });
}

export async function DELETE(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;
  const _id = parseId(id);
  if (!_id) return Response.json({ error: 'Invalid id' }, { status: 400 });

  const episodes = await episodeCollection();
  const result = await episodes.deleteOne({ _id });
  if (result.deletedCount === 0) return Response.json({ error: 'Not found' }, { status: 404 });

  // The file itself stays in Drive. Removing it from the catalog puts it back
  // in the scan's pending list, which is where a misfiled episode belongs.
  return Response.json({ ok: true });
}
