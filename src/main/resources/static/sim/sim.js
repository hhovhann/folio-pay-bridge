// Shared by the OPERA front-desk page and the processor dashboard. Plain script, no inline handlers; every value is escaped.
const $ = s => document.querySelector(s);
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const isOpera = location.pathname.endsWith('opera.html');
const money = (minor, cur) => { const d = ['KWD','BHD','OMR'].includes(cur) ? 3 : cur === 'JPY' ? 0 : 2; return (minor / 10 ** d).toFixed(d); };
let selected = null; // the reservation whose folio is open
// Rows that arrived since the last refresh are highlighted for a moment; on the first draw only the newest row is.
const seen = {};
function fresh(table, keys) {
  const first = !seen[table];
  const before = seen[table] || new Set();
  seen[table] = new Set(keys);
  return keys.map((k, i) => first ? i === keys.length - 1 : !before.has(k));
}
const hhmmss = at => (String(at || '').match(/T(\d\d:\d\d:\d\d)/) || [, ''])[1];
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
    return `<tr class="${payment ? 'credit-line' : ''}"><td>${esc(p.businessDate)}</td><td>${esc(p.transactionNo)}</td><td>${esc(p.folioWindow)}</td><td>${esc(p.code)}</td><td>${esc(p.description)}</td>`
      + `<td>${esc(p.cashierId)} ${esc(p.cashierName)}</td><td class="num">${payment ? '' : esc(p.currency) + ' ' + esc(p.amount)}</td>`
      + `<td class="num">${payment ? esc(p.currency) + ' ' + esc(p.amount) : ''}</td><td>${esc(p.reference)}</td></tr>`;
  }).join('') || '<tr><td colspan="9" class="mut">No postings yet.</td></tr>';
}
async function refresh() {
  const s = await call('/sim/state');
  const cf = $('[data-testid=current-fault]');
  cf.textContent = s.fault;
  cf.className = s.fault === 'NONE' ? 'fault' : 'fault bad';
  if (isOpera) {
    $('[data-testid=business-date]').textContent = s.businessDate;
    $('[data-testid=tokens]').textContent = s.tokensIssued;
    $('[data-testid=count]').textContent = '(' + s.payments.length + ')';
    $('#kpi-inhouse').textContent = s.reservations.filter(r => r.status === 'IN_HOUSE').length;
    $('#kpi-duein').textContent = s.reservations.filter(r => r.status === 'DUE_IN').length;
    $('#kpi-payments').textContent = s.payments.length;
    $('[data-testid=reservations] tbody').innerHTML = s.reservations.map(r =>
      `<tr class="pick${r.id === selected ? ' on' : ''}" data-reservation="${esc(r.id)}"><td>${esc(r.id)}</td><td>${esc(r.guest)}</td><td><span class="pill ${esc(r.status)}">${esc(r.status)}</span></td>`
      + `<td>${esc(r.room)}</td><td>${esc(r.arrival)}</td><td class="num ${owed(r.balance)}">${esc(r.currency)} ${esc(r.balance)}</td></tr>`).join('');
    const hooks = await call('/sim/webhooks');
    $('[data-testid=webhook-count]').textContent = '(' + hooks.length + ')';
    $('#kpi-hooks').textContent = hooks.length;
    const newHooks = fresh('webhooks', hooks.map(h => h.id));
    $('[data-testid=webhooks] tbody').innerHTML = hooks.map((h, i) => `<tr class="${newHooks[i] ? 'fresh' : ''}"><td>${esc(h.id)}</td><td>${esc(h.receivedAt)}</td></tr>`).join('');
    const newPayments = fresh('payments', s.payments.map(p => p.transactionNo));
    $('[data-testid=payments] tbody').innerHTML = s.payments.map((p, i) => `<tr class="${newPayments[i] ? 'fresh' : ''}"><td>${esc(p.transactionNo)}</td><td>${esc(p.reservationId)}</td><td>${esc(p.folioWindow)}</td><td><span class="code">${esc(p.code)}</span></td>`
      + `<td>${esc(p.cashierId)} ${esc(p.cashierName)}</td><td class="num">${esc(p.currency)} ${esc(p.amount)}</td><td>${esc(p.businessDate)}</td><td>${esc(p.reference)}</td><td>${esc(p.remark)}</td></tr>`).join('');
    await refreshFolio();
  } else {
    $('[data-testid=captured]').textContent = money(s.capturedMinor, 'USD');
    const count = kind => s.ledger.filter(e => e.kind === kind).length;
    $('#n-auth').textContent = count('AUTH');
    $('#n-capture').textContent = count('CAPTURE');
    $('#n-void').textContent = count('VOID');
    const newRows = fresh('ledger', s.ledger.map(e => e.ref));
    $('[data-testid=ledger] tbody').innerHTML = s.ledger.map((e, i) => `<tr class="${newRows[i] ? 'fresh' : ''}"><td class="ref">${esc(e.ref)}</td><td><span class="kind ${esc(e.kind)}">${esc(e.kind)}</span></td>`
      + `<td>${e.last4 ? `<span class="pan"><svg class="ic"><use href="/icons.svg#i-card"/></svg>**** ${esc(e.last4)}</span>` : ''}</td><td>${e.amountMinor ? esc(e.currency) + ' ' + money(e.amountMinor, e.currency) : ''}</td>`
      + `<td>${esc(e.state)}</td><td class="ref">${esc(e.authRef)}</td><td class="mut">${esc(hhmmss(e.at))}</td></tr>`).join('');
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
