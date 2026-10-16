import { QueryClient } from '@tanstack/react-query';
import { ApiError } from '../lib/api/client';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      // Do not retry client errors (401/403/404/409...); retry transient failures once.
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status < 500) && failureCount < 1,
    },
    mutations: {
      // Never auto-retry mutations: attendance/registration writes must not be silently repeated.
      retry: false,
    },
  },
});
