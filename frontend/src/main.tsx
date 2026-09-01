import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';

type AuthStatus = { authenticated: boolean; mode: 'mock' | 'cognito' };
type User = { name: string; email: string; subject: string };

async function api<T>(path: string): Promise<T> {
  const response = await fetch(path, { credentials: 'include' });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
  return response.json() as Promise<T>;
}

function App() {
  const [status, setStatus] = useState<AuthStatus | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    api<AuthStatus>('/api/auth/status').then((nextStatus) => {
      setStatus(nextStatus);
      if (nextStatus.authenticated) return api<User>('/api/me').then(setUser);
      return undefined;
    }).catch(() => setError('Start the Spring Boot backend on port 8080 to connect.'));
  }, []);

  return <main className="shell">
    <nav className="nav"><span className="mark">AWS / JAVA</span><span className="nav-note">SSO LAB · POC 01</span></nav>
    <section className="hero">
      <div className="eyebrow"><span className="signal" /> OIDC handshake monitor</div>
      <h1>One identity.<br /><em>Every service.</em></h1>
      <p className="intro">A small proving ground for AWS Cognito SSO, Spring Security, and a session-backed Java API.</p>
      <div className="actions">
        {status?.authenticated ? <button className="primary" onClick={() => { window.location.href = '/logout'; }}>Sign out <span>↗</span></button> : <button className="primary" onClick={async () => { if (status?.mode === 'mock') { await fetch('/api/auth/mock-login', { method: 'POST', credentials: 'include' }); window.location.reload(); } else { window.location.href = '/oauth2/authorization/cognito'; } }}>{status?.mode === 'mock' ? 'Sign in as demo user' : 'Continue with AWS SSO'} <span>↗</span></button>}
        <a className="secondary" href="http://localhost:8080/actuator/health" target="_blank" rel="noreferrer">API health <span>↗</span></a>
      </div>
      {error && <p className="error">{error}</p>}
    </section>
    <section className="status-grid">
      <article><span className="label">PROVIDER</span><strong>{status?.mode === 'mock' ? 'Local simulator' : 'AWS Cognito'}</strong><span className="detail">{status?.mode === 'mock' ? 'Demo identity / session' : 'OIDC / hosted UI'}</span></article>
      <article><span className="label">JAVA LAYER</span><strong>Spring Security</strong><span className="detail">OAuth 2.0 client</span></article>
      <article><span className="label">SESSION</span><strong className={status?.authenticated ? 'online' : ''}>{status?.authenticated ? 'Authenticated' : 'Awaiting sign-in'}</strong><span className="detail">HTTP-only session cookie</span></article>
    </section>
    {user && <section className="identity"><div><span className="label">CURRENT IDENTITY</span><h2>{user.name}</h2><p>{user.email}</p></div><code>{user.subject}</code></section>}
    <footer><span>LOCAL DEVELOPMENT</span><span>Spring Boot · Vite · Cognito</span><span>© 2026 Leonardo Santos-Macias · github.com/lsantos2000</span></footer>
  </main>;
}

createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>);
