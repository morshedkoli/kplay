// Smoke test for the DhakaFlix source — hits the real ISP index, no mocks,
// same pattern as scripts/test-gdrive.js.
//
//   node scripts/test-dhakaflix.js
//
// Only useful from inside the ISP network: 172.16.50.x is not routable from
// anywhere else, so a failure here off-network is expected, not a bug.

import { loadEnv } from '../lib/env.js';
import { crawl, dhakaFlixRoots, fileMetadata, isFeatureSized, listDirectory } from '../lib/dhakaflix.js';
import { isVideoFilename } from '../lib/library/video-types.js';
import { parseFilename } from '../lib/library/parse.js';

loadEnv();

async function main() {
  const roots = dhakaFlixRoots();
  console.log(`Roots (${roots.length}):`);
  for (const root of roots) console.log(`  ${root}`);

  const [first] = roots;
  const listing = await listDirectory(first);
  console.log(
    `\nListing ${first}\n  ${listing.directories.length} directories, ${listing.files.length} files`
  );

  // One root, shallow: enough to prove the crawler, parser and size filter
  // agree, without walking the whole catalog.
  const videos = [];
  await crawl({
    roots: [first],
    maxDepth: 2,
    onError: (e) => console.error(`  ! ${e.url}: ${e.error}`),
    onFile: (file) => {
      if (isVideoFilename(file.name) && isFeatureSized(file)) videos.push(file);
    },
  });

  console.log(`\nCrawl (depth 2) found ${videos.length} video files. First few:`);
  for (const file of videos.slice(0, 5)) {
    const parsed = parseFilename(file.name);
    console.log(`  ${parsed.type}: "${parsed.title}"${parsed.year ? ` (${parsed.year})` : ''}`);
    console.log(`    ${file.name}`);
  }

  if (videos.length) {
    const meta = await fileMetadata(videos[0].url);
    console.log(`\nHEAD on the first file: ${meta.size} bytes, ${meta.mimeType}`);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
