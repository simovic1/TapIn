import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client';
import type { Schemas } from './types';

export type SystemInfo = Schemas['SystemInfo'];

export const systemKeys = {
  info: ['system', 'info'] as const,
};

export function useSystemInfo() {
  return useQuery({
    queryKey: systemKeys.info,
    queryFn: () => apiFetch<SystemInfo>('/system/info'),
  });
}
