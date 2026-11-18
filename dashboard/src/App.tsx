import { useEffect, useState } from 'react';
import type { DashboardConfig } from './types';
import { api, useAccessToken } from './api';
import { startSession, type Session } from './auth';
import { Icon } from './components/ui';
import { OverviewPage } from './pages/OverviewPage';
import { PaymentsPage } from './pages/PaymentsPage';
import { LinksPage } from './pages/LinksPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { ReconciliationPage } from './pages/ReconciliationPage';

const NAV = [
  { path: '/', label: 'Overview', icon: Icon.overview },
  { path: '/payments', label: 'Payments', icon: Icon.card },
  { path: '/links', label: 'Payment links', icon: Icon.link },
  { path: '/notifications', label: 'Webhooks', icon: Icon.bell },
  { path: '/reconciliation', label: 'Reconciliation', icon: Icon.scale },
];

function readRoute(): { path: string; query: URLSearchParams } {
  const [path, query = ''] = (window.location.hash.replace(/^#/, '') || '/').split('?');
  return { path: NAV.some((n) => n.path === path) ? path : '/', query: new URLSearchParams(query) };
}

export function App() {
  const [config, setConfig] = useState<DashboardConfig | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [route, setRoute] = useState(readRoute);
  const [live, setLive] = useState(true);
  const [theme, setTheme] = useState<'light' | 'dark' | null>(() => stored('theme') as 'light' | 'dark' | null);

  useEffect(() => {
    const onHash = () => setRoute(readRoute());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    if (theme) document.documentElement.dataset.theme = theme;
    else delete document.documentElement.dataset.theme;
    store('theme', theme);
  }, [theme]);

  useEffect(() => {
    api<DashboardConfig>('/api/v1/public/dashboard-config')
      .then(async (c) => {
        setConfig(c);
        if (c.auth === 'unavailable') return;
        const s = await startSession(c);
        if (s === 'redirecting') return;
        useAccessToken(s.token, () => setFailure('Your session has ended or these credentials are not allowed. Sign in again.'));
        setSession(s);
      })
      .catch(() => setFailure('The payment bridge is not answering.'));
  }, []);

  const go = (path: string) => { window.location.hash = path; };

  if (failure && !session) return <Splash title="Cannot open the dashboard" text={failure} />;
  if (config?.auth === 'unavailable') {
    return <Splash title="Sign-in is not configured" text="This bridge requires credentials for its API, and no identity provider is set for the dashboard. Set foliopay.dashboard.oidc.authority and foliopay.dashboard.oidc.client-id." />;
  }
  if (!config || !session) return <Splash title="Folio Pay" text="Connecting…" />;

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand"><span className="logo">F</span><div><b>Folio Pay</b><small>Operator dashboard</small></div></div>
        <nav>
          {NAV.map((n) => (
            <a key={n.path} href={`#${n.path}`} className={route.path === n.path ? 'active' : ''} aria-current={route.path === n.path ? 'page' : undefined}>
              {n.icon}<span>{n.label}</span>
            </a>
          ))}
        </nav>
        <div className="sidebar-foot">
          <a href="/" className="muted-link">Classic console</a>
          <a href="/swagger-ui/index.html" className="muted-link">API reference</a>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="crumbs">{NAV.find((n) => n.path === route.path)?.label}</div>
          <div className="top-actions">
            <span className={`env ${config.demo ? 'demo' : 'prod'}`} title={config.demo ? 'Synthetic data, demo controls on' : 'Demo controls off'}>{config.demo ? 'Demo · synthetic data' : 'Live'}</span>
            <button type="button" className={`live ${live ? 'on' : ''}`} onClick={() => setLive(!live)} aria-pressed={live} title="Refresh every 5 seconds">
              <span className="dot" />{live ? 'Live' : 'Paused'}
            </button>
            <button type="button" className="ghost" onClick={() => setTheme(nextTheme(theme))} title="Theme">{theme === 'dark' ? '☾' : theme === 'light' ? '☀' : '◐'}</button>
            {session.name && <><span className="user">{session.name}</span><button type="button" className="ghost" onClick={session.signOut}>Sign out</button></>}
          </div>
        </header>
        {failure && <div className="banner bad">{failure}</div>}
        {route.path === '/' && <OverviewPage live={live} go={go} />}
        {route.path === '/payments' && <PaymentsPage key={route.query.toString()} live={live} initialFilter={route.query.get('filter') ?? undefined} />}
        {route.path === '/links' && <LinksPage live={live} />}
        {route.path === '/notifications' && <NotificationsPage key={route.query.toString()} live={live} initialFilter={route.query.get('filter') ?? undefined} />}
        {route.path === '/reconciliation' && <ReconciliationPage demo={config.demo} />}
      </div>
    </div>
  );
}

function Splash({ title, text }: { title: string; text: string }) {
  return <div className="splash"><span className="logo big">F</span><h1>{title}</h1><p>{text}</p></div>;
}

function nextTheme(t: 'light' | 'dark' | null): 'light' | 'dark' | null {
  return t === null ? 'dark' : t === 'dark' ? 'light' : null;
}

/** A per-browser convenience only: storage can be blocked (private windows), and the page works the same without it. */
function stored(key: string): string | null {
  try {
    return window.localStorage.getItem(`fpd.${key}`);
  } catch {
    return null;
  }
}

function store(key: string, value: string | null) {
  try {
    if (value === null) window.localStorage.removeItem(`fpd.${key}`);
    else window.localStorage.setItem(`fpd.${key}`, value);
  } catch {
    /* storage unavailable: the choice lasts for this page only */
  }
}
