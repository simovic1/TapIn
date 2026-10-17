import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PwaSupport } from './PwaSupport';

describe('PWA support', () => {
  it('offers the browser install prompt and consumes it only once', async () => {
    render(<PwaSupport />);
    const prompt = vi.fn().mockResolvedValue(undefined);
    const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
      prompt, userChoice: Promise.resolve({ outcome: 'accepted' }),
    });
    act(() => { window.dispatchEvent(event); });
    expect(event.defaultPrevented).toBe(true);
    await act(async () => { fireEvent.click(screen.getByText('Instaliraj TapIn')); });
    expect(prompt).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Instaliraj TapIn')).not.toBeInTheDocument();
  });

  it('announces loss of connection and clears it on reconnect', () => {
    render(<PwaSupport />);
    act(() => { window.dispatchEvent(new Event('offline')); });
    expect(screen.getByRole('status')).toHaveTextContent('Nema internetske veze');
    act(() => { window.dispatchEvent(new Event('online')); });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('explains installation on iOS and allows dismissing the instructions', () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone');
    render(<PwaSupport />);
    expect(screen.getByText(/Dodaj na početni zaslon/)).toBeInTheDocument();
    fireEvent.click(screen.getByText('Zatvori'));
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
  });

  it('does not offer installation in a standalone window', () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone');
    vi.stubGlobal('matchMedia', () => ({ matches: true }));
    render(<PwaSupport />);
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
  });
});
