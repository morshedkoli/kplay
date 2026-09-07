// POST /api/media/[id]/episodes — files the panel could not place on its own.
//
// A folder of loose files rarely parses cleanly into seasons and episodes, so
// the panel lets a person say which title a file belongs to and where in it.
// The file is already in Drive; only the catalog changes here.

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

export async function POST(request, { params }) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id } = await params;

  let mediaId;
  try {
    mediaId = new ObjectId(id);
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }

  let body;
  try {
    body = await request.json();
  } catch (err) {
    return Response.json({ error: 'Expected a JSON body' }, { status: 400 });
  }

  const { driveFileId, season, episode, title = null, size = 0 } = body ?? {};

  if (!driveFileId || typeof driveFileId !== 'string') {
    return Response.json({ error: 'driveFileId is required' }, { status: 400 });
  }
  if (!Number.isInteger(season) || season < 0) {
    return Response.json({ error: 'season must be a whole number' }, { status: 400 });
  }
  if (!Number.isInteger(episode) || episode < 0) {
    return Response.json({ error: 'episode must be a whole number' }, { status: 400 });
  }

  const media = await mediaCollection();
  const parent = await media.findOne({ _id: mediaId });
  if (!parent) return Response.json({ error: 'Not found' }, { status: 404 });
  if (parent.type !== 'series') {
    return Response.json({ error: 'Only a series can hold episodes' }, { status: 400 });
  }

  const episodes = await episodeCollection();

  // The same file assigned twice is a mis-click, not a second episode.
  const already = await episodes.findOne({ driveFileId });
  if (already) {
    return Response.json({ error: 'That file is already an episode' }, { status: 409 });
  }

  const doc = {
    mediaId,
    season,
    episode,
    title,
    driveFileId,
    size,
    createdAt: new Date(),
  };
  const { insertedId } = await episodes.insertOne(doc);

  return Response.json({ ok: true, id: insertedId.toString() }, { status: 201 });
}
