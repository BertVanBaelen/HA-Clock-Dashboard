import { useCallback, useEffect, useRef, useState } from 'react';
import { mdiAlarmPlus, mdiCellphoneArrowDown, mdiAlarmMultiple } from '@mdi/js';
import type {
  ActionDefinition,
  ActionsResponse,
  Alarm,
  Info,
  Me,
  PlannedAction,
  PlannedResponse,
  Settings,
} from '@alarm-actions/shared';
import { api, errorMessage } from './api';
import { AlarmEditor } from './AlarmEditor';
import { AlarmList } from './AlarmList';
import { AppTab } from './AppTab';
import { duplicateDraft, editDraft, emptyDraft, type Draft } from './draft';
import { Icon } from './Icon';
import { PlannedList } from './PlannedList';
import { t } from './strings';
import { useToast } from './Toast';

type Tab = 'alarms' | 'app';

export interface Data {
  info: Info;
  me: Me;
  alarms: Alarm[];
  /** Upcoming actions of other users' alarms. */
  planned: PlannedAction[];
}

/** Labeled scripts; loaded after the alarm list so they never delay opening the panel. */
export interface Scripts {
  list: ActionDefinition[] | null;
  label: string;
  error: string | null;
}

const POLL_MS = 30_000;

export function App() {
  const [data, setData] = useState<Data | null>(null);
  const [scripts, setScripts] = useState<Scripts>({ list: null, label: '', error: null });
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('alarms');
  const [editing, setEditing] = useState<Draft | null>(null);
  const loading = useRef(false);
  const toast = useToast();

  const load = useCallback(async () => {
    if (loading.current) return;
    loading.current = true;
    try {
      const [info, me, alarms, planned] = await Promise.all([
        api<Info>('info'),
        api<Me>('me'),
        api<{ alarms: Alarm[] }>('alarms'),
        api<PlannedResponse>('planned'),
      ]);
      setData({ info, me, alarms: alarms.alarms, planned: planned.planned });
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
      setScripts({ list: actions.actions, label: settings.action_label, error: null });
    } catch (err) {
      setScripts((s) => ({ ...s, error: errorMessage(err) }));
    }
  }, []);

  // Live data (FR-UI-01): poll while visible and refresh when the panel becomes visible again.
  useEffect(() => {
    void load().then(loadScripts);
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void load();
    }, POLL_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') void load();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [load, loadScripts]);

  const openEditor = useCallback(
    (draft: Draft) => {
      setEditing(draft);
      // Scripts change rarely; refresh them in the background whenever an alarm is opened.
      void loadScripts();
    },
    [loadScripts],
  );

  const replaceAlarm = useCallback((alarm: Alarm) => {
    setData((d) => {
      if (!d) return d;
      const exists = d.alarms.some((a) => a.id === alarm.id);
      const alarms = exists
        ? d.alarms.map((a) => (a.id === alarm.id ? alarm : a))
        : [...d.alarms, alarm];
      return { ...d, alarms };
    });
  }, []);

  const removeAlarm = useCallback((id: string) => {
    setData((d) => (d ? { ...d, alarms: d.alarms.filter((a) => a.id !== id) } : d));
  }, []);

  if (!data) {
    return (
      <main className="page">
        <h1 className="title">{t.title}</h1>
        {error ? (
          <div className="banner error" role="alert">
            <span>{error}</span>
            <button type="button" onClick={() => void load()}>
              {t.retry}
            </button>
          </div>
        ) : (
          <div className="skeleton-list" aria-busy="true" aria-label={t.loading}>
            {[0, 1, 2].map((i) => (
              <div key={i} className="skeleton" />
            ))}
          </div>
        )}
      </main>
    );
  }

  const showAppTab = data.info.app_available;
  const activeTab: Tab = tab === 'app' && !showAppTab ? 'alarms' : tab;

  return (
    <main className="page">
      <header className="toolbar">
        <h1 className="title">{t.title}</h1>
        {activeTab === 'alarms' ? (
          <button type="button" className="primary" onClick={() => openEditor(emptyDraft())}>
            <Icon path={mdiAlarmPlus} size={20} />
            <span>{t.newAlarm}</span>
          </button>
        ) : null}
      </header>

      {showAppTab ? (
        <nav className="tabs" role="tablist">
          <TabButton
            active={activeTab === 'alarms'}
            onClick={() => setTab('alarms')}
            icon={mdiAlarmMultiple}
          >
            {t.tabAlarms}
          </TabButton>
          <TabButton
            active={activeTab === 'app'}
            onClick={() => setTab('app')}
            icon={mdiCellphoneArrowDown}
          >
            {t.tabApp}
          </TabButton>
        </nav>
      ) : null}

      {error ? (
        <div className="banner error" role="alert">
          <span>{error}</span>
          <button type="button" onClick={() => void load()}>
            {t.retry}
          </button>
        </div>
      ) : null}
      {!data.info.ha_connected ? (
        <div className="banner warning" role="status">
          {t.haOffline}
        </div>
      ) : null}

      {activeTab === 'alarms' ? (
        <AlarmList
          data={data}
          scripts={scripts.list}
          onChanged={replaceAlarm}
          onReload={load}
          onNotify={toast.notify}
          onOpen={(alarm) => openEditor(editDraft(alarm))}
          onDuplicate={(alarm) => openEditor(duplicateDraft(alarm))}
        />
      ) : null}
      {activeTab === 'alarms' ? (
        <PlannedList planned={data.planned} timeZone={data.info.time_zone} />
      ) : null}
      {activeTab === 'app' ? (
        <div className="tab-panel">
          <AppTab />
        </div>
      ) : null}

      <footer className="footer muted small">{t.version(data.info.version)}</footer>

      {editing ? (
        <AlarmEditor
          key={`${editing.id ?? 'new'}-${editing.revision ?? 0}`}
          initial={editing}
          data={data}
          scripts={scripts}
          onClose={() => setEditing(null)}
          onNotify={toast.notify}
          onSaved={replaceAlarm}
          onDeleted={(id) => {
            removeAlarm(id);
            setEditing(null);
          }}
          onDuplicate={(draft) => setEditing(draft)}
          onReload={async (id) => {
            await load();
            try {
              const fresh = await api<Alarm>(`alarms/${id}`);
              replaceAlarm(fresh);
              setEditing(editDraft(fresh));
            } catch {
              setEditing(null);
            }
          }}
        />
      ) : null}
      {toast.element}
    </main>
  );
}

function TabButton(props: {
  active: boolean;
  onClick: () => void;
  icon: string;
  children: string;
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={props.active}
      className={props.active ? 'tab active' : 'tab'}
      onClick={props.onClick}
    >
      <Icon path={props.icon} size={20} />
      <span>{props.children}</span>
    </button>
  );
}
