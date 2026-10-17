# PWA

TapIn uses the existing manifest and icons with a production-only root service
worker (`/sw.js`). Serve the frontend through HTTPS (localhost is allowed for local
testing). A plain HTTP LAN address does not enable service workers.

Android/Chromium: use the Install TapIn button when the browser offers installation,
or its install menu. iPhone/iPad: Safari → Share → Add to Home Screen. The installed
app opens standalone and uses the same account and backend as the website.

Only `/offline.html` is cached. API responses, membership data, tokens, navigation
URLs and reports are never cached by the worker. A failed navigation shows a public
offline page with Retry. An already open app announces connection loss. Attendance
editing and registrations require a server connection; there is no offline write
queue. This avoids implying that an unconfirmed change was saved.

Updates use the browser's normal waiting lifecycle: close all TapIn tabs/windows
and reopen to activate a new worker. No forced reload interrupts forms. Normal
pages and hashed application bundles are fetched from the server. Increment the
offline cache version in `sw.js` whenever changing the offline page. Existing nginx
`no-cache` headers cover the worker and manifest.

## Verification

Run `npm --prefix frontend run build` then `npm --prefix frontend run preview -- --host
127.0.0.1`. Open localhost:4173, inspect Application → Manifest and Service Workers,
reload once so the page is controlled, switch DevTools to Offline and navigate to
a nested route. The offline page should appear. Reconnect and use Retry. Check
Cache Storage: only the public offline page should exist.

For authenticated testing use the Docker app stack with its backend. On real phones
use a trusted HTTPS deployment. Verify Android installation and iOS home-screen
launch, login, logout, nested routes, and navigation after reconnect. Device testing
is separate from the automated UI and worker tests.

Push notifications are enabled from the bell beside the user name. Installation alone does not request notification permission. See [notification setup](notifications.md) for VAPID configuration.
