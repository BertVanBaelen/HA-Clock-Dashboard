import type { FastifyBaseLogger } from 'fastify';
import type { ActionDefinition } from '@alarm-actions/shared';
import type { HaClient } from './client.js';

interface LabelEntry {
  label_id: string;
  name?: string | null;
}

interface EntityEntry {
  entity_id: string;
  labels?: string[];
  disabled_by?: string | null;
  name?: string | null;
  original_name?: string | null;
  icon?: string | null;
}

interface StateEntry {
  entity_id: string;
  state: string;
  attributes?: Record<string, unknown>;
}

const asString = (v: unknown): string | null => (typeof v === 'string' && v ? v : null);

/**
 * Scripts that carry the action label (FR-ACT-01, TR-HA-03), read from the label and entity
 * registries plus the states for name and icon. Cached; refreshed after every connect and on
 * `label_registry_updated` / `entity_registry_updated`.
 */
export class ScriptRegistry {
  private scripts: Map<string, ActionDefinition> | null = null;
  private running: Promise<void> | null = null;
  private again = false;
  private retryTimer: NodeJS.Timeout | null = null;

  constructor(
    private readonly ha: HaClient,
    private readonly log: FastifyBaseLogger,
    private readonly label: string,
  ) {
    const onUpdate = () => {
      this.refresh().catch((err: Error) =>
        log.warn({ err: err.message }, 'Could not refresh the labeled scripts'),
      );
    };
    ha.onEvent('label_registry_updated', onUpdate);
    ha.onEvent('entity_registry_updated', onUpdate);
    ha.onConnect(() => this.refresh());
  }

  /** True once the registries were read at least once. */
  get ready(): boolean {
    return this.scripts !== null;
  }

  stop(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
  }

  /** Labeled scripts, sorted by name. */
  list(): ActionDefinition[] {
    return [...(this.scripts?.values() ?? [])].sort((a, b) => a.name.localeCompare(b.name));
  }

  get(entityId: string): ActionDefinition | undefined {
    return this.scripts?.get(entityId);
  }

  /** True when the script exists, is enabled and carries the label right now (label gate). */
  isAvailable(entityId: string): boolean {
    return this.scripts?.has(entityId) ?? false;
  }

  /** Reloads; calls that arrive during a reload trigger exactly one more reload. */
  refresh(): Promise<void> {
    if (this.running) {
      this.again = true;
      return this.running;
    }
    this.running = (async () => {
      try {
        do {
          this.again = false;
          await this.load();
        } while (this.again);
      } finally {
        this.running = null;
      }
    })();
    return this.running;
  }

  private async load(): Promise<void> {
    const [labels, entities, states] = await Promise.all([
      this.ha.sendCommand<LabelEntry[]>({ type: 'config/label_registry/list' }),
      this.ha.sendCommand<EntityEntry[]>({ type: 'config/entity_registry/list' }),
      this.ha.sendCommand<StateEntry[]>({ type: 'get_states' }),
    ]);
    const wanted = this.label.toLowerCase();
    const labelIds = new Set(
      labels
        .filter((l) => l.label_id.toLowerCase() === wanted || l.name?.toLowerCase() === wanted)
        .map((l) => l.label_id),
    );
    const stateById = new Map(states.map((s) => [s.entity_id, s]));
    const scripts = new Map<string, ActionDefinition>();
    let missingState = false;
    for (const entity of entities) {
      if (!entity.entity_id.startsWith('script.')) continue;
      if (entity.disabled_by) continue;
      if (!(entity.labels ?? []).some((id) => labelIds.has(id))) continue;
      const state = stateById.get(entity.entity_id);
      if (!state) {
        missingState = true;
        continue;
      }
      const attrs = state.attributes ?? {};
      scripts.set(entity.entity_id, {
        entity_id: entity.entity_id,
        name:
          asString(entity.name) ??
          asString(attrs.friendly_name) ??
          asString(entity.original_name) ??
          entity.entity_id,
        icon: asString(entity.icon) ?? asString(attrs.icon),
        description: asString(attrs.description),
      });
    }
    const before = this.scripts ? [...this.scripts.keys()].sort().join(',') : null;
    this.scripts = scripts;
    const after = [...scripts.keys()].sort().join(',');
    if (before !== after) this.log.info({ scripts: [...scripts.keys()] }, 'Labeled scripts');
    // A script registered just now may not have a state yet; look again shortly.
    if (missingState && !this.retryTimer) {
      this.retryTimer = setTimeout(() => {
        this.retryTimer = null;
        if (this.ha.connected) this.refresh().catch(() => undefined);
      }, 5000);
      this.retryTimer.unref();
    }
  }
}
