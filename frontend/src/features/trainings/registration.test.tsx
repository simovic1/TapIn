import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { json, makeUser, mockApi, problem, renderApp } from '../../test/utils';
import type { TrainingDetail } from './api';
import { training } from './testFixtures';

const NOW = new Date('2026-10-16T10:00:00Z');
const open = training({ canRegister: true });
const registered = training({ myRegistrationStatus: 'REGISTERED', canCancelRegistration: true });
const member = () => json(200, makeUser(['MEMBER']));

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => vi.useRealTimers());

const result = (t: TrainingDetail, changed = true) => ({
  trainingId: t.trainingId,
  membershipId: 123,
  registrationStatus: t.myRegistrationStatus === 'REGISTERED' ? 'REGISTERED' : 'CANCELLED',
  attendanceStatus: t.myAttendanceStatus,
  registeredAt: '2026-10-16T10:00:00Z',
  cancelledAt: null,
  changed,
  notificationStatus: changed ? 'QUEUED' : 'NOT_REQUIRED',
});

const registrationCalls = (fetch: ReturnType<typeof mockApi>, method: string) =>
  fetch.mock.calls.filter(([url, init]) => String(url).endsWith('/my-registration') && init?.method === method);

describe('registration from the home screen', () => {
  it('registers with one tap and refreshes the shown state', async () => {
    let current: TrainingDetail = open;
    const fetch = mockApi({
      'GET /api/v1/me': member,
      'GET /api/v1/trainings': () => json(200, { items: [current] }),
      'PUT /api/v1/trainings/1/my-registration': () => {
        current = registered;
        return json(200, result(registered));
      },
    });
    renderApp('/');
    const user = userEvent.setup();

    const next = await screen.findByRole('region', { name: 'Sljedeći trening' });
    expect(within(next).getByText('Niste prijavljeni')).toBeInTheDocument();
    await user.click(within(next).getByRole('button', { name: 'Prijavi se' }));

    expect(await within(next).findByText('Prijavljeni ste. Potvrda je u redu za slanje e-mailom.')).toBeInTheDocument();
    expect(within(next).getByText('Prijavljeni ste')).toBeInTheDocument();
    expect(within(next).getByRole('button', { name: 'Otkaži prijavu' })).toBeInTheDocument();
    expect(within(next).queryByRole('button', { name: 'Prijavi se' })).not.toBeInTheDocument();
    expect(registrationCalls(fetch, 'PUT')).toHaveLength(1);
  });

  it('cancels and re-registers', async () => {
    let current: TrainingDetail = registered;
    mockApi({
      'GET /api/v1/me': member,
      'GET /api/v1/trainings': () => json(200, { items: [current] }),
      'DELETE /api/v1/trainings/1/my-registration': () => {
        current = training({ myRegistrationStatus: 'CANCELLED', canRegister: true });
        return json(200, result(current));
      },
    });
    renderApp('/');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Otkaži prijavu' }));
    expect(await screen.findByText('Prijava je otkazana.')).toBeInTheDocument();
    expect(screen.getByText('Prijava otkazana')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Prijavi se' })).toBeInTheDocument();
  });

  it('shows a network failure without success and keeps the state', async () => {
    mockApi({
      'GET /api/v1/me': member,
      'GET /api/v1/trainings': () => json(200, { items: [open] }),
      'PUT /api/v1/trainings/1/my-registration': () => Promise.reject(new TypeError('offline')),
    });
    renderApp('/');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Prijavi se' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Promjena nije spremljena. Poslužitelj nije dostupan');
    expect(screen.queryByText(/Prijavljeni ste/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Prijavi se' })).toBeEnabled();
  });

  it('refreshes after a 409 and then offers no own change once attendance is recorded', async () => {
    let current: TrainingDetail = registered;
    const fetch = mockApi({
      'GET /api/v1/me': member,
      'GET /api/v1/trainings': () => json(200, { items: [current] }),
      'DELETE /api/v1/trainings/1/my-registration': () => {
        current = training({ myRegistrationStatus: 'REGISTERED', myAttendanceStatus: 'PRESENT' });
        return problem(409, 'ATTENDANCE_ALREADY_RECORDED');
      },
    });
    renderApp('/');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Otkaži prijavu' }));

    expect(await screen.findByText(/Prikaz je osvježen/)).toBeInTheDocument();
    expect(await screen.findByText('Dolazak evidentiran: prisutni')).toBeInTheDocument();
    expect(screen.getByText('Prijavljeni ste')).toBeInTheDocument();
    expect(screen.getByText('Dolazak je evidentiran; promjene radi trener.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Otkaži prijavu' })).not.toBeInTheDocument();
    await waitFor(() =>
      expect(fetch.mock.calls.filter(([url]) => String(url).includes('/trainings?')).length).toBeGreaterThan(1),
    );
  });

  it('keeps registration and actual attendance apart', async () => {
    mockApi({
      'GET /api/v1/me': member,
      'GET /api/v1/trainings/1': () =>
        json(200, training({ myRegistrationStatus: 'CANCELLED', myAttendanceStatus: 'PRESENT' })),
    });
    renderApp('/treninzi/1');
    const section = await screen.findByRole('region', { name: 'Vaša prijava' });
    expect(within(section).getByText('Prijava otkazana')).toBeInTheDocument();
    expect(within(section).getByText('Dolazak evidentiran: prisutni')).toBeInTheDocument();
    expect(within(section).queryByRole('button')).not.toBeInTheDocument();
  });

  it('offers no registration to staff without the member role', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['TRAINER'])),
      'GET /api/v1/trainings': () => json(200, { items: [{ ...open, registeredCount: 3 }] }),
    });
    renderApp('/');
    await screen.findByRole('region', { name: 'Sljedeći trening' });
    expect(screen.queryByRole('button', { name: 'Prijavi se' })).not.toBeInTheDocument();
    expect(screen.queryByText('Niste prijavljeni')).not.toBeInTheDocument();
  });
});
