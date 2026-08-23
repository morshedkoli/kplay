// node lib/library/match.test.js
import assert from 'node:assert/strict';
import { matchAndStore, rematchUnmatchedMovies } from './match.js';

// Fake collections capturing inserts, and a fake TMDb — matchAndStore takes
// them as injected deps so this test needs neither Mongo nor network.
function fakeDeps({ tmdbResult }) {
  const mediaDocs = [];
  const episodeDocs = [];
  return {
    mediaDocs,
    episodeDocs,
    mediaCollection: async () => ({
      insertOne: async (doc) => {
        const _id = `media-${mediaDocs.length}`;
        mediaDocs.push({ _id, ...doc });
        return { insertedId: _id };
      },
      updateOne: async (filter, update) => {
        const doc = mediaDocs.find((d) => d._id === filter._id);
        Object.assign(doc, update.$set);
      },
      findOne: async (filter) => {
        return (
          mediaDocs.find((d) =>
            Object.entries(filter).every(([k, v]) => d[k] === v)
          ) || null
        );
      },
    }),
    episodeCollection: async () => ({
      insertOne: async (doc) => {
        episodeDocs.push(doc);
        return { insertedId: `ep-${episodeDocs.length}` };
      },
    }),
    searchMovie: async () => tmdbResult,
    searchSeries: async () => tmdbResult,
    getSeriesEpisode: async () => ({ title: 'Pilot', description: 'first ep' }),
  };
}

async function main() {
  // Matched movie.
  {
    const deps = fakeDeps({ tmdbResult: { tmdbId: 603, title: 'The Matrix', posterPath: '/x.jpg', description: 'desc' } });
    const result = await matchAndStore(
      { filename: 'The Matrix (1999).mkv', driveFileId: 'drive-1', size: 100 },
      deps
    );
    assert.equal(result.status, 'matched');
    assert.equal(deps.mediaDocs[0].posterPath, '/x.jpg');
    console.log('ok: matched movie stores poster/description');
  }

  // Unmatched (TMDb returns null) still stores the item.
  {
    const deps = fakeDeps({ tmdbResult: null });
    const result = await matchAndStore(
      { filename: 'some_random_video.mp4', driveFileId: 'drive-2', size: 50 },
      deps
    );
    assert.equal(result.status, 'unmatched');
    assert.equal(deps.mediaDocs[0].status, 'unmatched');
    console.log('ok: unmatched item still stored, upload not blocked');
  }

  // Series episode creates both a media doc and an episode doc.
  {
    const deps = fakeDeps({ tmdbResult: { tmdbId: 1396, title: 'Breaking Bad', posterPath: '/y.jpg', description: 'd' } });
    const result = await matchAndStore(
      { filename: 'Breaking Bad S01E02.mkv', driveFileId: 'drive-3', size: 200 },
      deps
    );
    assert.equal(result.status, 'matched');
    assert.equal(deps.episodeDocs.length, 1);
    assert.equal(deps.episodeDocs[0].season, 1);
    assert.equal(deps.episodeDocs[0].episode, 2);
    console.log('ok: series upload creates a media doc and an episode doc');
  }

  // Two episodes of the same series reuse the same media doc (find-or-create).
  {
    const deps = fakeDeps({ tmdbResult: { tmdbId: 1396, title: 'Breaking Bad', posterPath: '/y.jpg', description: 'd' } });
    const result1 = await matchAndStore(
      { filename: 'Breaking Bad S01E01.mkv', driveFileId: 'drive-4', size: 200 },
      deps
    );
    const result2 = await matchAndStore(
      { filename: 'Breaking Bad S01E02.mkv', driveFileId: 'drive-5', size: 200 },
      deps
    );
    assert.equal(result1.mediaId, result2.mediaId);
    assert.equal(deps.mediaDocs.length, 1);
    assert.equal(deps.episodeDocs.length, 2);
    assert.equal(deps.episodeDocs[0].mediaId, deps.mediaDocs[0]._id);
    assert.equal(deps.episodeDocs[1].mediaId, deps.mediaDocs[0]._id);
    console.log('ok: second episode of same series reuses existing media doc');
  }

  // An unmatched parent series doc gets upgraded to 'matched' once a later
  // episode upload produces a TMDb hit for the same series title.
  {
    const deps = fakeDeps({ tmdbResult: null });
    const result1 = await matchAndStore(
      { filename: 'Some Show S01E01.mkv', driveFileId: 'drive-6', size: 200 },
      deps
    );
    assert.equal(result1.status, 'unmatched');
    assert.equal(deps.mediaDocs.length, 1);
    assert.equal(deps.mediaDocs[0].status, 'unmatched');

    deps.searchSeries = async () => ({
      tmdbId: 42,
      title: 'Some Show',
      posterPath: '/z.jpg',
      description: 'now matched',
    });
    const result2 = await matchAndStore(
      { filename: 'Some Show S01E02.mkv', driveFileId: 'drive-7', size: 200 },
      deps
    );

    assert.equal(deps.mediaDocs.length, 1);
    assert.equal(result1.mediaId, result2.mediaId);
    assert.equal(deps.mediaDocs[0].status, 'matched');
    assert.equal(deps.mediaDocs[0].posterPath, '/z.jpg');
    assert.equal(deps.mediaDocs[0].description, 'now matched');
    assert.equal(deps.mediaDocs[0].tmdbId, 42);
    console.log('ok: unmatched parent series doc upgraded to matched on later hit');
  }

  // A movie stored as unmatched by an earlier scan gets a second chance, since
  // the scan route skips driveFileIds it already knows.
  {
    const doc = {
      _id: 'm1',
      type: 'movie',
      status: 'unmatched',
      title: 'Sicario 2015 1080p BluRay x264',
      filename: 'Sicario.2015.1080p.BluRay.x264.mkv',
      tmdbId: null,
      posterPath: null,
    };
    const updates = [];
    const media = {
      find: () => ({ toArray: async () => [doc] }),
      updateOne: async (filter, update) => updates.push({ filter, update }),
    };
    const rematched = await rematchUnmatchedMovies({
      mediaCollection: async () => media,
      searchMovie: async (title, year) => {
        assert.equal(title, 'Sicario');
        assert.equal(year, 2015);
        return { tmdbId: 42, title: 'Sicario', posterPath: '/p.jpg', backdropPath: '/b.jpg', description: 'x' };
      },
    });
    assert.equal(rematched.length, 1);
    assert.equal(updates.length, 1);
    assert.equal(updates[0].update.$set.status, 'matched');
    assert.equal(updates[0].update.$set.posterPath, '/p.jpg');
    console.log('ok: previously-unmatched movie is rematched in place');
  }

  // Still no TMDb hit — leave the doc alone rather than half-updating it.
  {
    const media = {
      find: () => ({ toArray: async () => [{ _id: 'm2', filename: 'nonsense.mkv' }] }),
      updateOne: async () => assert.fail('should not update on a miss'),
    };
    const rematched = await rematchUnmatchedMovies({
      mediaCollection: async () => media,
      searchMovie: async () => null,
    });
    assert.equal(rematched.length, 0);
    console.log('ok: rematch miss leaves the doc untouched');
  }

  console.log('PASS: all match checks passed');
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
