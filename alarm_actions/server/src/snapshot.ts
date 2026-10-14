import fs from 'node:fs';
import path from 'node:path';
import Database from 'better-sqlite3';
import { DB_FILE, SNAPSHOT_FILE } from './db.js';

/**
 * Writes a consistent copy of the live database to `<dataDir>/alarm_actions.snapshot.db` with the
 * SQLite online backup API, so it is safe while the add-on keeps running (hot backup, TR-DAT-07).
 * Returns null when there is no database yet.
 */
export async function writeSnapshot(dataDir: string): Promise<string | null> {
  const source = path.join(dataDir, DB_FILE);
  if (!fs.existsSync(source)) return null;
  const target = path.join(dataDir, SNAPSHOT_FILE);
  const tmp = `${target}.tmp`;
  const db = new Database(source, { fileMustExist: true });
  try {
    db.pragma('busy_timeout = 5000');
    await db.backup(tmp);
    fs.renameSync(tmp, target);
    return target;
  } finally {
    db.close();
    fs.rmSync(tmp, { force: true });
  }
}

/**
 * After a restore the backup contains only the snapshot (the live files are excluded), so the
 * snapshot becomes the live database. Returns true when it was restored.
 */
export function restoreFromSnapshot(dataDir: string): boolean {
  const live = path.join(dataDir, DB_FILE);
  const snapshot = path.join(dataDir, SNAPSHOT_FILE);
  if (fs.existsSync(live) || !fs.existsSync(snapshot)) return false;
  for (const suffix of ['-wal', '-shm']) fs.rmSync(live + suffix, { force: true });
  fs.copyFileSync(snapshot, live);
  return true;
}
