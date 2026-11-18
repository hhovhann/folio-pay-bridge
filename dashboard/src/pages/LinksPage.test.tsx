import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LinksPage } from './LinksPage';
import { ToastProvider } from '../components/Toasts';
import { fakeApi } from '../testing';

describe('LinksPage', () => {
  it('sends the amount in exact minor units and shows an app request apart from bookings', async () => {
    const calls = fakeApi({
      'GET /api/links': [{ id: 'lnk_1', bookingRef: 'req:FNB-1', folioId: 'RES-1', amountMinor: 45000, currency: 'USD', dueDate: '2030-01-01', reason: 'Brunch', status: 'OPEN' }],
      'POST /api/links': { links: [], refundReview: false, note: 'Brunch' },
    });
    render(<ToastProvider><LinksPage live={false} /></ToastProvider>);

    expect(await screen.findByText('app')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Reference'), 'FNB-2');
    await userEvent.type(screen.getByLabelText('Folio (reservation)'), 'RES-2');
    await userEvent.type(screen.getByLabelText('Amount'), '0.29');
    await userEvent.click(screen.getByRole('button', { name: 'Create request' }));

    const sent = calls.find((c) => c.method === 'POST' && c.path === '/api/links');
    expect(sent?.body).toMatchObject({ reference: 'FNB-2', folioId: 'RES-2', amountMinor: 29, currency: 'USD' });
  });

  it('will not send an amount the currency cannot have', async () => {
    const calls = fakeApi({ 'GET /api/links': [] });
    render(<ToastProvider><LinksPage live={false} /></ToastProvider>);
    await userEvent.type(screen.getByLabelText('Reference'), 'FNB-3');
    await userEvent.type(screen.getByLabelText('Folio (reservation)'), 'RES-3');
    await userEvent.selectOptions(screen.getByLabelText('Currency'), 'JPY');
    await userEvent.type(screen.getByLabelText('Amount'), '10.5');

    expect(screen.getByRole('button', { name: 'Create request' })).toBeDisabled();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});
