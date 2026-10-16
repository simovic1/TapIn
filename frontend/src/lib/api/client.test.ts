import { describe, expect, it } from 'vitest';
import { mockApi, json, problem } from '../../test/utils';
import { ApiError, apiFetch } from './client';

describe('apiFetch', () => {
  it('sends the CSRF cookie value as X-XSRF-TOKEN on state-changing requests only', async () => {
    document.cookie = 'XSRF-TOKEN=abc%3D123; Path=/';
    const fetchMock = mockApi({
      'GET /api/v1/me': () => json(200, {}),
      'POST /api/v1/auth/logout': () => new Response(null, { status: 204 }),
    });

    await apiFetch('/me');
    await apiFetch('/auth/logout', { method: 'POST' });

    const getHeaders = new Headers(fetchMock.mock.calls[0]![1]!.headers);
    const postHeaders = new Headers(fetchMock.mock.calls[1]![1]!.headers);
    expect(getHeaders.has('X-XSRF-TOKEN')).toBe(false);
    expect(postHeaders.get('X-XSRF-TOKEN')).toBe('abc=123');
    expect(fetchMock.mock.calls[1]![1]!.credentials).toBe('same-origin');
  });

  it('turns problem+json responses into ApiError with errorCode', async () => {
    mockApi({ 'GET /api/v1/members': () => problem(403, 'FORBIDDEN', '/api/v1/members') });

    const error = await apiFetch('/members').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(403);
    expect((error as ApiError).problem.errorCode).toBe('FORBIDDEN');
    expect((error as ApiError).problem.instance).toBe('/api/v1/members');
  });

  it('still produces an ApiError when the error body is not JSON', async () => {
    mockApi({ 'GET /api/v1/system/info': () => new Response('Bad gateway', { status: 502 }) });

    const error = (await apiFetch('/system/info').catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(502);
    expect(error.problem.errorCode).toBe('INTERNAL_ERROR');
  });
});
