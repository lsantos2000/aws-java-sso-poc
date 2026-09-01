import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from './main';

describe('SSO application', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ authenticated: false, mode: 'mock' }),
    }));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('shows the signed-out simulator state', async () => {
    render(<App />);

    expect(await screen.findByText('Local simulator')).toBeInTheDocument();
    expect(screen.getByText('Awaiting sign-in')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Sign in as demo user/ })).toBeInTheDocument();
  });

  it('shows the authenticated identity returned by the API', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce({ ok: true, json: async () => ({ authenticated: true, mode: 'mock' }) } as Response)
      .mockResolvedValueOnce({ ok: true, json: async () => ({ name: 'Demo User', email: 'demo@example.com', subject: 'mock-user-001' }) } as Response);

    render(<App />);

    expect(await screen.findByText('Demo User')).toBeInTheDocument();
    expect(screen.getByText('demo@example.com')).toBeInTheDocument();
    expect(screen.getByText('Authenticated')).toBeInTheDocument();
  });

  it('clears the authenticated identity after logout', async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce({ ok: true, json: async () => ({ authenticated: true, mode: 'mock' }) } as Response)
      .mockResolvedValueOnce({ ok: true, json: async () => ({ name: 'Demo User', email: 'demo@example.com', subject: 'mock-user-001' }) } as Response)
      .mockResolvedValueOnce({ ok: true, json: async () => ({}) } as Response);

    render(<App />);
    expect(await screen.findByText('Demo User')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Sign out/ }));

    await waitFor(() => expect(screen.getByText('Awaiting sign-in')).toBeInTheDocument());
    expect(screen.queryByText('Demo User')).not.toBeInTheDocument();
    expect(fetch).toHaveBeenLastCalledWith('/api/auth/logout', { method: 'POST', credentials: 'include' });
  });
});