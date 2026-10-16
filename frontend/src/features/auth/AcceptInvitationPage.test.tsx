import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json, makeUser, mockApi, problem, renderApp } from '../../test/utils';

const TOKEN = 'tok_abcdefghijklmnopqrstuvwxyz0123456789ABCD';
const preview = {
  email: 'nova@klub.hr',
  firstName: 'Nova',
  lastName: 'Članica',
  clubName: 'BJJ klub',
  expiresAt: '2026-10-27T10:00:00Z',
};
const systemInfo = { application: 'TapIn', version: '1', clubName: 'BJJ klub', timeZone: 'Europe/Zagreb', serverTime: '2026-10-20T10:00:00Z' };

describe('AcceptInvitationPage', () => {
  it('reads the token from the URL fragment, activates the account and signs the user in', async () => {
    const user = userEvent.setup();
    document.cookie = 'XSRF-TOKEN=csrf-1; Path=/';
    const fetchMock = mockApi({
      'GET /api/v1/me': () => problem(401, 'UNAUTHENTICATED'),
      'POST /api/v1/auth/invitations/preview': () => json(200, preview),
      'POST /api/v1/auth/invitations/accept': () => json(200, makeUser(['MEMBER'], { firstName: 'Nova', email: 'nova@klub.hr' })),
      'GET /api/v1/system/info': () => json(200, systemInfo),
    });

    const { router } = renderApp(`/pozivnica#${TOKEN}`);

    expect(await screen.findByText('nova@klub.hr')).toBeInTheDocument();
    expect(screen.getByLabelText(/^Ime/)).toHaveValue('Nova');
    await user.type(screen.getByLabelText(/^Nova lozinka/), 'tajna-lozinka-1');
    await user.type(screen.getByLabelText(/^Ponovite lozinku/), 'tajna-lozinka-1');
    await user.click(screen.getByRole('button', { name: 'Aktiviraj račun' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
    expect(await screen.findByRole('heading', { name: 'Bok, Nova!' })).toBeInTheDocument();

    const previewCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/preview'))!;
    expect(JSON.parse(String(previewCall[1]!.body))).toEqual({ token: TOKEN });
    const acceptCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/accept'))!;
    expect(JSON.parse(String(acceptCall[1]!.body))).toMatchObject({ token: TOKEN, password: 'tajna-lozinka-1', firstName: 'Nova' });
    expect(new Headers(acceptCall[1]!.headers).get('X-XSRF-TOKEN')).toBe('csrf-1');
    // The token is never part of a request URL.
    expect(fetchMock.mock.calls.every(([url]) => !String(url).includes(TOKEN))).toBe(true);
  });

  it('checks password length and confirmation before submitting', async () => {
    const user = userEvent.setup();
    const fetchMock = mockApi({
      'GET /api/v1/me': () => problem(401, 'UNAUTHENTICATED'),
      'POST /api/v1/auth/invitations/preview': () => json(200, preview),
    });

    renderApp(`/pozivnica#${TOKEN}`);
    await user.type(await screen.findByLabelText(/^Nova lozinka/), 'kratka');
    await user.type(screen.getByLabelText(/^Ponovite lozinku/), 'drugacija');
    await user.click(screen.getByRole('button', { name: 'Aktiviraj račun' }));

    expect(screen.getByLabelText(/^Nova lozinka/)).toHaveAccessibleDescription(/Najmanje 10 znakova/);
    expect(screen.getByLabelText(/^Ponovite lozinku/)).toHaveAccessibleDescription('Lozinke se ne podudaraju.');
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/accept'))).toBe(false);
  });

  it('explains an expired or already used invitation', async () => {
    mockApi({
      'GET /api/v1/me': () => problem(401, 'UNAUTHENTICATED'),
      'POST /api/v1/auth/invitations/preview': () => problem(422, 'INVITATION_INVALID_OR_EXPIRED'),
    });

    renderApp(`/pozivnica#${TOKEN}`);

    expect(await screen.findByRole('alert')).toHaveTextContent('Pozivnica nije valjana ili je istekla');
    expect(screen.getByRole('link', { name: /Prijavite se/ })).toHaveAttribute('href', '/prijava');
  });

  it('handles a link without token', async () => {
    mockApi({ 'GET /api/v1/me': () => problem(401, 'UNAUTHENTICATED') });

    renderApp('/pozivnica');

    expect(await screen.findByRole('alert')).toHaveTextContent('nije potpuna');
  });
});
