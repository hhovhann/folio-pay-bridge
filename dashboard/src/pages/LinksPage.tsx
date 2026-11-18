import { useState, type FormEvent } from 'react';
import type { Link } from '../types';
import { api, ApiError } from '../api';
import { useLive } from '../hooks';
import { money, toMinor } from '../format';
import { Card, Empty, ErrorBanner, Pill } from '../components/ui';
import { useToast } from '../components/Toasts';

const CURRENCIES = ['USD', 'AED', 'EUR', 'GBP', 'SAR', 'QAR', 'KWD', 'BHD', 'OMR', 'JPY'];
const REFERENCE = /^[A-Za-z0-9._-]{1,64}$/;

export function LinksPage({ live }: { live: boolean }) {
  const links = useLive<Link[]>(['/api/v1/links'], live);
  const toast = useToast();
  const today = new Date().toISOString().slice(0, 10);
  const [form, setForm] = useState({ reference: '', folioId: '', amount: '', currency: 'USD', dueDate: today, description: '' });
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });

  const amountMinor = toMinor(form.amount, form.currency);
  const problems = [
    !REFERENCE.test(form.reference) && 'a reference of letters, digits, dot, dash or underscore',
    !REFERENCE.test(form.folioId) && 'the folio (reservation) to post to',
    amountMinor === null && `an amount in ${form.currency}`,
  ].filter(Boolean) as string[];

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (problems.length) return;
    setBusy(true);
    try {
      const out = await api<{ note: string }>('/api/v1/links', {
        method: 'POST',
        body: { reference: form.reference, folioId: form.folioId, amountMinor, currency: form.currency, dueDate: form.dueDate, description: form.description || undefined },
      });
      toast(out.note.startsWith('No change') ? 'Already requested with these terms: nothing changed' : 'Payment request created', 'good');
      setForm({ ...form, reference: '', amount: '', description: '' });
      await links.reload();
    } catch (err) {
      toast(err instanceof ApiError ? err.message : 'Could not create the request', 'bad');
    } finally {
      setBusy(false);
    }
  }

  const rows = links.data ?? [];
  return (
    <div className="page">
      <ErrorBanner error={links.error} />
      <div className="page-head">
        <h1>Payment links</h1>
        <p className="muted">Requests from bookings (deposit policy) and from other teams and apps (F&amp;B, events, spa). One live request per reference: sending the same one again changes nothing.</p>
      </div>
      <div className="grid-2 wide-left">
        <Card title="Requests">
          {rows.length === 0 ? <Empty>No payment requests yet.</Empty> : (
            <table className="table" data-testid="links-table">
              <thead><tr><th>Reference</th><th className="hide-sm">Folio</th><th className="num">Amount</th><th className="hide-sm">Due</th><th>Status</th><th className="hide-sm">Why</th></tr></thead>
              <tbody>
                {rows.map((l) => (
                  <tr key={l.id}>
                    <td className="mono">{l.bookingRef.startsWith('req:') ? <><span className="tag">app</span>{l.bookingRef.slice(4)}</> : l.bookingRef}</td>
                    <td className="mono muted hide-sm">{l.folioId}</td>
                    <td className="num">{money(l.amountMinor, l.currency)}</td>
                    <td className="hide-sm nowrap">{l.dueDate}</td>
                    <td><Pill value={l.status} /></td>
                    <td className="muted ellipsis hide-sm" title={l.reason}>{l.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
        <Card title="New payment request">
          <form className="form" onSubmit={submit} noValidate>
            <label>Reference<input value={form.reference} onChange={set('reference')} placeholder="FNB-2026-1201" maxLength={64} /></label>
            <label>Folio (reservation)<input value={form.folioId} onChange={set('folioId')} placeholder="RES-1001" maxLength={64} /></label>
            <div className="row">
              <label className="grow">Amount<input value={form.amount} onChange={set('amount')} inputMode="decimal" placeholder="450.00" /></label>
              <label>Currency<select value={form.currency} onChange={set('currency')}>{CURRENCIES.map((c) => <option key={c}>{c}</option>)}</select></label>
            </div>
            <label>Due date<input type="date" value={form.dueDate} min={today} onChange={set('dueDate')} /></label>
            <label>Description<input value={form.description} onChange={set('description')} placeholder="Friday brunch, table for four" maxLength={120} /></label>
            {form.reference && problems.length > 0 && <p className="hint bad">Still needed: {problems.join(', ')}.</p>}
            <button type="submit" className="primary" disabled={busy || problems.length > 0}>{busy ? 'Creating…' : 'Create request'}</button>
            <p className="hint">The payer receives a link; when they pay, the payment is posted to this folio and the hotel's systems are told by webhook.</p>
          </form>
        </Card>
      </div>
    </div>
  );
}
