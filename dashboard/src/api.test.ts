import { api, ApiError, useAccessToken } from './api';
import { fakeApi } from './testing';

describe('api', () => {
  afterEach(() => useAccessToken(null, () => {}));

  it('reads the error code, not the message, and knows which codes may be retried', async () => {
    fakeApi({ 'POST /api/v1/payments': new Response(JSON.stringify({ detail: 'busy', code: 'CONCURRENT_UPDATE' }), { status: 409 }) });
    const error = (await api('/api/v1/payments', { method: 'POST', body: {} }).catch((e: unknown) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('CONCURRENT_UPDATE');
    expect(error.message).toBe('busy'); // the problem detail's text, shown to the operator
    expect(error.retryable).toBe(true);
  });

  it('treats a reused idempotency key as final', async () => {
    fakeApi({ 'POST /api/v1/payments': new Response(JSON.stringify({ detail: 'reused', code: 'IDEMPOTENCY_KEY_REUSED' }), { status: 409 }) });
    const error = (await api('/api/v1/payments', { method: 'POST', body: {} }).catch((e: unknown) => e)) as ApiError;
    expect(error.retryable).toBe(false);
  });

  it('sends the access token and reports a refusal so the operator can sign in again', async () => {
    const calls = fakeApi({ 'GET /api/v1/charges': new Response('{"detail":"no","code":"UNAUTHORIZED"}', { status: 401 }) });
    let refused = false;
    useAccessToken('token-123', () => { refused = true; });

    await api('/api/v1/charges').catch(() => undefined);

    expect(calls[0].headers.Authorization).toBe('Bearer token-123');
    expect(refused).toBe(true);
  });

  it('survives a body that is not JSON (a proxy’s error page)', async () => {
    fakeApi({ 'GET /api/v1/charges': new Response('<html>Bad gateway</html>', { status: 502, statusText: 'Bad Gateway' }) });
    const error = (await api('/api/v1/charges').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('HTTP_502');
  });
});
