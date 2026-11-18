// The only place the dashboard talks to the bridge. Same origin; a bearer token is attached when the operator signed in.
// Error bodies are {error, code}: the code (not the message) decides what the dashboard does.

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message);
  }
  /** The bridge says the same request may succeed later (D-29). */
  get retryable(): boolean {
    return ['DOWNSTREAM_UNAVAILABLE', 'CONCURRENT_UPDATE', 'PAYMENT_IN_PROGRESS'].includes(this.code);
  }
}

let accessToken: string | null = null;
let onUnauthorized: () => void = () => {};

export function useAccessToken(token: string | null, whenRefused: () => void) {
  accessToken = token;
  onUnauthorized = whenRefused;
}

export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  const response = await fetch(path, { method: init.method ?? 'GET', headers, body: init.body === undefined ? undefined : JSON.stringify(init.body) });
  const text = await response.text();
  const json = text ? safeJson(text) : null;
  if (!response.ok) {
    if (response.status === 401) onUnauthorized();
    const code = (json && typeof json.code === 'string' ? json.code : `HTTP_${response.status}`) as string;
    const message = (json && typeof json.error === 'string' ? json.error : response.statusText || 'Request failed') as string;
    throw new ApiError(response.status, code, message);
  }
  return json as T;
}

function safeJson(text: string): Record<string, unknown> | null {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
