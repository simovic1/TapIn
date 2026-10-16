import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { ApiError } from '../lib/api/client';
import { handleUnauthorized } from '../lib/session/session';

declare module '@tanstack/react-query' {
  interface Register {
    queryMeta: { ignoreUnauthorized?: boolean };
    mutationMeta: { ignoreUnauthorized?: boolean };
  }
}

export function createQueryClient() {
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (!query.meta?.ignoreUnauthorized) handleUnauthorized(client, error);
      },
    }),
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (!mutation.meta?.ignoreUnauthorized) handleUnauthorized(client, error);
      },
    }),
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        // Do not retry client errors (401/403/404/409...); retry transient failures once.
        retry: (failureCount, error) => !(error instanceof ApiError && error.status < 500) && failureCount < 1,
      },
      mutations: {
        // Never auto-retry mutations: attendance/registration writes must not be silently repeated.
        retry: false,
      },
    },
  });
  return client;
}

export const queryClient = createQueryClient();
