// MongoDB connection helper.
//
// Usage:
//   import { getDb } from '@/lib/db';
//   const db = await getDb();
//   await db.collection('files').insertOne({ ... });
//
// The client promise is cached on globalThis so Next.js hot reloads (and
// multiple route modules in the same process) reuse a single connection pool.

import { MongoClient } from 'mongodb';

const DEFAULT_DB_NAME = 'kdrive';

function getUri() {
  const uri = process.env.MONGODB_URI;
  if (!uri) {
    throw new Error('MONGODB_URI is not set — copy .env.example to .env.local and fill it in');
  }
  return uri;
}

export function getClientPromise() {
  if (!globalThis._mongoClientPromise) {
    const client = new MongoClient(getUri());
    globalThis._mongoClientPromise = client.connect();
  }
  return globalThis._mongoClientPromise;
}

export async function getDb() {
  const client = await getClientPromise();
  return client.db(process.env.MONGODB_DB_NAME || DEFAULT_DB_NAME);
}

export async function getCollection(name) {
  const db = await getDb();
  return db.collection(name);
}

// Only for standalone scripts — never call this from a request handler.
export async function closeClient() {
  if (globalThis._mongoClientPromise) {
    const client = await globalThis._mongoClientPromise;
    globalThis._mongoClientPromise = undefined;
    await client.close();
  }
}
