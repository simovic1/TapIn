import type { ReactNode } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import { EmptyState } from './EmptyState';
import { ErrorState } from './ErrorState';
import { LoadingState } from './LoadingState';

interface QueryStateProps<T> {
  query: UseQueryResult<T>;
  children: (data: T) => ReactNode;
  /** Return true when the loaded data should be shown as "no results". */
  isEmpty?: (data: T) => boolean;
  empty?: { title: string; description?: string; action?: ReactNode };
  loadingLabel?: string;
}

/** Renders the one correct state of a query: loading, error (with retry), empty, or the data. */
export function QueryState<T>({ query, children, isEmpty, empty, loadingLabel }: QueryStateProps<T>) {
  if (query.isPending) return <LoadingState label={loadingLabel} />;
  if (query.isError) {
    return <ErrorState error={query.error} onRetry={() => query.refetch()} retrying={query.isFetching} />;
  }
  if (isEmpty?.(query.data)) {
    return <EmptyState title={empty?.title ?? 'Nema rezultata'} description={empty?.description} action={empty?.action} />;
  }
  return <>{children(query.data)}</>;
}
