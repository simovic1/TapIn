import { apiFetch } from '../../lib/api/client';
import type { Schemas } from '../../lib/api/types';

export type Member = Schemas['MemberDetail'] & {
  statusHistory: { oldStatus: string | null; newStatus: string; effectiveAt: string; actorUserId: number | null }[];
};
export type MemberPage = { items: Member[]; page: Schemas['PageMetadata'] };
export type MemberChanges = Schemas['UpdateMemberRequest'] & { email?: string };
export const memberKeys = { all: ['members'] as const, detail: (id: number) => ['members', 'detail', id] as const };
export const listMembers = (params: URLSearchParams) => apiFetch<MemberPage>(`/members?${params}`);
export const getMember = (id: number) => apiFetch<Member>(`/members/${id}`);
export const updateMember = (id: number, fields: MemberChanges) =>
  apiFetch<Member>(`/members/${id}`, { method: 'PATCH', body: JSON.stringify(fields) });
export const replaceRoles = (id: number, request: Schemas['ReplaceRolesRequest']) =>
  apiFetch<Member>(`/members/${id}/roles`, { method: 'PUT', body: JSON.stringify(request) });
export const inviteMember = (request: Schemas['InviteMemberRequest']) =>
  apiFetch<Schemas['InvitationResult']>('/members/invitations', { method: 'POST', body: JSON.stringify(request) });
export const resendInvitation = (id: number) =>
  apiFetch<Schemas['InvitationResult']>(`/members/${id}/invitations/resend`, { method: 'POST' });
