'use client';

// Detail page for one library item, shared by /movies/[id] and /series/[id].
//
// Movie: Play button streams the media _id directly.
// Series: the parent doc holds no bytes — the episode list does. Picking an
// episode streams that episode's _id. Watch progress is keyed by whichever
// id is playing (media _id for a movie, episode _id for an episode), which is
// the same key the Android TV app posts under.

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';

const PROGRESS_POST_INTERVAL_SECONDS = 10;

function formatTimestamp(seconds) {
  const mins = Math.floor(seconds / 60);
  const secs = String(Math.floor(seconds % 60)).padStart(2, '0');
  return `${mins}:${secs}`;
}

function pad2(value) {
  return String(value).padStart(2, '0');
}

export default function MediaDetail({ id }) {
  const router = useRouter();
  const [item, setItem] = useState(null);
  const [error, setError] = useState(null);
  // The id currently loaded into the <video>: the media _id for a movie, an
  // episode _id for a series. null means nothing is playing yet.
  const [playingId, setPlayingId] = useState(null);
  const [resumeSeconds, setResumeSeconds] = useState(0);
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const videoRef = useRef(null);
  const lastPostedRef = useRef(0);

  const isSeries = item?.type === 'series';
  // Back link follows the item's own type, so a /movies/[id] URL that turns
  // out to hold a series still returns you to the section it belongs to.
  const listHref = isSeries ? '/series' : '/movies';

  useEffect(() => {
    fetch(`/api/media/${id}`)
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then(setItem)
      .catch((err) => setError(err.message));
  }, [id]);

  // Progress follows whatever is playing. Before anything is picked, show the
  // movie's own saved position; a series parent has no position of its own.
  const progressId = playingId ?? (item && !isSeries ? id : null);

  useEffect(() => {
    if (!progressId) return undefined;
    let cancelled = false;
    lastPostedRef.current = 0;
    fetch(`/api/media/progress?id=${progressId}`)
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => {
        if (!cancelled) setResumeSeconds(data?.positionSeconds ?? 0);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [progressId]);

  const postProgress = useCallback(() => {
    const video = videoRef.current;
    if (!video || !playingId) return;
    const now = video.currentTime;
    if (Math.abs(now - lastPostedRef.current) < PROGRESS_POST_INTERVAL_SECONDS) return;
    lastPostedRef.current = now;
    fetch('/api/media/progress', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // Duration goes along whenever the browser has decoded it. The TV app's
      // Watching shelf uses it to tell a half-watched title from a finished
      // one, so a film watched here still leaves the shelf correctly there.
      body: JSON.stringify({
        id: playingId,
        positionSeconds: now,
        ...(Number.isFinite(video.duration) && video.duration > 0
          ? { durationSeconds: video.duration }
          : {}),
      }),
    }).catch(() => {});
  }, [playingId]);

  async function remove() {
    setDeleting(true);
    try {
      const res = await fetch(`/api/media/${id}`, { method: 'DELETE' });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        setError(body.error || `Delete failed (HTTP ${res.status})`);
        return;
      }
      router.push(listHref);
    } catch (err) {
      setError(err.message);
    } finally {
      setDeleting(false);
    }
  }

  // Episodes grouped into seasons, in order. Empty for a movie.
  const seasons = useMemo(() => {
    if (!item?.episodes?.length) return [];
    const bySeason = new Map();
    for (const ep of item.episodes) {
      const key = ep.season ?? 0;
      if (!bySeason.has(key)) bySeason.set(key, []);
      bySeason.get(key).push(ep);
    }
    return [...bySeason.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([season, episodes]) => ({
        season,
        episodes: episodes.sort((a, b) => (a.episode ?? 0) - (b.episode ?? 0)),
      }));
  }, [item]);

  if (error && !item) {
    return (
      <main className="p-6">
        <p className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">{error}</p>
      </main>
    );
  }

  if (!item) {
    return (
      <main className="p-6">
        <div className="skeleton h-[42vh] w-full rounded-2xl" />
      </main>
    );
  }

  const episodeCount = item.episodes?.length ?? 0;
  const posterUrl = item.posterPath ? `https://image.tmdb.org/t/p/w500${item.posterPath}` : null;
  const moviePlayable = !isSeries && Boolean(item.driveFileId);
  const resumeLabel = resumeSeconds > 5 ? `Resume at ${formatTimestamp(resumeSeconds)}` : 'Play';
  const playingEpisode = isSeries
    ? item.episodes?.find((ep) => String(ep._id) === String(playingId))
    : null;

  let subtitle;
  if (isSeries) {
    const unmatched = item.status === 'unmatched' ? ' · not matched on TMDb' : '';
    subtitle = `${episodeCount} ${episodeCount === 1 ? 'episode' : 'episodes'}${unmatched}`;
  } else {
    subtitle = item.status === 'unmatched' ? 'Not matched on TMDb' : item.status;
  }

  return (
    <main>
      <div className="relative h-[42vh] min-h-[280px] w-full overflow-hidden">
        {posterUrl ? (
          <img
            src={posterUrl}
            alt=""
            aria-hidden
            className="h-full w-full scale-110 object-cover object-top opacity-40 blur-2xl"
          />
        ) : null}
        <div className="absolute inset-0 bg-gradient-to-t from-[var(--bg)] via-[var(--bg)]/40 to-[var(--bg)]/10" />

        <div className="absolute inset-x-0 bottom-0 flex items-end gap-6 p-6 sm:p-10">
          {posterUrl ? (
            <img
              src={posterUrl}
              alt={item.title}
              className="hidden h-56 w-auto shrink-0 rounded-xl object-cover shadow-2xl sm:block"
            />
          ) : null}

          <div className="min-w-0">
            <h1 className="text-2xl font-semibold text-[var(--ink)] sm:text-4xl">
              {item.title}
              {item.year ? <span className="ml-2 text-[var(--ink-soft)]">({item.year})</span> : null}
            </h1>
            <p className="mt-1 text-sm text-[var(--ink-soft)]">{subtitle}</p>

            {!isSeries && !playingId ? (
              <button
                type="button"
                onClick={() => setPlayingId(id)}
                disabled={!moviePlayable}
                className="mt-4 flex items-center gap-2 rounded-lg bg-[var(--accent)] px-5 py-2.5 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-40"
              >
                <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M8 5v14l11-7z" />
                </svg>
                {moviePlayable ? resumeLabel : 'Unavailable'}
              </button>
            ) : null}
          </div>
        </div>
      </div>

      <div className="mx-auto max-w-3xl p-6 sm:p-10">
        {playingId ? (
          <>
            {playingEpisode ? (
              <p className="mb-2 text-sm font-medium text-[var(--ink)]">
                {`S${pad2(playingEpisode.season)}E${pad2(playingEpisode.episode)}`}
                {playingEpisode.title ? ` — ${playingEpisode.title}` : ''}
              </p>
            ) : null}
            <video
              ref={videoRef}
              key={playingId}
              src={`/api/media/stream/${playingId}`}
              poster={posterUrl || undefined}
              controls
              autoPlay
              preload="auto"
              onLoadedMetadata={() => {
                if (resumeSeconds > 5 && videoRef.current) {
                  videoRef.current.currentTime = resumeSeconds;
                }
              }}
              onTimeUpdate={postProgress}
              className="mb-6 w-full rounded-xl bg-black"
            />
          </>
        ) : null}

        {item.description ? (
          <p className="text-sm leading-relaxed text-[var(--ink)]">{item.description}</p>
        ) : null}

        {isSeries && seasons.length ? (
          <div className="mt-8 space-y-6">
            {seasons.map(({ season, episodes }) => (
              <section key={season}>
                <h2 className="mb-2 text-sm font-semibold text-[var(--ink)]">Season {season}</h2>
                <ul className="divide-y divide-[var(--border)] overflow-hidden rounded-xl border border-[var(--border)]">
                  {episodes.map((ep) => {
                    const active = String(ep._id) === String(playingId);
                    return (
                      <li key={ep._id}>
                        <button
                          type="button"
                          onClick={() => setPlayingId(ep._id)}
                          disabled={!ep.driveFileId}
                          className={`flex w-full items-center gap-3 px-3 py-2.5 text-left text-sm transition disabled:opacity-40 ${
                            active
                              ? 'bg-[var(--accent-soft)] text-[var(--ink)]'
                              : 'text-[var(--ink)] hover:bg-[var(--surface-raised)]'
                          }`}
                        >
                          <span className="w-10 shrink-0 tabular-nums text-[var(--ink-soft)]">
                            {`E${pad2(ep.episode)}`}
                          </span>
                          <span className="min-w-0 flex-1 truncate">
                            {ep.title || `Episode ${ep.episode}`}
                          </span>
                          <svg
                            className="h-4 w-4 shrink-0 text-[var(--accent)]"
                            viewBox="0 0 24 24"
                            fill="currentColor"
                          >
                            <path d="M8 5v14l11-7z" />
                          </svg>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              </section>
            ))}
          </div>
        ) : null}

        {isSeries && !seasons.length ? (
          <p className="mt-8 text-sm text-[var(--ink-soft)]">
            No episodes yet — upload one named like Show S01E01.mkv
          </p>
        ) : null}

        {error ? (
          <p className="mt-4 rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">{error}</p>
        ) : null}

        <div className="mt-8 flex items-center gap-2 border-t border-[var(--border)] pt-6">
          <Link href={listHref} className="text-sm text-[var(--ink-soft)] hover:text-[var(--ink)]">
            {isSeries ? '← Back to series' : '← Back to movies'}
          </Link>

          {confirming ? (
            <span className="ml-auto flex items-center gap-2">
              <span className="text-sm text-[var(--ink-soft)]">
                {isSeries ? 'Remove the series and every episode?' : 'Remove from catalog?'}
              </span>
              <button
                type="button"
                onClick={remove}
                disabled={deleting}
                className="rounded-lg bg-red-600 px-3 py-2 text-sm text-white transition hover:opacity-90 disabled:opacity-40"
              >
                {deleting ? 'Removing…' : 'Yes, remove'}
              </button>
              <button
                type="button"
                onClick={() => setConfirming(false)}
                className="rounded-lg border border-[var(--border)] px-3 py-2 text-sm text-[var(--ink)]"
              >
                Cancel
              </button>
            </span>
          ) : (
            <button
              type="button"
              onClick={() => setConfirming(true)}
              className="ml-auto rounded-lg border border-red-500/30 px-3 py-2 text-sm text-red-400 transition hover:bg-red-500/10"
            >
              Remove
            </button>
          )}
        </div>
      </div>
    </main>
  );
}
