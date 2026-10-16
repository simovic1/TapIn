import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  document.cookie.split('; ').forEach((cookie) => {
    const name = cookie.split('=')[0];
    if (name) document.cookie = `${name}=; Max-Age=0; Path=/`;
  });
});
