// Parses an uploaded filename into a library-matchable candidate.
// Targets Jellyfin-style naming: "Title (Year).ext" for movies,
// "Title SxxEyy ....ext" (with optional " - " separators) for series.
// A filename that matches neither falls back to a movie with the cleaned
// filename as title and no year — TMDb matching may still succeed or the
// item lands as unmatched (see docs/superpowers/specs/
// 2026-08-20-media-server-backend-design.md, Error Handling).

// SxxEyy, and the same with a separator between the two halves (S01.E05,
// S01 - E05). The separator is optional so plain S01E05 still matches.
const SERIES_RE = /^(.*?)[\s._-]+S(\d{1,2})[\s._-]*E(\d{1,3})(?!\d)/i;
// The other common convention: 1x04, 01x04.
const SERIES_X_RE = /^(.*?)[\s._-]+(\d{1,2})x(\d{1,3})(?!\d)/i;
const MOVIE_YEAR_RE = /^(.*?)[\s.\-_]*\((\d{4})\)/;
// A year on a series title is release-year noise ("Dark (2017) S01E01"), and
// TMDb's /search/tv matches better without it.
const TRAILING_YEAR_RE = /[\s._-]*\(?(19|20)\d{2}\)?$/;

function clean(title) {
  return title.replace(/[._]+/g, ' ').replace(/\s+/g, ' ').replace(/[\s\-]+$/, '').trim();
}

function cleanSeriesTitle(title) {
  const cleaned = clean(title);
  // Only strip the year when something is left over — a show literally named
  // "1984" must not become the empty string.
  const stripped = clean(cleaned.replace(TRAILING_YEAR_RE, ''));
  return stripped || cleaned;
}

function stripExt(name) {
  return name.replace(/\.[a-zA-Z0-9]+$/, '');
}

export function parseFilename(name) {
  const base = stripExt(name);

  const seriesMatch = SERIES_RE.exec(base) || SERIES_X_RE.exec(base);
  if (seriesMatch) {
    return {
      type: 'series',
      title: cleanSeriesTitle(seriesMatch[1]),
      year: null,
      season: Number(seriesMatch[2]),
      episode: Number(seriesMatch[3]),
    };
  }

  const movieMatch = MOVIE_YEAR_RE.exec(base);
  if (movieMatch) {
    return {
      type: 'movie',
      title: clean(movieMatch[1]),
      year: Number(movieMatch[2]),
      season: null,
      episode: null,
    };
  }

  return { type: 'movie', title: clean(base), year: null, season: null, episode: null };
}
