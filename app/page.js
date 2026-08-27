// `/` — the home page for the admin site.
//
// This used to redirect straight to /movies, which meant the app had no front
// door: a signed-out visitor was thrown at a bare password box with no
// indication of what they had reached, and a signed-in one had nowhere that
// answered "is any of this actually working?".
//
// So it is one page with two states. Signed out it explains what the server
// is and offers the way in. Signed in it adds the live numbers — library
// counts, Drive usage, which pieces of configuration are present, what was
// imported most recently.
//
// The split matters: this host is reachable from the internet with a single
// password in front of it, so nothing behind that password — counts, titles,
// storage, configuration — is rendered for a visitor who has not signed in.

import Link from 'next/link';

import { isAdmin } from '@/lib/admin-auth.js';
import { formatBytes, formatDate } from '@/lib/format.js';
import { getOverview } from '@/lib/library/overview.js';
import Logo from './Logo.js';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

const HOW_IT_WORKS = [
  {
    step: '01',
    title: 'Put files in Drive',
    body: 'Copy video files into your Google Drive folder however you like — the web UI, the Drive app, rclone. Nothing uploads through this server.',
  },
  {
    step: '02',
    title: 'Press Sync',
    body: 'The filename decides what a title becomes. "Inception.2010.1080p.mkv" is a movie; "Dark S01E02.mkv" is an episode. TMDb fills in the poster, blurb and episode names.',
  },
  {
    step: '03',
    title: 'Play it anywhere',
    body: 'Bytes stream straight from Drive with HTTP Range support, so seeking works and nothing is ever transcoded. Watch position follows you between the browser and the TV.',
  },
];

const CLIENTS = [
  {
    name: 'Web',
    body: 'This site. Movies and series in separate sections, a detail page each, and a player that resumes where you left off.',
    meta: 'Next.js 15 · App Router',
  },
  {
    name: 'Android TV',
    body: 'A native Compose app for the television: D-pad browse, a focus-following hero, Media3 playback with an on-disk cache, audio-track selection, and its own Sync button.',
    meta: 'Kotlin · Media3 · sideloaded APK',
  },
];

const STACK = [
  ['Storage', 'Google Drive, single account, single folder. The only backend — no pool, no fallback.'],
  ['Metadata', 'MongoDB holds titles, episodes and watch position. Drive holds nothing but bytes.'],
  ['Matching', 'Filenames are parsed locally, then matched against TMDb for artwork and episode data.'],
  ['Streaming', 'Range-capable proxy at /api/media/stream/[id]. Direct play only — the server never transcodes.'],
  ['Auth', 'One admin password for the browser, one shared device key for the TV app.'],
];

export default async function HomePage() {
  const admin = await isAdmin();
  const overview = admin ? await getOverview() : null;

  return (
    <main className="mx-auto max-w-5xl px-6 py-14 sm:py-20">
      <Hero admin={admin} />

      {admin && overview ? <Dashboard overview={overview} /> : null}

      <Section title="How it works" id="how-it-works">
        <div className="grid gap-4 sm:grid-cols-3">
          {HOW_IT_WORKS.map((item) => (
            <Card key={item.step}>
              <span className="text-xs font-semibold tracking-widest text-[var(--accent)]">
                {item.step}
              </span>
              <h3 className="mt-2 text-sm font-semibold text-[var(--ink)]">{item.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-[var(--ink-soft)]">{item.body}</p>
            </Card>
          ))}
        </div>
      </Section>

      <Section title="Clients">
        <div className="grid gap-4 sm:grid-cols-2">
          {CLIENTS.map((client) => (
            <Card key={client.name}>
              <h3 className="text-sm font-semibold text-[var(--ink)]">{client.name}</h3>
              <p className="mt-2 text-sm leading-relaxed text-[var(--ink-soft)]">{client.body}</p>
              <p className="mt-3 text-xs text-[var(--ink-soft)]/70">{client.meta}</p>
            </Card>
          ))}
        </div>
      </Section>

      <Section title="Under the hood">
        <dl className="divide-y divide-[var(--border)] overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
          {STACK.map(([term, detail]) => (
            <div key={term} className="grid gap-1 px-5 py-4 sm:grid-cols-[9rem_1fr] sm:gap-4">
              <dt className="text-sm font-medium text-[var(--ink)]">{term}</dt>
              <dd className="text-sm leading-relaxed text-[var(--ink-soft)]">{detail}</dd>
            </div>
          ))}
        </dl>
      </Section>

      <p className="mt-14 text-xs text-[var(--ink-soft)]/70">
        Single account, single Drive, one shared library. There are no per-user spaces here by
        design.
      </p>
    </main>
  );
}

/* ── page furniture ─────────────────────────────────────────────────────── */

function Hero({ admin }) {
  return (
    <header>
      <Logo size={48} rounded="rounded-2xl" />
      <h1 className="mt-6 text-3xl font-semibold tracking-tight text-[var(--ink)] sm:text-4xl">
        kPlay
      </h1>
      <p className="mt-3 max-w-2xl text-base leading-relaxed text-[var(--ink-soft)]">
        A private media server for one household. Video files live in your own Google Drive; this
        app matches them against TMDb, gives them posters and episode lists, and streams them back
        to a browser or a television without transcoding a frame.
      </p>

      <div className="mt-7 flex flex-wrap items-center gap-3">
        {admin ? (
          <>
            <Link
              href="/movies"
              className="rounded-lg bg-[var(--accent)] px-4 py-2.5 text-sm font-medium text-white transition hover:opacity-90"
            >
              Open the library
            </Link>
            <Link
              href="/admin/usage"
              className="rounded-lg border border-[var(--border)] px-4 py-2.5 text-sm font-medium text-[var(--ink-soft)] transition hover:border-[var(--ink-soft)] hover:text-[var(--ink)]"
            >
              Drive usage
            </Link>
          </>
        ) : (
          <>
            {/* The only action a signed-out visitor has. Everything else on
                this page is description, so this is the one thing that should
                look like a button. */}
            <Link
              href="/login?next=%2F"
              className="rounded-lg bg-[var(--accent)] px-4 py-2.5 text-sm font-medium text-white transition hover:opacity-90"
            >
              Sign in as admin
            </Link>
            <a
              href="#how-it-works"
              className="rounded-lg border border-[var(--border)] px-4 py-2.5 text-sm font-medium text-[var(--ink-soft)] transition hover:border-[var(--ink-soft)] hover:text-[var(--ink)]"
            >
              How it works
            </a>
          </>
        )}
      </div>

      {!admin ? (
        <p className="mt-4 text-xs text-[var(--ink-soft)]/70">
          The library, storage figures and server status are only shown once you are signed in.
        </p>
      ) : null}
    </header>
  );
}

function Section({ title, id, children }) {
  return (
    <section id={id} className="mt-14 scroll-mt-8">
      <h2 className="text-sm font-semibold uppercase tracking-widest text-[var(--ink-soft)]">
        {title}
      </h2>
      <div className="mt-4">{children}</div>
    </section>
  );
}

function Card({ children }) {
  return (
    <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
      {children}
    </div>
  );
}

/* ── the signed-in half ─────────────────────────────────────────────────── */

function Dashboard({ overview }) {
  const { stats, statsError, usage, usageError, recent, configuration } = overview;

  return (
    <>
      <Section title="Library">
        {stats ? (
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="Movies" value={stats.movies} />
            <Stat label="Series" value={stats.series} />
            <Stat label="Episodes" value={stats.episodes} />
            <Stat label="Total size" value={formatBytes(stats.bytes)} />
          </div>
        ) : (
          <Problem>Couldn&apos;t read the library{statsError ? `: ${statsError}` : ''}</Problem>
        )}

        {stats?.unmatched ? (
          <p className="mt-3 text-sm text-[var(--ink-soft)]">
            {stats.unmatched} title{stats.unmatched === 1 ? '' : 's'} never matched on TMDb.
            Pressing <span className="text-[var(--ink)]">Sync</span> runs the matcher over them
            again, which is worth doing after a rename.
          </p>
        ) : null}
      </Section>

      <Section title="Drive storage">
        {usage ? <Usage usage={usage} /> : <Problem>Drive unreachable{usageError ? `: ${usageError}` : ''}</Problem>}
      </Section>

      <Section title="Server status">
        <ul className="divide-y divide-[var(--border)] overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
          {configuration.map((item) => (
            <li key={item.key} className="flex items-start gap-3 px-5 py-4">
              <span
                aria-hidden
                className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${
                  item.ok ? 'bg-emerald-400' : 'bg-amber-400'
                }`}
              />
              <div>
                <p className="text-sm font-medium text-[var(--ink)]">
                  {item.label}
                  <span className="sr-only">{item.ok ? ' — configured' : ' — not configured'}</span>
                </p>
                <p className="mt-0.5 text-sm text-[var(--ink-soft)]">{item.detail}</p>
              </div>
            </li>
          ))}
        </ul>
      </Section>

      {recent.length ? (
        <Section title="Recently added">
          <ul className="divide-y divide-[var(--border)] overflow-hidden rounded-2xl border border-[var(--border)] bg-[var(--surface)]">
            {recent.map((item) => (
              <li key={item.id}>
                <Link
                  href={`/${item.type === 'series' ? 'series' : 'movies'}/${item.id}`}
                  className="flex items-center justify-between gap-4 px-5 py-3.5 transition hover:bg-[var(--surface-raised)]"
                >
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-medium text-[var(--ink)]">
                      {item.title}
                      {item.year ? (
                        <span className="font-normal text-[var(--ink-soft)]"> ({item.year})</span>
                      ) : null}
                    </span>
                    <span className="mt-0.5 block text-xs text-[var(--ink-soft)]">
                      {item.type === 'series' ? 'Series' : 'Movie'}
                      {item.status === 'unmatched' ? ' · unmatched' : ''}
                    </span>
                  </span>
                  <span className="shrink-0 text-xs text-[var(--ink-soft)]">
                    {formatDate(item.createdAt)}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </Section>
      ) : null}
    </>
  );
}

function Stat({ label, value }) {
  return (
    <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-5">
      <p className="text-xs uppercase tracking-widest text-[var(--ink-soft)]">{label}</p>
      <p className="mt-2 text-2xl font-semibold text-[var(--ink)]">{value}</p>
    </div>
  );
}

function Usage({ usage }) {
  const { usedBytes, limitBytes } = usage;
  const pct =
    limitBytes && limitBytes > 0 ? Math.min(100, Math.round((usedBytes / limitBytes) * 100)) : null;

  return (
    <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6">
      <p className="text-2xl font-semibold text-[var(--ink)]">
        {formatBytes(usedBytes)}
        {limitBytes ? (
          <span className="text-base font-normal text-[var(--ink-soft)]">
            {' '}
            / {formatBytes(limitBytes)}
          </span>
        ) : null}
      </p>
      {pct !== null ? (
        <>
          <div className="mt-4 h-2 w-full overflow-hidden rounded-full bg-[var(--bg)]">
            <div className="h-full rounded-full bg-[var(--accent)]" style={{ width: `${pct}%` }} />
          </div>
          <p className="mt-2 text-sm text-[var(--ink-soft)]">{pct}% of this account used.</p>
        </>
      ) : (
        <p className="mt-3 text-sm text-[var(--ink-soft)]">This account has no storage limit.</p>
      )}
    </div>
  );
}

function Problem({ children }) {
  return (
    <p className="rounded-2xl bg-red-500/10 px-5 py-4 text-sm text-red-400">{children}</p>
  );
}
