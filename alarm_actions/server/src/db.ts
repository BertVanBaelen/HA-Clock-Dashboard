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
  // 2: alarms with their actions, and tombstones of deleted alarms (TR-DAT-06).
  `
  CREATE TABLE alarms (
    id              TEXT PRIMARY KEY,
    owner_user_id   TEXT NOT NULL,
    label           TEXT NOT NULL DEFAULT '',
    time            TEXT NOT NULL,
    repeat_days     TEXT NOT NULL DEFAULT '[]',
    enabled         INTEGER NOT NULL DEFAULT 1,
    skip_next       INTEGER NOT NULL DEFAULT 0,
    snooze_minutes  INTEGER,
    actions_json    TEXT NOT NULL DEFAULT '[]',
    disabled_reason TEXT,
    revision        INTEGER NOT NULL,
    change_seq      INTEGER NOT NULL,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
  );
  CREATE INDEX alarms_owner ON alarms (owner_user_id);
  CREATE TABLE tombstones (
    alarm_id      TEXT PRIMARY KEY,
    owner_user_id TEXT NOT NULL,
    change_seq    INTEGER NOT NULL,
    deleted_at    INTEGER NOT NULL
  );
  CREATE INDEX tombstones_owner ON tombstones (owner_user_id, change_seq);
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
  try {
    migrate(db);
  } catch (err) {
    db.close();
    throw err;
  }
  db.pragma('foreign_keys = ON');
  return db;
}

function migrate(db: DB): void {
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
