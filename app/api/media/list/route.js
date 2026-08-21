// GET /api/media/list — the library, newest first.
//
// Returns `items` (movies and series interleaved in one createdAt order, what
// the library grid renders) alongside the `movies` / `series` splits kept for
// the Android TV client. Series carry an `episodeCount` so the grid can label
// them without a second round trip.

import { requireDeviceOrSession } from '@/lib/auth.js';
import { episodeCollection, mediaCollection } from '@/lib/models/media.js';

export async function GET(request) {
  const authError = await requireDeviceOrSession(request);
  if (authError) return authError;

  const media = await mediaCollection();
  const docs = await media
    .find(
      {},
      {
        projection: {
          _id: 1,
          type: 1,
          title: 1,
          year: 1,
          posterPath: 1,
          status: 1,
          createdAt: 1,
        },
      }
    )
    .sort({ createdAt: -1 })
    .toArray();

  const seriesDocs = docs.filter((d) => d.type === 'series');

  // One grouped count for every series, rather than a query per row.
  if (seriesDocs.length) {
    const episodes = await episodeCollection();
    const counts = await episodes
      .aggregate([
        { $match: { mediaId: { $in: seriesDocs.map((d) => d._id) } } },
        { $group: { _id: '$mediaId', count: { $sum: 1 } } },
      ])
      .toArray();

    const byId = new Map(counts.map((c) => [String(c._id), c.count]));
    for (const doc of seriesDocs) {
      doc.episodeCount = byId.get(String(doc._id)) ?? 0;
    }
  }

  return Response.json({
    items: docs,
    movies: docs.filter((d) => d.type === 'movie'),
    series: seriesDocs,
  });
}
