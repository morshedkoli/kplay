'use client';

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';

const LINKS = [
  {
    href: '/movies',
    label: 'Movies',
    icon: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M15 10l5-3v10l-5-3M3 6h11a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1Z"
      />
    ),
  },
  {
    href: '/series',
    label: 'Series',
    icon: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M4 8h16a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1Zm4-4 4 4m4-4-4 4"
      />
    ),
  },
  {
    href: '/add',
    label: 'Add Media',
    icon: <path strokeLinecap="round" strokeLinejoin="round" d="M12 16V4m0 0-4 4m4-4 4 4M5 20h14" />,
  },
  {
    href: '/admin/usage',
    label: 'Usage',
    icon: (
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M4 20V10m6 10V4m6 16v-7"
      />
    ),
  },
];

export default function Sidebar() {
  const pathname = usePathname();
  const router = useRouter();

  async function signOut() {
    await fetch('/api/session', { method: 'DELETE' });
    router.replace('/login');
    router.refresh();
  }

  return (
    <aside className="fixed inset-y-0 left-0 z-20 flex w-16 flex-col border-r border-[var(--border)] bg-[var(--surface)] py-4 sm:w-56">
      <Link href="/movies" className="mb-6 flex items-center gap-2 px-4">
        <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[var(--accent)] text-sm font-bold text-white">
          K
        </span>
        <span className="hidden text-sm font-semibold tracking-tight text-[var(--ink)] sm:inline">
          KDrive
        </span>
      </Link>

      <nav className="flex flex-1 flex-col gap-1 px-2">
        {LINKS.map((link) => {
          const active = pathname === link.href || pathname.startsWith(`${link.href}/`);
          return (
            <Link
              key={link.href}
              href={link.href}
              className={`flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition ${
                active
                  ? 'bg-[var(--accent)] text-white'
                  : 'text-[var(--ink-soft)] hover:bg-[var(--surface-raised)] hover:text-[var(--ink)]'
              }`}
            >
              <svg
                className="h-5 w-5 shrink-0"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth={1.8}
              >
                {link.icon}
              </svg>
              <span className="hidden sm:inline">{link.label}</span>
            </Link>
          );
        })}
      </nav>

      <button
        type="button"
        onClick={signOut}
        className="mx-2 flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-[var(--ink-soft)] transition hover:bg-[var(--surface-raised)] hover:text-[var(--ink)]"
      >
        <svg className="h-5 w-5 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" />
        </svg>
        <span className="hidden sm:inline">Sign out</span>
      </button>
    </aside>
  );
}
