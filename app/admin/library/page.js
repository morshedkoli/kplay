// /admin/library — where a person fixes what the automatic pipeline got wrong.
//
// Scanning matches most files and misreads some. This screen is for the rest:
// correcting a title or TMDb id, and filing loose files into a series with a
// season and episode number.

import { redirect } from 'next/navigation';

import { isAdmin } from '@/lib/admin-auth.js';

import LibraryEditor from './LibraryEditor.js';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

export default async function LibraryPage() {
  if (!(await isAdmin())) redirect('/login');

  return (
    <main className="mx-auto max-w-5xl p-6">
      <h1 className="mb-6 text-2xl font-semibold">Library</h1>
      <LibraryEditor />
    </main>
  );
}
