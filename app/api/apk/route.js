// GET /api/apk          — download the Android TV APK
// GET /api/apk?meta=1   — what is on offer: version, size, date, SHA-256
//
// Admin session only, and that is not a formality. The APK carries a baked-in
// copy of KDRIVE_DEVICE_KEY (android-tv/app/build.gradle.kts explains why),
// so anyone who can download the file can extract the key and reach the whole
// library. That is exactly why it is not a static file under public/: nothing
// in there can be gated.
//
// The file itself is staged by `npm run apk:stage` into assets/android-tv/,
// outside the build directory, so what is served is a build someone chose to
// publish rather than whatever Gradle last produced.

import { createReadStream } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { Readable } from 'node:stream';

import { isAdmin } from '@/lib/admin-auth.js';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';

const APK_DIR = join(process.cwd(), 'assets/android-tv');
const APK_PATH = join(APK_DIR, 'kplay-tv.apk');
const METADATA_PATH = join(APK_DIR, 'metadata.json');

async function readMetadata() {
  try {
    return JSON.parse(await readFile(METADATA_PATH, 'utf8'));
  } catch {
    return null;
  }
}

export async function GET(request) {
  if (!(await isAdmin())) {
    return Response.json({ error: 'Unauthorized' }, { status: 401 });
  }

  const metadata = await readMetadata();
  const wantsMetadata = new URL(request.url).searchParams.has('meta');

  let size;
  try {
    size = (await stat(APK_PATH)).size;
  } catch {
    // Nothing staged. A missing build is a normal state — a fresh clone has
    // none — so say so plainly instead of failing as an error.
    const body = { available: false, reason: 'No APK has been staged yet.' };
    return Response.json(body, { status: wantsMetadata ? 200 : 404 });
  }

  if (wantsMetadata) {
    return Response.json({ available: true, sizeBytes: size, ...metadata });
  }

  // Streamed, not read into memory: this is 15 MB and the server has better
  // things to hold.
  const stream = Readable.toWeb(createReadStream(APK_PATH));
  const name = metadata?.versionName ? `kPlay-TV-${metadata.versionName}.apk` : 'kPlay-TV.apk';

  return new Response(stream, {
    headers: {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': String(size),
      'Content-Disposition': `attachment; filename="${name}"`,
      // The key inside makes this as sensitive as the library it opens.
      'Cache-Control': 'private, no-store',
    },
  });
}
