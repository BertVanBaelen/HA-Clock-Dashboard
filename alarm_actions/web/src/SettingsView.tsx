import type { Data } from './App';
import { SETTING_LABEL, t } from './strings';

/** Read-only settings (FR-SET-01, 02): they are add-on options, changed in the Supervisor UI. */
export function SettingsView({ data }: { data: Data }) {
  const rows = Object.entries(data.settings).map(([key, value]) => (
    <div key={key} className="setting">
      <dt>{SETTING_LABEL[key] ?? key}</dt>
      <dd>{value === null || value === '' ? '—' : String(value)}</dd>
    </div>
  ));
  return (
    <section className="card">
      <h2 className="section-title">{t.settingsTitle}</h2>
      <p className="muted">{data.me.is_admin ? t.settingsAdminHint : t.settingsUserHint}</p>
      <dl className="settings">
        {rows}
        <div className="setting">
          <dt>{t.timeZone}</dt>
          <dd>{data.info.time_zone ?? '—'}</dd>
        </div>
        <div className="setting">
          <dt>{t.version}</dt>
          <dd>{data.info.version}</dd>
        </div>
      </dl>
    </section>
  );
}
