import type { TrainingDetail } from './api';

/** A Tuesday session from the weekly rule (20 Oct 2026, 18:30-20:00 Zagreb) for tests. */
export function training(overrides: Partial<TrainingDetail> = {}): TrainingDetail {
  return {
    trainingId: 1,
    scheduleId: 1,
    occurrenceDate: '2026-10-20',
    title: null,
    startAt: '2026-10-20T16:30:00Z',
    endAt: '2026-10-20T18:00:00Z',
    localDate: '2026-10-20',
    status: 'SCHEDULED',
    coaches: [],
    myRegistrationStatus: 'NONE',
    myAttendanceStatus: 'UNMARKED',
    canRegister: false,
    canCancelRegistration: false,
    version: 0,
    cancellationReason: null,
    note: null,
    createdByUserId: null,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}
