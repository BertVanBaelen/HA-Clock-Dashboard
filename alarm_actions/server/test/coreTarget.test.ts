import http from 'node:http';
import type { AddressInfo } from 'node:net';
import pino from 'pino';
import { afterEach, describe, expect, it } from 'vitest';
import { CoreTargetResolver } from '../src/coreTarget.js';

const log = pino({ level: 'silent' });
let server: http.Server | null = null;

afterEach(async () => {
  await new Promise((r) => (server ? server.close(r) : r(null)));
  server = null;
});

/** Fake Supervisor answering `GET /core/info`; returns its URL and the request log. */
async function supervisor(answer: () => { status: number; body: unknown }) {
  const requests: { path: string | undefined; auth: string | undefined }[] = [];
  server = http.createServer((req, res) => {
    requests.push({ path: req.url, auth: req.headers.authorization });
    const { status, body } = answer();
    res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body));
  });
  await new Promise<void>((r) => server!.listen(0, '127.0.0.1', r));
  return { url: `http://127.0.0.1:${(server.address() as AddressInfo).port}`, requests };
}

describe('Core target for token checks', () => {
  it('uses the port from the Supervisor and caches it until invalidated', async () => {
    const sup = await supervisor(() => ({ status: 200, body: { data: { port: 8124, ssl: false } } }));
    const core = new CoreTargetResolver({ fixedUrl: null, supervisorToken: 'tok', supervisorUrl: sup.url, log });
    expect(await core.get()).toEqual({ url: 'ws://homeassistant:8124/api/websocket', tlsInsecure: false });
    await core.get();
    expect(sup.requests).toEqual([{ path: '/core/info', auth: 'Bearer tok' }]);
    core.invalidate();
    await core.get();
    expect(sup.requests).toHaveLength(2);
  });

  it('uses wss without certificate check when Core has its own certificate', async () => {
    const sup = await supervisor(() => ({ status: 200, body: { data: { port: 8123, ssl: true } } }));
    const core = new CoreTargetResolver({ fixedUrl: null, supervisorToken: 'tok', supervisorUrl: sup.url, log });
    expect(await core.get()).toEqual({ url: 'wss://homeassistant:8123/api/websocket', tlsInsecure: true });
  });

  it('falls back to port 8123 and retries the lookup when the Supervisor fails', async () => {
    let status = 403;
    const sup = await supervisor(() => ({ status, body: { data: { port: 9000 } } }));
    const core = new CoreTargetResolver({ fixedUrl: null, supervisorToken: 'tok', supervisorUrl: sup.url, log });
    expect((await core.get()).url).toBe('ws://homeassistant:8123/api/websocket');
    status = 200;
    expect((await core.get()).url).toBe('ws://homeassistant:9000/api/websocket');
  });

  it('uses a fixed URL without asking the Supervisor', async () => {
    const core = new CoreTargetResolver({ fixedUrl: 'ws://x/api/websocket', supervisorToken: 'tok', log });
    expect(await core.get()).toEqual({ url: 'ws://x/api/websocket', tlsInsecure: false });
  });
});
