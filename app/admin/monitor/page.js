// /admin/monitor — live view of the VPS: memory, swap, CPU, network, and the
// per-stream Drive-versus-client throughput that says which hop is slow.
//
// Server component only checks the session; every number is fetched by the
// client component so the page updates without a reload.

import Link from 'next/link';

import { isAdmin } from '@/lib/admin-auth.js';

import MonitorClient from './MonitorClient.js';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

export default async function AdminMonitorPage() {
  if (!(await isAdmin())) {
    return (
      <main className="mx-auto min-h-screen max-w-lg p-6">
        <h1 className="text-xl font-semibold text-[var(--ink)]">Server monitor</h1>
        <p className="mt-4 text-sm text-[var(--ink-soft)]">
          You need to sign in to view this page.{' '}
          <Link href="/login" className="text-[var(--accent)] underline">
            Sign in
          </Link>
        </p>
      </main>
    );
  }

  return <MonitorClient />;
}
