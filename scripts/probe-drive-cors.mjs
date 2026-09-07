// scripts/probe-drive-cors.mjs — throwaway. Deleted by Task 12.
//
// Serves a page on localhost that tries, from browser JavaScript, exactly the
// request the Service Worker in Task 8 would make. If the browser reports the
// bytes, Drive sends CORS headers for a Bearer-authenticated range request and
// browser direct play is possible. If it reports a CORS failure, it is not.

import { createServer } from 'node:http';

import { loadEnv } from '../lib/env.js';
loadEnv();

import { getAccessToken, listFolderFiles } from '../lib/gdrive.js';
import { isVideoFile } from '../lib/library/video-types.js';

console.log('Finding a video file in Drive...');
const files = await listFolderFiles();
const target = files.find(isVideoFile);
if (!target) {
  console.error('No video file in the Drive folder to probe with.');
  process.exit(1);
}
console.log(`Using ${target.name} (${target.driveFileId})`);

const { token } = await getAccessToken();
const url = `https://www.googleapis.com/drive/v3/files/${target.driveFileId}?alt=media`;

const page = `<!doctype html><meta charset="utf-8"><title>Drive CORS probe</title>
<pre id="out">probing ${target.name}...</pre>
<script>
const out = document.getElementById('out');
fetch(${JSON.stringify(url)}, {
  headers: { Authorization: 'Bearer ' + ${JSON.stringify(token)}, Range: 'bytes=0-1023' },
})
  .then(async (r) => {
    const body = await r.arrayBuffer();
    out.textContent = 'RESULT: reachable\\nstatus ' + r.status +
      '\\nbytes ' + body.byteLength +
      '\\ncontent-range ' + r.headers.get('content-range');
  })
  .catch((err) => {
    out.textContent = 'RESULT: blocked\\n' + err;
  });
</script>`;

createServer((_req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(page);
}).listen(4321, () => console.log('Probe at http://localhost:4321 — open it and read the page.'));
