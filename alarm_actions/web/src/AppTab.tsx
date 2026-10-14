import { useEffect, useState } from 'react';
import type { AppInfo } from '@alarm-actions/shared';
import { api, errorMessage } from './api';
import { t } from './strings';

/** "Install app" placeholder (FR-UI-07, TR-UI-06); download and pairing follow in phase 6. */
export function AppTab() {
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    api<AppInfo>('app/info')
      .then((res) => active && setInfo(res))
      .catch((err: unknown) => active && setError(errorMessage(err)));
    return () => {
      active = false;
    };
  }, []);

  return (
    <section className="card">
      <h2 className="section-title">{t.appTitle}</h2>
      <p>{t.appIntro}</p>
      {error ? <p className="field-error">{error}</p> : null}
      {info === null && !error ? <p className="muted">{t.loading}</p> : null}
      {info && !info.available ? <p className="muted">{t.appUnavailable}</p> : null}
      {info?.available ? (
        <>
          {info.version ? <p>{t.appVersion(info.version)}</p> : null}
          <p className="muted">{t.appComing}</p>
        </>
      ) : null}
    </section>
  );
}
