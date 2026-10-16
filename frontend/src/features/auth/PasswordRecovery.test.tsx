import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { mockApi, problem, renderApp } from '../../test/utils';

const anonymous = () => problem(401, 'UNAUTHENTICATED');

describe('password recovery', () => {
  it('accepts an empty 202 and gives a neutral answer', async () => {
    const fetch = mockApi({
      'GET /api/v1/me': anonymous,
      'POST /api/v1/auth/password-reset/request': () => new Response(null, { status: 202 }),
    });
    renderApp('/zaboravljena-lozinka');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/E-mail/), 'unknown@example.com');
    await user.click(screen.getByRole('button', { name: 'Pošalji poveznicu' }));
    expect(await screen.findByText(/Ako postoji aktivan račun/)).toBeInTheDocument();
    expect(fetch.mock.calls.filter(([url]) => String(url).endsWith('/request'))).toHaveLength(1);
  });

  it('checks confirmation, uses the fragment token and offers login after success', async () => {
    const fetch = mockApi({
      'GET /api/v1/me': anonymous,
      'POST /api/v1/auth/password-reset/confirm': () => new Response(null, { status: 204 }),
    });
    const { queryClient } = renderApp('/nova-lozinka#secret-token');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/Nova lozinka/, { selector: 'input' }), 'new-password-123');
    await user.type(screen.getByLabelText(/Ponovite lozinku/), 'different-password');
    await user.click(screen.getByRole('button', { name: 'Spremi lozinku' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Lozinke se ne podudaraju');
    await user.clear(screen.getByLabelText(/Ponovite lozinku/));
    await user.type(screen.getByLabelText(/Ponovite lozinku/), 'new-password-123');
    await user.click(screen.getByRole('button', { name: 'Spremi lozinku' }));
    expect(await screen.findByText(/Lozinka je promijenjena/)).toBeInTheDocument();
    const call = fetch.mock.calls.find(([url]) => String(url).endsWith('/confirm'));
    expect(JSON.parse(call![1]!.body as string)).toEqual({ token: 'secret-token', newPassword: 'new-password-123' });
    expect(queryClient.getQueryData(['session', 'current-user'])).toBeNull();
  });

  it.each([
    [422, 'PASSWORD_RESET_TOKEN_INVALID', /Poveznica nije valjana/],
    [429, 'RATE_LIMITED', /Previše pokušaja/],
  ] as const)('explains confirmation error %s', async (status, code, message) => {
    mockApi({ 'GET /api/v1/me': anonymous,
      'POST /api/v1/auth/password-reset/confirm': () => problem(status, code),
    });
    renderApp('/nova-lozinka#token');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/Nova lozinka/, { selector: 'input' }), 'new-password-123');
    await user.type(screen.getByLabelText(/Ponovite lozinku/), 'new-password-123');
    await user.click(screen.getByRole('button', { name: 'Spremi lozinku' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(message);
  });

  it('blocks repeated submits while sending and explains a network failure', async () => {
    let fail!: (error: Error) => void;
    const fetch = mockApi({ 'GET /api/v1/me': anonymous,
      'POST /api/v1/auth/password-reset/request': () => new Promise<Response>((_, reject) => { fail = reject; }),
    });
    renderApp('/zaboravljena-lozinka');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/E-mail/), 'ana@example.com');
    const submit = screen.getByRole('button', { name: 'Pošalji poveznicu' });
    await user.dblClick(submit);
    expect(submit).toBeDisabled();
    expect(fetch.mock.calls.filter(([url]) => String(url).endsWith('/request'))).toHaveLength(1);
    fail(new TypeError('offline'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Poslužitelj nije dostupan');
    await waitFor(() => expect(submit).toBeEnabled());
  });

  it('explains a missing token without submitting', async () => {
    mockApi({ 'GET /api/v1/me': anonymous });
    renderApp('/nova-lozinka');
    expect(await screen.findByRole('alert')).toHaveTextContent('Poveznica nije potpuna');
    expect(screen.queryByRole('button', { name: 'Spremi lozinku' })).not.toBeInTheDocument();
  });
});
