// Exercises the index builder against synthetic Matroska files, because the
// three cases that matter are exactly the ones a real library gives you no
// control over: cues reachable only through a chained SeekHead, cues with no
// pointer at all, and no cues whatsoever.

import assert from 'node:assert/strict';
import test from 'node:test';

import { buildMkvIndex } from './mkv-index.js';

/** EBML element id bytes are written literally; ids already carry their marker. */
function id(hex) {
  return Buffer.from(hex, 'hex');
}

/** An 8-byte data size, always the long form so patching a length is easy. */
function size(value) {
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(value));
  buf[0] |= 0x01; // the 8-byte length marker
  return buf;
}

function element(idHex, body) {
  return Buffer.concat([id(idHex), size(body.length), body]);
}

/** An EBML unsigned integer in as few bytes as the value needs. */
function uint(value) {
  if (value === 0) return Buffer.from([0]);
  const bytes = [];
  let v = value;
  while (v > 0) {
    bytes.unshift(v % 256);
    v = Math.floor(v / 256);
  }
  return Buffer.from(bytes);
}

function uintElement(idHex, value) {
  return element(idHex, uint(value));
}

/**
 * A uint written in a fixed 8 bytes. Positions have to be this: a value that
 * shrinks from two bytes to one when it changes would move every element after
 * it, and the layout these tests measure would stop matching the one they
 * write. EBML allows the leading zeros.
 */
function fixedUintElement(idHex, value) {
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(value));
  return element(idHex, buf);
}

function float64Element(idHex, value) {
  const buf = Buffer.alloc(8);
  buf.writeDoubleBE(value);
  return element(idHex, buf);
}

const EBML_HEADER = element('1a45dfa3', Buffer.from([0x42, 0x86, 0x81, 0x01]));

/** Info with a 1ms timecode scale and a duration in ticks. */
function info(durationTicks) {
  return element(
    '1549a966',
    Buffer.concat([uintElement('2ad7b1', 1000000), float64Element('4489', durationTicks)])
  );
}

function cluster(timeTicks, payloadBytes) {
  return element(
    '1f43b675',
    Buffer.concat([uintElement('e7', timeTicks), element('a3', Buffer.alloc(payloadBytes, 7))])
  );
}

/** A Cues element for [{ timeTicks, relativePosition }]. */
function cues(points) {
  return element(
    '1c53bb6b',
    Buffer.concat(
      points.map((p) =>
        element(
          'bb',
          Buffer.concat([
            uintElement('b3', p.timeTicks),
            element('b7', Buffer.concat([fixedUintElement('f1', p.relativePosition)])),
          ])
        )
      )
    )
  );
}

/** A SeekHead announcing { idHex -> position relative to segment data }. */
function seekHead(entries) {
  return element(
    '114d9b74',
    Buffer.concat(
      entries.map(([targetId, position]) =>
        element(
          '4dbb',
          Buffer.concat([element('53ab', id(targetId)), fixedUintElement('53ac', position)])
        )
      )
    )
  );
}

/** Wraps segment children into a Segment, and the whole thing into a file. */
function file(segmentChildren) {
  const body = Buffer.concat(segmentChildren);
  return Buffer.concat([EBML_HEADER, element('18538067', body)]);
}

/** The read(start, end) the builder wants, over an in-memory buffer. */
function readerFor(buf) {
  return async (start, end) => buf.subarray(start, Math.min(end + 1, buf.length));
}

/** Builds a file whose clusters are laid out for real, and reports positions. */
function withClusters(prefixChildren, clusterSpecs) {
  // Positions are relative to the start of the Segment's data, which is what
  // both SeekHead and Cues record — so lay the children out once, measure, and
  // only then build the file around them.
  const prefix = Buffer.concat(prefixChildren);
  const built = [];
  let offset = prefix.length;
  for (const spec of clusterSpecs) {
    const buf = cluster(spec.timeTicks, spec.payloadBytes);
    built.push({ ...spec, relativePosition: offset, buf });
    offset += buf.length;
  }
  return { prefix, clusters: built, endOfClusters: offset };
}

test('follows a chained SeekHead to the cues MatroskaExtractor would miss', async () => {
  const specs = [
    { timeTicks: 0, payloadBytes: 400 },
    { timeTicks: 5000, payloadBytes: 400 },
    { timeTicks: 10000, payloadBytes: 400 },
  ];

  // Every position in this file is relative to the start of the Segment's
  // data and has to be true, or the test would pass on cues that point at
  // nothing. Sizes here are always the 8-byte long form, so a first pass
  // measures the layout and a second writes the same layout with real
  // positions in it — no length can shift between the two.
  const draft = withClusters([info(20000), seekHead([['114d9b74', 0]])], specs);
  const secondHeadPosition = draft.endOfClusters;
  const secondHead = seekHead([['1c53bb6b', 0]]);
  const cuesPosition = secondHeadPosition + secondHead.length;

  const laid = withClusters([info(20000), seekHead([['114d9b74', secondHeadPosition]])], specs);
  assert.equal(laid.endOfClusters, secondHeadPosition);

  const segmentChildren = [
    info(20000),
    seekHead([['114d9b74', secondHeadPosition]]),
    ...laid.clusters.map((c) => c.buf),
    seekHead([['1c53bb6b', cuesPosition]]),
    cues(
      laid.clusters.map((c) => ({ timeTicks: c.timeTicks, relativePosition: c.relativePosition }))
    ),
  ];

  const buf = file(segmentChildren);
  const result = await buildMkvIndex({ read: readerFor(buf), size: buf.length });

  assert.equal(result.seekable, true);
  assert.equal(result.method, 'cues-seekhead');
  assert.equal(result.cues.length, 3);
  assert.deepEqual(
    result.cues.map(([timeMs]) => timeMs),
    [0, 5000, 10000]
  );
  // The offsets are the half that matters: a seek lands on them.
  for (const [, offset] of result.cues) {
    assert.equal(buf.subarray(offset, offset + 4).toString('hex'), '1f43b675');
  }
});

test('finds cues at the end of the file when nothing points at them', async () => {
  const specs = [
    { timeTicks: 0, payloadBytes: 300 },
    { timeTicks: 4000, payloadBytes: 300 },
  ];
  const laid = withClusters([info(8000)], specs);

  const buf = file([
    info(8000),
    ...laid.clusters.map((c) => c.buf),
    cues(
      laid.clusters.map((c) => ({ timeTicks: c.timeTicks, relativePosition: c.relativePosition }))
    ),
  ]);

  const result = await buildMkvIndex({ read: readerFor(buf), size: buf.length });

  assert.equal(result.seekable, true);
  assert.equal(result.method, 'cues-tail');
  assert.deepEqual(
    result.cues.map(([timeMs]) => timeMs),
    [0, 4000]
  );
});

test('builds an index from cluster headers when the file has no cues at all', async () => {
  const specs = [
    { timeTicks: 0, payloadBytes: 200 },
    { timeTicks: 3000, payloadBytes: 200 },
    { timeTicks: 6000, payloadBytes: 200 },
  ];
  const laid = withClusters([info(9000)], specs);
  const buf = file([info(9000), ...laid.clusters.map((c) => c.buf)]);

  const result = await buildMkvIndex({ read: readerFor(buf), size: buf.length });

  assert.equal(result.seekable, true);
  assert.equal(result.method, 'clusters');
  assert.deepEqual(
    result.cues.map(([timeMs]) => timeMs),
    [0, 3000, 6000]
  );
  // Every offset must land on a cluster, or seeking to it parses garbage.
  for (const [, offset] of result.cues) {
    assert.equal(buf.subarray(offset, offset + 4).toString('hex'), '1f43b675');
  }
});

test('cue offsets point at real cluster starts', async () => {
  const specs = [
    { timeTicks: 0, payloadBytes: 500 },
    { timeTicks: 7000, payloadBytes: 500 },
  ];
  const laid = withClusters([info(14000)], specs);
  const buf = file([
    info(14000),
    ...laid.clusters.map((c) => c.buf),
    cues(
      laid.clusters.map((c) => ({ timeTicks: c.timeTicks, relativePosition: c.relativePosition }))
    ),
  ]);

  const result = await buildMkvIndex({ read: readerFor(buf), size: buf.length });

  for (const [, offset] of result.cues) {
    assert.equal(buf.subarray(offset, offset + 4).toString('hex'), '1f43b675');
  }
});

test('reports a non-Matroska file as unseekable rather than guessing', async () => {
  const buf = Buffer.from('not an ebml file at all', 'utf8');
  const result = await buildMkvIndex({ read: readerFor(buf), size: buf.length });
  assert.equal(result.seekable, false);
  assert.equal(result.method, 'not-matroska');
});
