import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { json, makeUser, mockApi, problem, renderApp } from '../../test/utils';
import type { Roster, RosterEntry } from './api';
import { countsOf } from './api';

const NOW = new Date('2026-10-20T16:45:00Z'); // Tuesday 18:45 in Zagreb, during the training

const training = {
  trainingId: 1,
  scheduleId: 1,
  occurrenceDate: '2026-10-20',
  title: null,
  startAt: '2026-10-20T16:30:00Z',
  endAt: '2026-10-20T18:00:00Z',
  localDate: '2026-10-20',
  status: 'SCHEDULED' as const,
  coaches: [],
  registeredCount: 2,
  myRegistrationStatus: 'NONE' as const,
  myAttendanceStatus: 'UNMARKED' as const,
  canRegister: false,
  canCancelRegistration: false,
  version: 0,
};

function entry(id: number, firstName: string, overrides: Partial<RosterEntry> = {}): RosterEntry {
  return {
    membershipId: id,
    userId: id + 100,
    firstName,
    lastName: 'Test',
    currentBelt: 'UNKNOWN',
    registrationStatus: 'REGISTERED',
    attendanceStatus: 'UNMARKED',
    attendanceVersion: 0,
    attendanceUpdatedAt: null,
    attendanceUpdatedByUserId: null,
    ...overrides,
  };
}

function roster(entries: RosterEntry[]): Roster {
  return { training, counts: countsOf(entries), entries };
}

const ana = entry(1, 'Ana');
const ivo = entry(2, 'Ivo');
const luka = entry(3, 'Luka', { registrationStatus: 'CANCELLED' });
const gost = entry(4, 'Gost', { registrationStatus: null, attendanceStatus: 'PRESENT', attendanceVersion: 1 });
const trainer = () => json(200, makeUser(['TRAINER']));
const result = (e: RosterEntry, status: RosterEntry['attendanceStatus'], version: number) => ({
  trainingId: 1, membershipId: e.membershipId, userId: e.userId, status, version, changed: true,
  updatedAt: '2026-10-20T16:45:00Z', updatedByUserId: 9, notificationStatus: 'QUEUED',
});
const counter = (label: string) => within(screen.getByLabelText('Brojači')).getByText(label).nextElementSibling!;
const row = (name: string) => screen.getByText(`${name} Test`).closest('li')!;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => vi.useRealTimers());

describe('roll call', () => {
  it('shows registered, cancelled and walk-in members with counters', async () => {
    mockApi({ 'GET /api/v1/me': trainer, 'GET /api/v1/trainings/1/roster': () => json(200, roster([ana, ivo, luka, gost])) });
    renderApp('/prozivka/1');

    expect(await screen.findByRole('heading', { name: /Prozivka: Trening/ })).toBeInTheDocument();
    expect(within(row('Luka')).getByText(/Otkazao prijavu/)).toBeInTheDocument();
    expect(within(row('Gost')).getByText(/Bez prijave/)).toBeInTheDocument();
    expect(within(row('Gost')).getByRole('button', { name: 'Prisutan: Gost Test' })).toHaveAttribute('aria-pressed', 'true');
    expect(counter('Prijavljeni')).toHaveTextContent('2');
    expect(counter('Prisutni')).toHaveTextContent('1');
    expect(counter('Nepotvrđeni')).toHaveTextContent('2');
  });

  it('saves one tap per row, shows the progress and updates the counters', async () => {
    let finish!: () => void;
    const fetch = mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/1/roster': () => json(200, roster([ana, ivo])),
      'PUT /api/v1/trainings/1/attendance/1': () =>
        new Promise<Response>((resolve) => { finish = () => resolve(json(200, result(ana, 'PRESENT', 1))); }),
    });
    renderApp('/prozivka/1');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Prisutan: Ana Test' }));

    expect(within(row('Ana')).getByText('Spremanje…')).toBeInTheDocument();
    expect(within(row('Ana')).queryByText('Spremljeno')).not.toBeInTheDocument();
    finish();
    expect(await within(row('Ana')).findByText('Spremljeno')).toBeInTheDocument();
    expect(within(row('Ana')).getByRole('button', { name: 'Prisutan: Ana Test' })).toHaveAttribute('aria-pressed', 'true');
    expect(counter('Prisutni')).toHaveTextContent('1');
    expect(counter('Nepotvrđeni')).toHaveTextContent('1');
    const put = fetch.mock.calls.find(([url]) => String(url).endsWith('/attendance/1'))!;
    expect(JSON.parse(String(put[1]!.body))).toEqual({ status: 'PRESENT', expectedVersion: 0, note: '', source: 'SINGLE' });
  });

  it('does not show a failed save as saved and retries with the same choice', async () => {
    let fail = true;
    mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/1/roster': () => json(200, roster([ana])),
      'PUT /api/v1/trainings/1/attendance/1': () =>
        fail ? Promise.reject(new TypeError('offline')) : json(200, result(ana, 'ABSENT', 1)),
    });
    renderApp('/prozivka/1');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Odsutan: Ana Test' }));

    expect(await within(row('Ana')).findByRole('alert')).toHaveTextContent('Greška pri spremanju');
    expect(within(row('Ana')).getByRole('button', { name: 'Odsutan: Ana Test' })).toHaveAttribute('aria-pressed', 'false');
    expect(counter('Odsutni')).toHaveTextContent('0');
    fail = false;
    await user.click(within(row('Ana')).getByRole('button', { name: 'Pokušaj ponovno' }));
    expect(await within(row('Ana')).findByText('Spremljeno')).toBeInTheDocument();
    expect(counter('Odsutni')).toHaveTextContent('1');
  });

  it('shows a 409 with the other trainer\'s value and lets the trainer decide again', async () => {
    const theirs = { ...ana, attendanceStatus: 'ABSENT' as const, attendanceVersion: 3 };
    const fetch = mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/1/roster': () => json(200, roster([ana])),
      'PUT /api/v1/trainings/1/attendance/1': (_url, init) => {
        const body = JSON.parse(String(init.body));
        return body.expectedVersion === 0
          ? json(409, { type: 'urn:tapin:error:version-conflict', title: 'Conflict', status: 409, detail: 'x',
              instance: '/x', errorCode: 'VERSION_CONFLICT', currentState: theirs })
          : json(200, result(ana, 'PRESENT', 4));
      },
    });
    renderApp('/prozivka/1');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Prisutan: Ana Test' }));

    expect(await within(row('Ana')).findByRole('alert')).toHaveTextContent('sada „Odsutan”');
    expect(within(row('Ana')).getByRole('button', { name: 'Odsutan: Ana Test' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(row('Ana')).queryByText('Spremljeno')).not.toBeInTheDocument();
    await user.click(within(row('Ana')).getByRole('button', { name: 'Prisutan: Ana Test' }));
    expect(await within(row('Ana')).findByText('Spremljeno')).toBeInTheDocument();
    const puts = fetch.mock.calls.filter(([url]) => String(url).endsWith('/attendance/1'));
    expect(JSON.parse(String(puts[1]![1]!.body)).expectedVersion).toBe(3);
  });

  it('asks before the bulk action, lists the candidates and reports the real number changed', async () => {
    let afterBulk = false;
    const fetch = mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/1/roster': () =>
        json(200, roster(afterBulk
          ? [{ ...ana, attendanceStatus: 'ABSENT', attendanceVersion: 1 }, { ...ivo, attendanceStatus: 'PRESENT', attendanceVersion: 1 }, luka, gost]
          : [ana, ivo, luka, gost])),
      'POST /api/v1/trainings/1/attendance/mark-unconfirmed-absent': () => {
        afterBulk = true;
        return json(200, { trainingId: 1, updatedCount: 1, results: [result(ana, 'ABSENT', 1)] });
      },
    });
    renderApp('/prozivka/1');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Označi sve preostale odsutnima (2)' }));

    const panel = screen.getByText('Označiti odsutnima 2 članova?').parentElement!;
    expect(within(panel).getByText('Ana Test')).toBeInTheDocument();
    expect(within(panel).getByText('Ivo Test')).toBeInTheDocument();
    expect(within(panel).queryByText('Luka Test')).not.toBeInTheDocument();
    expect(fetch.mock.calls.some(([url]) => String(url).includes('mark-unconfirmed-absent'))).toBe(false);
    await user.click(within(panel).getByRole('button', { name: 'Potvrdi (2)' }));

    expect(await screen.findByText(/Označeno odsutnima: 1 od 2/)).toBeInTheDocument();
    const call = fetch.mock.calls.find(([url]) => String(url).includes('mark-unconfirmed-absent'))!;
    expect(JSON.parse(String(call[1]!.body))).toEqual({ expectedMembershipIds: [1, 2] });
    await waitFor(() => expect(counter('Odsutni')).toHaveTextContent('1'));
    // Already recorded and walk-in members stay visible.
    expect(row('Gost')).toBeInTheDocument();
    expect(row('Luka')).toBeInTheDocument();
  });

  it('adds a member who came without registering', async () => {
    let added = false;
    const fetch = mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/1/roster': () =>
        json(200, roster(added ? [ana, entry(7, 'Marko', { registrationStatus: null, attendanceStatus: 'PRESENT', attendanceVersion: 1 })] : [ana])),
      'GET /api/v1/members': () =>
        json(200, {
          items: [
            { membershipId: 1, firstName: 'Ana', lastName: 'Test' },
            { membershipId: 7, firstName: 'Marko', lastName: 'Test' },
          ],
          page: { page: 0, size: 10, totalElements: 2, totalPages: 1 },
        }),
      'PUT /api/v1/trainings/1/attendance/7': () => {
        added = true;
        return json(200, result(entry(7, 'Marko'), 'PRESENT', 1));
      },
    });
    renderApp('/prozivka/1');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/Pretraži članove/), 'te');

    const search = screen.getByRole('region', { name: 'Dodaj člana koji nije prijavljen' });
    await user.click(await within(search).findByRole('button', { name: 'Prisutan' }));
    expect(within(search).queryByText('Ana Test')).not.toBeInTheDocument(); // already on the roster
    expect(await screen.findByText(/Marko Test je dodan/)).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Prisutan: Marko Test' })).toHaveAttribute('aria-pressed', 'true');
    const put = fetch.mock.calls.find(([url]) => String(url).endsWith('/attendance/7'))!;
    expect(JSON.parse(String(put[1]!.body))).toEqual({ status: 'PRESENT', expectedVersion: 0, note: '', source: 'SINGLE' });
    expect(String(fetch.mock.calls.find(([url]) => String(url).startsWith('/api/v1/members'))![0])).toContain('membershipStatus=ACTIVE');
  });

  it('opens the suggested session from /prozivka and is closed to members', async () => {
    mockApi({ 'GET /api/v1/me': trainer, 'GET /api/v1/trainings': () => json(200, { items: [training] }) });
    renderApp('/prozivka');
    expect(await screen.findByRole('link', { name: /Otvori prozivku/ })).toHaveAttribute('href', '/prozivka/1');
  });

  it('refuses members', async () => {
    mockApi({ 'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])) });
    renderApp('/prozivka/1');
    expect(await screen.findByRole('heading', { name: 'Nemate pristup' })).toBeInTheDocument();
    void problem;
  });
});


describe('historical attendance and audit', () => {
  it('includes currently inactive members when searching a past training', async () => {
    const fetch = mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/1/roster': () => json(200, { ...roster([]), training: { ...training, status: 'HELD', startAt: '2026-08-15T16:30:00Z', endAt: '2026-08-15T18:00:00Z' } }),
      'GET /api/v1/members': () => json(200, { items: [{ membershipId: 5, userId: 105, firstName: 'Bivsi', lastName: 'Clan', membershipStatus: 'INACTIVE' }], page: { page: 0, size: 10, totalPages: 1, totalElements: 1 } }),
    });
    renderApp('/prozivka/1');
    await userEvent.setup().type(await screen.findByLabelText('Pretraži članove'), 'Bivsi');
    expect(await screen.findByText('Bivsi Clan')).toBeInTheDocument();
    const searches = fetch.mock.calls.filter(([url]) => String(url).startsWith('/api/v1/members?'));
    expect(searches.length).toBeGreaterThan(0);
    expect(searches.every(([url]) => !String(url).includes('membershipStatus=ACTIVE'))).toBe(true);
  });

  it('sends a historical source and note, then shows a read-only filtered audit', async () => {
    const user = userEvent.setup();
    const fetch = mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/1/roster': () => json(200, { ...roster([ana]), training: { ...training, status: 'HELD', startAt: '2026-08-15T16:30:00Z', endAt: '2026-08-15T18:00:00Z' } }),
      'PUT /api/v1/trainings/1/attendance/1': () => json(200, result(ana, 'PRESENT', 1)),
      'DELETE /api/v1/trainings/1/attendance/1': () => json(200, result(ana, 'UNMARKED', 2)),
      'GET /api/v1/trainings/1/attendance/audit': () => json(200, { items: [{ auditId: 1, trainingId: 1, membershipId: 1, actorUserId: 9, actorName: 'Ivan Trener', memberName: 'Ana Test', previousStatus: 'PRESENT', newStatus: 'UNMARKED', source: 'CORRECTION', note: 'Krivi unos', occurredAt: NOW.toISOString() }], page: { page: 0, size: 20, totalElements: 1, totalPages: 1 } }),
    });
    renderApp('/prozivka/1');
    await screen.findByText('Ana Test');
    expect(screen.getByText(/zasebna obavijest za svakoga/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Napomena za Ana Test'), 'Stara biljeznica');
    await user.click(screen.getByRole('button', { name: 'Prisutan: Ana Test' }));
    await screen.findByText('Spremljeno');
    const call = fetch.mock.calls.find(([url, init]) => String(url).endsWith('/attendance/1') && init?.method === 'PUT');
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ source: 'HISTORICAL_IMPORT', note: 'Stara biljeznica', expectedVersion: 0 });
    await user.click(screen.getByRole('button', { name: 'Poništi oznaku' }));
    await waitFor(() => expect(fetch.mock.calls.some(([url, init]) => init?.method === 'DELETE' && String(url).includes('expectedVersion=1&note=Stara%20biljeznica'))).toBe(true));
    await user.click(screen.getByRole('button', { name: 'Prikaži povijest' }));
    expect(await screen.findByText(/Autor: Ivan Trener/)).toBeInTheDocument();
    expect(screen.getByText('Ana Test: Prisutan → Nije označeno')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Član u povijesti'), '1');
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => String(url).includes('membershipId=1'))).toBe(true));
    expect(screen.queryByRole('button', { name: /Obriši audit|Uredi audit/ })).not.toBeInTheDocument();
  });
});
