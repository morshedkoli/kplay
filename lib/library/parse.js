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
// The far more common release naming has no parentheses:
// "Inception.2010.1080p.BluRay.x264-GROUP". Without this the whole string
// became the TMDb query and every such movie landed unmatched — series never
// hit this because SxxEyy already truncates their title.
// Global on purpose: a title can contain a year of its own ("Dial 1975 2026"),
// and it is the *last* plausible year that is the release year.
const MOVIE_YEAR_BARE_RE = /[\s._-]((?:19|20)\d{2})(?![\dp])/g;
// Everything from the first release tag onward is noise for a TMDb query.
// Anchored on a separator and a token boundary so real title words survive.
const JUNK_RE =
  /[\s._-]+(?:\d{3,4}p|4k|uhd|hdr\w*|x26[45]|h[\s._-]?26[45]|hevc|av1|xvid|divx|aac\d*|ac3|eac3|dts(?:[\s._-]?hd)?|truehd|atmos|ddp?[\s._-]?5[\s._-]?1|bluray|blu[\s._-]?ray|brrip|bdrip|webrip|web[\s._-]?dl|hdrip|dvdrip|hdtv|remux|remastered|extended|unrated|proper|repack|imax|multi|dual|subs?)(?![a-z0-9]).*$/i;
// A four-digit number is only a release year if it is plausibly in the past —
// otherwise it is part of the title ("Blade Runner 2049").
const MAX_YEAR = new Date().getFullYear() + 1;

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

/**
 * The last year in the name that is plausibly a release year, with the index of
 * the separator before it. A future year is title text ("Blade Runner 2049"),
 * so it is skipped rather than ending the search.
 */
function lastPlausibleYear(base) {
  let found = null;
  MOVIE_YEAR_BARE_RE.lastIndex = 0;
  for (let m; (m = MOVIE_YEAR_BARE_RE.exec(base)); ) {
    const year = Number(m[1]);
    if (year <= MAX_YEAR) found = { year, index: m.index };
  }
  return found;
}

function stripJunk(title) {
  return title.replace(JUNK_RE, '');
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

  const parenMatch = MOVIE_YEAR_RE.exec(base);
  if (parenMatch) {
    return {
      type: 'movie',
      title: clean(stripJunk(clean(parenMatch[1]))) || clean(parenMatch[1]),
      year: Number(parenMatch[2]),
      season: null,
      episode: null,
    };
  }

  const bareMatch = lastPlausibleYear(base);
  if (bareMatch) {
    const head = clean(base.slice(0, bareMatch.index));
    const title = clean(stripJunk(head)) || head;
    // A leading year with nothing before it must not yield an empty title.
    if (title) {
      return { type: 'movie', title, year: bareMatch.year, season: null, episode: null };
    }
  }

  // No usable year: still drop release tags so the TMDb query is just the title.
  const cleaned = clean(base);
  return {
    type: 'movie',
    title: clean(stripJunk(cleaned)) || cleaned,
    year: null,
    season: null,
    episode: null,
  };
}
