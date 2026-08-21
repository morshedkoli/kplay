'use client';

// The poster grid behind both library sections. `kind` picks which half of
// GET /api/media/list it renders — 'movie' for /movies, 'series' for /series
// — and which detail route its tiles link into.

import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';

const COPY = {
  movie: {
    heading: 'Movies',
    basePath: '/movies',
    empty: 'No movies in the catalog yet.',
    addLabel: 'Add your first movie →',
    noun: (n) => `${n} ${n === 1 ? 'movie' : 'movies'}`,
  },
  series: {
    heading: 'Series',
    basePath: '/series',
    empty: 'No series in the catalog yet.',
    addLabel: 'Add your first episode →',
    noun: (n) => `${n} ${n === 1 ? 'series' : 'series'}`,
  },
};

function MovieIcon() {
  return (
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      d="M15 10l5-3v10l-5-3M3 6h11a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1Z"
    />
  );
}

function SeriesIcon() {
  return (
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      d="M4 8h16a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Zm4-4 4 4m4-4-4 4"
    />
  );
}

function PosterTile({ item, kind, basePath }) {
  const [broken, setBroken] = useState(false);
  const posterUrl = item.posterPath ? `https://image.tmdb.org/t/p/w342${item.posterPath}` : null;
  const hasPoster = Boolean(posterUrl) && !broken;
  const isSeries = kind === 'series';

  return (
    <Link
      href={`${basePath}/${item._id}`}
      className="group relative aspect-[2/3] overflow-hidden rounded-xl bg-[var(--border)] transition hover:ring-2 hover:ring-[var(--accent)]"
      title={item.title}
    >
      {hasPoster ? (
        <img
          src={posterUrl}
          alt={item.title}
          loading="lazy"
          onError={() => setBroken(true)}
          className="h-full w-full object-cover transition duration-300 group-hover:scale-105"
        />
      ) : (
        <div className="flex h-full w-full flex-col items-center justify-center gap-1.5 px-2 text-center text-[var(--ink-soft)]">
          <svg
            className="h-8 w-8 opacity-60"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={1.6}
          >
            {isSeries ? <SeriesIcon /> : <MovieIcon />}
          </svg>
          <span className="text-[11px]">{item.title}</span>
        </div>
      )}

      <div className="pointer-events-none absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/85 to-transparent p-2 opacity-0 transition group-hover:opacity-100">
        <p className="truncate text-xs font-medium text-white">{item.title}</p>
        {isSeries ? (
          <p className="text-[11px] text-white/70">
            {item.episodeCount} {item.episodeCount === 1 ? 'episode' : 'episodes'}
          </p>
        ) : item.year ? (
          <p className="text-[11px] text-white/70">{item.year}</p>
        ) : null}
      </div>

      {item.status === 'unmatched' ? (
        <span className="absolute left-1.5 top-1.5 rounded-full bg-amber-500 px-1.5 py-0.5 text-[10px] font-medium text-white shadow">
          unmatched
        </span>
      ) : null}
    </Link>
  );
}

export default function LibraryGrid({ kind }) {
  const copy = COPY[kind];
  const [items, setItems] = useState(null);
  const [error, setError] = useState(null);
  const [query, setQuery] = useState('');

  useEffect(() => {
    let cancelled = false;
    fetch('/api/media/list')
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then((data) => {
        if (cancelled) return;
        // The response splits by type already; fall back to filtering `items`
        // so an older/newer response shape still renders.
        const list = data[kind === 'movie' ? 'movies' : 'series'];
        setItems(list ?? (data.items ?? []).filter((i) => i.type === kind));
      })
      .catch((err) => {
        if (!cancelled) setError(err.message);
      });
    return () => {
      cancelled = true;
    };
  }, [kind]);

  const filtered = useMemo(() => {
    if (!items) return [];
    const q = query.trim().toLowerCase();
    if (!q) return items;
    return items.filter((m) => m.title.toLowerCase().includes(q));
  }, [items, query]);

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-6">
      <div className="flex flex-wrap items-center gap-3 py-2">
        <h1 className="text-xl font-semibold text-[var(--ink)]">{copy.heading}</h1>
        {items ? (
          <span className="text-sm text-[var(--ink-soft)]">{copy.noun(items.length)}</span>
        ) : null}

        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Filter by title…"
          className="ml-auto w-full max-w-xs rounded-lg border border-[var(--border)] bg-[var(--surface)] px-3 py-1.5 text-sm text-[var(--ink)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent-soft)]"
        />
        <Link
          href="/add"
          className="rounded-full bg-[var(--accent)] px-3 py-1.5 text-sm font-medium text-white transition hover:opacity-90"
        >
          Add media
        </Link>
      </div>

      {error ? (
        <p className="mb-3 rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">{error}</p>
      ) : null}

      {items === null ? (
        <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-7">
          {Array.from({ length: 14 }).map((_, i) => (
            <div key={i} className="skeleton aspect-[2/3] rounded-xl" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <div className="flex flex-col items-center gap-3 py-20 text-center">
          <p className="text-sm text-[var(--ink-soft)]">{copy.empty}</p>
          <Link href="/add" className="text-sm font-medium text-[var(--accent)] hover:underline">
            {copy.addLabel}
          </Link>
        </div>
      ) : filtered.length === 0 ? (
        <p className="py-20 text-center text-sm text-[var(--ink-soft)]">
          Nothing matches “{query}”.
        </p>
      ) : (
        <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-7">
          {filtered.map((item) => (
            <PosterTile key={item._id} item={item} kind={kind} basePath={copy.basePath} />
          ))}
        </div>
      )}
    </main>
  );
}
