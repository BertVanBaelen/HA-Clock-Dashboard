import { useState, type CSSProperties } from 'react';
import { mdiCellphone, mdiDelete } from '@mdi/js';
import type { Client } from '@alarm-actions/shared';
import { api, errorMessage } from './api';
import { Icon } from './Icon';
import { formatDateTime, t } from './strings';

interface Props {
  clients: Client[];
  timeZone: string | null;
  onRemoved: (id: string) => void;
}

/** The user's phones with the app (FR-APP-04): last sync and away state; removable. */
export function PhonesList({ clients, timeZone, onRemoved }: Props) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (clients.length === 0) return null;

  const remove = async (id: string) => {
    setBusy(id);
    setError(null);
    try {
      await api<void>(`clients/${id}`, { method: 'DELETE' });
      onRemoved(id);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="planned">
      <h2 className="section-title">{t.phones}</h2>
      <p className="muted small">{t.phonesHint}</p>
      {error ? <p className="field-error">{error}</p> : null}
      <ul className="planned-list">
        {clients.map((c, i) => (
          <li key={c.id} className="planned-item" style={{ '--i': i } as CSSProperties}>
            <span className="script-icon round">
              <Icon path={mdiCellphone} />
            </span>
            <span className="planned-main">
              <span>{c.name}</span>
              <span className="muted small">
                {t.lastSync(formatDateTime(c.last_seen_at, timeZone))} · {c.app_version}
              </span>
              {c.away ? <span className="badge warning">{t.awayBadge(c.time_zone)}</span> : null}
            </span>
            <button
              type="button"
              className="icon-button"
              title={t.removePhone}
              aria-label={t.removePhone}
              disabled={busy === c.id}
              onClick={() => void remove(c.id)}
            >
              <Icon path={mdiDelete} />
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
