import { useEffect, useState } from 'react';
import { mdiCellphoneArrowDown, mdiLinkVariant } from '@mdi/js';
import type { AppInfo } from '@alarm-actions/shared';
import { api, errorMessage } from './api';
import { Icon } from './Icon';
import { t } from './strings';

/** Setup link the app opens (TR-APP-09); both URLs must be https. */
export function setupLink(haUrl: string, apiUrl: string): string {
  return `alarmactions://setup?ha=${encodeURIComponent(haUrl)}&api=${encodeURIComponent(apiUrl)}`;
}

const isHttps = (url: string | null): url is string => url !== null && /^https:\/\//i.test(url);

function formatSize(bytes: number): string {
  return `${(bytes / 1_048_576).toFixed(1)} MB`;
}

/**
 * "Install app" (FR-UI-07, FR-APP-05, TR-UI-06): download the APK through ingress (logged in),
 * install it, then open the setup link so the app knows where Home Assistant and the API are.
 * Only shown on Android devices.
 */
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

  const ready = info !== null && isHttps(info.ha_url) && isHttps(info.api_url);

  return (
    <section className="card">
      <h2 className="section-title">{t.appTitle}</h2>
      <p>{t.appIntro}</p>
      {error ? <p className="field-error">{error}</p> : null}
      {info === null && !error ? <p className="muted">{t.loading}</p> : null}
      {info && !info.available ? <p className="muted">{t.appUnavailable}</p> : null}
      {info?.available ? (
        <>
          <ol className="steps">
            <li>
              <p>{t.appStepDownload}</p>
              <a className="button primary" href="api/v1/app/apk" download>
                <Icon path={mdiCellphoneArrowDown} size={20} />
                <span>{t.appDownload}</span>
              </a>
              <p className="muted small">
                {info.version ? t.appVersion(info.version) : null}
                {info.size !== null ? ` · ${formatSize(info.size)}` : null}
              </p>
              {info.sha256 ? (
                <p className="muted small mono" title={t.appChecksum}>
                  SHA-256: {info.sha256}
                </p>
              ) : null}
            </li>
            <li>
              <p>{t.appStepInstall}</p>
            </li>
            <li>
              <p>{t.appStepSetup}</p>
              {ready ? (
                <>
                  <a className="button secondary" href={setupLink(info.ha_url!, info.api_url!)}>
                    <Icon path={mdiLinkVariant} size={20} />
                    <span>{t.appOpenSetup}</span>
                  </a>
                  <p className="muted small">{t.appManual}</p>
                  <dl className="urls small">
                    <dt>{t.appHaUrl}</dt>
                    <dd className="mono">{info.ha_url}</dd>
                    <dt>{t.appApiUrl}</dt>
                    <dd className="mono">{info.api_url}</dd>
                  </dl>
                </>
              ) : (
                <p className="banner warning">{t.appNotConfigured}</p>
              )}
            </li>
          </ol>
        </>
      ) : null}
    </section>
  );
}
