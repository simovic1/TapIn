import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { render } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { createQueryClient } from '../../app/queryClient';
import { json, makeUser, mockApi } from '../../test/utils';
import { NotificationBell } from './NotificationBell';

afterEach(() => vi.unstubAllGlobals());
function show() {
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><NotificationBell /></MemoryRouter></QueryClientProvider>);
}
describe('notification bell', () => {
  it('shows unread notifications and persists read state', async () => {
    let read = false;
    mockApi({
      'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])),
      'GET /api/v1/me/notifications': () => json(200, { unreadCount: read ? 0 : 1, items: [{ id: 12, title: 'Promjena termina', body: 'Trening je u 19:00.', url: '/treninzi/12', createdAt: '2026-10-17T10:00:00Z', read }] }),
      'PUT /api/v1/me/notifications/12/read': () => { read = true; return new Response(null, { status: 204 }); },
    });
    show();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Obavijesti, 1 nepročitanih' }));
    expect(screen.getByText('Trening je u 19:00.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Označi pročitano' }));
    expect(await screen.findByRole('button', { name: 'Obavijesti' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('region', { name: 'Obavijesti' })).not.toBeInTheDocument();
  });
  it('shows an empty inbox', async () => {
    mockApi({ 'GET /api/v1/me': () => json(200, makeUser(['MEMBER'])) });
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Obavijesti' }));
    expect(await screen.findByText('Još nema obavijesti.')).toBeInTheDocument();
  });
});
