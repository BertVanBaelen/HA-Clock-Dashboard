import type { FastifyBaseLogger } from 'fastify';
import type { HaClient } from './client.js';

interface CoreConfig {
  time_zone?: string;
  external_url?: string | null;
}

/**
 * Cached data read from HA Core: the time zone (TR-HA-02). Refreshed after every (re)connect and
 * on the matching registry/config events.
 */
export class HaDirectory {
  timeZone: string | null = null;
  externalUrl: string | null = null;
  private readonly timeZoneListeners = new Set<(tz: string) => void>();

  constructor(
    private readonly ha: HaClient,
    private readonly log: FastifyBaseLogger,
  ) {
    ha.onEvent('core_config_updated', () => {
      this.refreshConfig().catch((err: Error) =>
        log.warn({ err: err.message }, 'Could not refresh the Home Assistant config'),
      );
    });
    ha.onConnect(() => this.refreshConfig());
  }

  onTimeZoneChange(listener: (tz: string) => void): void {
    this.timeZoneListeners.add(listener);
  }

  async refreshConfig(): Promise<void> {
    const config = await this.ha.sendCommand<CoreConfig>({ type: 'get_config' });
    this.externalUrl = config.external_url ?? null;
    const tz = config.time_zone;
    if (!tz || tz === this.timeZone) return;
    const previous = this.timeZone;
    this.timeZone = tz;
    this.log.info({ previous, timeZone: tz }, 'Home Assistant time zone');
    for (const listener of this.timeZoneListeners) listener(tz);
  }
}
