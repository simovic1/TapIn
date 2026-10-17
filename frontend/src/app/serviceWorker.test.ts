/// <reference types="node" />
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { describe, expect, it, vi } from 'vitest';

function worker() {
  const handlers: Record<string, (event: unknown) => void> = {};
  const fetch = vi.fn();
  const fallback = new Response('offline');
  const cache = { add: vi.fn().mockResolvedValue(undefined) };
  const caches = {
    open: vi.fn().mockResolvedValue(cache),
    match: vi.fn().mockResolvedValue(fallback),
    keys: vi.fn().mockResolvedValue(['tapin-offline-v0', 'other-app']),
    delete: vi.fn().mockResolvedValue(true),
  };
  const showNotification = vi.fn().mockResolvedValue(undefined);
  const openWindow = vi.fn();
  runInNewContext(readFileSync('public/sw.js', 'utf8'), {
    self: { location: { origin: 'https://tapin.test' }, clients: { claim: vi.fn(), matchAll: vi.fn().mockResolvedValue([]), openWindow }, registration: { showNotification },
      addEventListener: (name: string, handler: (event: unknown) => void) => { handlers[name] = handler; } },
    fetch, caches, URL, Response,
  });
  return { handlers, fetch, caches, cache, fallback, showNotification, openWindow };
}

describe('service worker privacy and offline fallback', () => {
  it('caches only the public offline page and deletes only its own old caches', async () => {
    const w = worker();
    let completion: Promise<unknown> | undefined;
    const event = { waitUntil: (value: Promise<unknown>) => { completion = value; } };
    w.handlers.install!(event);
    await completion;
    expect(w.cache.add).toHaveBeenCalledExactlyOnceWith('/offline.html');
    w.handlers.activate!(event);
    await completion;
    expect(w.caches.delete).toHaveBeenCalledExactlyOnceWith('tapin-offline-v0');
  });

  it('does not intercept API calls, mutations, assets or foreign origins', () => {
    const w = worker();
    for (const [url, method, mode] of [
      ['https://tapin.test/api/v1/me', 'GET', 'navigate'],
      ['https://tapin.test/api/v1/attendance', 'POST', 'cors'],
      ['https://tapin.test/assets/main.js', 'GET', 'cors'],
      ['https://external.test/', 'GET', 'navigate'],
    ]) {
      const respondWith = vi.fn();
      w.handlers.fetch!({ request: { url, method, mode }, respondWith });
      expect(respondWith).not.toHaveBeenCalled();
    }
  });

  it('uses live navigation responses and falls back only when the network fails', async () => {
    const w = worker();
    const live = new Response('live');
    w.fetch.mockResolvedValueOnce(live).mockRejectedValueOnce(new TypeError('offline'));
    let response: Promise<Response> | undefined;
    const event = { request: { url: 'https://tapin.test/profil', method: 'GET', mode: 'navigate' },
      respondWith: (value: Promise<Response>) => { response = value; } };
    w.handlers.fetch!(event);
    expect(await response).toBe(live);
    expect(w.caches.match).not.toHaveBeenCalled();
    w.handlers.fetch!(event);
    expect(await response).toBe(w.fallback);
  });
});

it('displays push text and confines click URLs to the application origin', async () => {
  const w = worker();
  let completion: Promise<unknown> | undefined;
  const waitUntil = (value: Promise<unknown>) => { completion = value; };
  w.handlers.push!({ data: { json: () => ({ title: 'Trening', body: 'U 19:00', url: 'https://external.test/' }) }, waitUntil });
  await completion;
  expect(w.showNotification).toHaveBeenCalledWith('Trening', expect.objectContaining({ body: 'U 19:00', data: { url: '/' } }));
  w.handlers.notificationclick!({ notification: { close: vi.fn(), data: { url: '/treninzi/12' } }, waitUntil });
  await completion;
  expect(w.openWindow).toHaveBeenCalledWith('https://tapin.test/treninzi/12');
});
