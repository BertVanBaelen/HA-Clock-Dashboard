export class HttpError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly code: string,
    message: string,
    public readonly extra: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

export const unauthorized = () =>
  new HttpError(401, 'unauthorized', 'A Home Assistant login is required.');
export const forbidden = (message = 'You are not allowed to do this.') =>
  new HttpError(403, 'forbidden', message);
export const notFound = (what = 'Item') => new HttpError(404, 'not_found', `${what} not found.`);
export const unprocessable = (code: string, message: string, extra?: Record<string, unknown>) =>
  new HttpError(422, code, message, extra);
export const unavailable = (message: string) =>
  new HttpError(503, 'service_unavailable', message);
