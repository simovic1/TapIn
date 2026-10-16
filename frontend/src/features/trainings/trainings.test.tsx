import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clubDateTimeToIso, toClubParts } from '../../lib/format';
import { json, makeUser, mockApi, problem, renderApp } from '../../test/utils';
import { training } from './testFixtures';

/** Friday 16 Oct 2026, 12:00 in Zagreb. */
const NOW = new Date('2026-10-16T10:00:00Z');

const tuesday = training();
const thursday = training({
  trainingId: 2,
  occurrenceDate: '2026-10-22',
  startAt: '2026-10-22T16:30:00Z',
  endAt: '2026-10-22T18:00:00Z',
  localDate: '2026-10-22',
  coaches: [{ membershipId: 5, userId: 6, firstName: 'Marko', lastName: 'Trener' }],
});
// After the end of summer time (25 Oct): 17:30 UTC is still 18:30 in Zagreb.
const nextTuesday = training({
  trainingId: 3,
  occurrenceDate: '2026-10-27',
  startAt: '2026-10-27T17:30:00Z',
  endAt: '2026-10-27T19:00:00Z',
  localDate: '2026-10-27',
});

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => vi.useRealTimers());

describe('club time zone helpers', () => {
  it('converts wall-clock times in Zagreb to UTC on both sides of a DST change', () => {
    expect(clubDateTimeToIso('2026-10-20', '18:30')).toBe('2026-10-20T16:30:00.000Z');
    expect(clubDateTimeToIso('2026-10-27', '18:30')).toBe('2026-10-27T17:30:00.000Z');
    expect(clubDateTimeToIso('2027-03-30', '18:30')).toBe('2027-03-30T16:30:00.000Z');
    expect(clubDateTimeToIso('2026-02-31', '18:30')).toBeNull();
    expect(clubDateTimeToIso('2026-10-20', '8:30')).toBeNull();
    expect(toClubParts('2026-10-27T17:30:00Z')).toEqual({ date: '2026-10-27', time: '18:30' });
  });
});

describe('member home', () => {
  it('shows the next and the second next training in Zagreb time', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/trainings': () => json(200, { items: [tuesday, thursday, nextTuesday] }),
    });
    renderApp('/');

    const next = await screen.findByRole('region', { name: 'Sljedeći trening' });
    expect(within(next).getByText('18:30–20:00')).toBeInTheDocument();
    expect(within(next).getByText(/utorak, 20\. listopada 2026/i)).toBeInTheDocument();
    expect(within(next).getByText('Niste prijavljeni')).toBeInTheDocument();
    const second = screen.getByRole('region', { name: 'Nakon toga' });
    expect(within(second).getByText(/četvrtak, 22\. listopada 2026/i)).toBeInTheDocument();
    expect(within(second).getByText(/Marko Trener/)).toBeInTheDocument();
    expect(screen.queryByText(/27\. listopada/)).not.toBeInTheDocument();
  });

  it('marks a cancelled session and skips it when picking the next trainings', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/trainings': () =>
        json(200, { items: [{ ...tuesday, status: 'CANCELLED', cancellationReason: 'Blagdan' }, thursday, nextTuesday] }),
    });
    renderApp('/');

    const next = await screen.findByRole('region', { name: 'Sljedeći trening' });
    expect(within(next).getByText(/četvrtak, 22\. listopada/i)).toBeInTheDocument();
    const cancelled = screen.getByRole('region', { name: 'Otkazano' });
    expect(within(cancelled).getByText('Otkazan')).toBeInTheDocument();
    expect(within(cancelled).getByText(/20\. listopada 2026/)).toBeInTheDocument();
    // After the DST change the local start is still 18:30.
    expect(within(screen.getByRole('region', { name: 'Nakon toga' })).getByText('18:30–20:00')).toBeInTheDocument();
  });

  it('explains an empty schedule', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/trainings': () => json(200, { items: [] }),
    });
    renderApp('/');
    expect(await screen.findByText('Nema zakazanih treninga')).toBeInTheDocument();
  });

  it('shows a load error with a working retry', async () => {
    let calls = 0;
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/trainings': () => (++calls <= 2 ? problem(500, 'INTERNAL_ERROR') : json(200, { items: [tuesday] })),
    });
    renderApp('/');
    const user = userEvent.setup();
    expect(await screen.findByText('Podaci se nisu mogli učitati', {}, { timeout: 3000 })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Pokušaj ponovno' }));
    expect(await screen.findByRole('region', { name: 'Sljedeći trening' })).toBeInTheDocument();
  });
});

describe('calendar', () => {
  it('lists the month grouped by local day and marks cancelled sessions', async () => {
    const fetch = mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/trainings': () =>
        json(200, { items: [{ ...tuesday, status: 'CANCELLED' }, thursday, nextTuesday] }),
    });
    renderApp('/treninzi');

    expect(await screen.findByRole('heading', { name: /listopad 2026/i })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: /utorak, 20\. listopada 2026/i })).toBeInTheDocument();
    expect(screen.getAllByText('Otkazan')).toHaveLength(1);
    expect(screen.getAllByText('18:30–20:00')).toHaveLength(3);
    // October in Zagreb: [1 Oct 00:00 CEST, 1 Nov 00:00 CET).
    const url = String(fetch.mock.calls.find(([u]) => String(u).includes('/trainings?'))![0]);
    expect(decodeURIComponent(url)).toContain('from=2026-09-30T22:00:00.000Z&to=2026-10-31T23:00:00.000Z');
    // Members get no management actions and no registration counts.
    expect(screen.queryByRole('link', { name: 'Dodaj trening' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Prijavljeno/)).not.toBeInTheDocument();
  });

  it('moves between months and back', async () => {
    const fetch = mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/trainings': () => json(200, { items: [] }),
    });
    renderApp('/treninzi');
    const user = userEvent.setup();
    expect(await screen.findByText('Nema treninga u ovom mjesecu')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Prethodni mjesec' }));
    expect(await screen.findByRole('heading', { name: /rujan 2026/i })).toBeInTheDocument();
    await waitFor(() =>
      expect(fetch.mock.calls.some(([u]) => decodeURIComponent(String(u)).includes('from=2026-08-31T22:00:00.000Z'))).toBe(true),
    );
    await user.click(screen.getByRole('button', { name: 'Ovaj mjesec' }));
    expect(await screen.findByRole('heading', { name: /listopad 2026/i })).toBeInTheDocument();
  });

  it('shows staff the registration count and management links', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['TRAINER'])),
      'GET /api/v1/trainings': () => json(200, { items: [{ ...thursday, registeredCount: 7 }] }),
    });
    renderApp('/treninzi');
    expect(await screen.findByText('Prijavljeno: 7')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Dodaj trening' })).toHaveAttribute('href', '/treninzi/novi');
    expect(screen.getByRole('link', { name: 'Tjedni raspored' })).toHaveAttribute('href', '/treninzi/raspored');
  });
});

describe('training detail', () => {
  it('shows a cancelled training with its reason and no registrant data for members', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/trainings/1': () =>
        json(200, { ...tuesday, status: 'CANCELLED', cancellationReason: 'Dvorana zatvorena', coaches: thursday.coaches }),
    });
    renderApp('/treninzi/1');
    expect(await screen.findByRole('alert')).toHaveTextContent('Dvorana zatvorena');
    expect(screen.getByRole('heading', { level: 1, name: 'Trening' })).toBeInTheDocument();
    expect(screen.getByText('Marko Trener')).toBeInTheDocument();
    expect(screen.getByText('Niste prijavljeni')).toBeInTheDocument();
    expect(screen.queryByText('Prijavljeno')).not.toBeInTheDocument();
  });

  it('explains a missing training', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/trainings/9': () => problem(404, 'NOT_FOUND'),
    });
    renderApp('/treninzi/9');
    expect(await screen.findByText('Traženi podatak ne postoji.')).toBeInTheDocument();
  });
});
