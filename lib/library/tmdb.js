// Thin TMDb v3 REST client — plain fetch, no SDK. Used by lib/library/match.js
// to fill poster/description/season-episode metadata after an upload. A
// missing result here never blocks upload success — see
// docs/superpowers/specs/2026-08-20-media-server-backend-design.md.

const BASE = 'https://api.themoviedb.org/3';

function apiKey() {
  const key = process.env.TMDB_API_KEY;
  if (!key) throw new Error('TMDB_API_KEY is not set');
  return key;
}

async function get(path, params = {}) {
  const url = new URL(`${BASE}${path}`);
  url.searchParams.set('api_key', apiKey());
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);

  const res = await fetch(url);
  if (!res.ok) throw new Error(`TMDb ${path} -> ${res.status}`);
  return res.json();
}

export async function searchMovie(title, year) {
  const data = await get('/search/movie', { query: title, ...(year ? { year } : {}) });
  const hit = data.results?.[0];
  if (!hit) return null;
  return {
    tmdbId: hit.id,
    title: hit.title,
    posterPath: hit.poster_path,
    description: hit.overview || '',
  };
}

export async function searchSeries(title) {
  const data = await get('/search/tv', { query: title });
  const hit = data.results?.[0];
  if (!hit) return null;
  return {
    tmdbId: hit.id,
    title: hit.name,
    posterPath: hit.poster_path,
    description: hit.overview || '',
  };
}

export async function getSeriesEpisode(tmdbId, season, episode) {
  try {
    const data = await get(`/tv/${tmdbId}/season/${season}/episode/${episode}`);
    return { title: data.name || '', description: data.overview || '' };
  } catch {
    return null;
  }
}
