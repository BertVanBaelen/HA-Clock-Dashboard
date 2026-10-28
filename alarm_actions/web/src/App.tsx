import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import type {
  ActionDefinition,
  ActionsResponse,
  Alarm,
  Client,
  ClientsResponse,
  Info,
  Me,
  PlannedAction,
  PlannedResponse,
  Settings,
} from '@alarm-actions/shared';
import { DEFAULT_SETTINGS } from '@alarm-actions/shared';
import { api, errorMessage } from './api';
import { AlarmDetail, type OpenMode, type Stage } from './AlarmDetail';
import { AlarmList } from './AlarmList';
import { AppTab } from './AppTab';
import { editDraft, emptyDraft, type Draft } from './draft';
import { STROKE, StrokeIcon } from './Icon';
import { PhonesList } from './PhonesList';
import { PlannedList } from './PlannedList';
import { formatDuration, formatNext, t } from './strings';
import { MINI_WIDTH, aroundOffsets, geometry, prefersReducedMotion } from './timeline';
import { useToast } from './Toast';

type Tab = 'alarms' | 'app';

export interface Data {
  info: Info;
  me: Me;
  alarms: Alarm[];
  /** Upcoming actions of other users' alarms. */
  planned: PlannedAction[];
  /** The user's phones with the app. */
  clients: Client[];
}

/** The install tab is only useful on the phone itself (the APK is downloaded there). */
const IS_ANDROID = typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent);

/** Labeled scripts; loaded after the alarm list so they never delay opening the panel. */
export interface Scripts {
  list: ActionDefinition[] | null;
  label: string;
  /** Default snooze length from the add-on options. */
  snooze: number;
  error: string | null;
}

/** The open alarm; `seq` remounts the detail for every open. */
interface Editing {
  draft: Draft;
  mode: OpenMode;
  alarmId: string | null;
  seq: number;
  /** Fade in instead of morphing. */
  fade: boolean;
}

interface Point {
  x: number;
  y: number;
}

/** List ↔ detail morph (docs/design.md): where the mini timeline flies from and to. */
interface Morph {
  stage: Stage;
  from: Point | null;
  to: (Point & { clip: number }) | null;
  alarmId: string | null;
}

const POLL_MS = 30_000;
const MORPH_MS = 900;
const EASE_MORPH = 'cubic-bezier(.65,0,.35,1)';
const IDLE: Morph = { stage: 'idle', from: null, to: null, alarmId: null };

const raf2 = (f: () => void) => requestAnimationFrame(() => requestAnimationFrame(f));

export function App() {
  const [data, setData] = useState<Data | null>(null);
  const [scripts, setScripts] = useState<Scripts>({
    list: null,
    label: '',
    snooze: DEFAULT_SETTINGS.snooze_minutes,
    error: null,
  });
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('alarms');
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [morph, setMorph] = useState<Morph>(IDLE);
  const loading = useRef(false);
  const root = useRef<HTMLDivElement>(null);
  const seq = useRef(0);
  const timer = useRef<number | undefined>(undefined);
  const toast = useToast();

  const load = useCallback(async () => {
    if (loading.current) return;
    loading.current = true;
    try {
      const [info, me, alarms, planned, clients] = await Promise.all([
        api<Info>('info'),
        api<Me>('me'),
        api<{ alarms: Alarm[] }>('alarms'),
        api<PlannedResponse>('planned'),
        api<ClientsResponse>('clients'),
      ]);
      setData({ info, me, alarms: alarms.alarms, planned: planned.planned, clients: clients.clients });
      setError(null);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      loading.current = false;
    }
  }, []);

  const loadScripts = useCallback(async () => {
    try {
      const [actions, settings] = await Promise.all([
        api<ActionsResponse>('actions'),
        api<Settings>('settings'),
      ]);
      setScripts({
        list: actions.actions,
        label: settings.action_label,
        snooze: settings.snooze_minutes,
        error: null,
      });
    } catch (err) {
      setScripts((s) => ({ ...s, error: errorMessage(err) }));
    }
  }, []);

  // Live data (FR-UI-01): poll while visible and refresh when the panel becomes visible again.
  useEffect(() => {
    void load().then(loadScripts);
    const poll = window.setInterval(() => {
      if (document.visibilityState === 'visible') void load();
    }, POLL_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') void load();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.clearInterval(poll);
      window.clearTimeout(timer.current);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [load, loadScripts]);

  /** Position of an element relative to the panel root. */
  const rect = (el: Element) => {
    const r = el.getBoundingClientRect();
    const base = root.current!.getBoundingClientRect();
    return { left: r.left - base.left, top: r.top - base.top, width: r.width, height: r.height };
  };
  const listPoint = (id: string): Point | null => {
    const el = root.current?.querySelector(`[data-line="${CSS.escape(id)}"]`);
    if (!el) return null;
    const p = rect(el);
    return { x: p.left + p.width / 2, y: p.top + p.height / 2 };
  };
  const detailPoint = (): Morph['to'] => {
    const dot = root.current?.querySelector('[data-alarmdot]');
    const viewport = root.current?.querySelector('[data-viewport]');
    if (!dot || !viewport) return null;
    const p = rect(dot);
    return { x: p.left + p.width / 2, y: p.top + p.height / 2, clip: rect(viewport).top };
  };
  const finishLater = (then?: () => void) => {
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => {
      setMorph(IDLE);
      then?.();
    }, MORPH_MS);
  };

  const open = (draft: Draft, mode: OpenMode, alarm?: Alarm) => {
    const morphs = alarm !== undefined && aroundOffsets(alarm.actions).length > 0 && !prefersReducedMotion();
    const from = alarm && morphs ? listPoint(alarm.id) : null;
    setCreating(false);
    setEditing({ draft, mode, alarmId: alarm?.id ?? null, seq: ++seq.current, fade: !from });
    // Scripts change rarely; refresh them in the background whenever an alarm is opened.
    void loadScripts();
    if (!alarm || !from) {
      setMorph(IDLE);
      return;
    }
    setMorph({ stage: 'lift', from, to: null, alarmId: alarm.id });
    raf2(() => {
      const to = detailPoint();
      if (!to) return setMorph(IDLE);
      setMorph((m) => ({ ...m, stage: 'open', to }));
      finishLater();
    });
  };

  const close = () => {
    const alarm = data?.alarms.find((a) => a.id === editing?.alarmId);
    const morphs = alarm && aroundOffsets(alarm.actions).length > 0 && !prefersReducedMotion();
    const to = morphs ? detailPoint() : null;
    const from = alarm && to ? listPoint(alarm.id) : null;
    if (!alarm || !to || !from) {
      setMorph(IDLE);
      setEditing(null);
      return;
    }
    setMorph({ stage: 'unlift', from, to, alarmId: alarm.id });
    raf2(() => {
      setMorph((m) => ({ ...m, stage: 'close' }));
      finishLater(() => setEditing(null));
    });
  };

  const replaceAlarm = useCallback((alarm: Alarm) => {
    setData((d) => {
      if (!d) return d;
      const exists = d.alarms.some((a) => a.id === alarm.id);
      const alarms = exists
        ? d.alarms.map((a) => (a.id === alarm.id ? alarm : a))
        : [...d.alarms, alarm];
      return { ...d, alarms };
    });
    // A new alarm becomes the open one, so closing can morph back to its card.
    setEditing((e) => (e && e.alarmId === null ? { ...e, alarmId: alarm.id } : e));
  }, []);

  const removeAlarm = useCallback((id: string) => {
    setData((d) => (d ? { ...d, alarms: d.alarms.filter((a) => a.id !== id) } : d));
  }, []);

  const removeClient = useCallback(
    (id: string) => {
      setData((d) => (d ? { ...d, clients: d.clients.filter((c) => c.id !== id) } : d));
      // Away depends on the remaining phones.
      void load();
    },
    [load],
  );

  // Screen opacity and the morph overlay per stage, as in the mockup.
  const stage = morph.stage;
  const isList = editing === null || stage === 'close';
  const atDetail = stage === 'open' || stage === 'unlift';
  const animating = stage === 'open' || stage === 'close';
  const listOpacity = { idle: isList ? 1 : 0, lift: 1, open: 0, unlift: 0, close: 1 }[stage];
  const detailOpacity = { idle: isList ? 0 : 1, lift: 0, open: 1, unlift: 1, close: 0 }[stage];
  const listTransition =
    stage === 'open' ? 'opacity .25s' : stage === 'close' ? 'opacity .3s .5s' : stage === 'idle' ? 'opacity .3s' : 'none';
  const detailTransition =
    stage === 'open' ? 'opacity .35s .2s' : stage === 'close' ? 'opacity .2s' : 'none';
  const morphAlarm = data?.alarms.find((a) => a.id === morph.alarmId);
  const offsets = morphAlarm ? aroundOffsets(morphAlarm.actions) : [];
  const mg = geometry(offsets);
  const k = atDetail ? mg.ppm : MINI_WIDTH / (mg.range * 2);
  const point = atDetail ? morph.to : morph.from;
  const moving = animating
    ? `left .75s ${EASE_MORPH}, width .75s ${EASE_MORPH}, height .75s ${EASE_MORPH}, background-color .75s`
    : 'none';

  const showAppTab = IS_ANDROID && data !== null && data.info.app_available;
  const activeTab: Tab = tab === 'app' && !showAppTab ? 'alarms' : tab;

  const next = data?.alarms
    .filter((a) => a.enabled && a.next_occurrence)
    .map((a) => a.next_occurrence!)
    .sort()[0];
  const subtitle = !data
    ? ''
    : next
      ? t.nextLine(formatNext(next, data.info.time_zone), formatDuration(Date.parse(next) - Date.now()))
      : t.noneOn;

  return (
    <div className="app" ref={root}>
      <section
        className="screen"
        aria-label={t.title}
        aria-hidden={!isList}
        style={{
          opacity: listOpacity,
          pointerEvents: stage === 'idle' && isList ? 'auto' : 'none',
          transition: listTransition,
        }}
      >
        <header className="list-head">
          <div>
            <h1>{activeTab === 'app' ? t.tabApp : t.title}</h1>
            {activeTab === 'alarms' ? <p className="muted">{subtitle}</p> : null}
          </div>
          {activeTab === 'alarms' && data ? (
            <button
              type="button"
              className="add-button"
              aria-label={t.addAlarm}
              aria-expanded={creating}
              style={{ transform: creating ? 'rotate(45deg)' : 'none' }}
              onClick={() => setCreating((c) => !c)}
            >
              <StrokeIcon d={STROKE.plus} />
            </button>
          ) : null}
        </header>

        <div className="scroll">
          <div className="content">
            {error ? (
              <div className="banner error" role="alert">
                <span>{error}</span>
                <button type="button" onClick={() => void load()}>
                  {t.retry}
                </button>
              </div>
            ) : null}
            {!data ? (
              error ? null : (
                <div className="skeleton-list" aria-busy="true" aria-label={t.loading}>
                  {[0, 1, 2].map((i) => (
                    <div key={i} className="skeleton" />
                  ))}
                </div>
              )
            ) : (
              <>
                {!data.info.ha_connected ? (
                  <div className="banner warning" role="status">
                    {t.haOffline}
                  </div>
                ) : null}
                {data.me.away ? (
                  <div className="banner warning" role="status">
                    {t.awayBanner}
                  </div>
                ) : null}
                {activeTab === 'alarms' ? (
                  <>
                    {creating ? (
                      <div className="create rise">
                        <div className="create-head">
                          <span>{t.newAlarm}</span>
                          <button type="button" className="text-button" onClick={() => setCreating(false)}>
                            {t.cancel}
                          </button>
                        </div>
                        <button type="button" className="create-choice" onClick={() => open(emptyDraft(), 'view')}>
                          <span>{t.simpleAlarm}</span>
                          <span className="muted small">{t.simpleAlarmHint}</span>
                        </button>
                        <button type="button" className="create-choice" onClick={() => open(emptyDraft(), 'pick')}>
                          <span>{t.withActions}</span>
                          <span className="muted small">{t.withActionsHint}</span>
                        </button>
                      </div>
                    ) : null}
                    <AlarmList
                      data={data}
                      scripts={scripts.list}
                      lifted={stage !== 'idle' ? morph.alarmId : null}
                      onChanged={replaceAlarm}
                      onReload={load}
                      onNotify={toast.notify}
                      onOpen={(alarm) => open(editDraft(alarm), 'view', alarm)}
                    />
                    <PlannedList planned={data.planned} timeZone={data.info.time_zone} />
                    <PhonesList clients={data.clients} timeZone={data.info.time_zone} onRemoved={removeClient} />
                  </>
                ) : (
                  <div className="rise">
                    <AppTab />
                  </div>
                )}
                <footer className="footer muted small">{t.version(data.info.version)}</footer>
              </>
            )}
          </div>
        </div>

        {showAppTab ? (
          <nav className="bottom-nav" aria-label={t.title}>
            {(['alarms', 'app'] as const).map((id) => (
              <button
                key={id}
                type="button"
                aria-current={activeTab === id ? 'page' : undefined}
                onClick={() => setTab(id)}
              >
                <span className="nav-bar" />
                {id === 'alarms' ? t.tabAlarms : t.tabApp}
              </button>
            ))}
          </nav>
        ) : null}
      </section>

      {editing && data ? (
        <section
          className={editing.fade ? 'screen detail fade-in' : 'screen detail'}
          aria-label={t.timeline}
          style={{
            opacity: detailOpacity,
            pointerEvents: stage === 'idle' && !isList ? 'auto' : 'none',
            transition: detailTransition,
          }}
        >
          <AlarmDetail
            key={editing.seq}
            initial={editing.draft}
            initialMode={editing.mode}
            data={data}
            scripts={scripts}
            stage={stage}
            onBack={close}
            onNotify={toast.notify}
            onSaved={replaceAlarm}
            onDeleted={(id) => {
              removeAlarm(id);
              setMorph(IDLE);
              setEditing(null);
            }}
            onDuplicate={(draft) => open(draft, 'view')}
            onReload={async (id) => {
              await load();
              try {
                const fresh = await api<Alarm>(`alarms/${id}`);
                replaceAlarm(fresh);
                open(editDraft(fresh), 'view');
              } catch {
                setEditing(null);
              }
            }}
          />
        </section>
      ) : null}

      <div
        className="morph"
        aria-hidden="true"
        style={{
          opacity: stage !== 'idle' && point ? 1 : 0,
          clipPath: `inset(${atDetail && morph.to ? morph.to.clip : 0}px 0 0 0)`,
          transition: animating ? `clip-path .75s ${EASE_MORPH}` : 'none',
        }}
      >
        <div
          className="morph-wire"
          style={{
            transform: point ? `translate(${point.x}px, ${point.y}px) rotate(${atDetail ? 90 : 0}deg)` : 'none',
            transition: animating ? `transform .75s ${EASE_MORPH}` : 'none',
          }}
        >
          <span
            className="morph-line"
            style={{ left: -mg.range * k, width: mg.range * 2 * k, transition: moving }}
          />
          {[...offsets, 0].map((o, i) => {
            const alarmDot = i === offsets.length;
            const size = alarmDot ? (atDetail ? 13 : 10) : atDetail ? 9 : 6;
            return (
              <span
                key={i}
                className="morph-dot"
                style={
                  {
                    left: o * k,
                    width: size,
                    height: size,
                    background: alarmDot ? 'var(--accent)' : atDetail ? 'var(--ink)' : 'var(--muted)',
                    transition: moving,
                  } as CSSProperties
                }
              />
            );
          })}
        </div>
      </div>
      {toast.element}
    </div>
  );
}
