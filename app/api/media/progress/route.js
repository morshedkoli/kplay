// GET/POST /api/media/progress — resume-watch position, keyed by media or
// episode id. Single-user app, so no per-user scoping (see spec).

import { ObjectId } from 'mongodb';

import { requireDeviceOrSession } from '@/lib/auth.js';
import { getCollection } from '@/lib/db.js';

function progressCollection() {
  return getCollection('progress');
}

export async function GET(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const id = new URL(request.url).searchParams.get('id');
  if (!id) return Response.json({ error: 'id is required' }, { status: 400 });

  let doc;
  try {
    const progress = await progressCollection();
    doc = await progress.findOne({ itemId: new ObjectId(id) });
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }
  return Response.json({ positionSeconds: doc?.positionSeconds ?? 0 });
}

export async function POST(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const { id, positionSeconds } = await request.json();
  if (!id || typeof positionSeconds !== 'number') {
    return Response.json({ error: 'id and positionSeconds are required' }, { status: 400 });
  }

  let progress;
  let objectId;
  try {
    objectId = new ObjectId(id);
    progress = await progressCollection();
  } catch (err) {
    return Response.json({ error: 'Invalid id' }, { status: 400 });
  }
  await progress.updateOne(
    { itemId: objectId },
    { $set: { itemId: objectId, positionSeconds, updatedAt: new Date() } },
    { upsert: true }
  );
  return Response.json({ ok: true });
}
