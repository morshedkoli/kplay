'use client';

// The interactive half of /admin/library.
//
// Scanning is driven from here rather than from a cron: the route pages, so
// this component keeps calling with the cursor it was handed until none comes
// back, showing progress as it goes.

import { useCallback, useEffect, useState } from 'react';

export default function LibraryEditor() {
  const [items, setItems] = useState([]);
  const [scanning, setScanning] = useState(false);
  const [scanNote, setScanNote] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    try {
      const res = await fetch('/api/media/list');
      if (!res.ok) throw new Error(`list -> ${res.status}`);
      const body = await res.json();
      setItems(body.items ?? []);
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function scan() {
    setScanning(true);
    setError('');
    let cursor = null;
    let imported = 0;

    try {
      do {
        const url = cursor
          ? `/api/media/scan?cursor=${encodeURIComponent(cursor)}`
          : '/api/media/scan';
        const res = await fetch(url, { method: 'POST' });
        if (!res.ok) throw new Error(`scan -> ${res.status}`);
        const body = await res.json();
        imported += body.imported?.length ?? 0;
        cursor = body.nextCursor;
        setScanNote(`Imported ${imported} so far, ${body.remaining ?? 0} left.`);
      } while (cursor);

      setScanNote(`Imported ${imported}.`);
      await load();
    } catch (err) {
      setError(err.message);
    } finally {
      setScanning(false);
    }
  }

  async function patch(id, body) {
    setError('');
    const res = await fetch(`/api/media/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const result = await res.json();
    if (!res.ok) {
      setError(result.error ?? `patch -> ${res.status}`);
      return;
    }
    await load();
  }

  const unmatched = items.filter((item) => item.status === 'unmatched');

  return (
    <div className="space-y-8">
      <section>
        <button
          type="button"
          onClick={scan}
          disabled={scanning}
          className="rounded bg-white px-4 py-2 font-medium text-black disabled:opacity-50"
        >
          {scanning ? 'Scanning...' : 'Scan Drive folder'}
        </button>
        {scanNote ? <p className="mt-2 text-sm opacity-70">{scanNote}</p> : null}
        {error ? <p className="mt-2 text-sm text-red-400">{error}</p> : null}
      </section>

      <section>
        <h2 className="mb-3 text-lg font-medium">
          Needs attention ({unmatched.length})
        </h2>
        {unmatched.length === 0 ? (
          <p className="text-sm opacity-70">Everything matched.</p>
        ) : (
          <ul className="space-y-3">
            {unmatched.map((item) => (
              <UnmatchedRow key={item.id || item._id} item={item} onPatch={patch} />
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function UnmatchedRow({ item, onPatch }) {
  const [title, setTitle] = useState(item.title ?? '');
  const [tmdbId, setTmdbId] = useState('');
  const itemId = item.id || item._id;

  return (
    <li className="rounded border border-white/15 p-3">
      <p className="mb-2 font-mono text-xs opacity-60">{item.filename}</p>
      <div className="flex flex-wrap gap-2">
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Title"
          className="rounded bg-black/40 px-2 py-1"
        />
        <input
          value={tmdbId}
          onChange={(e) => setTmdbId(e.target.value)}
          placeholder="TMDb id"
          inputMode="numeric"
          className="w-28 rounded bg-black/40 px-2 py-1"
        />
        <button
          type="button"
          onClick={() =>
            onPatch(itemId, {
              title,
              ...(tmdbId ? { tmdbId: Number(tmdbId) } : {}),
            })
          }
          className="rounded bg-white px-3 py-1 text-black"
        >
          Save
        </button>
        <button
          type="button"
          onClick={() => onPatch(itemId, { type: item.type === 'movie' ? 'series' : 'movie' })}
          className="rounded border border-white/25 px-3 py-1"
        >
          Make {item.type === 'movie' ? 'series' : 'movie'}
        </button>
      </div>
    </li>
  );
}
