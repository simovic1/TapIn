import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { json, makeUser, mockApi, renderApp } from '../../test/utils';
import type { BeltHistory, BeltPeriod } from './api';

const NOW = new Date('2026-10-16T10:00:00Z');

function period(overrides: Partial<BeltPeriod>): BeltPeriod {
  return {
    beltPeriodId: 1,
    belt: 'WHITE',
    awardedOn: null,
    effectiveFromDate: '2024-03-01',
    effectiveToDate: null,
    trainingsCount: 0,
    awardedByUserId: null,
    note: null,
    createdByUserId: 9,
    createdAt: '2026-01-01T10:00:00Z',
    updatedAt: '2026-01-01T10:00:00Z',
    version: 0,
    ...overrides,
  };
}

const white = period({ trainingsCount: 40 });
const history: BeltHistory = {
  membershipId: 5,
  currentBelt: 'WHITE',
  currentBeltAwardedOn: null,
  currentBeltTrainings: 40,
  totalTrainings: 43,
  unassignedTrainings: 3,
  periods: [white],
};
const blue = period({ beltPeriodId: 2, belt: 'BLUE', awardedOn: '2026-10-15', effectiveFromDate: '2026-10-16', trainingsCount: 0 });
const promoted: BeltHistory = {
  ...history,
  currentBelt: 'BLUE',
  currentBeltAwardedOn: '2026-10-15',
  currentBeltTrainings: 0,
  periods: [{ ...white, effectiveToDate: '2026-10-16' }, blue],
};
const member = { membershipId: 5, firstName: 'Ana', lastName: 'Horvat', email: 'ana@klub.hr' };
const staff = () => json(200, makeUser(['TRAINER']));
const body = (fetch: ReturnType<typeof mockApi>, method: string) =>
  JSON.parse(String(fetch.mock.calls.find(([url, init]) => String(url).includes('/belts') && init?.method === method)![1]!.body));

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
});
afterEach(() => vi.useRealTimers());

describe('own profile', () => {
  it('explains a missing belt differently from unknown dates and unassigned trainings', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/me/belts': () =>
        json(200, { ...history, currentBelt: 'UNKNOWN', currentBeltTrainings: 0, periods: [], unassignedTrainings: 7, totalTrainings: 7 }),
    });
    renderApp('/profil');
    expect(await screen.findByText('Još nije evidentiran')).toBeInTheDocument();
    expect(screen.getByText(/Trener još nije unio vaš pojas/)).toBeInTheDocument();
    expect(screen.getByText(/Neraspoređeni dolasci \(još bez pojasa\)/)).toHaveTextContent('7');
    expect(screen.queryByText('datum nije evidentiran')).not.toBeInTheDocument();
  });
});

describe('belt management', () => {
  it('shows the history with unknown dates, counts and unassigned trainings', async () => {
    mockApi({
      'GET /api/v1/me': staff,
      'GET /api/v1/members/5': () => json(200, member),
      'GET /api/v1/members/5/belts': () => json(200, history),
      'GET /api/v1/members/5/belts/audit': () => json(200, { items: [], page: { page: 0, size: 50, totalElements: 0, totalPages: 0 } }),
    });
    renderApp('/clanovi/5/pojasevi');

    expect(await screen.findByRole('heading', { name: 'Pojasevi: Ana Horvat' })).toBeInTheDocument();
    const list = screen.getByRole('list', { name: 'Povijest pojaseva' });
    expect(within(list).getByText('datum nije evidentiran')).toBeInTheDocument();
    expect(within(list).getByText(/od 1\. ožujka 2024\./)).toBeInTheDocument();
    expect(screen.getByText(/Neraspoređeni po pojasu/)).toHaveTextContent('3');
    expect(screen.getByText('Ukupno treninga').nextElementSibling).toHaveTextContent('43');
  });

  it('records a promotion after training: counting starts the next day, the award date stays real', async () => {
    let current = history;
    const fetch = mockApi({
      'GET /api/v1/me': staff,
      'GET /api/v1/members/5': () => json(200, member),
      'GET /api/v1/members/5/belts': () => json(200, current),
      'GET /api/v1/members/5/belts/audit': () => json(200, { items: [], page: { page: 0, size: 50, totalElements: 0, totalPages: 0 } }),
      'POST /api/v1/members/5/belts': () => {
        current = promoted;
        return json(201, { beltPeriod: blue, history: promoted });
      },
    });
    renderApp('/clanovi/5/pojasevi');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Nova promocija' }));

    const form = screen.getByRole('form', { name: 'Nova promocija' });
    await user.selectOptions(within(form).getByLabelText('Pojas'), 'BLUE');
    await user.clear(within(form).getByLabelText(/stvarni datum polaganja/));
    await user.type(within(form).getByLabelText(/stvarni datum polaganja/), '2026-10-15');
    expect(within(form).getByText(/Početak brojanja treninga/)).toHaveTextContent('16. listopada 2026.');
    await user.click(within(form).getByRole('radio', { name: /Prije treninga/ }));
    expect(within(form).getByText(/Početak brojanja treninga/)).toHaveTextContent('15. listopada 2026.');
    await user.click(within(form).getByRole('radio', { name: /Nakon treninga/ }));
    await user.click(within(form).getByRole('button', { name: 'Spremi pojas' }));

    expect(await screen.findByText('Plavi pojas je spremljen.')).toBeInTheDocument();
    expect(body(fetch, 'POST')).toEqual({ belt: 'BLUE', awardedOn: '2026-10-15', promotionTiming: 'AFTER_TRAINING', note: null });
    expect(screen.getByText('Trenutačni pojas').nextElementSibling).toHaveTextContent('Plavi');
  });

  it('enters an older belt without inventing its award date', async () => {
    const fetch = mockApi({
      'GET /api/v1/me': staff,
      'GET /api/v1/members/5': () => json(200, member),
      'GET /api/v1/members/5/belts': () => json(200, { ...history, currentBelt: 'UNKNOWN', currentBeltTrainings: 0, periods: [], unassignedTrainings: 43 }),
      'GET /api/v1/members/5/belts/audit': () => json(200, { items: [], page: { page: 0, size: 50, totalElements: 0, totalPages: 0 } }),
      'POST /api/v1/members/5/belts': () => json(201, { beltPeriod: white, history }),
    });
    renderApp('/clanovi/5/pojasevi');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Početni unos pojasa' }));
    const form = screen.getByRole('form', { name: 'Početni unos pojasa' });
    await user.click(within(form).getByRole('checkbox', { name: /Datum dodjele nije poznat/ }));
    await user.click(within(form).getByRole('button', { name: 'Spremi pojas' }));
    expect(await within(form).findByText('Unesite od kada se treninzi broje u ovaj pojas.')).toBeInTheDocument();

    await user.type(within(form).getByLabelText(/Treninzi se broje od/), '2024-03-01');
    await user.click(within(form).getByRole('button', { name: 'Spremi pojas' }));
    expect(await screen.findByText('Bijeli pojas je spremljen.')).toBeInTheDocument();
    expect(body(fetch, 'POST')).toMatchObject({ belt: 'WHITE', awardedOn: null, effectiveFromDate: '2024-03-01' });
  });

  it('asks for a reason and decides again on fresh data after a 409', async () => {
    const theirs = { ...blue, awardedOn: '2026-10-14', effectiveFromDate: '2026-10-15', version: 3 };
    let calls = 0;
    const fetch = mockApi({
      'GET /api/v1/me': staff,
      'GET /api/v1/members/5': () => json(200, member),
      'GET /api/v1/members/5/belts': () => json(200, promoted),
      'GET /api/v1/members/5/belts/audit': () => json(200, { items: [], page: { page: 0, size: 50, totalElements: 0, totalPages: 0 } }),
      'PATCH /api/v1/members/5/belts/2': () =>
        ++calls === 1
          ? json(409, { type: 'urn:tapin:error:version-conflict', title: 'Conflict', status: 409, detail: 'x', instance: '/x',
              errorCode: 'VERSION_CONFLICT', currentState: theirs })
          : json(200, { beltPeriod: { ...theirs, version: 4 }, history: promoted }),
    });
    renderApp('/clanovi/5/pojasevi');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Ispravi Plavi pojas' }));
    const form = () => screen.getByRole('form', { name: 'Ispravak: Plavi pojas' });
    await user.click(within(form()).getByRole('button', { name: 'Spremi ispravak' }));
    expect(within(form()).getByText(/Upišite razlog ispravka/)).toBeInTheDocument();

    await user.type(within(form()).getByLabelText(/Razlog ispravka/), 'Krivi datum');
    await user.click(within(form()).getByRole('button', { name: 'Spremi ispravak' }));
    expect(await screen.findByText(/u međuvremenu izmijenio netko drugi/)).toBeInTheDocument();
    // The form now shows the other trainer's value and needs a new decision (and a new reason).
    expect(within(form()).getByLabelText(/stvarni datum polaganja/)).toHaveValue('2026-10-14');
    expect(screen.queryByText(/Ispravak je spremljen/)).not.toBeInTheDocument();

    await user.type(within(form()).getByLabelText(/Razlog ispravka/), 'Potvrđeno');
    await user.click(within(form()).getByRole('button', { name: 'Spremi ispravak' }));
    expect(await screen.findByText(/Ispravak je spremljen/)).toBeInTheDocument();
    const patches = fetch.mock.calls.filter(([, init]) => init?.method === 'PATCH');
    expect(JSON.parse(String(patches[1]![1]!.body))).toMatchObject({ expectedVersion: 3, awardedOn: '2026-10-14', reason: 'Potvrđeno' });
  });

  it('shows the own history read-only on the profile and closes management to members', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/me/belts': () => json(200, promoted),
    });
    const { router } = renderApp('/profil');
    const table = await screen.findByRole('table', { name: 'Povijest pojaseva' });
    expect(screen.getByText('Trenutačni pojas').nextElementSibling).toHaveTextContent('Plavi');
    expect(screen.getByText('Ukupno treninga').nextElementSibling).toHaveTextContent('43');
    expect(screen.getByText('Treninzi u ovom pojasu').nextElementSibling).toHaveTextContent('0');
    // Earlier belt with its own count, unknown award date, and unassigned trainings: three distinct labels.
    const rows = within(table).getAllByRole('row');
    expect(rows[2]).toHaveTextContent('Bijeli');
    expect(rows[2]).toHaveTextContent('datum nije evidentiran');
    expect(rows[2]).toHaveTextContent('40');
    expect(within(table).getByText('Neraspoređeni dolasci').closest('tr')).toHaveTextContent('3');
    expect(screen.queryByText(/crtic/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Ispravi/ })).not.toBeInTheDocument();

    expect(screen.queryByRole('button', { name: /pojas/i })).not.toBeInTheDocument();
    await router.navigate('/clanovi/5/pojasevi');
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Nemate pristup' })).toBeInTheDocument());
  });
});
