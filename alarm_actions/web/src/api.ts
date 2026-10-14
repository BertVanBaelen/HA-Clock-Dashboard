import type { ApiErrorBody } from '@alarm-actions/shared';
import { t } from './strings';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly body: Partial<ApiErrorBody> | null = null,
  ) {
    super(message);
  }
}

/**
 * Calls the add-on API. URLs are relative (TR-API-10): the panel lives below the per-install
 * ingress path, and the ingress session cookie provides the login (TR-UI-04).
 */
export async function api<T>(
  path: string,
  options: { method?: 'GET' | 'POST' | 'PUT' | 'DELETE'; body?: unknown } = {},
): Promise<T> {
  // Only requests with a body are JSON; the server rejects a JSON content type without one.
  const headers: Record<string, string> = {};
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  let res: Response;
  try {
    res = await fetch(`api/v1/${path}`, {
      method: options.method ?? 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    throw new ApiError(0, 'network', t.networkError);
  }
  if (res.status === 204) return undefined as T;
  const data = (await res.json().catch(() => null)) as (T & Partial<ApiErrorBody>) | null;
  if (!res.ok) {
    throw new ApiError(
      res.status,
      data?.error ?? 'unknown',
      data?.message ?? t.requestFailed(res.status),
      data,
    );
  }
  return data as T;
}

export function errorMessage(err: unknown): string {
  return err instanceof ApiError ? err.message : t.genericError;
}
