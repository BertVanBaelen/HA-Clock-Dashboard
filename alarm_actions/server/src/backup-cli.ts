import path from 'node:path';
import { writeSnapshot } from './snapshot.js';

/** Entry point of `alarm-actions-backup` (the `backup_pre` command in config.yaml). */
const dataDir = path.resolve(process.env.DATA_DIR || '/data');

writeSnapshot(dataDir)
  .then((file) => {
    console.log(file ? `Database snapshot written to ${file}` : 'No database yet; nothing to back up');
  })
  .catch((err: unknown) => {
    console.error('Database snapshot failed:', err);
    process.exit(1);
  });
