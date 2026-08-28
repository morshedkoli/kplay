// Copies the Android TV APK out of the Gradle build directory and into
// `assets/android-tv/`, where the server can serve it from /api/apk.
//
// The build directory is gitignored (it is 15 MB of regenerated output and it
// changes on every compile), so the APK the admin panel offers has to be an
// explicit, staged artefact rather than whatever happens to be lying in
// `app/build/outputs`. Running this is the act of publishing a build.
//
// It also writes `metadata.json`: version, size, build date and a SHA-256, so
// the panel can say what it is offering and anyone can check what they got.
//
// Usage:  npm run apk:stage        (after building the APK yourself)
//         npm run apk:build        (does the Gradle build first)
//
// The Gradle build runs from here rather than from an npm script string,
// because npm runs those through cmd.exe on Windows and cmd has no `./` —
// `cd android-tv && ./gradlew assembleDebug` failed with "'.' is not
// recognized". The wrapper's name differs per platform too, so choosing it is
// a decision, not something a shell string can carry.

import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFileSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const SOURCE = join(root, 'android-tv/app/build/outputs/apk/debug/app-debug.apk');
const BUILD_METADATA = join(root, 'android-tv/app/build/outputs/apk/debug/output-metadata.json');
const DEST_DIR = join(root, 'assets/android-tv');
const DEST_APK = join(DEST_DIR, 'kplay-tv.apk');
const DEST_METADATA = join(DEST_DIR, 'metadata.json');

function versionFromGradleOutput() {
  try {
    const parsed = JSON.parse(readFileSync(BUILD_METADATA, 'utf8'));
    const element = parsed.elements?.[0];
    return {
      versionName: element?.versionName ?? null,
      versionCode: element?.versionCode ?? null,
    };
  } catch {
    // Not fatal: the APK is the artefact, the version is a label on it.
    return { versionName: null, versionCode: null };
  }
}

/** Runs the Gradle build, with the wrapper this platform actually has. */
function build() {
  const androidDir = join(root, 'android-tv');
  const wrapper =
    process.platform === 'win32' ? join(androidDir, 'gradlew.bat') : join(androidDir, 'gradlew');

  console.log('Building the APK…');
  // Windows cannot exec a .bat directly, so it goes through the shell as one
  // quoted string. Node warns about passing an args array alongside `shell`,
  // and rightly — the two together concatenate without escaping.
  const result =
    process.platform === 'win32'
      ? spawnSync(`"${wrapper}" assembleDebug`, { cwd: androidDir, stdio: 'inherit', shell: true })
      : spawnSync(wrapper, ['assembleDebug'], { cwd: androidDir, stdio: 'inherit' });

  if (result.error) {
    console.error(`Could not run ${wrapper}: ${result.error.message}`);
    process.exit(1);
  }
  if (result.status !== 0) {
    console.error(`Gradle build failed (exit ${result.status}) — nothing staged.`);
    process.exit(result.status ?? 1);
  }
}

function main() {
  if (process.argv.includes('--build')) build();

  let apk;
  try {
    apk = readFileSync(SOURCE);
  } catch {
    console.error(`No APK at ${SOURCE}`);
    console.error('Build one first:  npm run apk:build');
    process.exit(1);
  }

  mkdirSync(DEST_DIR, { recursive: true });
  copyFileSync(SOURCE, DEST_APK);

  const { versionName, versionCode } = versionFromGradleOutput();
  const metadata = {
    fileName: 'kplay-tv.apk',
    versionName,
    versionCode,
    sizeBytes: apk.byteLength,
    sha256: createHash('sha256').update(apk).digest('hex'),
    // The APK's own mtime, not "now": staging an old build should not claim
    // to be a fresh one.
    builtAt: statSync(SOURCE).mtime.toISOString(),
    stagedAt: new Date().toISOString(),
  };
  writeFileSync(DEST_METADATA, `${JSON.stringify(metadata, null, 2)}\n`);

  const mb = (metadata.sizeBytes / 1024 / 1024).toFixed(1);
  console.log(`Staged ${metadata.fileName} — v${versionName ?? '?'}, ${mb} MB`);
  console.log(`  ${DEST_APK}`);
}

main();
