import { useEffect, useMemo, useState } from 'react';
import {
  mdiAlertOutline,
  mdiClose,
  mdiContentCopy,
  mdiDelete,
  mdiPlay,
  mdiPlus,
  mdiScriptTextOutline,
} from '@mdi/js';
import {
  LIMITS,
  TIMINGS,
  WEEKDAYS,
  type Alarm,
  type AlarmSaveResponse,
  type AlarmWarning,
  type RunNowResponse,
  type Weekday,
} from '@alarm-actions/shared';
import { ApiError, api, errorMessage } from './api';
import type { Data, Scripts } from './App';
import {
  copyDraft,
  draftBody,
  draftFromAlarm,
  missedBeforeActions,
  newAction,
  validateDraft,
  withTiming,
  type Draft,
  type DraftAction,
} from './draft';
import { Icon } from './Icon';
import { dayName, t } from './strings';
import { Switch } from './Switch';

interface Props {
  initial: Draft;
  data: Data;
  scripts: Scripts;
  onClose: () => void;
  onSaved: (alarm: Alarm) => void;
  onDeleted: (id: string) => void;
  onDuplicate: (draft: Draft) => void;
  onReload: (id: string) => Promise<void>;
}

type Problem = { kind: 'conflict' | 'error'; text: string } | null;

function parseNumber(value: string): number | null {
  if (value.trim() === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : null;
}

export function AlarmEditor({
  initial,
  data,
  scripts: scriptState,
  onClose,
  onSaved,
  onDeleted,
  onDuplicate,
  onReload,
}: Props) {
  const [draft, setDraft] = useState<Draft>(initial);
  const [base, setBase] = useState<Draft>(initial);
  const [saving, setSaving] = useState(false);
  const [problem, setProblem] = useState<Problem>(null);
  const [warnings, setWarnings] = useState<AlarmWarning[]>([]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [runResult, setRunResult] = useState<Record<string, string>>({});
  const [now, setNow] = useState(() => Date.now());
  const [touched, setTouched] = useState(false);

  // Keep the "before" warning current while the dialog stays open.
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // Scripts load in the background; until then the current choices show as their entity ids.
  const scriptsLoaded = scriptState.list !== null;
  const scripts = useMemo(() => scriptState.list ?? [], [scriptState.list]);
  const scriptByEntity = useMemo(() => new Map(scripts.map((s) => [s.entity_id, s])), [scripts]);
  const errors = validateDraft(draft);
  const missed = missedBeforeActions(draft, data.info.time_zone, now);
  const isNew = draft.id === undefined;
  const title = isNew ? t.newAlarm : t.editAlarm;

  const update = (patch: Partial<Draft>) => {
    setDraft((d) => ({ ...d, ...patch }));
    setWarnings([]);
  };
  const updateAction = (key: string, fn: (a: DraftAction) => DraftAction) =>
    update({ actions: draft.actions.map((a) => (a.key === key ? fn(a) : a)) });
  const toggleDay = (day: Weekday) =>
    update({
      repeat_days: draft.repeat_days.includes(day)
        ? draft.repeat_days.filter((d) => d !== day)
        : WEEKDAYS.filter((d) => d === day || draft.repeat_days.includes(d)),
    });

  const handleError = (err: unknown) => {
    if (err instanceof ApiError && err.status === 409)
      setProblem({ kind: 'conflict', text: t.conflict });
    else if (err instanceof ApiError && err.status === 403)
      setProblem({ kind: 'error', text: t.forbidden });
    else if (err instanceof ApiError && err.status === 404)
      setProblem({ kind: 'error', text: t.notFound });
    else setProblem({ kind: 'error', text: errorMessage(err) });
  };

  const save = async () => {
    setTouched(true);
    if (errors.size > 0) return;
    setSaving(true);
    setProblem(null);
    try {
      const res = await api<AlarmSaveResponse>(isNew ? 'alarms' : `alarms/${draft.id}`, {
        method: isNew ? 'POST' : 'PUT',
        body: draftBody(draft),
      });
      onSaved(res.alarm);
      if (res.warnings.length > 0) {
        const fresh = draftFromAlarm(res.alarm);
        setDraft(fresh);
        setBase(fresh);
        setWarnings(res.warnings);
      } else {
        onClose();
      }
    } catch (err) {
      handleError(err);
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!draft.id) return;
    setSaving(true);
    setProblem(null);
    try {
      await api(`alarms/${draft.id}`, { method: 'DELETE' });
      onDeleted(draft.id);
    } catch (err) {
      handleError(err);
      setConfirmDelete(false);
    } finally {
      setSaving(false);
    }
  };

  const runNow = async (action: DraftAction) => {
    if (!draft.id || !action.id) return;
    setRunResult((r) => ({ ...r, [action.key]: '…' }));
    try {
      const res = await api<RunNowResponse>(`alarms/${draft.id}/actions/${action.id}/run`, {
        method: 'POST',
      });
      const text = res.reason ? `${res.result} (${res.reason})` : res.result;
      setRunResult((r) => ({ ...r, [action.key]: t.runResult(text) }));
    } catch (err) {
      setRunResult((r) => ({ ...r, [action.key]: errorMessage(err) }));
    }
  };

  const saved = (action: DraftAction) =>
    action.id !== undefined &&
    base.actions.some(
      (a) =>
        a.id === action.id &&
        a.script_entity_id === action.script_entity_id &&
        a.timing === action.timing &&
        a.offset_minutes === action.offset_minutes &&
        a.delay_minutes === action.delay_minutes,
    );

  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="editor-title">
        <header className="dialog-header">
          <button type="button" className="icon-button" aria-label={t.close} onClick={onClose}>
            <Icon path={mdiClose} />
          </button>
          <h2 id="editor-title">{title}</h2>
          <button type="button" className="primary" disabled={saving} onClick={() => void save()}>
            {saving ? t.saving : t.save}
          </button>
        </header>

        <div className="dialog-body">
          {problem ? (
            <div className="banner error" role="alert">
              <span>{problem.text}</span>
              {problem.kind === 'conflict' && draft.id ? (
                <button type="button" onClick={() => void onReload(draft.id!)}>
                  {t.reload}
                </button>
              ) : null}
            </div>
          ) : null}
          {warnings.length > 0 ? (
            <div className="banner warning" role="status">
              <strong>{t.savedWithWarnings}</strong>
              <ul>
                {warnings.map((w) => (
                  <li key={w.alarm_action_id}>{w.message}</li>
                ))}
              </ul>
            </div>
          ) : null}

          <div className="fields">
            <div className="row">
              <label className="field time-field">
                <span>{t.time}</span>
                <input
                  type="time"
                  required
                  value={draft.time}
                  onChange={(e) => update({ time: e.target.value })}
                />
              </label>
              <div className="field switch-field">
                <span>{t.enabled}</span>
                <Switch
                  checked={draft.enabled}
                  label={t.enabled}
                  onChange={(enabled) => update({ enabled })}
                />
              </div>
            </div>
            {touched && errors.get('') ? <p className="field-error">{errors.get('')}</p> : null}

            <label className="field">
              <span>{t.label}</span>
              <input
                type="text"
                maxLength={LIMITS.labelLength}
                placeholder={t.labelPlaceholder}
                value={draft.label}
                onChange={(e) => update({ label: e.target.value })}
              />
            </label>

            <div className="field">
              <span>{t.repeat}</span>
              <div className="day-chips" role="group" aria-label={t.repeat}>
                {WEEKDAYS.map((day) => (
                  <button
                    key={day}
                    type="button"
                    className={draft.repeat_days.includes(day) ? 'chip selected' : 'chip'}
                    aria-pressed={draft.repeat_days.includes(day)}
                    aria-label={dayName(day, 'long')}
                    onClick={() => toggleDay(day)}
                  >
                    {dayName(day, 'narrow')}
                  </button>
                ))}
              </div>
              <small className="muted">{t.repeatHint}</small>
            </div>
          </div>

          <section className="actions-section">
            <h3>{t.actions}</h3>
            {draft.actions.length === 0 ? <p className="muted">{t.noActions}</p> : null}
            <ul className="action-list">
              {draft.actions.map((action) => {
                const script = scriptByEntity.get(action.script_entity_id);
                const unavailable = scriptsLoaded && action.script_entity_id !== '' && !script;
                const pending = !scriptsLoaded && action.script_entity_id !== '';
                const isMissed = missed.some((m) => m.key === action.key);
                const error = touched ? errors.get(action.key) : undefined;
                return (
                  <li key={action.key} className={action.enabled ? 'action' : 'action off'}>
                    <div className="action-head">
                      <span className="script-icon">
                        <Icon path={script?.icon_path ?? mdiScriptTextOutline} />
                      </span>
                      <select
                        aria-label={t.script}
                        value={action.script_entity_id}
                        onChange={(e) =>
                          updateAction(action.key, (a) => ({
                            ...a,
                            script_entity_id: e.target.value,
                          }))
                        }
                      >
                        {action.script_entity_id === '' ? (
                          <option value="">{t.pickScript}</option>
                        ) : null}
                        {unavailable ? (
                          <option value={action.script_entity_id}>
                            {action.script_entity_id} ({t.unavailable})
                          </option>
                        ) : null}
                        {pending ? (
                          <option value={action.script_entity_id}>{action.script_entity_id}</option>
                        ) : null}
                        {scripts.map((s) => (
                          <option key={s.entity_id} value={s.entity_id}>
                            {s.name}
                          </option>
                        ))}
                      </select>
                      <Switch
                        checked={action.enabled}
                        label={t.enabled}
                        onChange={(enabled) => updateAction(action.key, (a) => ({ ...a, enabled }))}
                      />
                      <button
                        type="button"
                        className="icon-button"
                        title={t.remove}
                        aria-label={t.remove}
                        onClick={() =>
                          update({ actions: draft.actions.filter((a) => a.key !== action.key) })
                        }
                      >
                        <Icon path={mdiDelete} />
                      </button>
                    </div>
                    {script?.description ? (
                      <p className="muted small">{script.description}</p>
                    ) : null}
                    <div className="action-timing">
                      <select
                        aria-label={t.timing}
                        value={action.timing}
                        onChange={(e) =>
                          updateAction(action.key, (a) =>
                            withTiming(a, e.target.value as DraftAction['timing']),
                          )
                        }
                      >
                        {/* after_dismiss runs need dismiss events (phase 6); only shown when already set. */}
                        {TIMINGS.filter((x) => x !== 'after_dismiss' || action.timing === x).map(
                          (timing) => (
                            <option key={timing} value={timing}>
                              {t.timings[timing]}
                            </option>
                          ),
                        )}
                      </select>
                      {action.offset_minutes !== null ||
                      action.timing === 'before' ||
                      action.timing === 'after' ? (
                        <label className="inline-field">
                          <input
                            type="number"
                            inputMode="numeric"
                            min={1}
                            max={720}
                            aria-label={t.minutes}
                            value={action.offset_minutes ?? ''}
                            onChange={(e) =>
                              updateAction(action.key, (a) => ({
                                ...a,
                                offset_minutes: parseNumber(e.target.value),
                              }))
                            }
                          />
                          <span>min</span>
                        </label>
                      ) : null}
                      {action.timing === 'after_dismiss' ? (
                        <label className="inline-field">
                          <input
                            type="number"
                            inputMode="numeric"
                            min={0}
                            max={120}
                            aria-label={t.delay}
                            value={action.delay_minutes ?? ''}
                            onChange={(e) =>
                              updateAction(action.key, (a) => ({
                                ...a,
                                delay_minutes: parseNumber(e.target.value),
                              }))
                            }
                          />
                          <span>min</span>
                        </label>
                      ) : null}
                      <span className="spacer" />
                      {saved(action) && script ? (
                        <button
                          type="button"
                          className="icon-button"
                          title={t.runNow}
                          aria-label={t.runNow}
                          onClick={() => void runNow(action)}
                        >
                          <Icon path={mdiPlay} />
                        </button>
                      ) : null}
                    </div>
                    {unavailable ? (
                      <p className="hint warning">
                        <Icon path={mdiAlertOutline} size={16} />
                        {t.unavailableHint}
                      </p>
                    ) : null}
                    {isMissed ? (
                      <p className="hint warning">
                        <Icon path={mdiAlertOutline} size={16} />
                        {t.beforeMissed(
                          action.offset_minutes ?? 0,
                          script?.name ?? action.script_entity_id,
                        )}
                      </p>
                    ) : null}
                    {error ? <p className="field-error">{error}</p> : null}
                    {runResult[action.key] ? (
                      <p className="muted small">{runResult[action.key]}</p>
                    ) : null}
                  </li>
                );
              })}
            </ul>
            {!scriptsLoaded ? (
              <p className={scriptState.error ? 'field-error' : 'muted'}>
                {scriptState.error ?? t.loadingScripts}
              </p>
            ) : scripts.length === 0 ? (
              <p className="muted">{t.noScripts(scriptState.label)}</p>
            ) : draft.actions.length >= LIMITS.actionsPerAlarm ? (
              <p className="muted">{t.actionLimit(LIMITS.actionsPerAlarm)}</p>
            ) : (
              <button
                type="button"
                className="secondary"
                onClick={() =>
                  update({ actions: [...draft.actions, newAction(scripts[0]!.entity_id)] })
                }
              >
                <Icon path={mdiPlus} size={20} />
                <span>{t.addAction}</span>
              </button>
            )}
          </section>

          {!isNew ? (
            <section className="danger-zone">
              {confirmDelete ? (
                <div className="confirm">
                  <span>{t.confirmDelete}</span>
                  <button
                    type="button"
                    className="danger"
                    disabled={saving}
                    onClick={() => void remove()}
                  >
                    {t.yesDelete}
                  </button>
                  <button type="button" onClick={() => setConfirmDelete(false)}>
                    {t.cancel}
                  </button>
                </div>
              ) : (
                <div className="confirm">
                  <button
                    type="button"
                    className="danger-outline"
                    onClick={() => setConfirmDelete(true)}
                  >
                    <Icon path={mdiDelete} size={20} />
                    <span>{t.delete}</span>
                  </button>
                  <button type="button" onClick={() => onDuplicate(copyDraft(base))}>
                    <Icon path={mdiContentCopy} size={20} />
                    <span>{t.duplicate}</span>
                  </button>
                </div>
              )}
            </section>
          ) : null}
        </div>
      </div>
    </div>
  );
}
