import { render, screen } from '@testing-library/react';
import { OverviewPage } from './OverviewPage';
import { fakeApi } from '../testing';

describe('OverviewPage', () => {
  it('shows money per currency, never one total across currencies, and counts what needs attention', async () => {
    fakeApi({
      'GET /api/overview': {
        at: '2026-11-18T08:00:00Z',
        capturedToday: [{ currency: 'AED', amountMinor: 45000, count: 1 }, { currency: 'USD', amountMinor: 160000, count: 2 }],
        chargesPending: 1, postingsPending: 0, deadLettersReplayable: 2, deadLettersRejected: 0, webhooksPending: 0, webhooksFailed: 1, linksOpen: 4, linksPaying: 1,
      },
      'GET /api/charges': [], 'GET /api/postings': [], 'GET /actuator/health': { status: 'UP' },
    });

    render(<OverviewPage live={false} go={() => {}} />);

    const captured = await screen.findByTestId('kpi-captured');
    expect(captured).toHaveTextContent('AED 450.00');
    expect(captured).toHaveTextContent('USD 1,600.00');
    expect(captured).not.toHaveTextContent('2,050');
    expect(screen.getByTestId('attention')).toHaveTextContent('4 need attention');
    expect(screen.getByTestId('kpi-dead')).toHaveTextContent('2');
  });
});
