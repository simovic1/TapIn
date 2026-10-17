import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { vi } from 'vitest';
import { createQueryClient } from '../app/queryClient';
import { routes } from '../app/router';
import type { CurrentUser, Role } from '../lib/session/roles';

export function makeUser(roles: Role[], overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    userId: 77,
    membershipId: 123,
    email: 'ana.horvat@example.com',
    firstName: 'Ana',
    lastName: 'Horvat',
    phone: null,
    accountStatus: 'ACTIVE',
    membershipStatus: 'ACTIVE',
    roles,
    joinedOn: '2024-03-01',
    club: { name: 'BD Samobor', timeZone: 'Europe/Zagreb' },
    version: 1,
    trainingRemindersEnabled: true,
    webPushPublicKey: null,
    ...overrides,
  };
}

export function json(status: number, body: unknown): Response {
  const contentType = status >= 400 ? 'application/problem+json' : 'application/json';
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

export function problem(status: number, errorCode: string, instance = '/api/v1/x') {
  return json(status, { type: `urn:tapin:error:${errorCode.toLowerCase()}`, title: 'Error', status, detail: 'detail', instance, errorCode });
}

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;

/** Stubs global fetch with a router of `"METHOD /path"` → handler; unknown requests fail loudly. */
export function mockApi(handlers: Record<string, Handler>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    const key = `${(init.method ?? 'GET').toUpperCase()} ${url.replace(/\?.*$/, '')}`;
    const handler = handlers[key] ?? (key === 'GET /api/v1/me/notifications' ? () => json(200, { items: [], unreadCount: 0 }) : undefined);
    if (!handler) throw new Error(`Unexpected request: ${key}`);
    return handler(url, init);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

/** Renders the real route tree (guards, layout, pages) at `path` with a fresh query cache. */
export function renderApp(path: string) {
  const queryClient = createQueryClient();
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { ...view, router, queryClient };
}
