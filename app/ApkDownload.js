'use client';

import { useEffect, useState } from 'react';

/**
 * Sidebar link for the Android TV build.
 *
 * A download, not a destination, so it sits with Sync and Sign out below the
 * navigation rather than among the sections.
 *
 * It asks /api/apk what is staged and shows the version and size, because
 * "download the app" with no version is unanswerable from the sofa: the whole
 * question when sideloading is whether the television already has this build.
 * When nothing is staged the link is not rendered at all — an entry that
 * 404s is worse than no entry.
 */
export default function ApkDownload() {
  const [info, setInfo] = useState(null);

  useEffect(() => {
    let cancelled = false;
    fetch('/api/apk?meta=1')
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => {
        if (!cancelled && data?.available) setInfo(data);
      })
      .catch(() => {
        // A sidebar link is not worth an error message; staying hidden is the
        // right failure.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!info) return null;

  const mb = (info.sizeBytes / 1024 / 1024).toFixed(1);
  const label = info.versionName ? `Android TV v${info.versionName}` : 'Android TV app';

  return (
    <a
      href="/api/apk"
      download
      title={`${label} · ${mb} MB${info.builtAt ? ` · built ${info.builtAt.slice(0, 10)}` : ''}`}
      className="mx-2 mt-1 flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-[var(--ink-soft)] transition hover:bg-[var(--surface-raised)] hover:text-[var(--ink)]"
    >
      <svg
        className="h-5 w-5 shrink-0"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={1.8}
      >
        <path strokeLinecap="round" strokeLinejoin="round" d="M12 3v11m0 0 4-4m-4 4-4-4" />
        <path strokeLinecap="round" strokeLinejoin="round" d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />
      </svg>
      <span className="hidden min-w-0 flex-1 sm:block">
        <span className="block truncate">{label}</span>
        <span className="block text-xs text-[var(--ink-soft)]/70">APK · {mb} MB</span>
      </span>
    </a>
  );
}
