# Prompt 03 — Next.js project wiring + MongoDB schema

**Feed this to Claude Code / Antigravity.**

---

## Context

`` already has a scaffold (package.json, folder structure, empty API
route stubs). I need the MongoDB connection and data models built out per
`PRD.md` section 7.

## Task

1. In `lib/db.js`, implement a MongoDB connection helper using the
   native `mongodb` driver (not Mongoose — keep it consistent with how
   Bhandar's existing backend connects). Cache the client connection across
   Next.js hot reloads (the standard `global._mongoClientPromise` pattern).
2. Create `lib/models/files.js` and
   `lib/models/storageAccounts.js` — thin wrapper modules exporting
   typed helper functions (not full ORM classes) for:
   - `files`: `insertFile()`, `findByHash(hash, deviceId)`,
     `listHashesForDevice(deviceId)`, `findById(fileId)`,
     `updateStatus(fileId, status)`
   - `storage_accounts`: `listActive()`, `incrementUsed(provider, bytes)`,
     `markStatus(provider, status)`
   Use the exact schemas from `PRD.md` section 7 — don't invent new fields.
3. Add a MongoDB index migration script `lib/migrations/001-indexes.js`
   creating:
   - unique index on `files.fileId`
   - compound index on `files.hash + files.deviceId` (for fast dedupe
     lookups)
   - unique index on `storage_accounts.provider`
4. Add `MONGODB_URI` and `MONGODB_DB_NAME` to `.env.example` if not already
   present.
5. Write a quick smoke-test script `scripts/test-db.js` that
   connects, inserts a dummy file record, reads it back, deletes it, and
   prints success/failure — so I can verify the connection works without
   spinning up the whole Next.js app.

## Acceptance criteria

- `node scripts/test-db.js` connects and passes the round-trip test
  against my real MongoDB URI (I'll provide it in `.env.local`).
- Indexes are created (verify with a note on how to check in MongoDB
  Compass or `mongosh`).
- No fields beyond what's specified in `PRD.md` section 7.
