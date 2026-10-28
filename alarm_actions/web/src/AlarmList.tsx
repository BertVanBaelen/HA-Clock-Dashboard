import { useState, type CSSProperties } from 'react';
import { mdiAlertCircleOutline, mdiAlarmOff, mdiSkipNext, mdiUndo } from '@mdi/js';
import type {
  ActionDefinition,
  Alarm,
  AlarmAction,
  CurrentOccurrence,
  OccurrenceEventResponse,
} from '@alarm-actions/shared';
import { ApiError, api, errorMessage } from './api';
import type { Data } from './App';
import { toggleBody } from './draft';
import { Icon } from './Icon';
import { formatDays, formatNext, formatTime, t } from './strings';
import { Switch } from './Switch';
import { aroundOffsets, geometry, miniLeft } from './timeline';

interface Props {
  data: Data;
  /** Null until loaded; the "Unavailable" badge waits for it. */
  scripts: ActionDefinition[] | null;
  /** Alarm whose mini timeline is hidden while it morphs into the detail. */
  lifted: string | null;
  onChanged: (alarm: Alarm) => void;
  onReload: () => Promise<void>;
  onNotify: (text: string) => void;
  onOpen: (alarm: Alarm) => void;
}

function sortKey(a: Alarm): string {
  return `${a.time}|${a.label}`;
}

/** "2 before · 1 with · 1 after", or "Simple alarm" without actions. */
export function actionSummary(actions: readonly AlarmAction[]): string {
  const count = (timing: AlarmAction['timing']) => actions.filter((a) => a.timing === timing).length;
  const parts = [
    [count('before'), t.summaryBefore],
    [count('at'), t.summaryWith],
    [count('after'), t.summaryAfter],
    [count('after_dismiss'), t.summaryDismiss],
  ] as const;
  const text = parts.filter(([n]) => n > 0).map(([n, f]) => f(n));
  return text.length > 0 ? text.join(' · ') : t.simpleAlarm;
}

export function AlarmList({ data, scripts, lifted, onChanged, onReload, onNotify, onOpen }: Props) {
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<{ text: string; reload: boolean } | null>(null);

  if (data.alarms.length === 0) return <p className="empty">{t.noAlarms}</p>;

  const byKey = (x: Alarm, y: Alarm) => sortKey(x).localeCompare(sortKey(y));
  const known = scripts ? new Set(scripts.map((a) => a.entity_id)) : null;

  const run = async (alarm: Alarm, request: () => Promise<Alarm>, done: string) => {
    setBusy(alarm.id);
    setMessage(null);
    try {
      onChanged(await request());
      onNotify(done);
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
    run(
      alarm,
      async () => {
        const res = await api<{ alarm: Alarm }>(`alarms/${alarm.id}`, {
          method: 'PUT',
          body: toggleBody(alarm, enabled),
        });
        return res.alarm;
      },
      enabled ? t.alarmOn(formatTime(alarm.time)) : t.alarmOff(formatTime(alarm.time)),
    );

  const skip = (alarm: Alarm) =>
    run(
      alarm,
      () =>
        api<Alarm>(`alarms/${alarm.id}/${alarm.skip_next ? 'unskip' : 'skip'}`, {
          method: 'POST',
        }),
      alarm.skip_next ? t.skipUndone : t.skipped,
    );

  // Manual dismissal from the panel (FR-EVT-01, FR-EXE-11): also early, up to an hour before.
  const dismiss = (alarm: Alarm, occurrence: CurrentOccurrence) =>
    run(
      alarm,
      async () => {
        await api<OccurrenceEventResponse>(
          `alarms/${alarm.id}/occurrences/${encodeURIComponent(occurrence.id)}/dismissed`,
          { method: 'POST' },
        );
        return api<Alarm>(`alarms/${alarm.id}`);
      },
      t.dismissedToast,
    );

  const renderItem = (alarm: Alarm, index: number) => {
    const broken = known !== null && alarm.actions.some((a) => !known.has(a.script_entity_id));
    const repeating = alarm.repeat_days.length > 0;
    const disabled = busy === alarm.id;
    const current = alarm.current_occurrence;
    const offsets = aroundOffsets(alarm.actions);
    const g = geometry(offsets);
    const canSkip = repeating && alarm.enabled;
    const canDismiss = current !== null && current.status !== 'dismissed';
    return (
      <li
        key={alarm.id}
        className={alarm.enabled ? 'alarm' : 'alarm off'}
        style={{ '--i': index } as CSSProperties}
      >
        <div className="alarm-row">
          <button type="button" className="alarm-open" onClick={() => onOpen(alarm)}>
            <span className="alarm-headline">
              <span className="alarm-time">{formatTime(alarm.time)}</span>
              {alarm.label ? <span className="alarm-label">{alarm.label}</span> : null}
            </span>
            <span className="alarm-sub">
              {formatDays(alarm.repeat_days)} · {actionSummary(alarm.actions)}
            </span>
            <span className="alarm-sub small">
              {alarm.next_occurrence
                ? `${t.next}: ${formatNext(alarm.next_occurrence, data.info.time_zone)}`
                : t.off}
              {alarm.skip_next ? ` · ${t.skipped}` : ''}
            </span>
            {current?.status === 'dismissed' ? (
              <span className="badge">
                {t.dismissedAt(formatNext(current.scheduled_at, data.info.time_zone))}
              </span>
            ) : null}
            {broken ? (
              <span className="badge warning">
                <Icon path={mdiAlertCircleOutline} size={14} />
                {t.unavailable}
              </span>
            ) : null}
            {offsets.length > 0 ? (
              <span
                className="mini"
                aria-hidden="true"
                data-line={alarm.id}
                style={{ opacity: lifted === alarm.id ? 0 : 1 }}
              >
                <span className="mini-track" />
                {offsets.map((o, i) => (
                  <span key={i} className="mini-dot" style={{ left: miniLeft(g, o) }} />
                ))}
                <span className="mini-dot alarm-dot" style={{ left: '50%' }} />
              </span>
            ) : null}
          </button>
          <Switch
            checked={alarm.enabled}
            disabled={disabled}
            label={t.enabled}
            onChange={(on) => void toggle(alarm, on)}
          />
        </div>
        {canSkip || canDismiss ? (
          <div className="alarm-pills">
            {canDismiss && current ? (
              <button
                type="button"
                className={current.status === 'pending' ? 'pill' : 'pill active'}
                disabled={disabled}
                onClick={() => void dismiss(alarm, current)}
              >
                <Icon path={mdiAlarmOff} size={16} />
                {current.status === 'pending' ? t.dismissEarly : t.dismiss}
              </button>
            ) : null}
            {canSkip ? (
              <button
                type="button"
                className={alarm.skip_next ? 'pill active' : 'pill'}
                aria-pressed={alarm.skip_next}
                disabled={disabled}
                onClick={() => void skip(alarm)}
              >
                <Icon path={alarm.skip_next ? mdiUndo : mdiSkipNext} size={16} />
                {alarm.skip_next ? t.undoSkip : t.skipNext}
              </button>
            ) : null}
          </div>
        ) : null}
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
