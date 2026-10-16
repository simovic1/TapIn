import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './client';

export interface SystemInfo {
  application: string;
  version: string;
  clubName: string;
  timeZone: string;
  serverTime: string;
}

export const systemKeys = {
  info: ['system', 'info'] as const,
};

export function useSystemInfo() {
  return useQuery({
    queryKey: systemKeys.info,
    queryFn: () => apiFetch<SystemInfo>('/system/info'),
  });
}
