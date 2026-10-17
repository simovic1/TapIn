import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json, makeUser, mockApi, problem, renderApp } from '../../test/utils';
import type { Member } from './api';

const member: Member = {
  membershipId: 42, userId: 52, firstName: 'Iva', lastName: 'Horvat', email: 'iva@example.com', phone: null,
  accountStatus: 'INVITED', membershipStatus: 'ACTIVE', roles: ['MEMBER'], joinedOn: '2024-01-01',
  currentBelt: 'UNKNOWN', currentBeltAwardedOn: null, lastPresentOn: null,
  createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z', version: 0,
  statusHistory: [{ oldStatus: null, newStatus: 'ACTIVE', effectiveAt: '2024-01-01T00:00:00Z', actorUserId: null }],
};
const page = (items = [member]) => ({ items, page: { page: 0, size: 20, totalElements: items.length, totalPages: 1 } });

describe('member management', () => {
  it('lets a trainer invite a MEMBER and find them in the directory', async () => {
    const fetch = mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['TRAINER'])),
      'GET /api/v1/members': () => json(200, page()),
      'POST /api/v1/members/invitations': () => json(201, { membershipId: 42, email: 'iva@example.com', notificationStatus: 'QUEUED' }),
    });
    renderApp('/clanovi');
    const user = userEvent.setup();
    expect(await screen.findByText('Pozivnica nije prihvaćena')).toBeInTheDocument();
    expect(screen.getByText('Aktivno članstvo')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Pozovi člana' }));
    const form = within(screen.getByRole('form', { name: 'Pozovi člana' }));
    expect(form.queryByRole('checkbox')).not.toBeInTheDocument();
    await user.type(form.getByLabelText(/^Ime/), 'Iva');
    await user.type(form.getByLabelText(/^Prezime/), 'Horvat');
    await user.type(form.getByLabelText(/^E-mail/), 'iva@example.com');
    await user.click(form.getByRole('button', { name: 'Pošalji pozivnicu' }));
    expect(await screen.findByText(/je stavljena u red za slanje/)).toBeInTheDocument();
    const invite = fetch.mock.calls.find(([url]) => String(url).endsWith('/members/invitations'));
    expect(JSON.parse(String(invite![1]!.body)).roles).toEqual(['MEMBER']);
    await user.type(screen.getByLabelText('Pretraga'), 'Iva');
    await user.click(screen.getByRole('button', { name: 'Pretraži' }));
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => String(url).includes('q=Iva'))).toBe(true));
  });

  it.each(['/clanovi', '/clanovi/42', '/administracija'])('denies a MEMBER the staff route %s', async path => {
    const fetch = mockApi({ 'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])) });
    renderApp(path);
    expect(await screen.findByRole('heading', { name: 'Nemate pristup' })).toBeInTheDocument();
    expect(fetch.mock.calls.every(([url]) => (String(url).endsWith('/me') || String(url).endsWith('/me/notifications')))).toBe(true);
    expect(screen.queryByRole('link', { name: 'Članovi' })).not.toBeInTheDocument();
  });

  it('preserves invite form values after a duplicate email error', async () => {
    mockApi({ 'GET /api/v1/me': () => json(200, makeUser(['ADMIN'])),
      'GET /api/v1/members': () => json(200, page()),
      'POST /api/v1/members/invitations': () => problem(409, 'DUPLICATE_RESOURCE'),
    });
    renderApp('/clanovi');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Pozovi člana' }));
    const form = within(screen.getByRole('form', { name: 'Pozovi člana' }));
    await user.type(form.getByLabelText(/^Ime/), 'Iva');
    await user.type(form.getByLabelText(/^Prezime/), 'Horvat');
    await user.type(form.getByLabelText(/^E-mail/), 'iva@example.com');
    await user.click(form.getByRole('button', { name: 'Pošalji pozivnicu' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(form.getByLabelText(/^Ime/)).toHaveValue('Iva');
    expect(form.getByLabelText(/^E-mail/)).toHaveValue('iva@example.com');
  });

  it('saves status and roles with the current version and keeps failed edits', async () => {
    let current = member;
    let fail = false;
    const fetch = mockApi({ 'GET /api/v1/me': () => json(200, makeUser(['ADMIN'])),
      'GET /api/v1/members/42': () => json(200, current),
      'PATCH /api/v1/members/42': (_, init) => {
        if (fail) return problem(409, 'VERSION_CONFLICT');
        current = { ...current, ...JSON.parse(String(init.body)), version: 1 };
        return json(200, current);
      },
      'PUT /api/v1/members/42/roles': (_, init) => {
        current = { ...current, ...JSON.parse(String(init.body)), version: 2 };
        return json(200, current);
      },
    });
    renderApp('/clanovi/42');
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText('Aktivnost članstva'), 'INACTIVE');
    await user.click(screen.getByRole('button', { name: 'Spremi podatke' }));
    expect(await screen.findByText('Neaktivno članstvo')).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Trener' }));
    await user.click(screen.getByRole('button', { name: 'Spremi uloge' }));
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => String(url).endsWith('/roles'))).toBe(true));
    const roles = fetch.mock.calls.find(([url]) => String(url).endsWith('/roles'))!;
    expect(JSON.parse(String(roles[1]!.body))).toEqual({ expectedVersion: 1, roles: ['MEMBER', 'TRAINER'] });
    fail = true;
    const name = screen.getByLabelText(/^Ime/, { selector: 'input' });
    await user.clear(name); await user.type(name, 'Nespremljeno');
    await user.click(screen.getByRole('button', { name: 'Spremi podatke' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Podatak je u međuvremenu izmijenjen');
    expect(name).toHaveValue('Nespremljeno');
  });

  it('shows trainer details without administrative forms and supports resending', async () => {
    mockApi({ 'GET /api/v1/me': () => json(200, makeUser(['TRAINER'])),
      'GET /api/v1/members/42': () => json(200, member),
      'POST /api/v1/members/42/invitations/resend': () => json(202, { notificationStatus: 'QUEUED' }),
    });
    renderApp('/clanovi/42');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Ponovno pošalji pozivnicu' }));
    expect(await screen.findByText(/Nova pozivnica je stavljena/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Spremi uloge' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Spremi podatke' })).not.toBeInTheDocument();
  });

  it('lets a member edit only their own phone', async () => {
    const own = makeUser(['MEMBER']);
    const fetch = mockApi({ 'GET /api/v1/me': () => json(200, own),
      'PATCH /api/v1/me/profile': () => json(200, { ...own, phone: '+385 91 1234567', version: 2 }),
    });
    renderApp('/profil');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('Telefon'), '+385 91 1234567');
    await user.click(screen.getByRole('button', { name: 'Spremi telefon' }));
    expect(await screen.findByText('Telefon je spremljen.')).toBeInTheDocument();
    const call = fetch.mock.calls.find(([url]) => String(url).endsWith('/me/profile'))!;
    expect(JSON.parse(String(call[1]!.body))).toEqual({ expectedVersion: own.version, phone: '+385 91 1234567' });
    // No role or status controls; the only switch is the own reminder preference.
    expect(screen.getAllByRole('checkbox').map(c => c.getAttribute('aria-label') ?? c.closest('label')?.textContent))
      .toEqual([expect.stringMatching(/Podsjetnik/)]);
  });
});
