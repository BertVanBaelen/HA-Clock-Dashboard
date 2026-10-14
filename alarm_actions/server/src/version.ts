/**
 * Add-on version, shown by `GET /api/v1/info`. The release workflow bumps it together with
 * `version` in `config.yaml` (a test checks that both are equal).
 */
export const APP_VERSION = '0.1.2';

/** API contract version (TR-API-01) and the oldest client API version this server supports. */
export const API_VERSION = 1;
export const MIN_CLIENT_API_VERSION = 1;
