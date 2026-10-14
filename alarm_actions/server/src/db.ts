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
  // 3: occurrences and execution records (TR-SCH-02, 06, TR-DAT-03). Records have no foreign key:
  // history outlives deleted alarms until it expires.
  `
  CREATE TABLE occurrences (
    id           TEXT PRIMARY KEY,
    alarm_id     TEXT NOT NULL REFERENCES alarms (id) ON DELETE CASCADE,
    scheduled_at INTEGER NOT NULL,
    status       TEXT NOT NULL,
    action_ids   TEXT,
    runs_done    INTEGER NOT NULL DEFAULT 0,
    updated_at   INTEGER NOT NULL
  );
  CREATE INDEX occurrences_alarm ON occurrences (alarm_id, status);
  CREATE INDEX occurrences_open ON occurrences (runs_done, status);
  CREATE TABLE execution_records (
    id               TEXT PRIMARY KEY,
    planned_run_id   TEXT NOT NULL UNIQUE,
    owner_user_id    TEXT NOT NULL,
    alarm_id         TEXT NOT NULL,
    alarm_action_id  TEXT NOT NULL,
    occurrence_id    TEXT,
    script_entity_id TEXT NOT NULL,
    planned_at       INTEGER NOT NULL,
    executed_at      INTEGER,
    result           TEXT NOT NULL,
    reason           TEXT,
    created_at       INTEGER NOT NULL
  );
  CREATE INDEX records_owner ON execution_records (owner_user_id, created_at);
  CREATE INDEX records_alarm ON execution_records (alarm_id, created_at);
  CREATE INDEX records_started ON execution_records (executed_at, script_entity_id);
  `,
  // 4: phones (Client), occurrence events and the dismissal time (phase 6). Snoozes can repeat;
  // the other event types count once per occurrence (idempotent reports, FR-EVT-01).
  `
  CREATE TABLE clients (
    id            TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL,
    name          TEXT NOT NULL,
    app_version   TEXT NOT NULL,
    time_zone     TEXT NOT NULL,
    last_sync_seq INTEGER NOT NULL,
    last_seen_at  INTEGER NOT NULL,
    created_at    INTEGER NOT NULL
  );
  CREATE INDEX clients_user ON clients (user_id, last_seen_at);
  ALTER TABLE occurrences ADD COLUMN dismissed_at INTEGER;
  CREATE TABLE occurrence_events (
    id            TEXT PRIMARY KEY,
    occurrence_id TEXT NOT NULL,
    alarm_id      TEXT NOT NULL,
    owner_user_id TEXT NOT NULL,
    type          TEXT NOT NULL,
    occurred_at   INTEGER NOT NULL,
    received_at   INTEGER NOT NULL,
    source        TEXT NOT NULL,
    user_id       TEXT NOT NULL
  );
  CREATE UNIQUE INDEX occurrence_events_once ON occurrence_events (occurrence_id, type)
    WHERE type != 'snoozed';
  CREATE UNIQUE INDEX occurrence_events_snooze ON occurrence_events (occurrence_id, occurred_at)
    WHERE type = 'snoozed';
  CREATE INDEX occurrence_events_received ON occurrence_events (received_at);
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
