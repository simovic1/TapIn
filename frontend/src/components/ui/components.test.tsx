import { useQuery } from '@tanstack/react-query';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../lib/api/client';
import { MoreMenu } from '../layout/MoreMenu';
import { Button } from './Button';
import { QueryState } from './QueryState';
import { TextField } from './TextField';

function withQuery(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe('TextField', () => {
  it('associates label, hint and error for assistive technology', () => {
    render(<TextField label="E-mail" hint="Službeni e-mail" error="Obavezno polje" required />);

    const input = screen.getByLabelText(/E-mail/);
    expect(input).toBeRequired();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Službeni e-mail Obavezno polje');
  });
});

describe('Button', () => {
  it('blocks repeated submits while loading and exposes aria-busy', async () => {
    const onClick = vi.fn();
    render(
      <Button loading onClick={onClick}>
        Spremi
      </Button>,
    );

    const button = screen.getByRole('button', { name: 'Spremi' });
    await userEvent.click(button);
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe('QueryState', () => {
  function List({ load }: { load: () => Promise<string[]> }) {
    const query = useQuery({ queryKey: ['list'], queryFn: load });
    return (
      <QueryState query={query} isEmpty={(items) => items.length === 0} empty={{ title: 'Nema treninga' }}>
        {(items) => <ul>{items.map((item) => <li key={item}>{item}</li>)}</ul>}
      </QueryState>
    );
  }

  it('shows loading, then data', async () => {
    render(withQuery(<List load={async () => ['Utorak']} />));
    expect(screen.getByRole('status')).toHaveTextContent('Učitavanje');
    expect(await screen.findByText('Utorak')).toBeInTheDocument();
  });

  it('shows the empty state for an empty result', async () => {
    render(withQuery(<List load={async () => []} />));
    expect(await screen.findByText('Nema treninga')).toBeInTheDocument();
  });

  it('shows the error with a retry that reloads', async () => {
    const load = vi
      .fn<() => Promise<string[]>>()
      .mockRejectedValueOnce(
        new ApiError({ type: 'x', title: 'x', status: 500, detail: 'x', instance: '/x', errorCode: 'INTERNAL_ERROR' }),
      )
      .mockResolvedValueOnce(['Četvrtak']);
    render(withQuery(<List load={load} />));

    expect(await screen.findByRole('alert')).toHaveTextContent('neočekivane pogreške');
    await userEvent.click(screen.getByRole('button', { name: /Pokušaj ponovno/ }));
    expect(await screen.findByText('Četvrtak')).toBeInTheDocument();
  });
});

describe('MoreMenu', () => {
  it('opens with the keyboard, focuses the first item and closes on Escape back to the button', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <MoreMenu
          items={[{ to: '/clanovi', label: 'Članovi', icon: 'users', roles: ['TRAINER'], primary: false }]}
          onLogout={() => {}}
          loggingOut={false}
        />
      </MemoryRouter>,
    );

    const toggle = screen.getByRole('button', { name: 'Više' });
    toggle.focus();
    await user.keyboard('{Enter}');
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('link', { name: 'Članovi' })).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle).toHaveFocus();
  });
});
