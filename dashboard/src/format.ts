import type { Charge, PaymentRow, Posting } from './types';

// Money is minor units plus a currency (D-1). Most currencies have two decimals; these do not.
const DIGITS: Record<string, number> = { JPY: 0, KRW: 0, VND: 0, CLP: 0, ISK: 0, KWD: 3, BHD: 3, OMR: 3, JOD: 3, TND: 3, LYD: 3, IQD: 3 };

export function fractionDigits(currency: string): number {
  return DIGITS[currency] ?? 2;
}

export function money(minor: number, currency: string): string {
  const digits = fractionDigits(currency);
  const value = minor / 10 ** digits;
  return `${currency} ${value.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
}

/** Parses "12.50" in a currency into minor units, exactly (no floating point), or null when it is not a valid amount for that currency. */
export function toMinor(text: string, currency: string): number | null {
  const digits = fractionDigits(currency);
  const pattern = digits === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${digits}})?$`);
  const trimmed = text.trim();
  if (!pattern.test(trimmed)) return null;
  const [whole, fraction = ''] = trimmed.split('.');
  const minor = Number(whole + fraction.padEnd(digits, '0'));
  return Number.isSafeInteger(minor) && minor > 0 ? minor : null;
}

export function when(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  return d.toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function short(id: string, keep = 8): string {
  return id.length > keep + 1 ? `${id.slice(0, keep)}…` : id;
}

/** Joins charges with their folio postings, newest first. */
export function paymentRows(charges: Charge[], postings: Posting[]): PaymentRow[] {
  const byCharge = new Map(postings.map((p) => [p.chargeId, p]));
  return charges
    .map((charge) => ({ charge, posting: byCharge.get(charge.id) ?? null }))
    .sort((a, b) => b.charge.createdAt.localeCompare(a.charge.createdAt));
}

/** Needs a person: a charge still pending, or a capture whose posting is dead-lettered. */
export function needsAttention(row: PaymentRow): boolean {
  return row.charge.status === 'PENDING' || row.posting?.status === 'DEAD_LETTER';
}
