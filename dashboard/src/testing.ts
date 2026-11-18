import { vi } from 'vitest';

/** Replaces fetch with fixed answers per "METHOD path"; records every call so a test can check what was sent. */
export function fakeApi(routes: Record<string, unknown>) {
  const calls: { method: string; path: string; body: unknown; headers: Record<string, string> }[] = [];
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    calls.push({ method, path, body: init?.body ? JSON.parse(String(init.body)) : undefined, headers: (init?.headers ?? {}) as Record<string, string> });
    const answer = routes[`${method} ${path}`];
    if (answer === undefined) return new Response(JSON.stringify({ detail: 'Not Found', code: 'NOT_FOUND' }), { status: 404 });
    if (answer instanceof Response) return answer;
    return new Response(JSON.stringify(answer), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fetchMock);
  return calls;
}

export const charge = (over: Record<string, unknown> = {}) => ({
  id: 'ch-1', idempotencyKey: 'k-1', bookingRef: 'BK-1', amountMinor: 80000, currency: 'USD', status: 'CAPTURED', pspRef: 'cap_1',
  lastError: null, attempts: 1, createdAt: '2026-11-18T08:00:00Z', ...over,
});

export const posting = (over: Record<string, unknown> = {}) => ({
  id: 'po-1', chargeId: 'ch-1', folioId: 'RES-1', amountMinor: 80000, currency: 'USD', businessDate: '2026-11-18', status: 'POSTED',
  operaRef: 'TX-1000', failureCategory: null, lastError: null, attempts: 1, createdAt: '2026-11-18T08:00:01Z', ...over,
});
