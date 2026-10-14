import { mdiAccount, mdiScriptTextOutline } from '@mdi/js';
import type { PlannedAction } from '@alarm-actions/shared';
import type { CSSProperties } from 'react';
import { Icon } from './Icon';
import { formatNext, t } from './strings';

interface Props {
  planned: PlannedAction[];
  timeZone: string | null;
}

/** Read-only list of what other people's alarms will do, with the time (FR-ACC-02). */
export function PlannedList({ planned, timeZone }: Props) {
  if (planned.length === 0) return null;
  return (
    <section className="planned">
      <h2 className="section-title">{t.planned}</h2>
      <p className="muted small">{t.plannedHint}</p>
      <ul className="planned-list">
        {planned.map((p, i) => (
          <li
            key={`${p.alarm_at}-${p.script_entity_id}-${i}`}
            className="planned-item"
            style={{ '--i': i } as CSSProperties}
          >
            <span className="script-icon round">
              <Icon path={p.icon_path ?? mdiScriptTextOutline} />
            </span>
            <span className="planned-main">
              <span className="planned-time">
                {p.run_at
                  ? formatNext(p.run_at, timeZone)
                  : t.afterDismiss(formatNext(p.alarm_at, timeZone), p.delay_minutes ?? 0)}
              </span>
              <span>{p.script_name ?? p.script_entity_id}</span>
            </span>
            <span className="muted small owner">
              <Icon path={mdiAccount} size={14} />
              {p.owner_name ?? t.someone}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
