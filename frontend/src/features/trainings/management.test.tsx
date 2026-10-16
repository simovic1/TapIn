import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { json, makeUser, mockApi, problem, renderApp } from '../../test/utils';
import type { RecurringSchedule } from './api';
import { training } from './testFixtures';

const NOW = new Date('2026-10-16T10:00:00Z');
const coaches = {
  items: [
    { membershipId: 5, userId: 6, firstName: 'Marko', lastName: 'Trener' },
    { membershipId: 7, userId: 8, firstName: 'Iva', lastName: 'Vodić' },
  ],
};
const trainer = () => json(200, makeUser(['TRAINER', 'MEMBER']));

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => vi.useRealTimers());

const body = (call: unknown[] | undefined) => JSON.parse(String((call?.[1] as RequestInit).body));
const callsTo = (fetch: ReturnType<typeof mockApi>, method: string, path: string) =>
  fetch.mock.calls.filter(([url, init]) => String(url).replace(/\?.*$/, '') === `/api/v1${path}` && (init?.method ?? 'GET') === method);

describe('extra training', () => {
  it('validates times on the client and never posts an invalid form', async () => {
    const fetch = mockApi({ 'GET /api/v1/me': trainer, 'GET /api/v1/trainings/coaches': () => json(200, coaches) });
    renderApp('/treninzi/novi');
    const user = userEvent.setup();
    await user.clear(await screen.findByLabelText(/^Datum/));
    await user.type(screen.getByLabelText(/^Datum/), '2026-10-24');
    await user.clear(screen.getByLabelText(/^Završetak/));
    await user.type(screen.getByLabelText(/^Završetak/), '17:00');
    await user.click(screen.getByRole('button', { name: 'Spremi trening' }));

    expect(await screen.findByText('Završetak mora biti nakon početka.')).toBeInTheDocument();
    expect(screen.getByLabelText(/^Završetak/)).toHaveAttribute('aria-invalid', 'true');
    expect(callsTo(fetch, 'POST', '/trainings')).toHaveLength(0);
  });

  it('shows an overlap from the server at the time field and does not report success', async () => {
    const fetch = mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/coaches': () => json(200, coaches),
      'POST /api/v1/trainings': () => problem(409, 'TRAINING_OVERLAP'),
    });
    const { router } = renderApp('/treninzi/novi');
    const user = userEvent.setup();
    await user.clear(await screen.findByLabelText(/^Datum/));
    await user.type(screen.getByLabelText(/^Datum/), '2026-10-20');
    await user.click(screen.getByRole('button', { name: 'Spremi trening' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Termin se preklapa s drugim aktivnim treningom');
    expect(screen.getByLabelText(/^Početak/)).toHaveAttribute('aria-invalid', 'true');
    expect(router.state.location.pathname).toBe('/treninzi/novi');
    expect(screen.queryByText(/dodan/)).not.toBeInTheDocument();
    expect(callsTo(fetch, 'POST', '/trainings')).toHaveLength(1);
  });

  it('saves Zagreb wall-clock time as UTC with coaches and opens the new training', async () => {
    const created = training({ trainingId: 40, scheduleId: null, occurrenceDate: null, title: 'Open mat', startAt: '2026-10-31T09:00:00Z', endAt: '2026-10-31T11:00:00Z', localDate: '2026-10-31' });
    const fetch = mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/coaches': () => json(200, coaches),
      'POST /api/v1/trainings': () => json(201, created),
      'GET /api/v1/trainings/40': () => json(200, created),
    });
    const { router } = renderApp('/treninzi/novi');
    const user = userEvent.setup();
    await user.clear(await screen.findByLabelText(/^Datum/));
    await user.type(screen.getByLabelText(/^Datum/), '2026-10-31');
    await user.clear(screen.getByLabelText(/^Početak/));
    await user.type(screen.getByLabelText(/^Početak/), '10:00');
    await user.clear(screen.getByLabelText(/^Završetak/));
    await user.type(screen.getByLabelText(/^Završetak/), '12:00');
    await user.type(screen.getByLabelText(/^Naziv/), 'Open mat');
    await user.click(await screen.findByRole('checkbox', { name: 'Iva Vodić' }));
    await user.click(screen.getByRole('checkbox', { name: 'Marko Trener' }));
    await user.click(screen.getByRole('button', { name: 'Spremi trening' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/treninzi/40'));
    expect(await screen.findByText('Trening je dodan u raspored.')).toBeInTheDocument();
    // 31 Oct is after the DST change: 10:00 in Zagreb = 09:00 UTC.
    expect(body(callsTo(fetch, 'POST', '/trainings')[0])).toEqual({
      startAt: '2026-10-31T09:00:00.000Z',
      endAt: '2026-10-31T11:00:00.000Z',
      title: 'Open mat',
      note: null,
      coachMembershipIds: [7, 5],
      historical: false,
    });
  });

  it('is not available to members', async () => {
    mockApi({ 'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])) });
    renderApp('/treninzi/novi');
    expect(await screen.findByRole('heading', { name: 'Nemate pristup' })).toBeInTheDocument();
  });
});

describe('one session', () => {
  it('edits only this occurrence and reports a concurrent change instead of success', async () => {
    const tuesday = training({ coaches: [coaches.items[0]!], version: 3 });
    const fetch = mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/1': () => json(200, tuesday),
      'GET /api/v1/trainings/coaches': () => json(200, coaches),
      'PATCH /api/v1/trainings/1': () => problem(409, 'VERSION_CONFLICT'),
    });
    renderApp('/treninzi/1/uredi');
    const user = userEvent.setup();

    expect(await screen.findByText('Mijenjate samo ovaj termin')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'tjedni raspored' })).toHaveAttribute('href', '/treninzi/raspored');
    expect(screen.getByLabelText(/^Početak/)).toHaveValue('18:30');
    await user.clear(screen.getByLabelText(/^Početak/));
    await user.type(screen.getByLabelText(/^Početak/), '19:00');
    await user.click(screen.getByRole('button', { name: 'Spremi samo ovaj termin' }));

    expect(await screen.findByText('Termin je u međuvremenu izmijenjen')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Učitaj najnovije' })).toBeInTheDocument();
    expect(body(callsTo(fetch, 'PATCH', '/trainings/1')[0])).toEqual({
      expectedVersion: 3,
      title: null,
      note: null,
      coachMembershipIds: [5],
      startAt: '2026-10-20T17:00:00.000Z',
      endAt: '2026-10-20T18:00:00.000Z',
    });
  });

  it('keeps the time of a held session read-only (D-03)', async () => {
    const held = training({ status: 'HELD', startAt: '2026-10-13T16:30:00Z', endAt: '2026-10-13T18:00:00Z', localDate: '2026-10-13', occurrenceDate: '2026-10-13' });
    mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/1': () => json(200, held),
      'GET /api/v1/trainings/coaches': () => json(200, coaches),
    });
    renderApp('/treninzi/1');
    expect(await screen.findByRole('link', { name: 'Uredi naziv, napomenu i trenere' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Otkaži trening' })).not.toBeInTheDocument();
  });

  it('shows what is cancelled before cancelling and only reports success from the server', async () => {
    let fail = true;
    const tuesday = training({ registeredCount: 4, version: 2 });
    const fetch = mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/trainings/1': () => json(200, tuesday),
      'POST /api/v1/trainings/1/cancel': () =>
        fail
          ? problem(500, 'INTERNAL_ERROR')
          : json(200, { training: { ...tuesday, status: 'CANCELLED' }, changed: true, notificationStatus: 'QUEUED' }),
    });
    renderApp('/treninzi/1');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Otkaži trening' }));

    const panel = screen.getByRole('heading', { name: 'Otkazujete samo ovaj termin' }).closest('form')!;
    expect(within(panel).getByText(/utorak, 20\. listopada 2026/i)).toBeInTheDocument();
    expect(within(panel).getByText('18:30–20:00')).toBeInTheDocument();
    expect(within(panel).getByText('4')).toBeInTheDocument();
    expect(within(panel).getByText(/Tjedni raspored ostaje/)).toBeInTheDocument();
    await user.type(within(panel).getByLabelText(/Razlog/), 'Blagdan');
    await user.click(within(panel).getByRole('button', { name: 'Potvrdi otkazivanje' }));

    expect(await screen.findByText('Trening nije otkazan')).toBeInTheDocument();
    expect(screen.queryByText(/Trening je otkazan/)).not.toBeInTheDocument();

    fail = false;
    await user.click(within(panel).getByRole('button', { name: 'Potvrdi otkazivanje' }));
    expect(await screen.findByText('Trening je otkazan. Obavijest prijavljenima je stavljena u red za slanje.')).toBeInTheDocument();
    expect(body(callsTo(fetch, 'POST', '/trainings/1/cancel')[1])).toEqual({ expectedVersion: 2, reason: 'Blagdan' });
    // The detail is reloaded after the successful change.
    await waitFor(() => expect(callsTo(fetch, 'GET', '/trainings/1').length).toBeGreaterThan(1));
  });
});

describe('weekly schedule', () => {
  const rule: RecurringSchedule = {
    scheduleId: 1,
    weekday: 'TUESDAY',
    startTime: '18:30',
    endTime: '20:00',
    validFrom: '2026-01-01',
    validTo: null,
    enabled: true,
    version: 0,
  };
  const sessions = [
    training(),
    training({ trainingId: 3, occurrenceDate: '2026-10-27', startAt: '2026-10-27T17:30:00Z', endAt: '2026-10-27T19:00:00Z', localDate: '2026-10-27' }),
    // Moved individually to Wednesday: not touched by rule changes.
    training({ trainingId: 4, occurrenceDate: '2026-11-03', startAt: '2026-11-04T17:30:00Z', endAt: '2026-11-04T19:00:00Z', localDate: '2026-11-04' }),
    training({ trainingId: 5, scheduleId: 2, occurrenceDate: '2026-10-22', startAt: '2026-10-22T16:30:00Z', endAt: '2026-10-22T18:00:00Z', localDate: '2026-10-22' }),
  ];

  it('previews the sessions a disabled rule cancels, then saves and refreshes the calendar', async () => {
    let saved = false;
    const fetch = mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/recurring-schedules': () => json(200, { items: [saved ? { ...rule, enabled: false, version: 1 } : rule] }),
      'GET /api/v1/trainings': () => json(200, { items: sessions }),
      'PATCH /api/v1/recurring-schedules/1': () => {
        saved = true;
        return json(200, { ...rule, enabled: false, version: 1 });
      },
    });
    renderApp('/treninzi/raspored');
    const user = userEvent.setup();

    expect(await screen.findByText(/Izmjena pravila mijenja buduće termine/)).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: /Uredi pravilo Utorak/ }));
    await user.click(screen.getByRole('checkbox', { name: 'Pravilo je aktivno' }));
    await user.click(screen.getByRole('button', { name: 'Pregledaj promjenu' }));

    const preview = await screen.findByText('Bit će otkazano (2):');
    const list = preview.parentElement!;
    expect(within(list).getByText(/20\. listopada 2026/)).toBeInTheDocument();
    expect(within(list).getByText(/27\. listopada 2026/)).toBeInTheDocument();
    expect(within(list).queryByText(/studenoga/)).not.toBeInTheDocument();
    expect(callsTo(fetch, 'PATCH', '/recurring-schedules/1')).toHaveLength(0);

    const trainingLoads = callsTo(fetch, 'GET', '/trainings').length;
    await user.click(screen.getByRole('button', { name: 'Potvrdi izmjenu pravila' }));
    expect(await screen.findByText('Tjedno pravilo je spremljeno; kalendar je osvježen.')).toBeInTheDocument();
    expect(body(callsTo(fetch, 'PATCH', '/recurring-schedules/1')[0])).toEqual({ expectedVersion: 0, enabled: false });
    expect(await screen.findByText('Isključeno')).toBeInTheDocument();
    await waitFor(() => expect(callsTo(fetch, 'GET', '/trainings').length).toBeGreaterThan(trainingLoads));
  });

  it('previews a time change and rejects invalid times', async () => {
    mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/recurring-schedules': () => json(200, { items: [rule] }),
      'GET /api/v1/trainings': () => json(200, { items: sessions }),
    });
    renderApp('/treninzi/raspored');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: /Uredi pravilo Utorak/ }));
    await user.clear(screen.getByLabelText(/^Završetak/));
    await user.type(screen.getByLabelText(/^Završetak/), '18:00');
    await user.click(screen.getByRole('button', { name: 'Pregledaj promjenu' }));
    expect(await screen.findByText('Završetak mora biti nakon početka.')).toBeInTheDocument();

    await user.clear(screen.getByLabelText(/^Završetak/));
    await user.type(screen.getByLabelText(/^Završetak/), '20:30');
    await user.click(screen.getByRole('button', { name: 'Pregledaj promjenu' }));
    expect(await screen.findByText('Novo vrijeme 18:30–20:30 dobiva 2 termina:')).toBeInTheDocument();
  });

  it('explains a rule overlap from the server', async () => {
    mockApi({
      'GET /api/v1/me': trainer,
      'GET /api/v1/recurring-schedules': () => json(200, { items: [rule] }),
      'POST /api/v1/recurring-schedules': () => problem(409, 'TRAINING_OVERLAP'),
    });
    renderApp('/treninzi/raspored');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Novo pravilo' }));
    await user.click(screen.getByRole('button', { name: 'Spremi pravilo' }));
    expect(await screen.findByText('Preklapa se s drugim pravilom ili treningom.')).toBeInTheDocument();
    expect(screen.queryByText(/Novo pravilo je spremljeno/)).not.toBeInTheDocument();
  });
});
