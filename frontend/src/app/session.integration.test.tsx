import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { sessionExpiry } from '../lib/session/sessionExpiry';
import { json, makeUser, mockApi, problem, renderApp } from '../test/utils';

const systemInfo = {
  application: 'TapIn',
  version: '1.0',
  clubName: 'BJJ klub',
  timeZone: 'Europe/Zagreb',
  serverTime: '2026-10-20T14:00:00Z',
};

describe('session, navigation and guards', () => {
  it('redirects to the login page when nobody is signed in', async () => {
    mockApi({ 'GET /api/v1/me': () => problem(401, 'UNAUTHENTICATED') });

    const { router } = renderApp('/statistika');

    expect(await screen.findByRole('heading', { name: 'Prijava' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/prijava');
    expect(screen.queryByText('Sesija je istekla')).not.toBeInTheDocument();
  });

  it('logs in through the shared client and returns to the requested page', async () => {
    const user = userEvent.setup();
    document.cookie = 'XSRF-TOKEN=token-1; Path=/';
    const fetchMock = mockApi({
      'GET /api/v1/me': () => problem(401, 'UNAUTHENTICATED'),
      'POST /api/v1/auth/login': () => json(200, makeUser(['MEMBER'])),
    });

    const { router } = renderApp('/statistika');
    await user.type(await screen.findByLabelText(/E-mail/), 'ana.horvat@example.com');
    await user.type(screen.getByLabelText(/Lozinka/), 'tajna-lozinka');
    await user.keyboard('{Enter}');

    await waitFor(() => expect(router.state.location.pathname).toBe('/statistika'));
    const login = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/auth/login'))!;
    expect(new Headers(login[1]!.headers).get('X-XSRF-TOKEN')).toBe('token-1');
    expect(JSON.parse(String(login[1]!.body))).toEqual({ email: 'ana.horvat@example.com', password: 'tajna-lozinka' });
  });

  it('shows wrong credentials without leaving the login page', async () => {
    const user = userEvent.setup();
    mockApi({
      'GET /api/v1/me': () => problem(401, 'UNAUTHENTICATED'),
      'POST /api/v1/auth/login': () => problem(401, 'INVALID_CREDENTIALS'),
    });

    renderApp('/prijava');
    await user.type(await screen.findByLabelText(/E-mail/), 'a@b.hr');
    await user.type(screen.getByLabelText(/Lozinka/), 'kriva');
    await user.click(screen.getByRole('button', { name: 'Prijavi se' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Neispravan e-mail ili lozinka.');
    expect(screen.queryByText('Sesija je istekla')).not.toBeInTheDocument();
  });

  it('gives a user with several roles a single navigation matching all of them', async () => {
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER', 'TRAINER'])),
      'GET /api/v1/system/info': () => json(200, systemInfo),
    });

    renderApp('/');

    await screen.findByRole('heading', { name: 'Bok, Ana!' });
    const navs = screen.getAllByRole('navigation', { name: 'Glavna navigacija' });
    const desktop = within(navs[0]!);
    expect(desktop.getByRole('link', { name: 'Prozivka' })).toBeInTheDocument();
    expect(desktop.getByRole('link', { name: 'Statistika' })).toBeInTheDocument();
    expect(desktop.queryByRole('link', { name: 'Administracija' })).not.toBeInTheDocument();
    expect(desktop.getAllByRole('link', { name: 'Treninzi' })).toHaveLength(1);
    expect(desktop.getByRole('link', { name: 'Početna' })).toHaveAttribute('aria-current', 'page');
  });

  it('shows 403 for a route outside the user roles', async () => {
    mockApi({ 'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])) });

    renderApp('/administracija');

    expect(await screen.findByRole('heading', { name: 'Nemate pristup' })).toBeInTheDocument();
  });

  it('sends the user to the login page with a notice when the session expires', async () => {
    sessionExpiry.clear();
    let signedIn = true;
    mockApi({
      'GET /api/v1/me': () => (signedIn ? json(200, makeUser(['MEMBER'])) : problem(401, 'UNAUTHENTICATED')),
      'GET /api/v1/system/info': () => {
        signedIn = false; // the server-side session disappears
        return problem(401, 'UNAUTHENTICATED', '/api/v1/system/info');
      },
    });

    const { router } = renderApp('/');

    expect(await screen.findByText('Sesija je istekla')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/prijava');
    expect(router.state.location.state).toEqual({ from: '/' });
  });

  it('logs out through the API and returns to the login page', async () => {
    const user = userEvent.setup();
    const fetchMock = mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/system/info': () => json(200, systemInfo),
      'POST /api/v1/auth/logout': () => new Response(null, { status: 204 }),
    });

    const { router } = renderApp('/');
    await user.click(await screen.findByRole('button', { name: 'Odjava' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/prijava'));
    expect(fetchMock.mock.calls.some(([url, init]) => String(url).endsWith('/auth/logout') && init?.method === 'POST')).toBe(
      true,
    );
  });
});
