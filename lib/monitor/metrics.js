// In-process metrics store for the VPS monitor (/admin/monitor).
//
// Why this exists rather than a Prometheus/Netdata stack: the box has 2 GB of
// RAM and the app is already the largest thing on it. More importantly, the
// question that actually needs answering — "is the stall the Drive->VPS hop or
// the VPS->TV hop?" — is not visible to any system monitor. Only this process
// sits between the two and can time both halves of the same byte.
//
// Everything is held in memory and bounded. Nothing is written to disk or to
// Mongo: a restart loses the history, which is the right trade for diagnosing
// a problem that is happening right now.

import { cpus, freemem, loadavg, totalmem, uptime as osUptime } from 'node:os';
import { readFile } from 'node:fs/promises';

/** How often the background sampler takes a system snapshot. */
const SAMPLE_INTERVAL_MS = 5_000;

/** Two hours of samples at 5s. ~1440 entries of a dozen numbers — under 1 MB. */
const SAMPLE_HISTORY = 1_440;

/** Recent notable moments (stream start/end, stall, error). */
const EVENT_HISTORY = 300;

/** Streams that ended are kept briefly so a finished playback is still
 * inspectable — you rarely open the dashboard until after the stall. */
const FINISHED_HISTORY = 40;

/**
 * A bounded FIFO. push() past the cap drops the oldest entry, so memory is
 * flat no matter how long the process runs.
 */
class Ring {
  constructor(limit) {
    this.limit = limit;
    this.items = [];
  }

  push(item) {
    this.items.push(item);
    if (this.items.length > this.limit) this.items.splice(0, this.items.length - this.limit);
    return item;
  }

  toArray() {
    return this.items;
  }
}

/**
 * Module state.
 *
 * Next.js can evaluate a module more than once across route bundles, so this
 * hangs off globalThis: two copies of the store would mean the dashboard route
 * reading a different object than the stream route writes to, and a monitor
 * that shows nothing.
 */
function store() {
  const key = Symbol.for('kdrive.monitor.metrics');
  if (!globalThis[key]) {
    globalThis[key] = {
      startedAt: Date.now(),
      samples: new Ring(SAMPLE_HISTORY),
      events: new Ring(EVENT_HISTORY),
      finished: new Ring(FINISHED_HISTORY),
      active: new Map(),
      seq: 0,
      totals: {
        streamsStarted: 0,
        streamsErrored: 0,
        driveBytes: 0,
        clientBytes: 0,
        driveErrors: 0,
        clientStalls: 0,
      },
      lastCpu: null,
      lastNet: null,
      timer: null,
    };
  }
  return globalThis[key];
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------

/**
 * Records a notable moment. `level` is one of 'info' | 'warn' | 'error' and is
 * only used for colouring the dashboard.
 */
export function recordEvent(level, message, detail = null) {
  return store().events.push({ at: Date.now(), level, message, detail });
}

export function recentEvents() {
  return store().events.toArray();
}

// ---------------------------------------------------------------------------
// Per-stream tracking
// ---------------------------------------------------------------------------

/**
 * Opens a tracking record for one range request and returns a handle the
 * stream route feeds byte counts into.
 *
 * The two counters are the whole point of this file:
 *
 *  - `driveBytes` is what arrived from Google into this process.
 *  - `clientBytes` is what left this process towards nginx and the television.
 *
 * On Vercel these were effectively the same number, because the function had
 * far more egress than one film needs. On a single VPS they diverge, and which
 * one is lower says which hop to fix. If Drive throughput is the low one, the
 * VPS-to-Google link (or Drive itself) is the ceiling and no buffer tuning
 * helps. If client throughput is the low one while Drive races ahead, the
 * bottleneck is the box's uplink or the viewer's connection.
 */
export function startStream({ id, title, driveFileId, rangeStart, rangeEnd, fileSize, client }) {
  // Sampling starts with the first byte served, not with the first visit to
  // the dashboard — otherwise the history is always empty exactly when the
  // stall you came to investigate has just happened.
  ensureSampler();
  const s = store();
  const key = `${++s.seq}`;
  const record = {
    key,
    id,
    title: title || null,
    driveFileId,
    rangeStart,
    rangeEnd,
    fileSize,
    client: client || null,
    startedAt: Date.now(),
    firstByteAt: null,
    endedAt: null,
    driveBytes: 0,
    clientBytes: 0,
    // Sampled each time the background sampler ticks, so the dashboard can
    // draw a rate rather than a total.
    driveRate: 0,
    clientRate: 0,
    lastDriveBytes: 0,
    lastClientBytes: 0,
    lastRateAt: Date.now(),
    // A backpressure event: the read-ahead buffer filled, meaning Drive is
    // outrunning the client. The healthy state for a player with a full
    // buffer, and the opposite of a starved one.
    fullBufferTicks: 0,
    starvedTicks: 0,
    outcome: null,
  };
  s.active.set(key, record);
  s.totals.streamsStarted += 1;
  recordEvent('info', `stream start${title ? ` — ${title}` : ''}`, {
    key,
    range: `${rangeStart}-${rangeEnd}`,
  });
  return {
    key,
    /** Bytes received from Drive. */
    drive(n) {
      record.driveBytes += n;
      s.totals.driveBytes += n;
      if (record.firstByteAt === null) record.firstByteAt = Date.now();
    },
    /** Bytes handed to the response (i.e. on their way to the player). */
    client(n) {
      record.clientBytes += n;
      s.totals.clientBytes += n;
    },
    /** Read-ahead buffer is full — Drive is ahead of the player. */
    buffered() {
      record.fullBufferTicks += 1;
    },
    /** Read-ahead buffer ran dry — the player is waiting on Drive. */
    starved() {
      record.starvedTicks += 1;
    },
    end(outcome) {
      finishStream(key, outcome);
    },
  };
}

function finishStream(key, outcome) {
  const s = store();
  const record = s.active.get(key);
  if (!record) return;
  s.active.delete(key);
  record.endedAt = Date.now();
  record.outcome = outcome;
  if (outcome === 'error') {
    s.totals.streamsErrored += 1;
    recordEvent('error', `stream failed${record.title ? ` — ${record.title}` : ''}`, { key });
  }
  s.finished.push(record);
}

export function activeStreams() {
  return [...store().active.values()];
}

export function finishedStreams() {
  return store().finished.toArray();
}

// ---------------------------------------------------------------------------
// Player-side reports
// ---------------------------------------------------------------------------

/**
 * A report posted by the Android TV client. The server can see its own
 * throughput but not what the television did with it, so a stall that never
 * showed up here is the client's or the last-mile link's — see
 * app/api/admin/playback-report/route.js.
 */
export function recordPlaybackReport(report) {
  const level = report.rebuffers > 0 || report.droppedFrames > 100 ? 'warn' : 'info';
  return recordEvent(level, `player: ${report.title || report.mediaId || 'unknown'}`, report);
}

// ---------------------------------------------------------------------------
// System sampling
// ---------------------------------------------------------------------------

/**
 * Total CPU jiffies across all cores. Differencing two of these gives real CPU
 * utilisation; `loadavg` alone does not distinguish a busy box from a blocked
 * one, and on a 2-core VPS the difference matters.
 */
function cpuTimes() {
  let idle = 0;
  let total = 0;
  for (const cpu of cpus()) {
    for (const [type, value] of Object.entries(cpu.times)) {
      total += value;
      if (type === 'idle') idle += value;
    }
  }
  return { idle, total };
}

/**
 * Bytes in and out of the container's network interfaces, from /proc/net/dev.
 *
 * Inside Docker this is the container's veth pair, which is exactly what is
 * wanted: `rx` is roughly what came from Drive, `tx` roughly what went to
 * nginx. Missing on non-Linux, where it simply returns null and the dashboard
 * hides the row.
 */
async function netCounters() {
  try {
    const text = await readFile('/proc/net/dev', 'utf8');
    let rx = 0;
    let tx = 0;
    for (const line of text.split('\n').slice(2)) {
      const [name, rest] = line.split(':');
      if (!rest) continue;
      if (name.trim() === 'lo') continue; // loopback is nginx talking to us
      const fields = rest.trim().split(/\s+/).map(Number);
      rx += fields[0] || 0;
      tx += fields[8] || 0;
    }
    return { rx, tx };
  } catch {
    return null;
  }
}

/**
 * Memory as the kernel sees it for this container, not as the host sees it.
 *
 * `os.totalmem()` inside a container reports the HOST's RAM, so on a limited
 * container it is a lie — and it is the number that would have told you the
 * box was near its ceiling. cgroup v2 first, v1 as fallback, then os.* for a
 * bare-metal run.
 */
async function memoryUsage() {
  const readNum = async (path) => {
    try {
      const text = (await readFile(path, 'utf8')).trim();
      if (text === 'max') return null;
      const n = Number(text);
      return Number.isFinite(n) ? n : null;
    } catch {
      return null;
    }
  };

  const limit =
    (await readNum('/sys/fs/cgroup/memory.max')) ??
    (await readNum('/sys/fs/cgroup/memory/memory.limit_in_bytes'));
  const current =
    (await readNum('/sys/fs/cgroup/memory.current')) ??
    (await readNum('/sys/fs/cgroup/memory/memory.usage_in_bytes'));

  // A cgroup limit above the host's RAM means "unlimited" expressed as a huge
  // number; treat it as absent so the dashboard shows the host figure.
  const hostTotal = totalmem();
  const usableLimit = limit && limit < hostTotal * 2 ? limit : hostTotal;

  return {
    limitBytes: usableLimit,
    usedBytes: current ?? hostTotal - freemem(),
    hostTotalBytes: hostTotal,
    hostFreeBytes: freemem(),
  };
}

/**
 * Swap in use. On a 2 GB VPS this is the single most diagnostic number on the
 * page: once the box swaps, every read stalls behind disk IO and playback
 * stutters even though CPU and bandwidth both look fine.
 */
async function swapUsage() {
  try {
    const text = await readFile('/proc/meminfo', 'utf8');
    const grab = (label) => {
      const match = new RegExp(`^${label}:\\s+(\\d+) kB`, 'm').exec(text);
      return match ? Number(match[1]) * 1024 : null;
    };
    const total = grab('SwapTotal');
    const free = grab('SwapFree');
    if (total === null || free === null) return null;
    return { totalBytes: total, usedBytes: total - free };
  } catch {
    return null;
  }
}

/** Takes one snapshot and files it in the ring. */
async function sample() {
  const s = store();
  const now = Date.now();

  const times = cpuTimes();
  let cpuPercent = 0;
  if (s.lastCpu) {
    const idleDelta = times.idle - s.lastCpu.idle;
    const totalDelta = times.total - s.lastCpu.total;
    if (totalDelta > 0) cpuPercent = Math.max(0, Math.min(100, 100 * (1 - idleDelta / totalDelta)));
  }
  s.lastCpu = times;

  const net = await netCounters();
  let rxRate = 0;
  let txRate = 0;
  if (net && s.lastNet) {
    const seconds = (now - s.lastNet.at) / 1000;
    if (seconds > 0) {
      rxRate = Math.max(0, (net.rx - s.lastNet.rx) / seconds);
      txRate = Math.max(0, (net.tx - s.lastNet.tx) / seconds);
    }
  }
  if (net) s.lastNet = { ...net, at: now };

  // Per-stream rates, computed here so every stream shares one clock.
  for (const record of s.active.values()) {
    const seconds = (now - record.lastRateAt) / 1000;
    if (seconds > 0) {
      record.driveRate = (record.driveBytes - record.lastDriveBytes) / seconds;
      record.clientRate = (record.clientBytes - record.lastClientBytes) / seconds;
    }
    record.lastDriveBytes = record.driveBytes;
    record.lastClientBytes = record.clientBytes;
    record.lastRateAt = now;
  }

  const heap = process.memoryUsage();
  const memory = await memoryUsage();
  const swap = await swapUsage();

  // Swap in use with memory near the limit is the classic 2 GB failure and is
  // worth an entry in the log, not just a line on a graph. Once only, when it
  // crosses, so a permanently-swapping box does not fill the ring.
  const pressure = swap && swap.usedBytes > 64 * 1024 * 1024;
  if (pressure && !s.swapWarned) {
    s.swapWarned = true;
    recordEvent('warn', 'swap in use — the box is over its RAM budget', {
      swapUsedBytes: swap.usedBytes,
    });
  } else if (!pressure) {
    s.swapWarned = false;
  }

  s.samples.push({
    at: now,
    cpuPercent,
    loadavg: loadavg()[0],
    memoryUsedBytes: memory.usedBytes,
    memoryLimitBytes: memory.limitBytes,
    swapUsedBytes: swap?.usedBytes ?? 0,
    swapTotalBytes: swap?.totalBytes ?? 0,
    heapUsedBytes: heap.heapUsed,
    rssBytes: heap.rss,
    externalBytes: heap.external,
    // ArrayBuffers is where the streaming buffers actually live — a rising
    // number here with flat heap means backed-up video, not a code leak.
    arrayBuffersBytes: heap.arrayBuffers,
    netRxBytesPerSec: rxRate,
    netTxBytesPerSec: txRate,
    activeStreams: s.active.size,
    driveBytesPerSec: [...s.active.values()].reduce((a, r) => a + r.driveRate, 0),
    clientBytesPerSec: [...s.active.values()].reduce((a, r) => a + r.clientRate, 0),
  });
}

/**
 * Starts the sampler once per process. Called by the metrics route rather than
 * at import time so a build-time evaluation never leaves a timer behind.
 */
export function ensureSampler() {
  const s = store();
  if (s.timer) return;
  s.timer = setInterval(() => {
    sample().catch((err) => console.error('[monitor] sample failed', err));
  }, SAMPLE_INTERVAL_MS);
  // Never hold the process open for a metrics timer.
  s.timer.unref?.();
  sample().catch(() => {});
}

/** Everything the dashboard needs, in one object. */
export function snapshot() {
  const s = store();
  return {
    now: Date.now(),
    processStartedAt: s.startedAt,
    processUptimeSeconds: process.uptime(),
    hostUptimeSeconds: osUptime(),
    cpuCount: cpus().length,
    sampleIntervalMs: SAMPLE_INTERVAL_MS,
    totals: s.totals,
    samples: s.samples.toArray(),
    active: activeStreams(),
    finished: finishedStreams(),
    events: recentEvents(),
  };
}
