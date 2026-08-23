'use client';

// Pulls in everything new from the Drive folder — the whole of what the old
// /add page did, minus the page. Because there is nothing to configure (the
// filename decides what a file becomes, and TMDb fills the rest in), the
// import never needed a screen of its own; it is one action, so it lives in
// the sidebar next to the sections it updates.
//
// POST /api/media/scan also runs a rematch pass over movies that missed TMDb
// on an earlier run, so pressing this after a parser fix heals old rows.

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';

/** Grids listen for this so a sync updates them without a navigation. */
export const LIBRARY_UPDATED_EVENT = 'kdrive:library-updated';

const CLEAR_STATUS_AFTER_MS = 8000;

function summarize({ scanned = 0, imported = [], rematched = [], failed = [] }) {
  const parts = [];
  if (imported.length) parts.push(`${imported.length} added`);
  if (rematched.length) parts.push(`${rematched.length} matched`);
  if (failed.length) parts.push(`${failed.length} failed`);
  if (!parts.length) return `Up to date — ${scanned} file${scanned === 1 ? '' : 's'}`;
  return parts.join(' · ');
}

/**
 * `variant` picks the shell: 'sidebar' is the nav row, 'link' is the inline
 * call to action an empty library shows. Both drive the same request.
 */
export default function SyncButton({ variant = 'sidebar' }) {
  const router = useRouter();
  const [syncing, setSyncing] = useState(false);
  const [status, setStatus] = useState(null); // { tone: 'ok'|'error', text }
  const timer = useRef(null);
  // A sync outlives the empty-state button that started it, since a successful
  // one replaces the empty state with a grid — don't setState after unmount.
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      clearTimeout(timer.current);
    };
  }, []);

  const sync = useCallback(async () => {
    if (syncing) return;
    clearTimeout(timer.current);
    setStatus(null);
    setSyncing(true);
    try {
      const res = await fetch('/api/media/scan', { method: 'POST' });
      const body = await res.json().catch(() => ({}));
      if (!alive.current) return;
      if (!res.ok) {
        setStatus({ tone: 'error', text: body.error || `Sync failed (HTTP ${res.status})` });
        return;
      }
      setStatus({ tone: 'ok', text: summarize(body) });
      window.dispatchEvent(new CustomEvent(LIBRARY_UPDATED_EVENT));
      router.refresh();
    } catch (err) {
      if (alive.current) setStatus({ tone: 'error', text: err.message });
    } finally {
      if (alive.current) {
        setSyncing(false);
        timer.current = setTimeout(() => alive.current && setStatus(null), CLEAR_STATUS_AFTER_MS);
      }
    }
  }, [router, syncing]);

  if (variant === 'link') {
    return (
      <div className="flex flex-col items-center gap-2">
        <button
          type="button"
          onClick={sync}
          disabled={syncing}
          className="text-sm font-medium text-[var(--accent)] transition hover:underline disabled:opacity-40"
        >
          {syncing ? 'Syncing…' : 'Sync from Drive →'}
        </button>
        {status ? (
          <span
            className={`text-xs ${status.tone === 'error' ? 'text-red-400' : 'text-[var(--ink-soft)]'}`}
          >
            {status.text}
          </span>
        ) : null}
      </div>
    );
  }

  return (
    <div className="px-2">
      <button
        type="button"
        onClick={sync}
        disabled={syncing}
        title="Import new files from your Drive folder"
        className="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-[var(--ink-soft)] transition hover:bg-[var(--surface-raised)] hover:text-[var(--ink)] disabled:opacity-60"
      >
        <svg
          className={`h-5 w-5 shrink-0 ${syncing ? 'animate-spin' : ''}`}
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={1.8}
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M20 11a8 8 0 0 0-14.2-4.9M4 13a8 8 0 0 0 14.2 4.9M20 4v5h-5M4 20v-5h5"
          />
        </svg>
        <span className="hidden sm:inline">{syncing ? 'Syncing…' : 'Sync'}</span>
      </button>

      {status ? (
        <p
          className={`mt-1 hidden px-3 text-[11px] leading-snug sm:block ${
            status.tone === 'error' ? 'text-red-400' : 'text-[var(--ink-soft)]'
          }`}
        >
          {status.text}
        </p>
      ) : null}
    </div>
  );
}
