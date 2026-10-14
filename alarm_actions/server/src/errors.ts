export class HttpError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly code: string,
    message: string,
    public readonly extra: Record<string, unknown> = {},
    public readonly headers: Record<string, string> = {},
  ) {
    super(message);
  }
}

export const badRequest = (message: string) => new HttpError(400, 'invalid_input', message);
export const unauthorized = () =>
  new HttpError(401, 'unauthorized', 'A Home Assistant login is required.');
export const forbidden = (message = 'You are not allowed to do this.') =>
  new HttpError(403, 'forbidden', message);
export const notFound = (what = 'Item') => new HttpError(404, 'not_found', `${what} not found.`);
export const conflict = (currentRevision: number) =>
  new HttpError(409, 'conflict', 'The alarm was changed elsewhere. Reload it and try again.', {
    current_revision: currentRevision,
  });
export const unprocessable = (code: string, message: string, extra?: Record<string, unknown>) =>
  new HttpError(422, code, message, extra);
export const tooManyRequests = (retryAfterSeconds: number) =>
  new HttpError(
    429,
    'rate_limited',
    'Too many requests. Try again shortly.',
    {},
    { 'retry-after': String(retryAfterSeconds) },
  );
export const unavailable = (message: string) =>
  new HttpError(503, 'service_unavailable', message);
