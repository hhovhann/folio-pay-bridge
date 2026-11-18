// Shared by the OPERA front-desk page and the processor dashboard. Plain script, no inline handlers.
const $ = s => document.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const isOpera = location.pathname.endsWith('opera.html');
const money = (minor, cur) => { const d = ['KWD','BHD','OMR'].includes(cur) ? 3 : cur === 'JPY' ? 0 : 2; return (minor / 10 ** d).toFixed(d); };
async function call(path, method = 'GET', body) {
  const r = await fetch(path, {method, headers: {'Content-Type': 'application/json'}, body: body ? JSON.stringify(body) : undefined});
  return r.json().catch(() => ({}));
}
async function refresh() {
  const s = await call('/sim/state');
  $('[data-testid=current-fault]').textContent = s.fault;
  if (isOpera) {
    $('[data-testid=business-date]').textContent = s.businessDate;
    $('[data-testid=tokens]').textContent = s.tokensIssued;
    $('[data-testid=count]').textContent = '(' + s.payments.length + ')';
    const hooks = await call('/sim/webhooks');
    $('[data-testid=webhook-count]').textContent = '(' + hooks.length + ')';
    $('[data-testid=webhooks] tbody').innerHTML = hooks.map(h => `<tr><td>${esc(h.id)}</td><td>${esc(h.receivedAt)}</td></tr>`).join('');
    $('[data-testid=payments] tbody').innerHTML = s.payments.map(p => `<tr><td>${esc(p.transactionNo)}</td><td>${esc(p.reservationId)}</td><td>${esc(p.folioWindow)}</td><td>${esc(p.paymentCode)}</td><td>${esc(p.cashierId)}</td><td>${esc(p.currency)} ${esc(p.amount)}</td><td>${esc(p.businessDate)}</td><td>${esc(p.reference)}</td><td>${esc(p.pspRef)}</td></tr>`).join('');
  } else {
    $('[data-testid=captured]').textContent = money(s.capturedMinor, 'USD');
    $('[data-testid=ledger] tbody').innerHTML = s.ledger.map(e => `<tr><td>${esc(e.ref)}</td><td>${esc(e.kind)}</td><td>${e.last4 ? '**** ' + esc(e.last4) : ''}</td><td>${e.amountMinor ? esc(e.currency) + ' ' + money(e.amountMinor, e.currency) : ''}</td><td>${esc(e.state)}</td></tr>`).join('');
  }
}
const ACTIONS = {
  fault: async () => { await call('/sim/faults', 'POST', {fault: $('#fault').value}); refresh(); },
  expire: async () => { await call('/sim/expire-tokens', 'POST'); refresh(); },
  reset: async () => { await call('/sim/reset', 'POST'); refresh(); },
  'night-audit': async () => { await call('/sim/night-audit', 'POST'); refresh(); },
  booking: async (ref, plan, days, total) => {
    const r = await call('/sim/booking-events', 'POST', {bookingRef: ref, ratePlan: plan, arrivalInDays: days, totalMinor: total});
    $('[data-testid=booking-note]').textContent = r.note || r.error || 'Sent.';
  }
};
document.addEventListener('click', e => {
  const el = e.target.closest('[data-action]');
  if (el && ACTIONS[el.dataset.action]) ACTIONS[el.dataset.action](...JSON.parse(el.dataset.args || '[]'));
});
refresh();
setInterval(refresh, 1500);
