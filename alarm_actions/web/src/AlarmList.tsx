import { useState } from 'react';
import { mdiContentCopy, mdiPencil, mdiSkipNext, mdiUndo, mdiAlertCircleOutline } from '@mdi/js';
import type { ActionDefinition, Alarm } from '@alarm-actions/shared';
import { ApiError, api, errorMessage } from './api';
import type { Data } from './App';
import { toggleBody } from './draft';
import { Icon } from './Icon';
import { formatDays, formatNext, formatTime, t } from './strings';
import { Switch } from './Switch';

interface Props {
  data: Data;
  /** Null until loaded; the "Unavailable" badge waits for it. */
  scripts: ActionDefinition[] | null;
  onChanged: (alarm: Alarm) => void;
  onReload: () => Promise<void>;
  onOpen: (alarm: Alarm) => void;
  onDuplicate: (alarm: Alarm) => void;
}

function sortKey(a: Alarm): string {
  return `${a.time}|${a.label}`;
}

export function AlarmList({ data, scripts, onChanged, onReload, onOpen, onDuplicate }: Props) {
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<{ text: string; reload: boolean } | null>(null);

  if (data.alarms.length === 0) return <p className="empty">{t.noAlarms}</p>;

  const byKey = (x: Alarm, y: Alarm) => sortKey(x).localeCompare(sortKey(y));

  const run = async (alarm: Alarm, request: () => Promise<Alarm>) => {
    setBusy(alarm.id);
    setMessage(null);
    try {
      onChanged(await request());
    } catch (err) {
      const conflict = err instanceof ApiError && (err.status === 409 || err.status === 404);
      setMessage({
        text: err instanceof ApiError && err.status === 409 ? t.conflict : errorMessage(err),
        reload: conflict,
      });
      if (conflict) void onReload();
    } finally {
      setBusy(null);
    }
  };

  const toggle = (alarm: Alarm, enabled: boolean) =>
    run(alarm, async () => {
      const res = await api<{ alarm: Alarm }>(`alarms/${alarm.id}`, {
        method: 'PUT',
        body: toggleBody(alarm, enabled),
      });
      return res.alarm;
    });

  const skip = (alarm: Alarm) =>
    run(alarm, () =>
      api<Alarm>(`alarms/${alarm.id}/${alarm.skip_next ? 'unskip' : 'skip'}`, { method: 'POST' }),
    );

  const renderItem = (alarm: Alarm) => {
    const known = scripts ? new Set(scripts.map((a) => a.entity_id)) : null;
    const broken = known !== null && alarm.actions.some((a) => !known.has(a.script_entity_id));
    const repeating = alarm.repeat_days.length > 0;
    const disabled = busy === alarm.id;
    return (
      <li key={alarm.id} className={alarm.enabled ? 'alarm' : 'alarm off'}>
        <button type="button" className="alarm-main" onClick={() => onOpen(alarm)}>
          <span className="alarm-time">{formatTime(alarm.time)}</span>
          <span className="alarm-meta">
            {alarm.label ? <span className="alarm-label">{alarm.label}</span> : null}
            <span className="muted">
              {formatDays(alarm.repeat_days)}
              {alarm.actions.length > 0 ? ` · ${t.actionCount(alarm.actions.length)}` : ''}
            </span>
            <span className="muted small">
              {alarm.next_occurrence
                ? `${t.next}: ${formatNext(alarm.next_occurrence, data.info.time_zone)}`
                : t.off}
              {alarm.skip_next ? ` · ${t.skipped}` : ''}
            </span>
            {broken ? (
              <span className="badge warning">
                <Icon path={mdiAlertCircleOutline} size={14} />
                {t.unavailable}
              </span>
            ) : null}
          </span>
        </button>
        <div className="alarm-controls">
          {repeating && alarm.enabled ? (
            <button
              type="button"
              className={alarm.skip_next ? 'icon-button active' : 'icon-button'}
              title={alarm.skip_next ? t.undoSkip : t.skipNext}
              aria-label={alarm.skip_next ? t.undoSkip : t.skipNext}
              aria-pressed={alarm.skip_next}
              disabled={disabled}
              onClick={() => void skip(alarm)}
            >
              <Icon path={alarm.skip_next ? mdiUndo : mdiSkipNext} />
            </button>
          ) : null}
          <button
            type="button"
            className="icon-button hide-narrow"
            title={t.duplicate}
            aria-label={t.duplicate}
            onClick={() => onDuplicate(alarm)}
          >
            <Icon path={mdiContentCopy} />
          </button>
          <button
            type="button"
            className="icon-button hide-narrow"
            title={t.edit}
            aria-label={t.edit}
            onClick={() => onOpen(alarm)}
          >
            <Icon path={mdiPencil} />
          </button>
          <Switch
            checked={alarm.enabled}
            disabled={disabled}
            label={t.enabled}
            onChange={(on) => void toggle(alarm, on)}
          />
        </div>
      </li>
    );
  };

  return (
    <>
      {message ? (
        <div className="banner error" role="alert">
          <span>{message.text}</span>
          {message.reload ? (
            <button type="button" onClick={() => void onReload()}>
              {t.reload}
            </button>
          ) : null}
        </div>
      ) : null}
      <ul className="alarm-list">{[...data.alarms].sort(byKey).map(renderItem)}</ul>
    </>
  );
}
