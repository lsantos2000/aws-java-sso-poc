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

  it('keeps the user signed in when logout fails', async () => {
    // Clearing state on a failed logout would show "signed out" while the server session is live.
    vi.mocked(fetch)
      .mockResolvedValueOnce({ ok: true, json: async () => ({ authenticated: true, mode: 'mock' }) } as Response)
      .mockResolvedValueOnce({ ok: true, json: async () => ({ name: 'Demo User', email: 'demo@example.com', subject: 'mock-user-001' }) } as Response)
      .mockResolvedValueOnce({ ok: false, status: 500, statusText: 'Internal Server Error' } as Response);

    render(<App />);
    expect(await screen.findByText('Demo User')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Sign out/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Logout failed with 500/);
    expect(screen.getByText('Demo User')).toBeInTheDocument();
    expect(screen.getByText('Authenticated')).toBeInTheDocument();
  });

  it('does not offer a sign-in route while the provider is unknown', async () => {
    // Regression: with the backend unreachable, status stayed null and the button fell through
    // to the Cognito branch, sending mock-profile users to /oauth2/authorization/cognito.
    vi.mocked(fetch).mockRejectedValue(new TypeError('Failed to fetch'));

    render(<App />);

    const button = await screen.findByRole('button', { name: /Connecting/ });
    expect(button).toBeDisabled();
    expect(screen.queryByRole('button', { name: /Continue with AWS SSO/ })).not.toBeInTheDocument();
    expect(screen.getByText('Not connected')).toBeInTheDocument();

    fireEvent.click(button);
    expect(fetch).not.toHaveBeenCalledWith('/api/auth/mock-login', expect.anything());
  });

  it('surfaces the message the backend returns on a failed request', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
      json: async () => ({ status: 401, error: 'Unauthorized', message: 'Cognito sign-in is not available.' }),
    } as Response);

    render(<App />);

    // The message also appears in the console log, so target the hero alert specifically.
    expect(await screen.findByRole('alert')).toHaveTextContent('Cognito sign-in is not available.');
  });

  it('reports an unreachable backend distinctly from an HTTP failure', async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError('Failed to fetch'));

    render(<App />);

    expect(await screen.findByRole('alert')).toHaveTextContent(/Cannot reach the backend on port 8080/);
  });
});