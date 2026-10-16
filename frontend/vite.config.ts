import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// In development the SPA and the API share one origin (http://localhost:5173): Vite proxies backend paths,
// so the session cookie and CSRF cookie work exactly as behind the production reverse proxy.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backend = env.VITE_BACKEND_URL ?? 'http://localhost:8080';
  const proxied = ['/api', '/actuator', '/v3/api-docs', '/swagger-ui'];

  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: Object.fromEntries(proxied.map((path) => [path, { target: backend, changeOrigin: false }])),
    },
  };
});
