'use client';

// The monitor dashboard.
//
// Reads /api/admin/metrics on a short interval and draws it. Deliberately has
// no charting dependency: the shapes needed are sparklines and bars, both a
// few lines of SVG, and adding a library to a 2 GB box's bundle to draw them
// would be its own small irony.

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';

import { formatBytes } from '@/lib/format.js';

const POLL_MS = 3_000;

/** Bytes/second as the unit people actually think about video in. */
function formatRate(bytesPerSec) {
  if (!bytesPerSec || bytesPerSec < 1) return '0';
  const mbits = (bytesPerSec * 8) / 1_000_000;
  if (mbits >= 1) return `${mbits.toFixed(1)} Mbps`;
  return `${((bytesPerSec * 8) / 1000).toFixed(0)} kbps`;
}

function formatDuration(seconds) {
  if (!Number.isFinite(seconds)) return '—';
  const s = Math.floor(seconds);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m ${s % 60}s`;
}

/**
 * Mean of a sample field over the last `seconds`.
 *
 * Every rate on this page is bursty by nature — Drive fills the read-ahead
 * buffer and then stops while the player drains it — so the newest single
 * sample regularly reads zero on a healthy stream. Averaging is what makes the
 * number comparable to a file's bitrate.
 */
function average(samples, key, seconds, intervalMs) {
  const count = Math.max(1, Math.round((seconds * 1000) / intervalMs));
  const window = samples.slice(-count);
  if (!window.length) return 0;
  return window.reduce((total, sample) => total + (sample[key] || 0), 0) / window.length;
}

function timeAgo(at, now) {
  const seconds = Math.max(0, Math.round((now - at) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`;
  return `${Math.round(seconds / 3600)}h ago`;
}

/**
 * A sparkline over the sample ring.
 *
 * Scaled to `max` when one is given (memory against its limit, CPU against
 * 100%) and to the series' own peak otherwise, because throughput has no
 * meaningful ceiling to draw against.
 */
function Spark({ values, max = null, color = 'var(--accent)', height = 44 }) {
  if (!values.length) return <div style={{ height }} />;
  const peak = max ?? Math.max(...values, 1);
  const width = 240;
  const step = values.length > 1 ? width / (values.length - 1) : width;
  const y = (v) => height - (Math.min(v, peak) / peak) * (height - 2) - 1;
  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${(i * step).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const area = `${line} L${width},${height} L0,${height} Z`;

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      className="w-full"
      style={{ height }}
      aria-hidden="true"
    >
      <path d={area} fill={color} opacity="0.14" />
      <path d={line} fill="none" stroke={color} strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

function Card({ title, value, sub, children, tone = 'normal' }) {
  const toneClass =
    tone === 'bad'
      ? 'border-red-500/40 bg-red-500/5'
      : tone === 'warn'
        ? 'border-amber-500/40 bg-amber-500/5'
        : 'border-[var(--border)] bg-[var(--surface)]';
  return (
    <div className={`rounded-2xl border p-4 ${toneClass}`}>
      <p className="text-xs uppercase tracking-wide text-[var(--ink-soft)]">{title}</p>
      <p className="mt-1 text-2xl font-semibold text-[var(--ink)]">{value}</p>
      {sub ? <p className="mt-0.5 text-xs text-[var(--ink-soft)]">{sub}</p> : null}
      {children ? <div className="mt-3">{children}</div> : null}
    </div>
  );
}

/**
 * The verdict line.
 *
 * The whole reason the dashboard exists is to answer one question without the
 * reader having to interpret six graphs, so it is answered in a sentence at
 * the top. The rules below are the ones that actually distinguish the causes
 * on this hardware; anything not covered says so rather than guessing.
 */
function diagnose(data) {
  if (!data) return null;
  const latest = data.samples[data.samples.length - 1];
  if (!latest) return null;

  // Paging rate, not swap occupancy: a swap file still holding pages from an
  // earlier build costs nothing, and testing occupancy reported a swapping box
  // on an idle one with over a gigabyte free.
  const swapping = average(data.samples, 'swapPagesPerSec', 60, data.sampleIntervalMs) > 100;
  const memoryPct = latest.memoryLimitBytes
    ? (latest.memoryUsedBytes / latest.memoryLimitBytes) * 100
    : 0;

  if (swapping) {
    return {
      tone: 'bad',
      text:
        'The box is paging to disk. Every read queues behind disk IO and playback will stutter ' +
        'regardless of bandwidth. Reduce concurrent streams, lower the read-ahead buffer, or add RAM.',
    };
  }
  if (memoryPct > 90) {
    return {
      tone: 'warn',
      text: `Memory is at ${memoryPct.toFixed(0)}% of the container limit. One more concurrent stream will push it into swap.`,
    };
  }
  if (latest.cpuPercent > 85) {
    return {
      tone: 'warn',
      text: 'CPU is saturated. On this box that usually means TLS plus proxying is the ceiling, not the network.',
    };
  }

  const streams = data.active;
  if (!streams.length) {
    return { tone: 'normal', text: 'Nothing streaming right now. Start a title to see the throughput comparison.' };
  }

  // Sustained rates, because the instantaneous ones swing between zero and a
  // burst and neither extreme means anything on its own.
  const driveAvg = streams.reduce((total, s) => total + s.driveAverage, 0);
  const clientAvg = streams.reduce((total, s) => total + s.clientAverage, 0);

  // What the file actually needs, taken from the last thing the television
  // reported. Without a report this stays zero and the verdict says less
  // rather than guessing.
  const report = [...data.events].reverse().find((e) => e.detail && 'videoBitrate' in e.detail);
  const needed = report?.detail?.videoBitrate || 0;

  if (needed > 0 && clientAvg * 8 < needed * 0.8) {
    return {
      tone: 'bad',
      text:
        `The file needs about ${(needed / 1_000_000).toFixed(1)} Mbps and the chain is sustaining ` +
        `${((clientAvg * 8) / 1_000_000).toFixed(1)} Mbps. That gap is the stutter, and no buffer size closes ` +
        'it — the fix is a faster path (a region closer to the viewer) or a smaller file (transcoding).',
    };
  }

  const starved = streams.filter((s) => s.starvedTicks > s.fullBufferTicks);
  if (starved.length && driveAvg <= clientAvg * 1.1) {
    return {
      tone: 'bad',
      text:
        'The read-ahead buffer is running dry and Drive is not getting ahead: the VPS-to-Google hop is the ' +
        'bottleneck. No client-side buffer tuning fixes that.',
    };
  }
  if (driveAvg > clientAvg * 1.3) {
    return {
      tone: 'warn',
      text:
        'Drive is delivering faster than the player is taking bytes, so the slow hop is downstream of this box — ' +
        'the VPS uplink or the last mile to the television.',
    };
  }
  return {
    tone: 'normal',
    text:
      'Drive and the player are moving at about the same rate. Compare the sustained figure below against the ' +
      "file's bitrate: if they are close, the link is simply at its limit.",
  };
}

export default function MonitorClient() {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [paused, setPaused] = useState(false);
  const pausedRef = useRef(paused);
  pausedRef.current = paused;

  const load = useCallback(async () => {
    try {
      const res = await fetch('/api/admin/metrics', { cache: 'no-store' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setData(await res.json());
      setError(null);
    } catch (err) {
      setError(err.message || 'Failed to load metrics');
    }
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(() => {
      if (!pausedRef.current) load();
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [load]);

  if (error && !data) {
    return (
      <main className="mx-auto min-h-screen max-w-6xl p-6">
        <h1 className="text-xl font-semibold text-[var(--ink)]">Server monitor</h1>
        <p className="mt-4 rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-400">{error}</p>
      </main>
    );
  }

  if (!data) {
    return (
      <main className="mx-auto min-h-screen max-w-6xl p-6">
        <h1 className="text-xl font-semibold text-[var(--ink)]">Server monitor</h1>
        <div className="skeleton mt-6 h-40 rounded-2xl" />
      </main>
    );
  }

  const samples = data.samples;
  const latest = samples[samples.length - 1] || {};
  const verdict = diagnose(data);
  const memoryPct = latest.memoryLimitBytes
    ? Math.round((latest.memoryUsedBytes / latest.memoryLimitBytes) * 100)
    : null;
  const pagingAvg = average(samples, 'swapPagesPerSec', 60, data.sampleIntervalMs);
  const driveSustained = average(samples, 'driveBytesPerSec', 60, data.sampleIntervalMs);
  const clientSustained = average(samples, 'clientBytesPerSec', 60, data.sampleIntervalMs);

  return (
    <main className="mx-auto min-h-screen max-w-6xl p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-[var(--ink)]">Server monitor</h1>
          <p className="mt-1 text-xs text-[var(--ink-soft)]">
            App up {formatDuration(data.processUptimeSeconds)} · host up{' '}
            {formatDuration(data.hostUptimeSeconds)} · {data.cpuCount} vCPU · sampling every{' '}
            {data.sampleIntervalMs / 1000}s
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => setPaused((p) => !p)}
            className="rounded-lg border border-[var(--border)] bg-[var(--surface)] px-3 py-1.5 text-sm text-[var(--ink)]"
          >
            {paused ? 'Resume' : 'Pause'}
          </button>
          <Link href="/admin/usage" className="text-sm text-[var(--accent)] underline">
            Drive usage
          </Link>
        </div>
      </div>

      {verdict ? (
        <p
          className={`mt-4 rounded-xl border px-4 py-3 text-sm ${
            verdict.tone === 'bad'
              ? 'border-red-500/40 bg-red-500/10 text-red-300'
              : verdict.tone === 'warn'
                ? 'border-amber-500/40 bg-amber-500/10 text-amber-300'
                : 'border-[var(--border)] bg-[var(--surface)] text-[var(--ink-soft)]'
          }`}
        >
          {verdict.text}
        </p>
      ) : null}

      {/* System. On a 2 GB box the memory and swap cards are the ones that
          explain a stall the bandwidth numbers cannot. */}
      <section className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card
          title="Memory"
          value={memoryPct !== null ? `${memoryPct}%` : formatBytes(latest.memoryUsedBytes)}
          sub={`${formatBytes(latest.memoryUsedBytes)} of ${formatBytes(latest.memoryLimitBytes)}`}
          tone={memoryPct > 90 ? 'bad' : memoryPct > 75 ? 'warn' : 'normal'}
        >
          <Spark
            values={samples.map((s) => s.memoryUsedBytes)}
            max={latest.memoryLimitBytes}
            color={memoryPct > 90 ? '#f87171' : 'var(--accent)'}
          />
        </Card>

        {/* Paging rate is the headline, not occupancy: pages parked in swap
            since an earlier build are harmless, pages moving are not. */}
        <Card
          title="Paging"
          value={`${Math.round(latest.swapPagesPerSec || 0)}/s`}
          sub={
            latest.swapTotalBytes
              ? `${formatBytes(latest.swapUsedBytes)} of ${formatBytes(latest.swapTotalBytes)} swap held`
              : 'no swap configured'
          }
          tone={pagingAvg > 100 ? 'bad' : pagingAvg > 10 ? 'warn' : 'normal'}
        >
          <Spark values={samples.map((s) => s.swapPagesPerSec || 0)} color="#f87171" />
        </Card>

        <Card
          title="CPU"
          value={`${Math.round(latest.cpuPercent || 0)}%`}
          sub={`load ${(latest.loadavg || 0).toFixed(2)}`}
          tone={latest.cpuPercent > 85 ? 'warn' : 'normal'}
        >
          <Spark values={samples.map((s) => s.cpuPercent)} max={100} />
        </Card>

        <Card
          title="Node heap"
          value={formatBytes(latest.heapUsedBytes)}
          sub={`RSS ${formatBytes(latest.rssBytes)} · buffers ${formatBytes(latest.arrayBuffersBytes)}`}
        >
          {/* Buffers rather than heap: the streaming read-ahead lives in
              ArrayBuffers, so this is the line that grows when video is
              backing up inside the process. */}
          <Spark values={samples.map((s) => s.arrayBuffersBytes)} color="#60a5fa" />
        </Card>
      </section>

      {/* The comparison the whole page is for. */}
      <section className="mt-4 grid gap-4 sm:grid-cols-2">
        {/* Sustained figure first. Drive bursts — it fills the read-ahead
            buffer then stops while the player drains it — so the
            instantaneous rate reads zero during every pause and is only
            meaningful beside the average. */}
        <Card
          title="Drive → VPS"
          value={formatRate(driveSustained)}
          sub={`sustained over 60s · now ${formatRate(latest.driveBytesPerSec)}`}
        >
          <Spark values={samples.map((s) => s.driveBytesPerSec)} color="#34d399" />
        </Card>
        <Card
          title="VPS → player"
          value={formatRate(clientSustained)}
          sub={`sustained over 60s · now ${formatRate(latest.clientBytesPerSec)}`}
        >
          <Spark values={samples.map((s) => s.clientBytesPerSec)} color="#60a5fa" />
        </Card>
      </section>

      <section className="mt-4 grid gap-4 sm:grid-cols-2">
        <Card
          title="Interface in"
          value={formatRate(average(samples, 'netRxBytesPerSec', 60, data.sampleIntervalMs))}
          sub="everything the container received, video included"
        >
          <Spark values={samples.map((s) => s.netRxBytesPerSec)} color="#34d399" />
        </Card>
        <Card
          title="Interface out"
          value={formatRate(average(samples, 'netTxBytesPerSec', 60, data.sampleIntervalMs))}
          sub="everything the container sent"
        >
          <Spark values={samples.map((s) => s.netTxBytesPerSec)} color="#60a5fa" />
        </Card>
      </section>

      <StreamTable
        title={`Active streams (${data.active.length})`}
        rows={data.active}
        now={data.now}
        empty="Nothing playing."
      />

      {data.finished.length ? (
        <StreamTable
          title="Recently finished"
          rows={[...data.finished].reverse()}
          now={data.now}
          empty=""
          showOutcome
        />
      ) : null}

      <section className="mt-8">
        <h2 className="text-sm font-semibold text-[var(--ink)]">Events</h2>
        <p className="mt-1 text-xs text-[var(--ink-soft)]">
          Server-side stream lifecycle plus whatever the television reported about how playback
          actually looked. A player report showing rebuffers while the rates above were healthy
          points past this box.
        </p>
        <ul className="mt-3 space-y-1 text-xs">
          {[...data.events].reverse().map((event, i) => (
            <li
              key={`${event.at}-${i}`}
              className={`flex gap-3 rounded-lg px-3 py-1.5 ${
                event.level === 'error'
                  ? 'bg-red-500/10 text-red-300'
                  : event.level === 'warn'
                    ? 'bg-amber-500/10 text-amber-300'
                    : 'bg-[var(--surface)] text-[var(--ink-soft)]'
              }`}
            >
              <span className="shrink-0 tabular-nums opacity-70">{timeAgo(event.at, data.now)}</span>
              <span className="flex-1">{event.message}</span>
              {event.detail ? (
                <span className="hidden shrink-0 opacity-60 sm:block">
                  {summariseDetail(event.detail)}
                </span>
              ) : null}
            </li>
          ))}
          {data.events.length === 0 ? (
            <li className="px-3 py-1.5 text-[var(--ink-soft)]">Nothing yet.</li>
          ) : null}
        </ul>
      </section>

      <p className="mt-8 text-xs text-[var(--ink-soft)]">
        History is held in memory and is lost on restart. Totals since start:{' '}
        {data.totals.streamsStarted} streams, {formatBytes(data.totals.driveBytes)} from Drive,{' '}
        {formatBytes(data.totals.clientBytes)} to players, {data.totals.streamsErrored} failed.
      </p>
    </main>
  );
}

/** Renders whatever an event carried, without assuming which fields exist. */
function summariseDetail(detail) {
  if (typeof detail !== 'object' || detail === null) return String(detail);
  if ('rebuffers' in detail) {
    return `${detail.rebuffers} rebuffer${detail.rebuffers === 1 ? '' : 's'} · ${detail.droppedFrames} dropped`;
  }
  if ('range' in detail) return detail.range;
  return '';
}

function StreamTable({ title, rows, now, empty, showOutcome = false }) {
  return (
    <section className="mt-8">
      <h2 className="text-sm font-semibold text-[var(--ink)]">{title}</h2>
      {rows.length === 0 ? (
        empty ? <p className="mt-2 text-xs text-[var(--ink-soft)]">{empty}</p> : null
      ) : (
        <div className="mt-3 overflow-x-auto rounded-2xl border border-[var(--border)]">
          <table className="w-full min-w-[720px] text-left text-xs">
            <thead className="bg-[var(--surface-raised)] text-[var(--ink-soft)]">
              <tr>
                <th className="px-3 py-2 font-medium">Title</th>
                <th className="px-3 py-2 font-medium">Started</th>
                <th className="px-3 py-2 font-medium">From Drive</th>
                <th className="px-3 py-2 font-medium">To player</th>
                <th className="px-3 py-2 font-medium">Now</th>
                <th className="px-3 py-2 font-medium">Transferred</th>
                {/* The honest answer to "was the server keeping up": how often
                    the read-ahead buffer was full versus empty. */}
                <th className="px-3 py-2 font-medium">Buffer</th>
                {showOutcome ? <th className="px-3 py-2 font-medium">Outcome</th> : null}
              </tr>
            </thead>
            <tbody className="text-[var(--ink)]">
              {rows.map((row) => {
                const starving = row.starvedTicks > row.fullBufferTicks;
                return (
                  <tr key={row.key} className="border-t border-[var(--border)]">
                    <td className="max-w-[240px] truncate px-3 py-2" title={row.title || row.id}>
                      {row.title || row.id}
                    </td>
                    <td className="px-3 py-2 text-[var(--ink-soft)]">{timeAgo(row.startedAt, now)}</td>
                    {/* Averages over the stream's life — the figures that
                        compare against a file's bitrate. */}
                    <td className="px-3 py-2 tabular-nums">{formatRate(row.driveAverage)}</td>
                    <td className="px-3 py-2 tabular-nums">{formatRate(row.clientAverage)}</td>
                    <td className="px-3 py-2 tabular-nums text-[var(--ink-soft)]">
                      {formatRate(row.clientRate)}
                    </td>
                    <td className="px-3 py-2 tabular-nums text-[var(--ink-soft)]">
                      {formatBytes(row.clientBytes)}
                      {row.fileSize ? ` / ${formatBytes(row.fileSize)}` : ''}
                    </td>
                    <td className={`px-3 py-2 ${starving ? 'text-red-400' : 'text-emerald-400'}`}>
                      {starving ? 'starved' : 'full'}
                    </td>
                    {showOutcome ? (
                      <td className="px-3 py-2 text-[var(--ink-soft)]">{row.outcome || '—'}</td>
                    ) : null}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
