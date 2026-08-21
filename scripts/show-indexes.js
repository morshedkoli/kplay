// Prints the indexes on the media library collections. Run with: npm run show:indexes

import { loadEnv } from '../lib/env.js';
import { closeClient, getDb } from '../lib/db.js';

async function main() {
  loadEnv();
  const db = await getDb();
  console.log(`database: ${db.databaseName}\n`);

  for (const name of ['media', 'episode', 'progress']) {
    const indexes = await db.collection(name).indexes();
    console.log(name);
    for (const idx of indexes) {
      const keys = Object.entries(idx.key)
        .map(([k, v]) => `${k}:${v}`)
        .join(', ');
      console.log(`  ${idx.name.padEnd(18)} { ${keys} }${idx.unique ? ' unique' : ''}`);
    }
    console.log('');
  }
}

main()
  .catch((err) => {
    console.error(err.message);
    process.exitCode = 1;
  })
  .finally(() => closeClient());
