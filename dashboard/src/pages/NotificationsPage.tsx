import { useState } from 'react';
import type { Notification } from '../types';
import { api, ApiError } from '../api';
import { useLive } from '../hooks';
import { when } from '../format';
import { Card, Drawer, Empty, ErrorBanner, Pill, Segmented } from '../components/ui';
import { useToast } from '../components/Toasts';

type Filter = 'all' | 'PENDING' | 'DELIVERED' | 'FAILED';

export function NotificationsPage({ live, initialFilter }: { live: boolean; initialFilter?: string }) {
  const list = useLive<Notification[]>(['/api/v1/notifications'], live);
  const [filter, setFilter] = useState<Filter>((initialFilter as Filter) ?? 'all');
  const [open, setOpen] = useState<Notification | null>(null);
  const toast = useToast();
  const rows = list.data ?? [];
  const shown = rows.filter((n) => filter === 'all' || n.status === filter);
  const count = (s: Filter) => (s === 'all' ? rows.length : rows.filter((n) => n.status === s).length);

  async function redeliver(n: Notification) {
    try {
      await api<Notification>(`/api/v1/notifications/${encodeURIComponent(n.id)}/redeliver`, { method: 'POST' });
      toast('Queued again: it will be sent on the next run', 'good');
      setOpen(null);
      await list.reload();
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'Could not queue it again', 'bad');
    }
  }

  return (
    <div className="page">
      <ErrorBanner error={list.error} />
      <div className="page-head">
        <h1>Webhooks</h1>
        <p className="muted">What the hotel's systems were told: captured, declined, posted, dead-lettered. Signed, sent at least once, retried with growing waits, never dropped.</p>
      </div>
      <Card>
        <div className="toolbar">
          <Segmented label="Filter webhooks" value={filter} onChange={setFilter} options={(['all', 'PENDING', 'DELIVERED', 'FAILED'] as Filter[]).map((f) => ({ value: f, label: f === 'all' ? 'All' : f.charAt(0) + f.slice(1).toLowerCase(), count: count(f) }))} />
        </div>
        {shown.length === 0 ? <Empty>{rows.length ? 'Nothing matches this filter.' : 'No events yet, or webhooks are not configured (foliopay.webhook.url).'}</Empty> : (
          <table className="table" data-testid="webhooks-table">
            <thead><tr><th>Event</th><th>Status</th><th className="num">Attempts</th><th>Delivered / next try</th><th>Last error</th><th /></tr></thead>
            <tbody>
              {shown.map((n) => (
                <tr key={n.id} className="clickable" onClick={() => setOpen(n)}>
                  <td><span className="mono">{n.type}</span><span className="sub mono">{n.eventId.split(':')[1]}</span></td>
                  <td><Pill value={n.status} /></td>
                  <td className="num">{n.attempts}</td>
                  <td className="muted">{n.deliveredAt ? when(n.deliveredAt) : n.status === 'PENDING' ? when(n.nextAttemptAt) : ''}</td>
                  <td className="muted ellipsis" title={n.lastError ?? ''}>{n.lastError ?? ''}</td>
                  <td className="actions" onClick={(e) => e.stopPropagation()}>{n.status === 'FAILED' && <button type="button" className="primary small" onClick={() => void redeliver(n)}>Send again</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      <Drawer open={open !== null} title={open?.type ?? ''} onClose={() => setOpen(null)}>
        {open && (
          <>
            <dl className="facts">
              <dt>Event id</dt><dd className="mono">{open.eventId}</dd>
              <dt>Status</dt><dd><Pill value={open.status} /></dd>
              <dt>Attempts</dt><dd>{open.attempts}</dd>
              {open.lastError && <><dt>Last error</dt><dd>{open.lastError}</dd></>}
            </dl>
            <h3>Payload (no card data, ever)</h3>
            <pre className="code">{pretty(open.payload)}</pre>
            {open.status === 'FAILED' && <button type="button" className="primary" onClick={() => void redeliver(open)}>Send again</button>}
          </>
        )}
      </Drawer>
    </div>
  );
}

function pretty(json: string): string {
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}
