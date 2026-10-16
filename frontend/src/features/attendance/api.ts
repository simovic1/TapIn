import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../../lib/api/client';
import type { Schemas } from '../../lib/api/types';

export type Roster = Schemas['Roster'];
export type RosterEntry = Schemas['RosterEntry'];
export type RosterCounts = Schemas['RosterCounts'];
export type AttendanceResult = Schemas['AttendanceResult'];
export type AttendanceMark = Schemas['AttendanceMarkStatus'];
export type TrainingSummary = Schemas['TrainingSummary'];
export type MemberSummary = Schemas['MemberSummary'];

export const attendanceKeys = {
  roster: (trainingId: number) => ['attendance', 'roster', trainingId] as const,
  sessions: ['attendance', 'sessions'] as const,
  memberSearch: (q: string) => ['attendance', 'member-search', q] as const,
};

export function useRoster(trainingId: number) {
  return useQuery({
    queryKey: attendanceKeys.roster(trainingId),
    queryFn: () => apiFetch<Roster>(`/trainings/${trainingId}/roster`),
    enabled: Number.isFinite(trainingId),
  });
}

/** Sessions around now for choosing the roll call: from 12 h ago until a week ahead, not cancelled. */
export function useRollCallSessions() {
  return useQuery({
    queryKey: attendanceKeys.sessions,
    queryFn: async () => {
      const now = Date.now();
      const from = new Date(now - 7 * 86_400_000).toISOString();
      const to = new Date(now + 7 * 86_400_000).toISOString();
      const list = await apiFetch<Schemas['TrainingList']>(
        `/trainings?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&status=SCHEDULED&status=HELD`,
      );
      return list.items;
    },
  });
}

/** Active members by name or e-mail, for adding someone who came without registering (AC-06). */
export function useMemberSearch(q: string) {
  const query = q.trim();
  return useQuery({
    queryKey: attendanceKeys.memberSearch(query),
    queryFn: () =>
      apiFetch<Schemas['MemberSummaryPage']>(
        `/members?q=${encodeURIComponent(query)}&membershipStatus=ACTIVE&size=10`,
      ).then((page) => page.items),
    enabled: query.length >= 2,
    staleTime: 60_000,
  });
}

/** Recomputes the counters exactly like the server (spec §6.2) after a local row change. */
export function countsOf(entries: readonly RosterEntry[]): RosterCounts {
  let registered = 0, present = 0, absent = 0, unmarked = 0;
  for (const e of entries) {
    const active = e.registrationStatus === 'REGISTERED';
    if (active) registered++;
    if (e.attendanceStatus === 'PRESENT') present++;
    if (e.attendanceStatus === 'ABSENT') absent++;
    if (active && e.attendanceStatus === 'UNMARKED') unmarked++;
  }
  return { registered, present, absent, unmarked };
}

/** Puts a server-confirmed row into the cached roster (no refetch per tap; 20 members on a phone). */
export function useApplyRosterEntry(trainingId: number) {
  const queryClient = useQueryClient();
  return (entry: RosterEntry) =>
    queryClient.setQueryData<Roster>(attendanceKeys.roster(trainingId), (roster) => {
      if (!roster) return roster;
      const exists = roster.entries.some((e) => e.membershipId === entry.membershipId);
      const entries = exists
        ? roster.entries.map((e) => (e.membershipId === entry.membershipId ? entry : e))
        : [...roster.entries, entry];
      return { ...roster, entries, counts: countsOf(entries) };
    });
}

export function setAttendance(trainingId: number, membershipId: number, status: AttendanceMark, expectedVersion: number) {
  return apiFetch<AttendanceResult>(`/trainings/${trainingId}/attendance/${membershipId}`, {
    method: 'PUT',
    body: JSON.stringify({ status, expectedVersion }),
  });
}

export function resetAttendance(trainingId: number, membershipId: number, expectedVersion: number) {
  return apiFetch<AttendanceResult>(
    `/trainings/${trainingId}/attendance/${membershipId}?expectedVersion=${expectedVersion}`,
    { method: 'DELETE' },
  );
}

export function useMarkUnconfirmedAbsent(trainingId: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (membershipIds: number[]) =>
      apiFetch<Schemas['BulkAttendanceResult']>(`/trainings/${trainingId}/attendance/mark-unconfirmed-absent`, {
        method: 'POST',
        body: JSON.stringify({ expectedMembershipIds: membershipIds }),
      }),
    // Success or not, the roster is reloaded: the server decided per member.
    onSettled: () => queryClient.invalidateQueries({ queryKey: attendanceKeys.roster(trainingId) }),
  });
}
