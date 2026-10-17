import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router';
import { queryClient } from './queryClient';
import { router } from './router';
import { PwaSupport } from './PwaSupport';

export function AppProviders() {
  return (
    <QueryClientProvider client={queryClient}>
      <PwaSupport />
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}
