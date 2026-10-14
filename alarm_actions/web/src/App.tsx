import { useCallback, useEffect, useRef, useState } from 'react';
import { mdiAlarmPlus, mdiCellphoneArrowDown, mdiCog, mdiAlarmMultiple } from '@mdi/js';
import type {
  ActionDefinition,
  ActionsResponse,
  Alarm,
  Info,
  Me,
  Settings,
} from '@alarm-actions/shared';
import { api, errorMessage } from './api';
import { AlarmEditor } from './AlarmEditor';
import { AlarmList } from './AlarmList';
import { AppTab } from './AppTab';
import { draftFromAlarm, duplicateDraft, emptyDraft, type Draft } from './draft';
import { Icon } from './Icon';
import { SettingsView } from './SettingsView';
import { t } from './strings';

type Tab = 'alarms' | 'settings' | 'app';

export interface Data {
  info: Info;
  me: Me;
  settings: Settings;
  actions: ActionDefinition[];
  alarms: Alarm[];
}

const POLL_MS = 30_000;

export function App() {
  const [data, setData] = useState<Data | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('alarms');
  const [editing, setEditing] = useState<{ draft: Draft; readOnly: boolean } | null>(null);
  const loading = useRef(false);

  const load = useCallback(async () => {
    if (loading.current) return;
    loading.current = true;
    try {
      const [info, me, settings, actions, alarms] = await Promise.all([
        api<Info>('info'),
        api<Me>('me'),
        api<Settings>('settings'),
        api<ActionsResponse>('actions'),
        api<{ alarms: Alarm[] }>('alarms'),
      ]);
      setData({ info, me, settings, actions: actions.actions, alarms: alarms.alarms });
      setError(null);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      loading.current = false;
    }
  }, []);

  // Live data (FR-UI-01): poll while visible and refresh when the panel becomes visible again.
  useEffect(() => {
    void load();
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
  }, [load]);

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
          <p className="muted">{t.loading}</p>
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
          <button
            type="button"
            className="primary"
            onClick={() => setEditing({ draft: emptyDraft(), readOnly: false })}
          >
            <Icon path={mdiAlarmPlus} size={20} />
            <span>{t.newAlarm}</span>
          </button>
        ) : null}
      </header>

      <nav className="tabs" role="tablist">
        <TabButton active={activeTab === 'alarms'} onClick={() => setTab('alarms')} icon={mdiAlarmMultiple}>
          {t.tabAlarms}
        </TabButton>
        <TabButton active={activeTab === 'settings'} onClick={() => setTab('settings')} icon={mdiCog}>
          {t.tabSettings}
        </TabButton>
        {showAppTab ? (
          <TabButton active={activeTab === 'app'} onClick={() => setTab('app')} icon={mdiCellphoneArrowDown}>
            {t.tabApp}
          </TabButton>
        ) : null}
      </nav>

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
          onChanged={replaceAlarm}
          onReload={load}
          onOpen={(alarm) => setEditing({ draft: draftFromAlarm(alarm), readOnly: !alarm.editable })}
          onDuplicate={(alarm) => setEditing({ draft: duplicateDraft(alarm), readOnly: false })}
        />
      ) : null}
      {activeTab === 'settings' ? <SettingsView data={data} /> : null}
      {activeTab === 'app' ? <AppTab /> : null}

      {editing ? (
        <AlarmEditor
          key={`${editing.draft.id ?? 'new'}-${editing.draft.revision ?? 0}`}
          initial={editing.draft}
          readOnly={editing.readOnly}
          data={data}
          onClose={() => setEditing(null)}
          onSaved={replaceAlarm}
          onDeleted={(id) => {
            removeAlarm(id);
            setEditing(null);
          }}
          onDuplicate={(draft) => setEditing({ draft, readOnly: false })}
          onReload={async (id) => {
            await load();
            try {
              const fresh = await api<Alarm>(`alarms/${id}`);
              replaceAlarm(fresh);
              setEditing({ draft: draftFromAlarm(fresh), readOnly: !fresh.editable });
            } catch {
              setEditing(null);
            }
          }}
        />
      ) : null}
    </main>
  );
}

function TabButton(props: { active: boolean; onClick: () => void; icon: string; children: string }) {
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
