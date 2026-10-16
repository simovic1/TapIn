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
      ? {
          // Prism serves the contract paths without the `/api/v1` server prefix.
          '/api/v1': { target: mockServer, rewrite: (path) => path.replace(/^\/api\/v1/, '') },
        }
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
  };
});
