import { StrictMode, useCallback, useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';

type AuthStatus = { authenticated: boolean; mode: 'mock' | 'cognito' };
type User = { name: string; email: string; subject: string };

type TraceKind = 'request' | 'ok' | 'fail' | 'note';
type Trace = { id: number; time: string; kind: TraceKind; label: string; detail?: string };
type BackendLine = { time: string; level: string; logger: string; message: string };
type StreamState = 'connecting' | 'live' | 'unavailable';

type Recorder = (kind: TraceKind, label: string, detail?: string) => void;

const clock = () => new Date().toTimeString().slice(0, 8);

async function api<T>(path: string, trace: Recorder): Promise<T> {
  trace('request', `GET ${path}`);

  let response: Response;
  try {
    response = await fetch(path, { credentials: 'include' });
  } catch {
    const message = 'Cannot reach the backend on port 8080. Start it, then reload this page.';
    trace('fail', `GET ${path}`, message);
    throw new Error(message);
  }

  if (!response.ok) {
    // The backend reports auth failures as JSON with a message; prefer it over a bare status.
    const reported = await response.json().then((body: { message?: string }) => body.message).catch(() => undefined);
    const message = reported ?? `Request to ${path} failed with ${response.status} ${response.statusText}.`;
    trace('fail', `${response.status} ${path}`, message);
    throw new Error(message);
  }

  const body = (await response.json()) as T;
  trace('ok', `${response.status} ${path}`, summarize(body));
  return body;
}

function summarize(body: unknown): string {
  if (body === null || typeof body !== 'object') return String(body);
  return Object.entries(body as Record<string, unknown>)
    .filter(([key]) => key !== 'claims')
    .map(([key, value]) => `${key}: ${typeof value === 'object' ? '{…}' : String(value)}`)
    .join('  ·  ');
}

export function App() {
  const [status, setStatus] = useState<AuthStatus | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [error, setError] = useState('');

  const [tab, setTab] = useState<'session' | 'backend'>('session');
  const [traces, setTraces] = useState<Trace[]>([]);
  const [backendLines, setBackendLines] = useState<BackendLine[]>([]);
  const [streamState, setStreamState] = useState<StreamState>('connecting');

  const nextTraceId = useRef(0);
  const started = useRef(false);
  const logBody = useRef<HTMLDivElement>(null);

  const trace = useCallback<Recorder>((kind, label, detail) => {
    const entry: Trace = { id: nextTraceId.current++, time: clock(), kind, label, detail };
    setTraces((entries) => [...entries, entry].slice(-120));
  }, []);

  useEffect(() => {
    // StrictMode remounts in development; without this the opening handshake logs twice.
    if (started.current) return;
    started.current = true;

    trace('note', 'Console ready', 'Watching the sign-in handshake.');
    api<AuthStatus>('/api/auth/status', trace).then((nextStatus) => {
      setStatus(nextStatus);
      if (nextStatus.authenticated) return api<User>('/api/me', trace).then(setUser);
      return undefined;
    }).catch((cause: Error) => setError(cause.message));
  }, [trace]);

  useEffect(() => {
    // jsdom and older browsers have no EventSource; the console degrades to the session tab.
    if (typeof EventSource === 'undefined') {
      setStreamState('unavailable');
      return;
    }
    const source = new EventSource('/api/logs/stream');
    source.addEventListener('open', () => setStreamState('live'));
    source.addEventListener('log', (event) => {
      const line = JSON.parse((event as MessageEvent<string>).data) as BackendLine;
      setBackendLines((lines) => [...lines, line].slice(-200));
    });
    source.addEventListener('error', () => setStreamState('unavailable'));
    return () => source.close();
  }, []);

  const activeLineCount = tab === 'session' ? traces.length : backendLines.length;
  useEffect(() => {
    const body = logBody.current;
    if (body) body.scrollTop = body.scrollHeight;
  }, [activeLineCount, tab]);

  // Until /api/auth/status answers we do not know which provider is configured, so there is no
  // sign-in route to offer. Guessing Cognito here sends mock-profile users to an endpoint that
  // does not exist on the backend.
  const signIn = async () => {
    if (!status || status.authenticated) return;
    if (status.mode === 'mock') {
      trace('request', 'POST /api/auth/mock-login');
      await fetch('/api/auth/mock-login', { method: 'POST', credentials: 'include' });
      window.location.reload();
      return;
    }
    trace('note', 'Redirecting to the Cognito hosted UI', '/oauth2/authorization/cognito');
    window.location.href = '/oauth2/authorization/cognito';
  };

  const signOut = async () => {
    trace('request', 'POST /api/auth/logout');
    // Clearing state regardless of the result would show "signed out" while the session is still
    // live on the server — the most misleading direction for this particular failure to point.
    try {
      const response = await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' });
      if (!response.ok) throw new Error(`Logout failed with ${response.status} ${response.statusText}.`);
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : 'Logout failed.';
      trace('fail', 'POST /api/auth/logout', `${message} You may still be signed in.`);
      setError(message);
      return;
    }
    trace('ok', 'Session invalidated', 'The cookie no longer grants access to /api/me.');
    setUser(null);
    setError('');
    setStatus({ authenticated: false, mode: status?.mode ?? 'mock' });
  };

  const signInLabel = status === null
    ? 'Connecting…'
    : status.mode === 'mock' ? 'Sign in as demo user' : 'Continue with AWS SSO';

  const streamLabel = streamState === 'live'
    ? 'streaming'
    : streamState === 'connecting' ? 'connecting' : 'unavailable';

  return <main className="shell">
    <nav className="nav"><span className="mark">AWS / JAVA</span><span className="nav-note">SSO LAB · POC 01</span></nav>
    <section className="hero">
      <div className="eyebrow"><span className="signal" /> OIDC handshake monitor</div>
      <div className="hero-grid">
        <h1>One identity.<br /><em>Every service.</em></h1>
        <div className="hero-aside">
          <p className="intro">A small proving ground for AWS Cognito SSO, Spring Security, and a session-backed Java API.</p>
          <div className="actions">
            <a className="secondary" href="http://localhost:8080/actuator/health" target="_blank" rel="noreferrer">API health <span>↗</span></a>
          </div>
        </div>
      </div>
    </section>

    {user && <section className="identity"><div><span className="label">CURRENT IDENTITY</span><h2>{user.name}</h2><p>{user.email}</p></div><code>{user.subject}</code></section>}

    <section className="console">
      <div className="console-head">
        <span className="label">CONSOLE</span>
        <div className="console-actions">
          <button
            type="button"
            className="console-btn go"
            onClick={status?.authenticated ? signOut : signIn}
            disabled={status === null}
          >{status?.authenticated ? 'Sign out' : signInLabel}</button>
        </div>
      </div>

      <div className="term">
        <div className="term-bar">
          <div className="term-tabs" role="tablist" aria-label="Console output">
            <button type="button" role="tab" aria-selected={tab === 'session'} className={`${tab === 'session' ? 'on' : ''}${error ? ' flag' : ''}`} onClick={() => setTab('session')}>SESSION</button>
            <button type="button" role="tab" aria-selected={tab === 'backend'} className={tab === 'backend' ? 'on' : ''} onClick={() => setTab('backend')}>BACKEND</button>
          </div>
          <span className={`term-state ${streamState}`}>{tab === 'backend' ? streamLabel : `${traces.length} events`}</span>
        </div>

        <div className="term-body" ref={logBody} role="log" aria-live="polite">
          {tab === 'session' && traces.map((entry) => <div key={entry.id} className={`line ${entry.kind}`} role={entry.kind === 'fail' ? 'alert' : undefined}>
            <span className="ts">{entry.time}</span>
            <span className="msg">{entry.label}{entry.detail && <span className="det">{entry.detail}</span>}</span>
          </div>)}

          {tab === 'backend' && backendLines.map((line, index) => <div key={index} className={`line lvl-${line.level.toLowerCase()}`}>
            <span className="ts">{line.time}</span>
            <span className="msg"><span className="lvl">{line.level}</span> <span className="src">{line.logger}</span><span className="det">{line.message}</span></span>
          </div>)}

          {tab === 'backend' && backendLines.length === 0 && <div className="line note">
            <span className="ts">{clock()}</span>
            <span className="msg">{streamState === 'unavailable'
              ? 'Backend log stream unavailable. It runs under the mock profile only.'
              : 'Waiting for the backend log stream…'}</span>
          </div>}

          <div className="caret" aria-hidden="true" />
        </div>
      </div>
    </section>

    <section className="status-grid">
      <article><span className="label">PROVIDER</span><strong>{status === null ? 'Not connected' : status.mode === 'mock' ? 'Local simulator' : 'AWS Cognito'}</strong><span className="detail">{status === null ? 'Waiting for the backend' : status.mode === 'mock' ? 'Demo identity / session' : 'OIDC / hosted UI'}</span></article>
      <article><span className="label">JAVA LAYER</span><strong>Spring Security</strong><span className="detail">OAuth 2.0 client</span></article>
      <article><span className="label">SESSION</span><strong className={status?.authenticated ? 'online' : ''}>{status?.authenticated ? 'Authenticated' : 'Awaiting sign-in'}</strong><span className="detail">HTTP-only session cookie</span></article>
    </section>

    <footer>
      <span>LOCAL DEVELOPMENT</span>
      <span>Spring Boot · Vite · Cognito</span>
      <span>© 2026 Leonardo Santos-Macias · <a href="https://github.com/lsantos2000" target="_blank" rel="noreferrer">github.com/lsantos2000</a></span>
    </footer>
  </main>;
}

const rootElement = document.getElementById('root');
if (rootElement) {
  createRoot(rootElement).render(<StrictMode><App /></StrictMode>);
}
