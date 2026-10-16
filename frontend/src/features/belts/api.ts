import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../../lib/api/client';
import type { Schemas } from '../../lib/api/types';

export type BeltHistory = Schemas['BeltHistory'];
export type BeltPeriod = Schemas['BeltPeriod'];
export type Belt = Schemas['Belt'];
export type CurrentBelt = Schemas['CurrentBelt'];
export type PromotionTiming = Schemas['PromotionTiming'];
export type CreateBeltPeriodRequest = Schemas['CreateBeltPeriodRequest'];
export type CorrectBeltPeriodRequest = Schemas['CorrectBeltPeriodRequest'];
export type BeltPeriodMutationResult = Schemas['BeltPeriodMutationResult'];
export type BeltAuditEntry = Schemas['BeltAuditEntry'];

export const BELTS: Belt[] = ['WHITE', 'BLUE', 'PURPLE', 'BROWN', 'BLACK'];

export const BELT_LABELS: Record<CurrentBelt, string> = {
  WHITE: 'Bijeli',
  BLUE: 'Plavi',
  PURPLE: 'Ljubičasti',
  BROWN: 'Smeđi',
  BLACK: 'Crni',
  UNKNOWN: 'Nije evidentiran',
};

export const beltKeys = {
  all: ['belts'] as const,
  history: (membershipId: number | 'me') => ['belts', 'history', membershipId] as const,
  audit: (membershipId: number) => ['belts', 'audit', membershipId] as const,
  member: (membershipId: number) => ['belts', 'member', membershipId] as const,
};

export function useBeltHistory(membershipId: number | 'me') {
  return useQuery({
    queryKey: beltKeys.history(membershipId),
    queryFn: () => apiFetch<BeltHistory>(membershipId === 'me' ? '/me/belts' : `/members/${membershipId}/belts`),
  });
}

export function useBeltAudit(membershipId: number) {
  return useQuery({
    queryKey: beltKeys.audit(membershipId),
    queryFn: () => apiFetch<Schemas['BeltAuditPage']>(`/members/${membershipId}/belts/audit?size=50`).then((p) => p.items),
  });
}

/** Name and e-mail for the page header (members API, not the members feature). */
export function useMemberName(membershipId: number) {
  return useQuery({
    queryKey: beltKeys.member(membershipId),
    queryFn: () => apiFetch<Schemas['MemberDetail']>(`/members/${membershipId}`),
  });
}

export const createBeltPeriod = (membershipId: number, request: CreateBeltPeriodRequest) =>
  apiFetch<BeltPeriodMutationResult>(`/members/${membershipId}/belts`, { method: 'POST', body: JSON.stringify(request) });

export const correctBeltPeriod = (membershipId: number, periodId: number, request: CorrectBeltPeriodRequest) =>
  apiFetch<BeltPeriodMutationResult>(`/members/${membershipId}/belts/${periodId}`, {
    method: 'PATCH',
    body: JSON.stringify(request),
  });

/**
 * After a confirmed change: the history (with counts) comes from the response, the audit and every member view
 * (directory, detail with the derived current belt) are reloaded.
 */
export function useBeltSaved(membershipId: number) {
  const queryClient = useQueryClient();
  return (result: BeltPeriodMutationResult) => {
    queryClient.setQueryData(beltKeys.history(membershipId), result.history);
    void queryClient.invalidateQueries({ queryKey: beltKeys.audit(membershipId) });
    void queryClient.invalidateQueries({ queryKey: ['members'] });
  };
}
