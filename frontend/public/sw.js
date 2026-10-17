// Only a public offline page is cached. Member data, credentials, API responses
// and invitation/reset URLs must never be persisted by this worker.
const CACHE = 'tapin-offline-v1';
const OFFLINE = '/offline.html';

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.add(OFFLINE)));
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) {
      if (key.startsWith('tapin-offline-') && key !== CACHE) await caches.delete(key);
    }
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || event.request.mode !== 'navigate'
      || url.origin !== self.location.origin
      || /^\/(api|actuator|v3|swagger-ui)(\/|$)/.test(url.pathname)) return;
  event.respondWith(fetch(event.request).catch(async () => {
    return (await caches.match(OFFLINE)) || Response.error();
  }));
});

self.addEventListener('push', (event) => {
  event.waitUntil((async () => {
    let message;
    try { message = event.data?.json(); } catch { message = null; }
    if (!message) return;
    const target = new URL(message.url || '/', self.location.origin);
    const url = target.origin === self.location.origin ? target.pathname + target.search : '/';
    await self.registration.showNotification(message.title || 'TapIn', {
      body: message.body || '', tag: message.tag, data: { url },
    });
    for (const client of await self.clients.matchAll({ type: 'window' })) client.postMessage({ type: 'NOTIFICATIONS_UPDATED' });
  })());
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const target = new URL(event.notification.data?.url || '/', self.location.origin);
    if (target.origin !== self.location.origin) return;
    for (const client of await self.clients.matchAll({ type: 'window', includeUncontrolled: true })) {
      if (new URL(client.url).origin === target.origin && 'navigate' in client) {
        await client.navigate(target.href);
        await client.focus();
        return;
      }
    }
    await self.clients.openWindow(target.href);
  })());
});
