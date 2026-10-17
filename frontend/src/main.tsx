import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AppProviders } from './app/AppProviders';
import './index.css';

// Development remains free of persistent workers and stale offline content.
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' }).catch(() => {
      console.warn('TapIn: offline podrška trenutačno nije dostupna.');
    });
  });
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppProviders />
  </StrictMode>,
);
