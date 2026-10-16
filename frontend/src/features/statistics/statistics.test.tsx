import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { json, makeUser, mockApi, renderApp } from '../../test/utils';
import type { AttendanceHistoryEntry, AttendanceHistoryPage, MemberStatistics } from './api';

const NOW = new Date('2026-10-16T10:00:00Z');

const stats: MemberStatistics = {
  membershipId: 123,
  userId: 77,
  asOf: '2026-10-16T10:00:00Z',
  windows: { today: '2026-10-16', thisMonthFrom: '2026-10-01', last7DaysFrom: '2026-10-10', last30DaysFrom: '2026-09-17' },
  memberSince: '2026-09-01',
  totalPresent: 4,
  presentThisMonth: 2,
  presentLast7Days: 1,
  presentLast30Days: 3,
  registeredButAbsent: 1,
  registrations: { total: 5, present: 3, absent: 1, unmarked: 1 },
  attendanceRate: { numerator: 4, denominator: 6, rate: 4 / 6, basis: 'HELD_SESSIONS_WHILE_ACTIVE' },
  registrationAttendanceRate: { numerator: 3, denominator: 4, rate: 3 / 4, basis: 'REGISTRATIONS' },
  currentBelt: 'WHITE',
  currentBeltAwardedOn: null,
  currentBeltTrainings: 3,
  unassignedTrainings: 1,
  beltPeriods: [
    { beltPeriodId: 1, belt: 'WHITE', awardedOn: null, effectiveFromDate: '2026-09-10', effectiveToDate: null, trainingsCount: 3 },
  ],
};

const entry = (overrides: Partial<AttendanceHistoryEntry>): AttendanceHistoryEntry => ({
  trainingId: 1,
  localDate: '2026-10-15',
  startAt: '2026-10-15T16:30:00Z',
  title: null,
  trainingStatus: 'HELD',
  registrationStatus: 'REGISTERED',
  attendanceStatus: 'PRESENT',
  ...overrides,
});

const history: AttendanceHistoryPage = {
  items: [
    entry({ trainingId: 8, title: 'Open mat' }),
    entry({ trainingId: 6, localDate: '2026-10-12', startAt: '2026-10-12T16:30:00Z', attendanceStatus: 'UNMARKED' }),
    entry({ trainingId: 4, localDate: '2026-10-01', startAt: '2026-10-01T16:30:00Z', attendanceStatus: 'ABSENT' }),
    entry({ trainingId: 5, localDate: '2026-10-05', startAt: '2026-10-05T16:30:00Z', trainingStatus: 'CANCELLED', attendanceStatus: 'UNMARKED' }),
  ],
  page: { page: 0, size: 20, totalElements: 4, totalPages: 1 },
};

const empty: MemberStatistics = {
  ...stats,
  totalPresent: 0,
  presentThisMonth: 0,
  presentLast7Days: 0,
  presentLast30Days: 0,
  registeredButAbsent: 0,
  registrations: { total: 0, present: 0, absent: 0, unmarked: 0 },
  attendanceRate: { numerator: 0, denominator: 0, rate: null, basis: 'HELD_SESSIONS_WHILE_ACTIVE' },
  registrationAttendanceRate: { numerator: 0, denominator: 0, rate: null, basis: 'REGISTRATIONS' },
  currentBelt: 'UNKNOWN',
  currentBeltTrainings: 0,
  unassignedTrainings: 0,
  beltPeriods: [],
};

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => vi.useRealTimers());

const counter = (label: string) => screen.getByText(label, { selector: 'dt' }).parentElement!;

describe('Moja statistika', () => {
  it('shows trainings, registrations, periods and the basis of every percentage', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/me/statistics': () => json(200, stats),
      'GET /api/v1/me/attendance': () => json(200, history),
    });
    renderApp('/statistika');

    expect(await screen.findByRole('heading', { name: 'Moja statistika' })).toBeInTheDocument();
    await screen.findByText('Ukupno', { selector: 'dt' });
    expect(within(counter('Ukupno')).getByText('4')).toBeInTheDocument();
    expect(within(counter('Ovaj mjesec')).getByText('1. listopada 2026. – 16. listopada 2026.')).toBeInTheDocument();
    expect(within(counter('Zadnjih 7 dana')).getByText('10. listopada 2026. – 16. listopada 2026.')).toBeInTheDocument();
    expect(within(counter('Zadnjih 30 dana')).getByText('3')).toBeInTheDocument();

    // Registrations are not trainings; UNMARKED is "waiting", not an absence.
    expect(within(counter('Prijave')).getByText('5')).toBeInTheDocument();
    expect(within(counter('Prijavljeni, niste došli')).getByText('1')).toBeInTheDocument();
    expect(within(counter('Čeka evidenciju')).getByText('trener još nije označio – nije odsutnost')).toBeInTheDocument();

    expect(screen.getByText('67 %')).toBeInTheDocument();
    expect(screen.getByText('75 %')).toBeInTheDocument();
    expect(screen.getByText('4 od 6 održanih treninga od učlanjenja (1. rujna 2026.), dok je članstvo bilo aktivno.')).toBeInTheDocument();
    expect(screen.getByText('3 od 4 prijava za koje je trener evidentirao dolazak ili izostanak.')).toBeInTheDocument();
    expect(screen.getByText('1 prijava čeka evidenciju i nije uračunata.')).toBeInTheDocument();

    const belts = screen.getByRole('table');
    expect(within(belts).getByText('Bijeli')).toBeInTheDocument();
    expect(within(belts).getByText('Neraspoređeni dolasci')).toBeInTheDocument();

    const rows = within(await screen.findByRole('list', { name: 'Povijest treninga' })).getAllByRole('listitem');
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringContaining('Došli ste – broji se'),
      expect.stringContaining('Čeka evidenciju trenera'),
      expect.stringContaining('Niste došli'),
      expect.stringContaining('Trening otkazan – ne broji se'),
    ]);
    expect(rows[0]).toHaveTextContent('Open mat · Prijavljeni');
    expect(rows[1]).not.toHaveTextContent('Niste došli');
  });

  it('shows empty data without errors and without a made-up percentage', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/me/statistics': () => json(200, empty),
      'GET /api/v1/me/attendance': () => json(200, { items: [], page: { page: 0, size: 20, totalElements: 0, totalPages: 0 } }),
    });
    renderApp('/statistika');

    expect(await screen.findByText('Još nema održanih treninga od vašeg učlanjenja.')).toBeInTheDocument();
    expect(screen.getByText('Još nema prijava s evidentiranim dolaskom ili izostankom.')).toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
    expect(await screen.findByText('Još nema treninga')).toBeInTheDocument();
  });

  it('reloads the numbers from the server instead of recounting them', async () => {
    const user = userEvent.setup();
    let current = stats;
    const fetch = mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/me/statistics': () => json(200, current),
      'GET /api/v1/me/attendance': () => json(200, history),
    });
    renderApp('/statistika');
    expect(await screen.findByText('67 %')).toBeInTheDocument();

    // A trainer corrected ABSENT to PRESENT meanwhile.
    current = {
      ...stats,
      totalPresent: 5,
      registeredButAbsent: 0,
      attendanceRate: { numerator: 5, denominator: 6, rate: 5 / 6, basis: 'HELD_SESSIONS_WHILE_ACTIVE' },
    };
    await user.click(screen.getByRole('button', { name: 'Osvježi' }));

    await waitFor(() => expect(screen.getByText('83 %')).toBeInTheDocument());
    expect(within(counter('Ukupno')).getByText('5')).toBeInTheDocument();
    expect(fetch.mock.calls.filter(([url]) => String(url).endsWith('/me/statistics'))).toHaveLength(2);
  });
});
