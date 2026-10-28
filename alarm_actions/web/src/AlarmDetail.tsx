import { useCallback, useEffect, useRef, useState, type PointerEvent } from 'react';
import { mdiAlertOutline, mdiContentCopy, mdiDelete, mdiPlay, mdiScriptTextOutline } from '@mdi/js';
import {
  LIMITS,
  WEEKDAYS,
  type ActionDefinition,
  type Alarm,
  type AlarmSaveResponse,
  type AlarmWarning,
  type RunNowResponse,
  type Weekday,
} from '@alarm-actions/shared';
import { ApiError, api, errorMessage } from './api';
import type { Data, Scripts } from './App';
import { ConfirmDialog } from './ConfirmDialog';
import {
  copyDraft,
  draftBody,
  draftFromAlarm,
  missedBeforeActions,
  newKey,
  sameDraft,
  validateDraft,
  type Draft,
  type DraftAction,
} from './draft';
import { Icon, STROKE, StrokeIcon } from './Icon';
import { dayName, formatDays, formatTime, t } from './strings';
import { Switch } from './Switch';
import {
  PAD,
  cardTops,
  fromSigned,
  geometry,
  shiftTime,
  signedOffset,
  yOf,
  type Geometry,
} from './timeline';

/** Stage of the list ↔ detail morph (App.tsx). */
export type Stage = 'idle' | 'lift' | 'open' | 'unlift' | 'close';
export type OpenMode = 'view' | 'pick';
type Mode = OpenMode | 'place';
type EditorKind = 'label' | 'repeat' | 'snooze';

/** The action being placed: a new one (key null) or an existing one. */
interface Placement {
  key: string | null;
  script: string;
  dismiss: boolean;
  /** Signed minutes from the alarm (around the alarm). */
  offset: number;
  /** Minutes after the dismissal (after dismiss). */
  delay: number;
  enabled: boolean;
}

interface Props {
  initial: Draft;
  initialMode: OpenMode;
  data: Data;
  scripts: Scripts;
  stage: Stage;
  onBack: () => void;
  onNotify: (text: string) => void;
  onSaved: (alarm: Alarm) => void;
  onDeleted: (id: string) => void;
  onDuplicate: (draft: Draft) => void;
  onReload: (id: string) => Promise<void>;
}

type Problem = { kind: 'conflict' | 'error'; text: string } | null;

const MAX_OFFSET = 720;
const MAX_DELAY = 120;
const SNOOZE_MAX = 30;
const PICK_OFFSET = -15;
const FLASH_MS = 1900;
const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

/** The draft with the placement applied, and the key of the placed action. */
function applyPlacement(draft: Draft, p: Placement): { draft: Draft; key: string } {
  const position = p.dismiss
    ? { timing: 'after_dismiss' as const, offset_minutes: null, delay_minutes: p.delay }
    : { ...fromSigned(p.offset), delay_minutes: null };
  if (p.key) {
    const key = p.key;
    return {
      key,
      draft: {
        ...draft,
        actions: draft.actions.map((a) =>
          a.key === key ? { ...a, ...position, enabled: p.enabled } : a,
        ),
      },
    };
  }
  const key = newKey();
  const action: DraftAction = { key, script_entity_id: p.script, ...position, enabled: p.enabled };
  return { key, draft: { ...draft, actions: [...draft.actions, action] } };
}

function relLabel(action: DraftAction): string {
  if (action.timing === 'after_dismiss') {
    return action.delay_minutes ? t.relAfter(action.delay_minutes) : t.relNow;
  }
  const o = signedOffset(action) ?? 0;
  return o < 0 ? t.relBefore(-o) : o > 0 ? t.relAfter(o) : t.relWith;
}

/**
 * Alarm detail (FR-UI-02..04): the alarm with its actions on a timeline. Changes stay in a draft
 * until Save; picking and placing an action only changes the draft (docs/design.md).
 */
export function AlarmDetail({
  initial,
  initialMode,
  data,
  scripts: scriptState,
  stage,
  onBack,
  onNotify,
  onSaved,
  onDeleted,
  onDuplicate,
  onReload,
}: Props) {
  const [draft, setDraft] = useState<Draft>(initial);
  const [base, setBase] = useState<Draft>(initial);
  const [mode, setMode] = useState<Mode>(initialMode);
  const [place, setPlace] = useState<Placement | null>(null);
  const [editor, setEditor] = useState<{ kind: EditorKind; row: boolean } | null>(null);
  const [saving, setSaving] = useState(false);
  const [problem, setProblem] = useState<Problem>(null);
  const [warnings, setWarnings] = useState<AlarmWarning[]>([]);
  const [touched, setTouched] = useState(false);
  const [menu, setMenu] = useState(false);
  const [confirm, setConfirm] = useState<'delete' | 'discard' | null>(null);
  const [runResult, setRunResult] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const scroller = useRef<HTMLDivElement | null>(null);
  const dragging = useRef(false);
  const flashTimer = useRef<number | undefined>(undefined);

  // Keep the "before" warning current while the detail stays open.
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => {
      window.clearInterval(timer);
      window.clearTimeout(flashTimer.current);
    };
  }, []);

  const scriptsLoaded = scriptState.list !== null;
  const scripts: ActionDefinition[] = scriptState.list ?? [];
  const scriptOf = (entity: string) => scripts.find((s) => s.entity_id === entity);
  const scriptName = (entity: string) => scriptOf(entity)?.name ?? entity;
  const errors = validateDraft(draft);
  const missed = new Set(missedBeforeActions(draft, data.info.time_zone, now).map((a) => a.key));
  const isNew = draft.id === undefined;
  const changed = !sameDraft(draft, base);
  const active = mode === 'place' ? place : null;
  const placing = active !== null;
  const placeAround = active && !active.dismiss ? active : null;
  const placeDismiss = active && active.dismiss ? active : null;
  const defaultSnooze = scriptState.snooze;
  const snooze = draft.snooze_minutes ?? defaultSnooze;
  const canAdd = scriptsLoaded && scripts.length > 0 && draft.actions.length < LIMITS.actionsPerAlarm;

  const around = draft.actions
    .map((a) => ({ a, o: signedOffset(a) }))
    .filter((x): x is { a: DraftAction; o: number } => x.o !== null);
  const afterDismiss = draft.actions.filter((a) => a.timing === 'after_dismiss');
  const g: Geometry = geometry([
    ...around.map((x) => x.o),
    ...(placeAround ? [placeAround.offset] : []),
  ]);
  const showTimeline = draft.actions.length > 0 || mode !== 'view';

  const update = (patch: Partial<Draft>) => {
    setDraft((d) => ({ ...d, ...patch }));
    setWarnings([]);
  };

  const toggleEditor = (kind: EditorKind, row: boolean) =>
    setEditor((e) => (e && e.kind === kind && e.row === row ? null : { kind, row }));

  const leaveMode = () => {
    setMode('view');
    setPlace(null);
    setRunResult(null);
  };

  const back = useCallback(() => {
    if (mode !== 'view') {
      setMode('view');
      setPlace(null);
      return;
    }
    if (changed && !saving) setConfirm('discard');
    else onBack();
  }, [mode, changed, saving, onBack]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || confirm) return;
      if (menu) setMenu(false);
      else back();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [back, confirm, menu]);

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
      onNotify(isNew ? t.alarmCreated(formatTime(res.alarm.time)) : t.alarmSaved);
      const fresh = draftFromAlarm(res.alarm);
      setDraft(fresh);
      setBase(fresh);
      setWarnings(res.warnings);
      setTouched(false);
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
      setConfirm(null);
      onNotify(t.alarmDeleted);
      onDeleted(draft.id);
    } catch (err) {
      handleError(err);
      setConfirm(null);
    } finally {
      setSaving(false);
    }
  };

  /** Saved, unchanged actions of a saved alarm can run now (FR-UI-08). */
  const savedAction = (key: string | null) => {
    const action = draft.actions.find((a) => a.key === key);
    if (!action?.id || isNew) return null;
    const same = base.actions.find(
      (a) =>
        a.id === action.id &&
        a.script_entity_id === action.script_entity_id &&
        a.timing === action.timing &&
        a.offset_minutes === action.offset_minutes &&
        a.delay_minutes === action.delay_minutes,
    );
    return same && scriptOf(action.script_entity_id) ? action : null;
  };

  const runNow = async (action: DraftAction) => {
    if (!draft.id || !action.id) return;
    setRunResult('…');
    try {
      const res = await api<RunNowResponse>(`alarms/${draft.id}/actions/${action.id}/run`, {
        method: 'POST',
      });
      setRunResult(t.runResult(res.reason ? `${res.result} (${res.reason})` : res.result));
    } catch (err) {
      setRunResult(errorMessage(err));
    }
  };

  const scrollToOffset = (offset: number) =>
    scroller.current?.scrollTo({ top: Math.max(0, yOf(g, offset) - 180), behavior: 'smooth' });

  // The timeline opens with the alarm a bit above the middle, as the morph expects. Stable, so it
  // only runs when the scroll area mounts.
  const alarmY = useRef(0);
  alarmY.current = yOf(g, 0);
  const scrollRef = useCallback((el: HTMLDivElement | null) => {
    if (el) el.scrollTop = Math.max(0, alarmY.current - el.clientHeight * 0.45);
    scroller.current = el;
  }, []);

  const startPlace = (action: DraftAction) => {
    setEditor(null);
    setRunResult(null);
    setMode('place');
    setPlace({
      key: action.key,
      script: action.script_entity_id,
      dismiss: action.timing === 'after_dismiss',
      offset: signedOffset(action) ?? PICK_OFFSET,
      delay: action.delay_minutes ?? 0,
      enabled: action.enabled,
    });
  };

  const pick = (script: string) => {
    setMode('place');
    setPlace({ key: null, script, dismiss: false, offset: PICK_OFFSET, delay: 0, enabled: true });
    scrollToOffset(PICK_OFFSET);
  };

  const commit = () => {
    if (!place) return;
    const result = applyPlacement(draft, place);
    update({ actions: result.draft.actions });
    leaveMode();
    setFlash(result.key);
    window.clearTimeout(flashTimer.current);
    flashTimer.current = window.setTimeout(() => setFlash(null), FLASH_MS);
  };

  const removePlaced = () => {
    if (!place?.key) return;
    const key = place.key;
    update({ actions: draft.actions.filter((a) => a.key !== key) });
    leaveMode();
  };

  const moveTo = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    const minutes = (e.clientY - r.top - PAD) / g.ppm - g.range;
    const o = clamp(Math.round(minutes / 5) * 5, -g.range, g.range);
    setPlace((p) => (p && p.offset !== o ? { ...p, offset: o } : p));
  };

  const dragOn = placeAround !== null;
  const onDown = (e: PointerEvent<HTMLDivElement>) => {
    if (!dragOn) return;
    dragging.current = true;
    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch {
      // Capture is optional.
    }
    moveTo(e);
  };
  const onMove = (e: PointerEvent<HTMLDivElement>) => {
    if (dragging.current && dragOn) moveTo(e);
  };
  const onUp = () => {
    dragging.current = false;
  };

  const cardOpacity = (key: string) =>
    stage === 'lift' ? 0 : active ? (key === active.key ? 0 : 0.35) : 1;
  const cardTransition =
    stage === 'open'
      ? 'opacity .3s .65s, top .35s var(--ease-soft)'
      : 'opacity .25s, top .35s var(--ease-soft)';
  const dotTransition = stage === 'open' ? 'opacity .1s .72s' : 'opacity .2s';

  const label = draft.label.trim() || (isNew ? t.newAlarm : t.alarm);

  const renderEditor = (kind: EditorKind) => (
    <div className="inline-editor rise">
      {kind === 'label' ? (
        <input
          type="text"
          autoFocus
          aria-label={t.label}
          maxLength={LIMITS.labelLength}
          placeholder={t.labelPlaceholder}
          value={draft.label}
          onChange={(e) => update({ label: e.target.value })}
          onKeyDown={(e) => e.key === 'Enter' && setEditor(null)}
        />
      ) : null}
      {kind === 'repeat' ? (
        <>
          <div className="day-chips" role="group" aria-label={t.repeat}>
            {WEEKDAYS.map((day) => (
              <button
                key={day}
                type="button"
                className={draft.repeat_days.includes(day) ? 'day selected' : 'day'}
                aria-pressed={draft.repeat_days.includes(day)}
                aria-label={dayName(day, 'long')}
                onClick={() => toggleDay(day)}
              >
                {dayName(day, 'narrow')}
              </button>
            ))}
          </div>
          <p className="muted small">{t.repeatHint}</p>
        </>
      ) : null}
      {kind === 'snooze' ? (
        <div className="stepper-row">
          <div className="stepper" role="group" aria-label={t.snooze}>
            <button
              type="button"
              className="round"
              aria-label="−"
              disabled={snooze <= 1}
              onClick={() => update({ snooze_minutes: clamp(snooze - 1, 1, SNOOZE_MAX) })}
            >
              −
            </button>
            <span className="stepper-value">{snooze} min</span>
            <button
              type="button"
              className="round"
              aria-label="+"
              disabled={snooze >= SNOOZE_MAX}
              onClick={() => update({ snooze_minutes: clamp(snooze + 1, 1, SNOOZE_MAX) })}
            >
              +
            </button>
          </div>
          <button
            type="button"
            className={draft.snooze_minutes === null ? 'chip selected' : 'chip'}
            aria-pressed={draft.snooze_minutes === null}
            onClick={() => update({ snooze_minutes: null })}
          >
            {t.useDefault(defaultSnooze)}
          </button>
        </div>
      ) : null}
    </div>
  );

  const toggleDay = (day: Weekday) =>
    update({
      repeat_days: draft.repeat_days.includes(day)
        ? draft.repeat_days.filter((d) => d !== day)
        : WEEKDAYS.filter((d) => d === day || draft.repeat_days.includes(d)),
    });

  const renderCard = (action: DraftAction, top: number | null) => {
    const script = scriptOf(action.script_entity_id);
    const unavailable = scriptsLoaded && !script;
    const classes = ['tl-card'];
    if (!action.enabled) classes.push('off');
    if (unavailable) classes.push('unavailable');
    if (flash === action.key) classes.push('flash');
    if (touched && errors.has(action.key)) classes.push('error');
    if (missed.has(action.key)) classes.push('missed');
    const name = scriptName(action.script_entity_id);
    const rel = action.enabled ? relLabel(action) : t.off;
    return (
      <button
        key={action.key}
        type="button"
        className={classes.join(' ')}
        aria-label={`${name}, ${rel}`}
        title={
          unavailable
            ? t.unavailableHint
            : missed.has(action.key)
              ? t.beforeMissed(action.offset_minutes ?? 0, name)
              : (errors.get(action.key) ?? undefined)
        }
        style={{
          top: top ?? undefined,
          opacity: cardOpacity(action.key),
          pointerEvents: placing ? 'none' : undefined,
          transition: cardTransition,
        }}
        onClick={() => startPlace(action)}
      >
        <Icon path={unavailable ? mdiAlertOutline : (script?.icon_path ?? mdiScriptTextOutline)} size={16} />
        <span className="tl-name">{name}</span>
        <span className="tl-detail">{script?.description ?? ''}</span>
        <span className="tl-rel">{rel}</span>
      </button>
    );
  };

  const placedName = place ? scriptName(place.script) : '';
  const placedIcon = place ? (scriptOf(place.script)?.icon_path ?? mdiScriptTextOutline) : '';
  const runsAt = place ? formatTime(shiftTime(draft.time, place.offset)) : '';
  const preview = place ? applyPlacement(draft, place) : null;
  const previewMissed =
    preview !== null &&
    missedBeforeActions(preview.draft, data.info.time_zone, now).some((a) => a.key === preview.key);
  const runnable = place ? savedAction(place.key) : null;

  const readout = !place
    ? ''
    : place.dismiss
      ? place.delay === 0
        ? t.readoutDismissNow
        : t.readoutDismiss(place.delay)
      : place.offset < 0
        ? t.readoutBefore(-place.offset)
        : place.offset > 0
          ? t.readoutAfter(place.offset)
          : t.readoutAt;

  const step = (delta: number) =>
    setPlace((p) =>
      !p
        ? p
        : p.dismiss
          ? { ...p, delay: clamp(p.delay + delta, 0, MAX_DELAY) }
          : { ...p, offset: clamp(p.offset + delta, -MAX_OFFSET, MAX_OFFSET) },
    );

  const ticks: number[] = [];
  for (let o = -g.range; o <= g.range; o += g.major) if (o !== 0) ticks.push(o);
  const minor: number[] = [];
  for (let o = -g.range; o <= g.range; o += g.minor) minor.push(o);
  const tops = cardTops(around.map((x) => yOf(g, x.o)));
  const y0 = yOf(g, 0);
  const gy = placeAround ? yOf(g, placeAround.offset) : 0;

  return (
    <>
      <nav className="crumbs" aria-label={t.title}>
        <button type="button" className="crumb-back" onClick={back}>
          <StrokeIcon d={STROKE.back} size={22} />
          {t.title}
        </button>
        <span aria-hidden="true" className="muted">
          /
        </span>
        <span aria-current="page" className="crumb-here">
          {formatTime(draft.time)}
        </span>
        <span className="spacer" />
        {(isNew || changed) && mode === 'view' ? (
          <button type="button" className="save rise" disabled={saving} onClick={() => void save()}>
            {saving ? t.saving : t.save}
          </button>
        ) : null}
        {!isNew ? (
          <span className="menu-anchor">
            <button
              type="button"
              className="icon-button"
              aria-label={t.more}
              aria-expanded={menu}
              onClick={() => setMenu((m) => !m)}
            >
              <StrokeIcon d={STROKE.more} width={3} />
            </button>
            {menu ? (
              <>
                <div className="menu-backdrop" onClick={() => setMenu(false)} />
                <div className="menu rise" role="menu">
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      setMenu(false);
                      onDuplicate(copyDraft(base));
                    }}
                  >
                    <Icon path={mdiContentCopy} size={18} />
                    {t.duplicate}
                  </button>
                  <button
                    type="button"
                    role="menuitem"
                    className="danger-text"
                    onClick={() => {
                      setMenu(false);
                      setConfirm('delete');
                    }}
                  >
                    <Icon path={mdiDelete} size={18} />
                    {t.delete}
                  </button>
                </div>
              </>
            ) : null}
          </span>
        ) : null}
      </nav>

      <div className="det-head">
        <div className="det-title">
          <input
            type="time"
            className="big-time"
            required
            aria-label={t.time}
            value={draft.time}
            onChange={(e) => update({ time: e.target.value })}
          />
          <button type="button" className="det-label" onClick={() => toggleEditor('label', false)}>
            {label} · {formatDays(draft.repeat_days)}
          </button>
        </div>
        <div className="chips" role="group">
          <button
            type="button"
            className={editor?.kind === 'repeat' && !editor.row ? 'chip selected' : 'chip'}
            aria-expanded={editor?.kind === 'repeat' && !editor.row}
            onClick={() => toggleEditor('repeat', false)}
          >
            <StrokeIcon d={STROKE.repeat} size={16} />
            {formatDays(draft.repeat_days)}
          </button>
          <button
            type="button"
            className={editor?.kind === 'snooze' && !editor.row ? 'chip selected' : 'chip'}
            aria-expanded={editor?.kind === 'snooze' && !editor.row}
            onClick={() => toggleEditor('snooze', false)}
          >
            <StrokeIcon d={STROKE.snooze} size={16} />
            {t.snoozeChip(snooze)}
          </button>
        </div>
        {editor && !editor.row ? renderEditor(editor.kind) : null}
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
        {touched && errors.size > 0 ? (
          <div className="banner error" role="alert">
            <strong>{t.fixFirst}</strong>
            <ul>
              {[...new Set(errors.values())].map((text) => (
                <li key={text}>{text}</li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>

      {!showTimeline ? (
        <div className="scroll simple rise">
          {(['label', 'repeat', 'snooze'] as const).map((kind) => (
            <div key={kind}>
              <button
                type="button"
                className="row-button"
                aria-expanded={editor?.kind === kind && editor.row}
                onClick={() => toggleEditor(kind, true)}
              >
                <span>{kind === 'label' ? t.label : kind === 'repeat' ? t.repeat : t.snooze}</span>
                <span className="muted">
                  {kind === 'label'
                    ? draft.label.trim() || '—'
                    : kind === 'repeat'
                      ? formatDays(draft.repeat_days)
                      : `${snooze} min`}
                </span>
              </button>
              {editor?.kind === kind && editor.row ? renderEditor(kind) : null}
            </div>
          ))}
          <button type="button" className="add-actions" onClick={() => setMode('pick')}>
            <span className="add-actions-title">
              <StrokeIcon d={STROKE.plus} size={18} width={2} />
              {t.addActions}
            </span>
            <span className="muted small">{t.addActionsHint}</span>
          </button>
        </div>
      ) : (
        <div className="scroll tl-scroll" data-viewport="1" ref={scrollRef}>
          <div
            className="tl"
            aria-label={t.timeline}
            style={{ height: g.height, touchAction: dragOn ? 'none' : 'auto', cursor: dragOn ? 'ns-resize' : undefined }}
            onPointerDown={onDown}
            onPointerMove={onMove}
            onPointerUp={onUp}
            onPointerCancel={onUp}
          >
            <span className="tl-rail" aria-hidden="true" style={{ top: PAD, height: g.height - PAD * 2 }} />
            {minor.map((o) => (
              <span key={`m${o}`} className="tl-minor" aria-hidden="true" style={{ top: yOf(g, o) }} />
            ))}
            {ticks.map((o) => (
              <span key={`t${o}`} className="tl-tick" aria-hidden="true" style={{ top: yOf(g, o) - 8 }}>
                {formatTime(shiftTime(draft.time, o))}
              </span>
            ))}
            <span className="tl-alarm-line" aria-hidden="true" style={{ top: y0 - 1 }} />
            <span className="tl-alarm-label" style={{ top: y0 - 17 }}>
              {formatTime(draft.time)}
            </span>
            <span className="tl-alarm-dot" data-alarmdot="1" aria-hidden="true" style={{ top: y0 - 6.5 }} />
            {around.map(({ a, o }) => (
              <span
                key={`d${a.key}`}
                className="tl-dot"
                aria-hidden="true"
                style={{ top: yOf(g, o), opacity: cardOpacity(a.key), transition: dotTransition }}
              />
            ))}
            {around.map(({ a }, i) => renderCard(a, tops[i]!))}
            {placeAround ? (
              <>
                <span className="tl-guide" aria-hidden="true" style={{ top: gy }} />
                <span className="tl-guide-label" aria-hidden="true" style={{ top: gy - 8 }}>
                  {runsAt}
                </span>
                <span className="tl-knob" aria-hidden="true" style={{ top: gy - 8 }} />
                <span className="tl-ghost" aria-hidden="true" style={{ top: gy - 18 }}>
                  <Icon path={placedIcon} size={16} />
                  <span className="tl-name">{placedName}</span>
                  <StrokeIcon d={STROKE.move} size={16} width={2} />
                </span>
              </>
            ) : null}
          </div>
          {afterDismiss.length > 0 || placeDismiss !== null ? (
            <section className="dismiss-section">
              <h3>{t.afterYouDismiss}</h3>
              <p className="muted small">{t.afterYouDismissHint}</p>
              <div className="dismiss-list">
                {afterDismiss.map((a) => renderCard(a, null))}
                {placeDismiss ? (
                  <span className="tl-ghost static" aria-hidden="true">
                    <Icon path={placedIcon} size={16} />
                    <span className="tl-name">{placedName}</span>
                    <span>{placeDismiss.delay === 0 ? t.relNow : t.relAfter(placeDismiss.delay)}</span>
                  </span>
                ) : null}
              </div>
            </section>
          ) : null}
        </div>
      )}

      {showTimeline ? (
        <div className="det-bottom">
          {mode === 'view' ? (
            <div className="rise bottom-view" key="view">
              {canAdd ? (
                <>
                  <button type="button" className="primary-wide" onClick={() => setMode('pick')}>
                    <StrokeIcon d={STROKE.plus} size={18} width={2} />
                    {t.addAction}
                  </button>
                  <p className="muted small center">{t.tapHint}</p>
                </>
              ) : (
                <p className="muted small center">
                  {!scriptsLoaded
                    ? (scriptState.error ?? t.loadingScripts)
                    : scripts.length === 0
                      ? t.noScripts(scriptState.label)
                      : t.actionLimit(LIMITS.actionsPerAlarm)}
                </p>
              )}
            </div>
          ) : null}
          {mode === 'pick' ? (
            <div className="rise bottom-pick" key="pick">
              <div className="bottom-title">
                <span>{t.whatShouldHappen}</span>
                <button type="button" className="text-button" onClick={leaveMode}>
                  {t.cancel}
                </button>
              </div>
              {canAdd ? (
                <div className="tiles">
                  {scripts.map((s) => (
                    <button key={s.entity_id} type="button" className="tile" onClick={() => pick(s.entity_id)}>
                      <Icon path={s.icon_path ?? mdiScriptTextOutline} size={20} />
                      <span>{s.name}</span>
                    </button>
                  ))}
                </div>
              ) : (
                <p className="muted small pad">
                  {!scriptsLoaded
                    ? (scriptState.error ?? t.loadingScripts)
                    : scripts.length === 0
                      ? t.noScripts(scriptState.label)
                      : t.actionLimit(LIMITS.actionsPerAlarm)}
                </p>
              )}
            </div>
          ) : null}
          {active ? (
            <div className="rise bottom-place" key="place">
              <div className="segmented" role="group">
                <button type="button" aria-pressed={!active.dismiss} onClick={() => setPlace({ ...active, dismiss: false })}>
                  {t.aroundAlarm}
                </button>
                <button type="button" aria-pressed={active.dismiss} onClick={() => setPlace({ ...active, dismiss: true })}>
                  {t.timings.after_dismiss}
                </button>
              </div>
              <div className="place-readout">
                <button type="button" className="round" aria-label={t.earlier} onClick={() => step(-1)}>
                  <StrokeIcon d={STROKE.up} size={18} />
                </button>
                <div className="readout">
                  <div className="readout-main">{readout}</div>
                  <div className="muted small">
                    {active.dismiss ? placedName : t.runsAt(placedName, runsAt)}
                  </div>
                </div>
                <button type="button" className="round" aria-label={t.later} onClick={() => step(1)}>
                  <StrokeIcon d={STROKE.down} size={18} />
                </button>
              </div>
              {previewMissed ? (
                <p className="hint warning">
                  <Icon path={mdiAlertOutline} size={16} />
                  {t.beforeMissed(-active.offset, placedName)}
                </p>
              ) : null}
              <div className="place-options">
                <span>{t.enabled}</span>
                <Switch
                  checked={active.enabled}
                  label={t.enabled}
                  onChange={(enabled) => setPlace({ ...active, enabled })}
                />
                <span className="spacer" />
                {runnable ? (
                  <button type="button" className="pill" onClick={() => void runNow(runnable)}>
                    <Icon path={mdiPlay} size={16} />
                    {t.runNow}
                  </button>
                ) : null}
              </div>
              {runResult ? <p className="muted small">{runResult}</p> : null}
              <div className="place-buttons">
                {active.key ? (
                  <button type="button" className="sheet-button" onClick={removePlaced}>
                    {t.remove}
                  </button>
                ) : null}
                <button type="button" className="sheet-button" onClick={leaveMode}>
                  {t.cancel}
                </button>
                <button type="button" className="sheet-button accent" onClick={commit}>
                  {active.key ? t.done : t.placeHere}
                </button>
              </div>
            </div>
          ) : null}
        </div>
      ) : null}

      {confirm === 'delete' ? (
        <ConfirmDialog
          title={t.confirmDelete}
          text={t.deleteHint}
          confirmLabel={t.yesDelete}
          busy={saving}
          onConfirm={() => void remove()}
          onCancel={() => setConfirm(null)}
        />
      ) : null}
      {confirm === 'discard' ? (
        <ConfirmDialog
          title={t.discardTitle}
          text={t.discardText}
          confirmLabel={t.discard}
          busy={false}
          onConfirm={() => {
            setConfirm(null);
            onBack();
          }}
          onCancel={() => setConfirm(null)}
        />
      ) : null}
    </>
  );
}
