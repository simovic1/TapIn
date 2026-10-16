import { useSyncExternalStore } from 'react';

/**
 * Tiny store remembering that the session expired while the user was signed in, so the login page can say so.
 * Cleared on the next successful login.
 */
let expired = false;
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((listener) => listener());
}

export const sessionExpiry = {
  markExpired() {
    if (!expired) {
      expired = true;
      emit();
    }
  },
  clear() {
    if (expired) {
      expired = false;
      emit();
    }
  },
  isExpired: () => expired,
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};

export function useSessionExpired(): boolean {
  return useSyncExternalStore(sessionExpiry.subscribe, sessionExpiry.isExpired, sessionExpiry.isExpired);
}
