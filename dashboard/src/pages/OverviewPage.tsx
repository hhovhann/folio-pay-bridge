import type { Charge, Overview, Posting } from '../types';
import { useLive } from '../hooks';
import { money, paymentRows, when } from '../format';
import { Card, Empty, ErrorBanner, Icon, Kpi, Pill } from '../components/ui';

type Props = { live: boolean; go: (route: string) => void };

export function OverviewPage({ live, go }: Props) {
  const overview = useLive<Overview>(['/api/v1/overview'], live);
  const ledger = useLive<[Charge[], Posting[]]>(['/api/v1/charges', '/api/v1/postings'], live);
  const health = useLive<{ status: string }>(['/actuator/health'], live);
  const o = overview.data;
  const recent = ledger.data ? paymentRows(ledger.data[0], ledger.data[1]).filter((r) => !r.charge.idempotencyKey.startsWith('shadow-')).slice(0, 8) : [];
  const capturedCount = o ? o.capturedToday.reduce((n, c) => n + c.count, 0) : 0;
  const attention = o ? o.chargesPending + o.deadLettersReplayable + o.deadLettersRejected + o.webhooksFailed : 0;

  return (
    <div className="page">
      <ErrorBanner error={overview.error} />
      <div className="hero">
        <div>
          <h1>Today at the hotel</h1>
          <p className="muted">Money captured since midnight in the hotel's time zone, and every queue that needs a person.</p>
        </div>
        <div className={`attention ${attention ? 'bad' : 'good'}`} data-testid="attention">
          {o ? (attention ? <>{Icon.alert}<span><b>{attention}</b> need{attention === 1 ? 's' : ''} attention</span></> : <>{Icon.check}<span>All clear</span></>) : '…'}
        </div>
      </div>

      <div className="kpis">
        <Kpi label="Captured today" icon={Icon.money} tone="good" testId="kpi-captured"
          value={o ? (o.capturedToday.length ? o.capturedToday.map((c) => <div key={c.currency}>{money(c.amountMinor, c.currency)}</div>) : '0') : '…'}
          hint={o ? `${capturedCount} payment${capturedCount === 1 ? '' : 's'}, per currency, never added across currencies` : undefined} />
        <Kpi label="Charges pending" icon={Icon.clock} tone={o?.chargesPending ? 'warn' : 'good'} value={o?.chargesPending ?? '…'} testId="kpi-pending"
          hint="Retried with the same key: never charged twice" onClick={() => go('/payments?filter=attention')} />
        <Kpi label="Waiting for OPERA" icon={Icon.alert} tone={o?.deadLettersReplayable ? 'bad' : 'good'} value={o?.deadLettersReplayable ?? '…'} testId="kpi-dead"
          hint="Captured, not on the folio yet: replay when OPERA is back" onClick={() => go('/payments?filter=attention')} />
        <Kpi label="Rejected by OPERA" icon={Icon.alert} tone={o?.deadLettersRejected ? 'bad' : 'good'} value={o?.deadLettersRejected ?? '…'} testId="kpi-rejected"
          hint="Needs a person to fix the folio" onClick={() => go('/payments?filter=attention')} />
        <Kpi label="Webhooks failed" icon={Icon.bell} tone={o?.webhooksFailed ? 'bad' : 'good'} value={o?.webhooksFailed ?? '…'} testId="kpi-webhooks"
          hint={o ? `${o.webhooksPending} waiting for their next attempt` : undefined} onClick={() => go('/notifications?filter=FAILED')} />
      </div>

      <Card title="Payment flow" className="flow-card">
        <ol className="flow" data-testid="flow">
          <Stage label="Requests open" value={o?.linksOpen} onClick={() => go('/links')} />
          <Stage label="Being paid" value={o?.linksPaying} onClick={() => go('/links')} />
          <Stage label="Captured today" value={o ? capturedCount : undefined} tone="good" onClick={() => go('/payments')} />
          <Stage label="Posting to OPERA" value={o?.postingsPending} onClick={() => go('/payments')} />
          <Stage label="Dead letters" value={o ? o.deadLettersReplayable + o.deadLettersRejected : undefined} tone={o && o.deadLettersReplayable + o.deadLettersRejected ? 'bad' : 'good'} onClick={() => go('/payments?filter=attention')} />
        </ol>
      </Card>

      <div className="grid-2">
        <Card title="Latest payments" actions={<button type="button" className="ghost" onClick={() => go('/payments')}>All payments →</button>}>
          {recent.length === 0 ? <Empty>No payments yet.</Empty> : (
            <table className="table compact">
              <thead><tr><th>Booking</th><th className="num">Amount</th><th>Card</th><th>Folio</th><th className="hide-sm">When</th></tr></thead>
              <tbody>
                {recent.map(({ charge, posting }) => (
                  <tr key={charge.id}>
                    <td className="mono">{charge.bookingRef}</td>
                    <td className="num">{money(charge.amountMinor, charge.currency)}</td>
                    <td><Pill value={charge.status} /></td>
                    <td><Pill value={posting?.status} /></td>
                    <td className="muted hide-sm">{when(charge.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
        <Card title="System">
          <dl className="facts">
            <dt>Service health</dt><dd><Pill value={health.data?.status ?? (health.error ? 'DOWN' : null)} /></dd>
            <dt>Last refresh</dt><dd>{o ? when(o.at) : '…'}</dd>
            <dt>Card data</dt><dd>Tokenised on arrival; envelope encryption under KMS; never logged</dd>
            <dt>Exactly once</dt><dd>Idempotency key per request; OPERA asked before any repeat post</dd>
          </dl>
        </Card>
      </div>
    </div>
  );
}

function Stage({ label, value, tone, onClick }: { label: string; value: number | undefined; tone?: 'good' | 'bad'; onClick: () => void }) {
  return (
    <li>
      <button type="button" className={`stage ${tone ?? ''}`} onClick={onClick}>
        <span className="stage-value">{value ?? '…'}</span>
        <span className="stage-label">{label}</span>
      </button>
    </li>
  );
}
