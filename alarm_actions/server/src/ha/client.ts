import WebSocket from 'ws';
import type { FastifyBaseLogger } from 'fastify';

/** The command was not sent because there is no authenticated connection. Safe to retry. */
export class NotConnectedError extends Error {
  constructor() {
    super('Not connected to Home Assistant Core');
  }
}

/** The connection dropped after the command was sent; it may or may not have been executed. */
export class ConnectionLostError extends Error {
  constructor() {
    super('Connection to Home Assistant Core lost');
  }
}

/** Core answered the command with an error (or did not answer in time). */
export class HaCommandError extends Error {
  constructor(
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export interface HaClientOptions {
  url: string;
  token: string;
  log: FastifyBaseLogger;
  commandTimeoutMs?: number;
  minBackoffMs?: number;
  maxBackoffMs?: number;
}

type EventHandler = (data: Record<string, unknown>) => void;
type ConnectHandler = () => void | Promise<void>;

interface Pending {
  resolve: (value: unknown) => void;
  reject: (err: Error) => void;
  timer: NodeJS.Timeout;
}

/**
 * Websocket connection to HA Core through the Supervisor proxy (TR-HA-01): authenticates with
 * the Supervisor token, reconnects with exponential backoff, re-subscribes to events and runs
 * the connect handlers (refresh time zone, users, registries) after every (re)connect.
 */
export class HaClient {
  private ws: WebSocket | null = null;
  private authenticated = false;
  private nextId = 1;
  private readonly pending = new Map<number, Pending>();
  private readonly eventHandlers = new Map<string, Set<EventHandler>>();
  private readonly connectHandlers = new Set<ConnectHandler>();
  private readonly disconnectHandlers = new Set<() => void>();
  private stopped = true;
  private backoffMs: number;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private readonly log: FastifyBaseLogger;
  /** Number of completed connects (subscriptions renewed and connect handlers run). */
  readyCount = 0;

  constructor(private readonly opts: HaClientOptions) {
    this.log = opts.log;
    this.backoffMs = opts.minBackoffMs ?? 1000;
  }

  get connected(): boolean {
    return this.authenticated && this.ws?.readyState === WebSocket.OPEN;
  }

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    this.connect();
  }

  async stop(): Promise<void> {
    this.stopped = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    const ws = this.ws;
    this.ws = null;
    this.authenticated = false;
    this.rejectPending(new ConnectionLostError());
    if (ws && ws.readyState !== WebSocket.CLOSED) {
      await new Promise<void>((resolve) => {
        ws.once('close', () => resolve());
        ws.terminate();
      });
    }
  }

  /** Subscribes to a HA event type; the subscription is renewed on every reconnect. */
  onEvent(eventType: string, handler: EventHandler): void {
    let handlers = this.eventHandlers.get(eventType);
    if (!handlers) {
      handlers = new Set();
      this.eventHandlers.set(eventType, handlers);
      if (this.connected) void this.subscribe(eventType);
    }
    handlers.add(handler);
  }

  onConnect(handler: ConnectHandler): void {
    this.connectHandlers.add(handler);
  }

  onDisconnect(handler: () => void): void {
    this.disconnectHandlers.add(handler);
  }

  /** Sends a websocket command and resolves with its `result`. */
  sendCommand<T = unknown>(command: Record<string, unknown>): Promise<T> {
    const ws = this.ws;
    if (!ws || !this.connected) return Promise.reject(new NotConnectedError());
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new HaCommandError('timeout', `No answer to ${String(command.type)} in time`));
      }, this.opts.commandTimeoutMs ?? 30_000);
      this.pending.set(id, { resolve: resolve as (v: unknown) => void, reject, timer });
      ws.send(JSON.stringify({ ...command, id }), (err) => {
        if (!err) return;
        const entry = this.pending.get(id);
        if (!entry) return;
        clearTimeout(entry.timer);
        this.pending.delete(id);
        reject(new ConnectionLostError());
      });
    });
  }

  private connect(): void {
    const ws = new WebSocket(this.opts.url);
    this.ws = ws;
    ws.on('message', (raw) => this.onMessage(ws, raw.toString()));
    ws.on('close', () => this.onClose(ws));
    ws.on('error', (err) => {
      this.log.debug({ err: err.message }, 'Home Assistant websocket error');
    });
  }

  private onMessage(ws: WebSocket, raw: string): void {
    if (ws !== this.ws) return;
    let msg: Record<string, unknown>;
    try {
      msg = JSON.parse(raw) as Record<string, unknown>;
    } catch {
      return;
    }
    switch (msg.type) {
      case 'auth_required':
        ws.send(JSON.stringify({ type: 'auth', access_token: this.opts.token }));
        break;
      case 'auth_ok':
        this.authenticated = true;
        this.backoffMs = this.opts.minBackoffMs ?? 1000;
        this.log.info('Connected to Home Assistant Core');
        void this.afterConnect();
        break;
      case 'auth_invalid':
        this.log.error('Home Assistant rejected the Supervisor token');
        ws.close();
        break;
      case 'result': {
        const entry = this.pending.get(msg.id as number);
        if (!entry) return;
        clearTimeout(entry.timer);
        this.pending.delete(msg.id as number);
        if (msg.success) {
          entry.resolve(msg.result);
        } else {
          const error = (msg.error ?? {}) as { code?: string; message?: string };
          entry.reject(
            new HaCommandError(error.code ?? 'unknown_error', error.message ?? 'Command failed'),
          );
        }
        break;
      }
      case 'event': {
        const event = (msg.event ?? {}) as { event_type?: string; data?: Record<string, unknown> };
        const handlers = event.event_type ? this.eventHandlers.get(event.event_type) : undefined;
        for (const handler of handlers ?? []) {
          try {
            handler(event.data ?? {});
          } catch (err) {
            this.log.error({ err }, `Handler for ${event.event_type} failed`);
          }
        }
        break;
      }
    }
  }

  private async subscribe(eventType: string): Promise<void> {
    try {
      await this.sendCommand({ type: 'subscribe_events', event_type: eventType });
    } catch (err) {
      this.log.warn({ err: (err as Error).message }, `Could not subscribe to ${eventType}`);
    }
  }

  private async afterConnect(): Promise<void> {
    await Promise.all([...this.eventHandlers.keys()].map((type) => this.subscribe(type)));
    for (const handler of this.connectHandlers) {
      try {
        await handler();
      } catch (err) {
        this.log.error({ err: (err as Error).message }, 'Refresh after connect failed');
      }
    }
    this.readyCount++;
  }

  private onClose(ws: WebSocket): void {
    if (ws !== this.ws) return;
    const wasConnected = this.authenticated;
    this.ws = null;
    this.authenticated = false;
    this.rejectPending(new ConnectionLostError());
    if (wasConnected) {
      this.log.warn('Connection to Home Assistant Core lost');
      for (const handler of this.disconnectHandlers) handler();
    }
    if (this.stopped) return;
    const delay = this.backoffMs;
    this.backoffMs = Math.min(this.backoffMs * 2, this.opts.maxBackoffMs ?? 60_000);
    this.log.debug(`Reconnecting to Home Assistant Core in ${delay} ms`);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.stopped) this.connect();
    }, delay);
  }

  private rejectPending(err: Error): void {
    for (const entry of this.pending.values()) {
      clearTimeout(entry.timer);
      entry.reject(err);
    }
    this.pending.clear();
  }
}
