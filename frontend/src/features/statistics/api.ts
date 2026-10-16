import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { apiFetch } from '../../lib/api/client';
import type { Schemas } from '../../lib/api/types';

export type MemberStatistics = Schemas['MemberStatistics'];
export type AttendanceRate = Schemas['AttendanceRate'];
export type AttendanceHistoryEntry = Schemas['AttendanceHistoryEntry'];
export type AttendanceHistoryPage = Schemas['AttendanceHistoryPage'];

/**
 * Every successful mutation invalidates `statistics` (app/queryClient), so a registration, an attendance correction
 * or a belt change made in this browser is reflected without recounting anything here; changes made by a trainer
 * elsewhere arrive on the next focus/visit (the server always computes from the latest data).
 */
export const statisticsKeys = {
  all: ['statistics'] as const,
  own: ['statistics', 'me'] as const,
  ownHistory: (page: number) => ['statistics', 'me', 'history', page] as const,
};

export function useOwnStatistics() {
  return useQuery({
    queryKey: statisticsKeys.own,
    queryFn: () => apiFetch<MemberStatistics>('/me/statistics'),
    refetchOnWindowFocus: 'always',
  });
}

export const HISTORY_PAGE_SIZE = 20;

export function useOwnHistory(page: number) {
  return useQuery({
    queryKey: statisticsKeys.ownHistory(page),
    queryFn: () => apiFetch<AttendanceHistoryPage>(`/me/attendance?page=${page}&size=${HISTORY_PAGE_SIZE}`),
    placeholderData: keepPreviousData,
    refetchOnWindowFocus: 'always',
  });
}

/** "67 %" (hr-HR), or null when there is no denominator yet. */
export function percent(rate: AttendanceRate): string | null {
  if (rate.rate === null || rate.denominator === 0) return null;
  return new Intl.NumberFormat('hr-HR', { style: 'percent', maximumFractionDigits: 0 }).format(rate.rate);
}
