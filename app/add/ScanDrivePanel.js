'use client';

// Imports files you placed in the Drive folder yourself.
//
// Replaces the old browser upload: bytes go into Drive directly (desktop
// client, drive.google.com, rclone — whatever you like), and this just tells
// the server to look. What each file becomes is decided by its filename, the
// same rule the upload path used; title/year/description come from TMDb
// matching on the server, with no manual override.

import Link from 'next/link';
import { useState } from 'react';
import { useRouter } from 'next/navigation';

export default function ScanDrivePanel() {
  const router = useRouter();
  const [stage, setStage] = useState('idle'); // idle | scanning | done
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);

  async function scan() {
    setError(null);
    setResult(null);
    setStage('scanning');
    try {
      const res = await fetch('/api/media/scan', { method: 'POST' });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        setError(body.error || `Scan failed (HTTP ${res.status})`);
        setStage('idle');
        return;
      }
      setResult(body);
      setStage('done');
      // New items should be visible the moment you navigate to the library.
      router.refresh();
    } catch (err) {
      setError(err.message);
      setStage('idle');
    }
  }

  const imported = result?.imported ?? [];
  const failed = result?.failed ?? [];

  return (
    <main className="mx-auto max-w-2xl p-6 sm:p-10">
      <h1 className="text-xl font-semibold text-[var(--ink)]">Add media</h1>
      <p className="mt-1 text-sm text-[var(--ink-soft)]">
        Put video files in your Drive folder, then scan to import them.
      </p>

      <div className="mt-6 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6">
        <p className="text-sm text-[var(--ink)]">
          Name files so the library can file them correctly:
        </p>
        <ul className="mt-3 space-y-1.5 text-sm text-[var(--ink-soft)]">
          <li>
            <strong className="text-[var(--ink)]">Inception 2010.mkv</strong> becomes a movie
          </li>
          <li>
            <strong className="text-[var(--ink)]">Show 1x02.mkv</strong> becomes an episode, filed
            under its series
          </li>
        </ul>

        <button
          type="button"
          onClick={scan}
          disabled={stage === 'scanning'}
          className="mt-6 rounded-lg bg-[var(--accent)] px-5 py-2.5 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-40"
        >
          {stage === 'scanning' ? 'Scanning…' : 'Scan Drive folder'}
        </button>

        {stage === 'scanning' ? (
          <p className="mt-3 text-sm text-[var(--ink-soft)]">
            Reading the folder and matching titles on TMDb. This can take a moment for a large
            batch.
          </p>
        ) : null}

        {error ? (
          <p className="mt-4 rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">{error}</p>
        ) : null}

        {stage === 'done' ? (
          <div className="mt-5 space-y-3">
            <p className="text-sm text-[var(--ink)]">
              {imported.length === 0
                ? `Nothing new — all ${result.scanned} file(s) in the folder are already in the library.`
                : `Imported ${imported.length} of ${result.scanned} file(s).`}
            </p>

            {imported.length ? (
              <ul className="divide-y divide-[var(--border)] overflow-hidden rounded-xl border border-[var(--border)]">
                {imported.map((item) => (
                  <li
                    key={item.filename}
                    className="flex items-center gap-3 px-3 py-2 text-sm text-[var(--ink)]"
                  >
                    <span className="min-w-0 flex-1 truncate">{item.filename}</span>
                    <span className="shrink-0 text-xs text-[var(--ink-soft)]">
                      {item.type}
                      {item.status === 'unmatched' ? ' · not matched' : ''}
                    </span>
                  </li>
                ))}
              </ul>
            ) : null}

            {failed.length ? (
              <div className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
                <p className="font-medium">Could not import {failed.length} file(s):</p>
                <ul className="mt-1 space-y-0.5">
                  {failed.map((f) => (
                    <li key={f.filename} className="truncate">
                      {f.filename} — {f.error}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            <div className="flex gap-4 pt-1">
              <Link href="/movies" className="text-sm font-medium text-[var(--accent)] hover:underline">
                Go to movies →
              </Link>
              <Link href="/series" className="text-sm font-medium text-[var(--accent)] hover:underline">
                Go to series →
              </Link>
            </div>
          </div>
        ) : null}
      </div>
    </main>
  );
}
