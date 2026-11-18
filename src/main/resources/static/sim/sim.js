// Shared by the OPERA front-desk page and the processor dashboard. Plain script, no inline handlers; every value is escaped.
const $ = s => document.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const isOpera = location.pathname.endsWith('opera.html');
const money = (minor, cur) => { const d = ['KWD','BHD','OMR'].includes(cur) ? 3 : cur === 'JPY' ? 0 : 2; return (minor / 10 ** d).toFixed(d); };
let selected = null; // the reservation whose folio is open
async function call(path, method = 'GET', body) {
  const r = await fetch(path, {method, headers: {'Content-Type': 'application/json'}, body: body ? JSON.stringify(body) : undefined});
  return r.json().catch(() => ({}));
}
const owed = b => b.startsWith('-') ? 'credit' : (/^[0.]+$/.test(b) ? '' : 'owe');
async function refreshFolio() {
  const title = $('[data-testid=folio-title]');
  ['check-in', 'room-charge', 'check-out'].forEach(a => { $(`[data-testid=${a}]`).disabled = !selected; });
  if (!selected) return;
  const f = await call('/sim/reservations/' + encodeURIComponent(selected) + '/folio');
  if (!f.reservation) { selected = null; title.textContent = '(none selected)'; return; }
  const r = f.reservation;
  title.innerHTML = `${esc(r.id)} · ${esc(r.guest)} · <span class="pill ${esc(r.status)}">${esc(r.status)}</span>${r.room ? ' · room ' + esc(r.room) : ''}`;
  $('[data-testid=check-in]').disabled = r.status !== 'DUE_IN';
  $('[data-testid=room-charge]').disabled = r.status !== 'IN_HOUSE';
  $('[data-testid=check-out]').disabled = r.status !== 'IN_HOUSE';
  const bal = $('[data-testid=folio-balance]');
  bal.textContent = r.currency + ' ' + f.balance;
  bal.className = owed(f.balance);
  $('[data-testid=folio-lines] tbody').innerHTML = f.postings.map(p => {
    const payment = p.type === 'Payment';
    return `<tr><td>${esc(p.businessDate)}</td><td>${esc(p.transactionNo)}</td><td>${esc(p.folioWindow)}</td><td>${esc(p.code)}</td><td>${esc(p.description)}</td>`
      + `<td>${esc(p.cashierId)} ${esc(p.cashierName)}</td><td class="num">${payment ? '' : esc(p.currency) + ' ' + esc(p.amount)}</td>`
      + `<td class="num">${payment ? esc(p.currency) + ' ' + esc(p.amount) : ''}</td><td>${esc(p.reference)}</td></tr>`;
  }).join('') || '<tr><td colspan="9" class="mut">No postings yet.</td></tr>';
}
async function refresh() {
  const s = await call('/sim/state');
  $('[data-testid=current-fault]').textContent = s.fault;
  if (isOpera) {
    $('[data-testid=business-date]').textContent = s.businessDate;
    $('[data-testid=tokens]').textContent = s.tokensIssued;
    $('[data-testid=count]').textContent = '(' + s.payments.length + ')';
    $('[data-testid=reservations] tbody').innerHTML = s.reservations.map(r =>
      `<tr class="pick${r.id === selected ? ' on' : ''}" data-reservation="${esc(r.id)}"><td>${esc(r.id)}</td><td>${esc(r.guest)}</td><td><span class="pill ${esc(r.status)}">${esc(r.status)}</span></td>`
      + `<td>${esc(r.room)}</td><td>${esc(r.arrival)}</td><td class="num ${owed(r.balance)}">${esc(r.currency)} ${esc(r.balance)}</td></tr>`).join('');
    const hooks = await call('/sim/webhooks');
    $('[data-testid=webhook-count]').textContent = '(' + hooks.length + ')';
    $('[data-testid=webhooks] tbody').innerHTML = hooks.map(h => `<tr><td>${esc(h.id)}</td><td>${esc(h.receivedAt)}</td></tr>`).join('');
    $('[data-testid=payments] tbody').innerHTML = s.payments.map(p => `<tr><td>${esc(p.transactionNo)}</td><td>${esc(p.reservationId)}</td><td>${esc(p.folioWindow)}</td><td>${esc(p.code)}</td>`
      + `<td>${esc(p.cashierId)} ${esc(p.cashierName)}</td><td class="num">${esc(p.currency)} ${esc(p.amount)}</td><td>${esc(p.businessDate)}</td><td>${esc(p.reference)}</td><td>${esc(p.remark)}</td></tr>`).join('');
    await refreshFolio();
  } else {
    $('[data-testid=captured]').textContent = money(s.capturedMinor, 'USD');
    $('[data-testid=ledger] tbody').innerHTML = s.ledger.map(e => `<tr><td>${esc(e.ref)}</td><td>${esc(e.kind)}</td><td>${e.last4 ? '**** ' + esc(e.last4) : ''}</td><td>${e.amountMinor ? esc(e.currency) + ' ' + money(e.amountMinor, e.currency) : ''}</td><td>${esc(e.state)}</td></tr>`).join('');
  }
}
async function frontDesk(action) {
  const r = await call('/sim/reservations/' + encodeURIComponent(selected) + '/' + action, 'POST');
  $('[data-testid=folio-note]').textContent = r.detail || '';
  refresh();
}
const ACTIONS = {
  fault: async () => { await call('/sim/faults', 'POST', {fault: $('#fault').value}); refresh(); },
  expire: async () => { await call('/sim/expire-tokens', 'POST'); refresh(); },
  reset: async () => { selected = null; await call('/sim/reset', 'POST'); refresh(); },
  'night-audit': async () => { await call('/sim/night-audit', 'POST'); refresh(); },
  'check-in': () => frontDesk('check-in'),
  'room-charge': () => frontDesk('room-charge'),
  'check-out': () => frontDesk('check-out'),
  booking: async (ref, plan, days, total) => {
    const r = await call('/sim/booking-events', 'POST', {bookingRef: ref, ratePlan: plan, arrivalInDays: days, totalMinor: total});
    $('[data-testid=booking-note]').textContent = r.note || r.error || 'Sent.';
    refresh();
  }
};
document.addEventListener('click', e => {
  const row = e.target.closest('[data-reservation]');
  if (row) { selected = row.dataset.reservation; $('[data-testid=folio-note]').textContent = ''; refresh(); return; }
  const el = e.target.closest('[data-action]');
  if (el && !el.disabled && ACTIONS[el.dataset.action]) ACTIONS[el.dataset.action](...JSON.parse(el.dataset.args || '[]'));
});
refresh();
setInterval(refresh, 1500);
