import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PaymentsPage } from './PaymentsPage';
import { ToastProvider } from '../components/Toasts';
import { charge, fakeApi, posting } from '../testing';

function setUp() {
  return fakeApi({
    'GET /api/v1/charges': [
      charge(),
      charge({ id: 'ch-2', bookingRef: 'BK-2', createdAt: '2026-11-18T09:00:00Z' }),
      charge({ id: 'ch-3', bookingRef: 'BK-3', createdAt: '2026-11-18T10:00:00Z' }),
      charge({ id: 'ch-s', idempotencyKey: 'shadow-run-1-BK-9', bookingRef: 'BK-9' }),
    ],
    'GET /api/v1/postings': [
      posting(),
      posting({ id: 'po-2', chargeId: 'ch-2', status: 'DEAD_LETTER', failureCategory: 'OPERA_UNAVAILABLE', operaRef: null, lastError: 'OPERA answered 503' }),
      posting({ id: 'po-3', chargeId: 'ch-3', status: 'DEAD_LETTER', failureCategory: 'REJECTED_BY_OPERA', operaRef: null, lastError: 'FOLIO_CLOSED' }),
    ],
    'GET /api/v1/notifications': [],
    'POST /api/v1/postings/po-2/replay': posting({ id: 'po-2', chargeId: 'ch-2', operaRef: 'TX-2000' }),
  });
}

const renderPage = (filter?: string) => render(<ToastProvider><PaymentsPage live={false} initialFilter={filter} /></ToastProvider>);

describe('PaymentsPage', () => {
  it('offers Replay only where OPERA was unavailable, never for a posting OPERA rejected', async () => {
    setUp();
    renderPage();
    const table = await screen.findByTestId('payments-table');
    const row = (ref: string) => within(table).getByText(ref).closest('tr')!;

    expect(within(row('BK-2')).getByRole('button', { name: 'Replay' })).toBeInTheDocument();
    expect(within(row('BK-3')).queryByRole('button', { name: 'Replay' })).toBeNull();
    expect(within(row('BK-1')).queryByRole('button', { name: 'Replay' })).toBeNull();
  });

  it('keeps shadow-mode runs out of the ledger', async () => {
    setUp();
    renderPage();
    await screen.findByTestId('payments-table');
    expect(screen.queryByText('BK-9')).toBeNull();
  });

  it('replays through the API and says where the payment landed', async () => {
    const calls = setUp();
    renderPage();
    const table = await screen.findByTestId('payments-table');
    await userEvent.click(within(within(table).getByText('BK-2').closest('tr')!).getByRole('button', { name: 'Replay' }));

    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/v1/postings/po-2/replay')).toBe(true);
    expect(await screen.findByText(/Posted to folio RES-1 as TX-2000/)).toBeInTheDocument();
  });

  it('shows only what needs a person under "Needs attention"', async () => {
    setUp();
    renderPage('attention');
    const table = await screen.findByTestId('payments-table');
    expect(within(table).queryByText('BK-1')).toBeNull();
    expect(within(table).getByText('BK-2')).toBeInTheDocument();
    expect(within(table).getByText('BK-3')).toBeInTheDocument();
  });
});
