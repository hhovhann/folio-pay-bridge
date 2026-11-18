import { useEffect, type ReactNode } from 'react';

const TONE: Record<string, string> = {
  CAPTURED: 'good', POSTED: 'good', DELIVERED: 'good', PAID: 'good', UP: 'good',
  PENDING: 'warn', PAYING: 'warn', OPEN: 'info',
  DECLINED: 'bad', DEAD_LETTER: 'bad', FAILED: 'bad', DOWN: 'bad',
  CANCELLED: 'muted', SUPERSEDED: 'muted', OPERA_UNAVAILABLE: 'warn', REJECTED_BY_OPERA: 'bad',
};

export function Pill({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="pill muted">none</span>;
  return <span className={`pill ${TONE[value] ?? 'info'}`}>{value.replaceAll('_', ' ').toLowerCase()}</span>;
}

export function Kpi(props: { label: string; value: ReactNode; hint?: ReactNode; tone?: 'good' | 'warn' | 'bad' | 'info'; icon?: ReactNode; onClick?: () => void; testId?: string }) {
  const body = (
    <>
      <div className="kpi-top"><span className="kpi-label">{props.label}</span>{props.icon && <span className={`kpi-icon ${props.tone ?? 'info'}`}>{props.icon}</span>}</div>
      <div className="kpi-value" data-testid={props.testId}>{props.value}</div>
      {props.hint && <div className="kpi-hint">{props.hint}</div>}
    </>
  );
  return props.onClick
    ? <button type="button" className={`kpi clickable ${props.tone ?? ''}`} onClick={props.onClick}>{body}</button>
    : <div className={`kpi ${props.tone ?? ''}`}>{body}</div>;
}

export function Card({ title, actions, children, className }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className ?? ''}`}>
      {(title || actions) && <header className="card-head"><h2>{title}</h2><div className="card-actions">{actions}</div></header>}
      {children}
    </section>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

export function ErrorBanner({ error }: { error: Error | null }) {
  if (!error) return null;
  return <div className="banner bad" role="alert">Could not load: {error.message}. Showing the last data received.</div>;
}

export function Drawer({ open, title, onClose, children }: { open: boolean; title: ReactNode; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="drawer-backdrop" onClick={onClose}>
      <aside className="drawer" role="dialog" aria-modal="true" aria-label={typeof title === 'string' ? title : 'Details'} onClick={(e) => e.stopPropagation()}>
        <header className="drawer-head"><h2>{title}</h2><button type="button" className="ghost" onClick={onClose} aria-label="Close">✕</button></header>
        <div className="drawer-body">{children}</div>
      </aside>
    </div>
  );
}

export function Segmented<T extends string>({ value, options, onChange, label }: { value: T; options: { value: T; label: string; count?: number }[]; onChange: (v: T) => void; label: string }) {
  return (
    <div className="segmented" role="tablist" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" role="tab" aria-selected={o.value === value} className={o.value === value ? 'on' : ''} onClick={() => onChange(o.value)}>
          {o.label}{o.count !== undefined && <span className="count">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}

export const Icon = {
  overview: <svg viewBox="0 0 24 24"><path d="M3 13h8V3H3zm10 8h8V11h-8zM3 21h8v-6H3zm10-18v6h8V3z" /></svg>,
  card: <svg viewBox="0 0 24 24"><path d="M3 5h18a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zm-1 4h20v2H2z" /></svg>,
  link: <svg viewBox="0 0 24 24"><path d="M10.6 13.4a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1 1.4 1.4 1-1a2 2 0 1 1 2.9 2.9l-3 3a2 2 0 0 1-2.9 0zm2.8-2.8a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1-1.4-1.4-1 1a2 2 0 1 1-2.9-2.9l3-3a2 2 0 0 1 2.9 0z" /></svg>,
  bell: <svg viewBox="0 0 24 24"><path d="M12 22a2.5 2.5 0 0 0 2.5-2.5h-5A2.5 2.5 0 0 0 12 22zm7-6V11a7 7 0 0 0-5.5-6.8V3h-3v1.2A7 7 0 0 0 5 11v5l-2 2v1h18v-1z" /></svg>,
  scale: <svg viewBox="0 0 24 24"><path d="M11 3h2v3h6l-3 7a3.5 3.5 0 0 0 6 0l-3-7h.5V4H13zM2 13a3.5 3.5 0 0 0 6 0L5 6H11V4H2v2h1zM11 8h2v12h4v2H7v-2h4z" /></svg>,
  money: <svg viewBox="0 0 24 24"><path d="M12 1v3m0 16v3M17 5.5C15.5 4.5 14 4 12 4c-3 0-5 1.5-5 3.7 0 5.3 10 2.7 10 8.3 0 2.3-2.2 4-5 4-2.2 0-4-.6-5.5-1.8" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>,
  clock: <svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 10.4 3.5 2.1-1 1.7L11 13V6h2z" /></svg>,
  alert: <svg viewBox="0 0 24 24"><path d="M1 21h22L12 2zm12-3h-2v-2h2zm0-4h-2v-4h2z" /></svg>,
  check: <svg viewBox="0 0 24 24"><path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z" /></svg>,
};
