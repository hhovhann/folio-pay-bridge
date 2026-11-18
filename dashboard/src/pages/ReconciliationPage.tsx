import { useState, type FormEvent } from 'react';
import type { ReconReport } from '../types';
import { api, ApiError } from '../api';
import { money } from '../format';
import { Card, Empty, Kpi } from '../components/ui';

const MEANING: Record<string, string> = {
  MISSING_IN_SETTLEMENT: 'Captured, but the acquirer did not settle it',
  AMOUNT_DIFFERENCE: 'Settled for a different amount',
  CURRENCY_DIFFERENCE: 'Settled in another currency',
  UNKNOWN_IN_SETTLEMENT: 'Settled, but we never captured it',
  DUPLICATE_IN_SETTLEMENT: 'Settled more than once',
  NOT_POSTED_TO_PMS: 'Settled, but not on the guest folio',
};

export function ReconciliationPage({ demo }: { demo: boolean }) {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [csv, setCsv] = useState('');
  const [mode, setMode] = useState<'clean' | 'messy'>('clean');
  const [report, setReport] = useState<ReconReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setReport(await api<ReconReport>('/api/v1/reconcile', { method: 'POST', body: { mode, csv: csv.trim() || undefined, from: from || undefined, to: to || undefined } }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Reconciliation failed');
    } finally {
      setBusy(false);
    }
  }

  const exceptionCount = report ? report.items.length : 0;
  return (
    <div className="page">
      <div className="page-head">
        <h1>Reconciliation</h1>
        <p className="muted">Matches the acquirer's settlement against what was captured and what reached the folio. Every exception has a name finance can act on.</p>
      </div>
      <div className="grid-2 wide-right">
        <Card title="Statement">
          <form className="form" onSubmit={run}>
            <div className="row">
              <label className="grow">From (business date)<input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
              <label className="grow">To<input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
            </div>
            <p className="hint">Only captures taken in this period are expected in the file. Leave both empty to expect every capture.</p>
            <label>Paste a settlement file (optional)<textarea rows={5} value={csv} onChange={(e) => setCsv(e.target.value)} placeholder={'psp_ref,amount_minor,currency\ncap_1,80000,USD'} /></label>
            {!csv.trim() && demo && (
              <label>Acquirer statement<select value={mode} onChange={(e) => setMode(e.target.value as 'clean' | 'messy')}><option value="clean">As settled</option><option value="messy">Messy (demo: one missing, one short, one unknown)</option></select></label>
            )}
            <button type="submit" className="primary" disabled={busy}>{busy ? 'Reconciling…' : csv.trim() ? 'Reconcile this file' : "Reconcile the acquirer's statement"}</button>
            {error && <p className="hint bad">{error}</p>}
          </form>
        </Card>
        <div className="stack">
          {!report ? <Card><Empty>Run a reconciliation to see the result.</Empty></Card> : (
            <>
              <div className="kpis">
                <Kpi label="Matched" value={report.matched} tone="good" testId="recon-matched" />
                <Kpi label="Exceptions" value={exceptionCount} tone={exceptionCount ? 'bad' : 'good'} testId="recon-exceptions" />
                {Object.entries(report.totals).map(([currency, t]) => (
                  <Kpi key={currency} label={`${currency} difference`} tone={t.settledMinor === t.expectedMinor ? 'good' : 'bad'}
                    value={money(t.settledMinor - t.expectedMinor, currency)} hint={`expected ${money(t.expectedMinor, currency)}, settled ${money(t.settledMinor, currency)}`} />
                ))}
              </div>
              <Card title="Exceptions">
                {report.items.length === 0 ? <Empty>None: every capture settled for the right amount and reached the folio.</Empty> : (
                  <table className="table" data-testid="recon-table">
                    <thead><tr><th>What</th><th>Processor ref</th><th>Booking</th><th className="num">Expected</th><th className="num">Settled</th></tr></thead>
                    <tbody>
                      {report.items.map((i, n) => (
                        <tr key={`${i.pspRef}-${i.category}-${n}`}>
                          <td><span className="pill bad">{i.category.replaceAll('_', ' ').toLowerCase()}</span><span className="sub">{MEANING[i.category] ?? ''}</span></td>
                          <td className="mono">{i.pspRef}</td>
                          <td className="mono muted">{i.bookingRef}</td>
                          <td className="num">{i.expectedCurrency ? money(i.expectedMinor, i.expectedCurrency) : ''}</td>
                          <td className="num">{i.settledCurrency ? money(i.settledMinor, i.settledCurrency) : ''}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </Card>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
