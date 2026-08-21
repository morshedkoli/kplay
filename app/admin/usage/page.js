// /admin/usage — Google Drive storage usage for the configured account.

import Link from 'next/link';

import { isAdmin } from '@/lib/admin-auth.js';
import { formatBytes } from '@/lib/format.js';
import { getStorageUsage } from '@/lib/gdrive.js';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

function Shell({ children }) {
  return (
    <main className="mx-auto min-h-screen max-w-lg p-6">
      <h1 className="text-xl font-semibold text-[var(--ink)]">Drive usage</h1>
      {children}
    </main>
  );
}

export default async function AdminUsagePage() {
  const admin = await isAdmin();
  if (!admin) {
    return (
      <Shell>
        <p className="mt-4 text-sm text-[var(--ink-soft)]">
          You need to sign in to view this page.{' '}
          <Link href="/login" className="text-[var(--accent)] underline">
            Sign in
          </Link>
        </p>
      </Shell>
    );
  }

  let usage = null;
  let error = null;
  try {
    usage = await getStorageUsage();
  } catch (err) {
    error = err.message || 'Failed to load Drive usage';
  }

  if (!usage) {
    return (
      <Shell>
        <p className="mt-4 rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">
          Drive not configured{error ? `: ${error}` : ''}
        </p>
      </Shell>
    );
  }

  const { usedBytes, limitBytes } = usage;
  const pct =
    limitBytes && limitBytes > 0 ? Math.min(100, Math.round((usedBytes / limitBytes) * 100)) : null;

  return (
    <Shell>
      <div className="mt-6 rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6">
        <p className="text-sm text-[var(--ink-soft)]">Used</p>
        <p className="mt-1 text-2xl font-semibold text-[var(--ink)]">
          {formatBytes(usedBytes)}
          {limitBytes ? (
            <span className="text-base font-normal text-[var(--ink-soft)]"> / {formatBytes(limitBytes)}</span>
          ) : null}
        </p>
        {pct !== null ? (
          <div className="mt-4 h-2 w-full overflow-hidden rounded-full bg-[var(--bg)]">
            <div
              className="h-full rounded-full bg-[var(--accent)]"
              style={{ width: `${pct}%` }}
            />
          </div>
        ) : null}
        {limitBytes === null ? (
          <p className="mt-4 text-sm text-[var(--ink-soft)]">This account has no storage limit.</p>
        ) : null}
      </div>
    </Shell>
  );
}
