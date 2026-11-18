// The shapes the bridge's API returns (see docs/openapi.json). Amounts are integers in minor units, always with their currency.

export type ChargeStatus = 'PENDING' | 'CAPTURED' | 'DECLINED';
export type PostingStatus = 'PENDING' | 'POSTED' | 'DEAD_LETTER';
export type FailureCategory = 'OPERA_UNAVAILABLE' | 'REJECTED_BY_OPERA';
export type LinkStatus = 'OPEN' | 'PAYING' | 'PAID' | 'CANCELLED' | 'SUPERSEDED';
export type NotificationStatus = 'PENDING' | 'DELIVERED' | 'FAILED';

export interface Charge {
  id: string; idempotencyKey: string; bookingRef: string; amountMinor: number; currency: string;
  status: ChargeStatus; pspRef: string | null; lastError: string | null; attempts: number; createdAt: string;
}

export interface Posting {
  id: string; chargeId: string; folioId: string; amountMinor: number; currency: string; businessDate: string;
  status: PostingStatus; operaRef: string | null; failureCategory: FailureCategory | null; lastError: string | null; attempts: number; createdAt: string;
}

export interface Link {
  id: string; bookingRef: string; folioId: string; amountMinor: number; currency: string; dueDate: string; reason: string; status: LinkStatus;
}

export interface Notification {
  id: string; eventId: string; type: string; status: NotificationStatus; attempts: number;
  nextAttemptAt: string | null; deliveredAt: string | null; lastError: string | null; payload: string;
}

export interface Overview {
  at: string;
  capturedToday: { currency: string; amountMinor: number; count: number }[];
  chargesPending: number; postingsPending: number; deadLettersReplayable: number; deadLettersRejected: number;
  webhooksPending: number; webhooksFailed: number; linksOpen: number; linksPaying: number;
}

export interface ReconItem {
  category: string; pspRef: string; bookingRef: string; expectedMinor: number; expectedCurrency: string; settledMinor: number; settledCurrency: string;
}

export interface ReconReport {
  matched: number; exceptions: Record<string, number>; items: ReconItem[]; totals: Record<string, { expectedMinor: number; settledMinor: number }>;
}

export interface DashboardConfig {
  auth: 'none' | 'oidc' | 'unavailable'; authority: string | null; clientId: string | null; scope: string | null; demo: boolean;
}

/** One payment as an operator thinks of it: the charge, and where its folio posting stands. */
export interface PaymentRow { charge: Charge; posting: Posting | null; }
