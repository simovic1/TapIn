import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '../../lib/api/client';
import type { Schemas } from '../../lib/api/types';

export type TrainingSummary = Schemas['TrainingSummary'];
export type TrainingDetail = Schemas['TrainingDetail'];
export type TrainingStatus = Schemas['TrainingStatus'];
export type TrainingMutationResult = Schemas['TrainingMutationResult'];
export type CreateTrainingRequest = Schemas['CreateTrainingRequest'];
export type UpdateTrainingRequest = Schemas['UpdateTrainingRequest'];
export type CoachRef = Schemas['CoachRef'];
export type RecurringSchedule = Schemas['RecurringSchedule'];
export type Weekday = Schemas['Weekday'];
export type CreateRecurringScheduleRequest = Schemas['CreateRecurringScheduleRequest'];
export type UpdateRecurringScheduleRequest = Schemas['UpdateRecurringScheduleRequest'];

export interface TrainingRange {
  from: string;
  to: string;
}

export const trainingKeys = {
  all: ['trainings'] as const,
  list: (range: TrainingRange) => ['trainings', 'list', range.from, range.to] as const,
  upcoming: ['trainings', 'upcoming'] as const,
  detail: (id: number) => ['trainings', 'detail', id] as const,
  coaches: ['trainings', 'coaches'] as const,
  schedules: ['trainings', 'schedules'] as const,
};

const listTrainings = (range: TrainingRange) =>
  apiFetch<Schemas['TrainingList']>(
    `/trainings?from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`,
  ).then((list) => list.items);

/** Sessions starting in `[from, to)` (max. 92 days), including cancelled ones. */
export function useTrainings(range: TrainingRange) {
  return useQuery({
    queryKey: trainingKeys.list(range),
    queryFn: () => listTrainings(range),
    placeholderData: keepPreviousData,
  });
}

const UPCOMING_DAYS = 60;

/**
 * Sessions that have not ended yet, for the next {@link UPCOMING_DAYS} days. Starts slightly in the past so a
 * session in progress is still listed (it ends later).
 */
export function useUpcomingTrainings() {
  return useQuery({
    queryKey: trainingKeys.upcoming,
    queryFn: async () => {
      const now = Date.now();
      const items = await listTrainings({
        from: new Date(now - 12 * 3_600_000).toISOString(),
        to: new Date(now + UPCOMING_DAYS * 86_400_000).toISOString(),
      });
      return items.filter((t) => Date.parse(t.endAt) > now);
    },
    refetchInterval: 5 * 60_000,
  });
}

export function useTraining(id: number) {
  return useQuery({
    queryKey: trainingKeys.detail(id),
    queryFn: () => apiFetch<TrainingDetail>(`/trainings/${id}`),
    enabled: Number.isFinite(id),
  });
}

export function useCoachCandidates(enabled = true) {
  return useQuery({
    queryKey: trainingKeys.coaches,
    queryFn: () => apiFetch<Schemas['CoachList']>('/trainings/coaches').then((list) => list.items),
    enabled,
    staleTime: 5 * 60_000,
  });
}

export function useRecurringSchedules() {
  return useQuery({
    queryKey: trainingKeys.schedules,
    queryFn: () => apiFetch<Schemas['RecurringScheduleList']>('/recurring-schedules').then((list) => list.items),
  });
}

/**
 * Every schedule change can move or cancel sessions, so the whole training cache is refreshed after it. Only on
 * success: a failed save never changes what the calendar shows.
 */
function useInvalidateTrainings() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: trainingKeys.all });
}

export function useCreateTraining() {
  const invalidate = useInvalidateTrainings();
  return useMutation({
    mutationFn: (request: CreateTrainingRequest) =>
      apiFetch<TrainingDetail>('/trainings', { method: 'POST', body: JSON.stringify(request) }),
    onSuccess: invalidate,
  });
}

export function useUpdateTraining(id: number) {
  const invalidate = useInvalidateTrainings();
  return useMutation({
    mutationFn: (request: UpdateTrainingRequest) =>
      apiFetch<TrainingMutationResult>(`/trainings/${id}`, { method: 'PATCH', body: JSON.stringify(request) }),
    onSuccess: invalidate,
  });
}

export function useCancelTraining(id: number) {
  const invalidate = useInvalidateTrainings();
  return useMutation({
    mutationFn: (request: Schemas['CancelTrainingRequest']) =>
      apiFetch<TrainingMutationResult>(`/trainings/${id}/cancel`, { method: 'POST', body: JSON.stringify(request) }),
    onSuccess: invalidate,
  });
}

export function useCreateSchedule() {
  const invalidate = useInvalidateTrainings();
  return useMutation({
    mutationFn: (request: CreateRecurringScheduleRequest) =>
      apiFetch<RecurringSchedule>('/recurring-schedules', { method: 'POST', body: JSON.stringify(request) }),
    onSuccess: invalidate,
  });
}

export function useUpdateSchedule(id: number) {
  const invalidate = useInvalidateTrainings();
  return useMutation({
    mutationFn: (request: UpdateRecurringScheduleRequest) =>
      apiFetch<RecurringSchedule>(`/recurring-schedules/${id}`, { method: 'PATCH', body: JSON.stringify(request) }),
    onSuccess: invalidate,
  });
}
