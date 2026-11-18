import { useMemo, useState } from 'react';
import type { Charge, Notification, PaymentRow, Posting } from '../types';
import { api, ApiError } from '../api';
import { useLive } from '../hooks';
import { money, needsAttention, paymentRows, short, when } from '../format';
import { Card, Drawer, Empty, ErrorBanner, Pill, Segmented } from '../components/ui';
import { useToast } from '../components/Toasts';

type Filter = 'all' | 'attention' | 'CAPTURED' | 'PENDING' | 'DECLINED';

export function PaymentsPage({ live, initialFilter }: { live: boolean; initialFilter?: string }) {
  const ledger = useLive<[Charge[], Posting[], Notification[]]>(['/api/charges', '/api/postings', '/api/notifications'], live);
  const [filter, setFilter] = useState<Filter>((initialFilter as Filter) ?? 'all');
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState<PaymentRow | null>(null);
  const toast = useToast();

  const rows = useMemo(() => (ledger.data ? paymentRows(ledger.data[0], ledger.data[1]).filter((r) => !r.charge.idempotencyKey.startsWith('shadow-')) : []), [ledger.data]);
  const counts = {
    all: rows.length,
    attention: rows.filter(needsAttention).length,
    CAPTURED: rows.filter((r) => r.charge.status === 'CAPTURED').length,
    PENDING: rows.filter((r) => r.charge.status === 'PENDING').length,
    DECLINED: rows.filter((r) => r.charge.status === 'DECLINED').length,
  };
  const term = search.trim().toLowerCase();
  const shown = rows
    .filter((r) => (filter === 'all' ? true : filter === 'attention' ? needsAttention(r) : r.charge.status === filter))
    .filter((r) => !term || [r.charge.bookingRef, r.charge.pspRef ?? '', r.posting?.operaRef ?? '', r.posting?.folioId ?? ''].some((v) => v.toLowerCase().includes(term)));

  async function replay(posting: Posting) {
    try {
      const after = await api<Posting>(`/api/postings/${encodeURIComponent(posting.id)}/replay`, { method: 'POST' });
      toast(after.status === 'POSTED' ? `Posted to folio ${after.folioId} as ${after.operaRef}` : `Still not posted: ${after.lastError ?? after.failureCategory}`, after.status === 'POSTED' ? 'good' : 'bad');
      await ledger.reload();
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'Replay failed', 'bad');
    }
  }

  const events = (row: PaymentRow) => (ledger.data?.[2] ?? []).filter((n) => n.eventId.endsWith(`:${row.charge.id}`) || (row.posting && n.eventId.endsWith(`:${row.posting.id}`)));

  return (
    <div className="page">
      <ErrorBanner error={ledger.error} />
      <div className="page-head">
        <h1>Payments</h1>
        <p className="muted">Each charge and where its folio posting stands. A payment is done when the card is captured and the folio shows it.</p>
      </div>
      <Card>
        <div className="toolbar">
          <Segmented label="Filter payments" value={filter} onChange={setFilter} options={[
            { value: 'all', label: 'All', count: counts.all },
            { value: 'attention', label: 'Needs attention', count: counts.attention },
            { value: 'CAPTURED', label: 'Captured', count: counts.CAPTURED },
            { value: 'PENDING', label: 'Pending', count: counts.PENDING },
            { value: 'DECLINED', label: 'Declined', count: counts.DECLINED },
          ]} />
          <input className="search" type="search" placeholder="Search booking, folio or reference" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Search payments" />
        </div>
        {shown.length === 0 ? <Empty>{rows.length ? 'Nothing matches this filter.' : 'No payments yet.'}</Empty> : (
          <table className="table" data-testid="payments-table">
            <thead><tr><th>Booking</th><th className="num">Amount</th><th>Card</th><th>Folio posting</th><th className="hide-sm">Processor ref</th><th className="hide-sm">When</th><th /></tr></thead>
            <tbody>
              {shown.map((row) => (
                <tr key={row.charge.id} className="clickable" onClick={() => setOpen(row)}>
                  <td className="mono">{row.charge.bookingRef}</td>
                  <td className="num">{money(row.charge.amountMinor, row.charge.currency)}</td>
                  <td><Pill value={row.charge.status} /></td>
                  <td><Pill value={row.posting?.status} />{row.posting?.failureCategory && <span className="sub">{row.posting.failureCategory.replaceAll('_', ' ').toLowerCase()}</span>}</td>
                  <td className="mono muted hide-sm">{row.charge.pspRef ?? ''}</td>
                  <td className="muted hide-sm">{when(row.charge.createdAt)}</td>
                  <td className="actions" onClick={(e) => e.stopPropagation()}>
                    {row.posting?.status === 'DEAD_LETTER' && row.posting.failureCategory === 'OPERA_UNAVAILABLE' &&
                      <button type="button" className="primary small" onClick={() => void replay(row.posting!)}>Replay</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Drawer open={open !== null} title={open ? `Payment for ${open.charge.bookingRef}` : ''} onClose={() => setOpen(null)}>
        {open && <PaymentDetail row={open} events={events(open)} onReplay={replay} />}
      </Drawer>
    </div>
  );
}

function PaymentDetail({ row, events, onReplay }: { row: PaymentRow; events: Notification[]; onReplay: (p: Posting) => Promise<void> }) {
  const { charge, posting } = row;
  return (
    <>
      <ol className="timeline">
        <li className="done"><b>Charge created</b><span>{when(charge.createdAt)} · key {short(charge.idempotencyKey, 24)}</span></li>
        <li className={charge.status === 'CAPTURED' ? 'done' : charge.status === 'DECLINED' ? 'bad' : 'wait'}>
          <b>Card {charge.status.toLowerCase()}</b><span>{charge.pspRef ? `processor ref ${charge.pspRef}` : charge.lastError ?? `attempts ${charge.attempts}`}</span>
        </li>
        {posting && (
          <li className={posting.status === 'POSTED' ? 'done' : posting.status === 'DEAD_LETTER' ? 'bad' : 'wait'}>
            <b>Folio {posting.status === 'POSTED' ? 'posted' : posting.status === 'DEAD_LETTER' ? 'dead-lettered' : 'posting'}</b>
            <span>{posting.operaRef ? `OPERA ${posting.operaRef} on folio ${posting.folioId}, business date ${posting.businessDate}` : posting.lastError ?? ''}</span>
          </li>
        )}
      </ol>
      {posting?.status === 'DEAD_LETTER' && posting.failureCategory === 'OPERA_UNAVAILABLE' && (
        <div className="banner warn">OPERA did not answer. The money is captured and waiting here, never dropped. Replay looks for the payment on the folio first, so it cannot post twice.
          <button type="button" className="primary small" onClick={() => void onReplay(posting)}>Replay now</button></div>
      )}
      {posting?.failureCategory === 'REJECTED_BY_OPERA' && <div className="banner bad">OPERA refused this posting ({posting.lastError}). Asking again cannot change that: fix the folio in OPERA, then post it by hand.</div>}
      <h3>Details</h3>
      <dl className="facts">
        <dt>Amount</dt><dd>{money(charge.amountMinor, charge.currency)}</dd>
        <dt>Idempotency key</dt><dd className="mono">{charge.idempotencyKey}</dd>
        <dt>Charge id</dt><dd className="mono">{charge.id}</dd>
        {posting && <><dt>Posting attempts</dt><dd>{posting.attempts}</dd></>}
        <dt>Card</dt><dd>Only a token is stored with the charge; the number never appears here or in any log.</dd>
      </dl>
      <h3>Webhooks sent</h3>
      {events.length === 0 ? <p className="muted">None yet (or webhooks are not configured).</p> : (
        <ul className="events">{events.map((n) => <li key={n.id}><Pill value={n.status} /> <span className="mono">{n.type}</span> <span className="muted">{n.deliveredAt ? when(n.deliveredAt) : `attempts ${n.attempts}`}</span></li>)}</ul>
      )}
    </>
  );
}
