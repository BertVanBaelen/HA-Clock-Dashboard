import fs from 'node:fs';
import path from 'node:path';
import Fastify, { type FastifyError, type FastifyInstance } from 'fastify';
import fastifyStatic from '@fastify/static';
import type { ApiErrorBody } from '@alarm-actions/shared';
import { HttpError, unauthorized } from './errors.js';
import type { Services } from './services.js';

declare module 'fastify' {
  interface FastifyInstance {
    svc: Services;
  }
}

/** Strips the IPv4-mapped IPv6 prefix (`::ffff:172.30.32.2`). */
export function clientAddress(ip: string): string {
  return ip.startsWith('::ffff:') ? ip.slice(7) : ip;
}

export async function buildApp(svc: Services): Promise<FastifyInstance> {
  const app = Fastify({
    loggerInstance: svc.log,
    // Only the Supervisor ingress proxy talks to this listener; never trust forwarded headers.
    trustProxy: false,
    bodyLimit: 64 * 1024,
    disableRequestLogging: true,
  });
  app.decorate('svc', svc);

  app.setErrorHandler((err: FastifyError | Error, req, reply) => {
    let status = 500;
    let body: ApiErrorBody = { error: 'internal_error', message: 'Something went wrong.' };
    if (err instanceof HttpError) {
      status = err.statusCode;
      body = { ...err.extra, error: err.code, message: err.message };
    } else if ('statusCode' in err && err.statusCode && err.statusCode < 500) {
      status = err.statusCode;
      body = {
        error: status === 413 ? 'payload_too_large' : 'bad_request',
        message: err.message,
      };
    }
    if (status >= 500) req.log.error({ err }, 'Request failed');
    return reply.code(status).send(body);
  });

  // Every route requires an ingress identity from the Supervisor ingress proxy (SEC-01).
  app.addHook('onRequest', async (req) => {
    const source = clientAddress(req.ip);
    const userId = req.headers['x-remote-user-id'];
    if (source !== svc.config.trustedProxy || typeof userId !== 'string' || !userId) {
      throw unauthorized();
    }
  });

  const webDist = svc.config.webDistDir;
  if (webDist && fs.existsSync(path.join(webDist, 'index.html'))) {
    await app.register(fastifyStatic, {
      root: webDist,
      wildcard: false,
      setHeaders: (res, filePath) => {
        res.setHeader(
          'Cache-Control',
          /[\\/]assets[\\/]/.test(filePath) ? 'public, max-age=31536000, immutable' : 'no-cache',
        );
      },
    });
  } else {
    app.log.warn('No web/dist found; serving the API only');
  }

  app.setNotFoundHandler((_req, reply) =>
    reply.code(404).send({ error: 'not_found', message: 'Not found.' } satisfies ApiErrorBody),
  );

  return app;
}
