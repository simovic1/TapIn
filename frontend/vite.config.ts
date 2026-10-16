/// <reference types="vitest/config" />
import { defineConfig, loadEnv, type ProxyOptions } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// In development the SPA and the API share one origin (http://localhost:5173): Vite proxies backend paths,
// so the session cookie and CSRF cookie work exactly as behind the production reverse proxy.
//
// `npm run dev:mock` (mode "mock") sends /api/v1 to the Prism mock server generated from api/openapi.yaml
// (`npm --prefix api run mock`), so the UI can be built before the endpoints exist.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backend = env.VITE_BACKEND_URL ?? 'http://localhost:8080';
  const mockServer = env.VITE_MOCK_URL ?? 'http://127.0.0.1:4010';

  const proxy: Record<string, ProxyOptions> =
    mode === 'mock'
      ? { '/api/v1': mockProxy(mockServer) }
      : Object.fromEntries(
          ['/api', '/actuator', '/v3/api-docs', '/swagger-ui'].map((path) => [path, { target: backend }]),
        );

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      strictPort: true,
      proxy,
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      css: false,
    },
  };
});

/**
 * Prism serves contract paths without the `/api/v1` prefix and does not manage cookies. This proxy emulates the
 * backend's cookies so the real session flow can be exercised against the mock:
 * - every response sets a readable `XSRF-TOKEN` (the client echoes it in `X-XSRF-TOKEN`),
 * - `POST /auth/login` (and invitation accept) sets `TAPIN_SESSION`, `POST /auth/logout` clears it.
 */
function mockProxy(target: string): ProxyOptions {
  return {
    target,
    rewrite: (path) => path.replace(/^\/api\/v1/, ''),
    configure: (proxy) => {
      proxy.on('proxyRes', (proxyRes, req) => {
        const url = req.url ?? '';
        const cookies = ['XSRF-TOKEN=mock-csrf; Path=/; SameSite=Lax'];
        if (proxyRes.statusCode === 200 && /\/auth\/(login|invitations\/accept)/.test(url)) {
          cookies.push('TAPIN_SESSION=mock-session; Path=/; HttpOnly; SameSite=Lax');
        }
        if (url.includes('/auth/logout')) {
          cookies.push('TAPIN_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax');
        }
        proxyRes.headers['set-cookie'] = cookies;
      });
    },
  };
}
