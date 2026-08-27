'use client';

import { Suspense, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import Logo from '../Logo.js';

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const next = params.get('next') || '/';

  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError(null);

    try {
      const res = await fetch('/api/session', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password }),
      });

      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        setError(body.error || 'Sign in failed');
        return;
      }

      router.replace(next);
      router.refresh();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-[var(--bg)] p-6">
      <div className="w-full max-w-sm rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-8 shadow-xl shadow-black/5">
        <Logo size={44} rounded="rounded-xl" />
        <h1 className="mt-4 text-xl font-semibold text-[var(--ink)]">kPlay</h1>
        <p className="mt-1 text-sm text-[var(--ink-soft)]">Sign in to browse your backups.</p>

        <form onSubmit={submit} className="mt-6 space-y-3">
          <input
            type="password"
            name="password"
            autoFocus
            autoComplete="current-password"
            placeholder="Admin password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="w-full rounded-lg border border-[var(--border)] bg-[var(--bg)] px-3 py-2.5 text-sm text-[var(--ink)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent-soft)]"
          />
          <button
            type="submit"
            disabled={busy || !password}
            className="w-full rounded-lg bg-[var(--accent)] px-3 py-2.5 text-sm font-medium text-white transition hover:opacity-90 disabled:opacity-40"
          >
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
          {error ? (
            <p className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
              {error}
            </p>
          ) : null}
        </form>
      </div>
    </main>
  );
}

export default function LoginPage() {
  // useSearchParams needs a Suspense boundary to keep the route static-safe.
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
