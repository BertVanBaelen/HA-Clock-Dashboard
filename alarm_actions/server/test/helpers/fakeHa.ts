import { WebSocketServer, type WebSocket } from 'ws';
import type { AddressInfo } from 'node:net';

export interface FakeUser {
  id: string;
  name: string;
  is_active: boolean;
  is_owner?: boolean;
  system_generated?: boolean;
  group_ids: string[];
}

export interface FakeEntity {
  entity_id: string;
  labels: string[];
  disabled_by?: string | null;
  name?: string | null;
  original_name?: string | null;
  icon?: string | null;
}

export interface FakeState {
  entity_id: string;
  state: string;
  attributes: Record<string, unknown>;
}

export interface ServiceCall {
  domain: string;
  service: string;
  target?: unknown;
  service_data?: unknown;
  at: number;
}

/**
 * A minimal HA Core websocket API for tests: auth, get_config, users, label/entity registries,
 * states, subscribe_events and call_service. Tests change `state` and fire events.
 */
export class FakeHaCore {
  readonly token = 'test-supervisor-token';
  readonly calls: ServiceCall[] = [];
  /** Return an error message to make a `call_service` fail. */
  failCall: ((call: ServiceCall) => string | null) | null = null;
  /** When false, new connections are refused (Core down). */
  accepting = true;
  /** User access tokens (external API, `auth/current_user`): token → user id. */
  readonly userTokens = new Map<string, string>();
  /** Logins with a user token (to check caching). */
  userLogins = 0;
  /** `fire_event` commands. */
  readonly firedEvents: Array<{ event_type: string; event_data: unknown }> = [];
  state = {
    config: { time_zone: 'Europe/Brussels', external_url: 'https://home.example.com' as string | null },
    users: [
      { id: 'user-a', name: 'Alice', is_active: true, group_ids: ['system-users'] },
      { id: 'user-b', name: 'Bob', is_active: true, group_ids: ['system-users'] },
      { id: 'admin', name: 'Admin', is_active: true, is_owner: true, group_ids: ['system-admin'] },
    ] as FakeUser[],
    labels: [{ label_id: 'alarm_action', name: 'alarm_action' }] as Array<{
      label_id: string;
      name: string;
    }>,
    entities: [
      { entity_id: 'script.heat_bathroom', labels: ['alarm_action'] },
      { entity_id: 'script.coffee', labels: ['alarm_action'] },
      { entity_id: 'script.unlabeled', labels: [] },
    ] as FakeEntity[],
    states: [
      {
        entity_id: 'script.heat_bathroom',
        state: 'off',
        attributes: { friendly_name: 'Heat bathroom', icon: 'mdi:radiator' },
      },
      { entity_id: 'script.coffee', state: 'off', attributes: { friendly_name: 'Coffee' } },
      { entity_id: 'script.unlabeled', state: 'off', attributes: { friendly_name: 'Other' } },
    ] as FakeState[],
  };
  private readonly sockets = new Set<WebSocket>();
  private readonly subscriptions = new Map<WebSocket, Map<number, string>>();

  private constructor(private readonly wss: WebSocketServer) {
    wss.on('connection', (ws) => this.onConnection(ws));
  }

  static async start(): Promise<FakeHaCore> {
    const wss = new WebSocketServer({ port: 0, host: '127.0.0.1' });
    await new Promise<void>((resolve) => wss.once('listening', () => resolve()));
    return new FakeHaCore(wss);
  }

  get url(): string {
    return `ws://127.0.0.1:${(this.wss.address() as AddressInfo).port}`;
  }

  get connectionCount(): number {
    return this.sockets.size;
  }

  /** Sends an event to every connection subscribed to its type. */
  fireEvent(eventType: string, data: Record<string, unknown> = {}): void {
    for (const [ws, subs] of this.subscriptions) {
      for (const [id, type] of subs) {
        if (type !== eventType) continue;
        ws.send(JSON.stringify({ id, type: 'event', event: { event_type: eventType, data } }));
      }
    }
  }

  /** Simulates a Core restart/outage: drops all connections. */
  dropConnections(): void {
    for (const ws of this.sockets) ws.terminate();
  }

  async close(): Promise<void> {
    this.dropConnections();
    await new Promise<void>((resolve) => this.wss.close(() => resolve()));
  }

  private onConnection(ws: WebSocket): void {
    if (!this.accepting) {
      ws.terminate();
      return;
    }
    this.sockets.add(ws);
    this.subscriptions.set(ws, new Map());
    let authed = false;
    let userId: string | null = null;
    ws.on('close', () => {
      this.sockets.delete(ws);
      this.subscriptions.delete(ws);
    });
    ws.send(JSON.stringify({ type: 'auth_required', ha_version: '2026.1.0' }));
    ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString()) as Record<string, unknown>;
      if (!authed) {
        const userToken =
          msg.type === 'auth' && typeof msg.access_token === 'string'
            ? this.userTokens.get(msg.access_token)
            : undefined;
        if (userToken) this.userLogins++;
        if (msg.type === 'auth' && (msg.access_token === this.token || userToken)) {
          authed = true;
          userId = userToken ?? null;
          ws.send(JSON.stringify({ type: 'auth_ok', ha_version: '2026.1.0' }));
        } else {
          ws.send(JSON.stringify({ type: 'auth_invalid', message: 'Invalid access token' }));
          ws.close();
        }
        return;
      }
      this.handle(ws, msg, userId);
    });
  }

  private handle(ws: WebSocket, msg: Record<string, unknown>, userId: string | null): void {
    const id = msg.id as number;
    const ok = (result: unknown) =>
      ws.send(JSON.stringify({ id, type: 'result', success: true, result }));
    const fail = (code: string, message: string) =>
      ws.send(JSON.stringify({ id, type: 'result', success: false, error: { code, message } }));

    switch (msg.type) {
      case 'auth/current_user':
        return userId ? ok({ id: userId, name: userId, is_admin: false }) : fail('unauthorized', 'No user');
      case 'fire_event':
        this.firedEvents.push({ event_type: msg.event_type as string, event_data: msg.event_data });
        return ok({ context: { id: 'ctx' } });
      case 'get_config':
        return ok(this.state.config);
      case 'config/auth/list':
        return ok(this.state.users);
      case 'config/label_registry/list':
        return ok(this.state.labels);
      case 'config/entity_registry/list':
        return ok(this.state.entities);
      case 'get_states':
        return ok(this.state.states);
      case 'subscribe_events':
        this.subscriptions.get(ws)?.set(id, msg.event_type as string);
        return ok(null);
      case 'call_service': {
        const call: ServiceCall = {
          domain: msg.domain as string,
          service: msg.service as string,
          target: msg.target,
          service_data: msg.service_data,
          at: Date.now(),
        };
        this.calls.push(call);
        const error = this.failCall?.(call);
        return error ? fail('service_error', error) : ok({ context: { id: 'ctx' } });
      }
      default:
        return fail('unknown_command', `Unknown command ${String(msg.type)}`);
    }
  }
}
