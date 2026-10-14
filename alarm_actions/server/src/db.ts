import Database from 'better-sqlite3';

export type DB = Database.Database;

/** Live database (TR-DAT-01) and the snapshot written before each HA backup (TR-DAT-07). */
export const DB_FILE = 'alarm_actions.db';
export const SNAPSHOT_FILE = 'alarm_actions.snapshot.db';

/**
 * Append-only list of migrations; index + 1 is stored in `PRAGMA user_version` (TR-DAT-02).
 * Never edit a migration that has been released; add a new one.
 */
const migrations: string[] = [
  `
  CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
  );
  INSERT INTO meta (key, value) VALUES ('change_seq', '0');
  `,
];

export const SCHEMA_VERSION = migrations.length;

export function openDatabase(file: string): DB {
  const db = new Database(file);
  db.pragma('journal_mode = WAL');
  db.pragma('busy_timeout = 5000');
  db.pragma('synchronous = NORMAL');

  // Foreign keys stay off during migrations, so table rebuilds don't cascade.
  db.pragma('foreign_keys = OFF');
  const current = db.pragma('user_version', { simple: true }) as number;
  if (current > migrations.length) {
    throw new Error(
      `Database schema ${current} is newer than this add-on version supports (${migrations.length}). ` +
        'Restore a backup made with this version or update the add-on.',
    );
  }
  for (let i = current; i < migrations.length; i++) {
    db.transaction(() => {
      db.exec(migrations[i]!);
      const broken = db.pragma('foreign_key_check') as unknown[];
      if (broken.length > 0) throw new Error(`Migration ${i + 1}: broken references`);
      db.pragma(`user_version = ${i + 1}`);
    })();
  }
  db.pragma('foreign_keys = ON');
  return db;
}

export function getMeta(db: DB, key: string): string | null {
  const row = db.prepare('SELECT value FROM meta WHERE key = ?').get(key) as
    | { value: string }
    | undefined;
  return row?.value ?? null;
}

export function setMeta(db: DB, key: string, value: string): void {
  db.prepare(
    'INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value',
  ).run(key, value);
}
