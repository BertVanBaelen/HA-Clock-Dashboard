import fs from 'node:fs';
import path from 'node:path';
import Fastify, { LogController, type FastifyError, type FastifyInstance } from 'fastify';
import fastifyStatic from '@fastify/static';
import type { ApiErrorBody } from '@alarm-actions/shared';
import { HttpError } from './errors.js';
import { apiRoutes } from './routes/api.js';
import { ingressUserId } from './security.js';
import type { Services } from './services.js';

export { clientAddress } from './security.js';

declare module 'fastify' {
  interface FastifyInstance {
    svc: Services;
    /** Every registered route, for the route-enumeration test (SEC-11). */
    routeList: Array<{ method: string; url: string }>;
  }
}

export async function buildApp(svc: Services): Promise<FastifyInstance> {
  const app = Fastify({
    loggerInstance: svc.log,
    // Only the Supervisor ingress proxy talks to this listener; never trust forwarded headers.
    trustProxy: false,
    bodyLimit: 64 * 1024,
    logController: new LogController({ disableRequestLogging: true }),
  });
  app.decorate('svc', svc);
  app.decorate('routeList', [] as Array<{ method: string; url: string }>);
  app.addHook('onRoute', (route) => {
    for (const method of [route.method].flat()) app.routeList.push({ method, url: route.url });
  });

  app.setErrorHandler((err: FastifyError | Error, req, reply) => {
    let status = 500;
    let body: ApiErrorBody = { error: 'internal_error', message: 'Something went wrong.' };
    if (err instanceof HttpError) {
      status = err.statusCode;
      body = { ...err.extra, error: err.code, message: err.message };
      for (const [name, value] of Object.entries(err.headers)) reply.header(name, value);
    } else if ('statusCode' in err && err.statusCode && err.statusCode < 500) {
      status = err.statusCode;
      body = {
        error:
          status === 413 ? 'payload_too_large' : status === 415 ? 'unsupported_media_type' : 'bad_request',
        message: err.message,
      };
    }
    if (status >= 500) req.log.error({ err }, 'Request failed');
    return reply.code(status).send(body);
  });

  // Every route requires an ingress identity from the Supervisor ingress proxy (SEC-01).
  app.addHook('onRequest', async (req) => {
    ingressUserId(req, svc.config.trustedProxy);
  });

  await app.register(apiRoutes, { prefix: '/api/v1' });

  const webDist = svc.config.webDistDir;
  if (webDist && fs.existsSync(path.join(webDist, 'index.html'))) {
    await app.register(fastifyStatic, {
      root: webDist,
      wildcard: false,
      setHeaders: (reply, filePath) => {
        reply.header(
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
