// Operator console. No inline script: the page's content security policy allows scripts from this origin only.
const TODAY='2026-11-17';let TOKEN=null;
const $=s=>document.querySelector(s);
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const DIGITS={KWD:3,BHD:3,OMR:3,JPY:0,KRW:0};
const money=(m,c='USD')=>{const d=c in DIGITS?DIGITS[c]:2;return (m/10**d).toLocaleString('en-US',{minimumFractionDigits:d,maximumFractionDigits:d})};
async function api(path,method='GET',body){const r=await fetch('/api'+path,{method,headers:{'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});let j=null;try{j=await r.json()}catch(e){}return{status:r.status,body:j}}
const pill=s=>s?`<span class="pill ${s}">${s.replace('_',' ')}</span>`:'';
async function refresh(){
  const [links,charges,posts,sim,logs]=await Promise.all([api('/links'),api('/charges'),api('/postings'),api('/sim'),api('/logs')]);
  $('[data-testid=links] tbody').innerHTML=links.body.map(l=>`<tr data-testid="link-${esc(l.bookingRef)}-${l.status}"><td>${l.id.slice(4,10)}</td><td>${esc(l.bookingRef)}</td><td>${l.currency} ${money(l.amountMinor,l.currency)}</td><td>${l.dueDate}</td><td>${pill(l.status)}</td><td>${l.status==='OPEN'?`<button data-testid="pay-${esc(l.bookingRef)}" data-action="payLink" data-args='["${l.id}"]'>Pay</button><button data-testid="again-${esc(l.bookingRef)}" data-action="payLink" data-args='["${l.id}"]'>Pay again</button>`:''}</td></tr>`).join('');
  const pm=Object.fromEntries(posts.body.map(p=>[p.chargeId,p]));
  $('[data-testid=ledger] tbody').innerHTML=charges.body.filter(c=>!c.idempotencyKey.startsWith('shadow-')).map(c=>{const p=pm[c.id];return `<tr data-testid="ledger-${esc(c.bookingRef)}"><td>${esc(c.bookingRef)}</td><td>${money(c.amountMinor,c.currency)}</td><td>${pill(c.status)}</td><td>${p?pill(p.status)+(p.failureCategory?`<div class="mut">${esc(p.failureCategory)}</div>`:''):''}</td><td>${p&&p.status==='DEAD_LETTER'&&p.failureCategory==='OPERA_UNAVAILABLE'?`<button data-testid="replay-${esc(c.bookingRef)}" data-action="replay" data-args='["${p.id}"]'>Replay</button>`:''}</td></tr>`}).join('');
  if(sim.body&&sim.body.opera==='EXTERNAL'){const h=location.hostname;$('#actors').innerHTML=`Full-flow mode. Other actors: <a href="http://${h}:8090/" target="_blank" rel="noopener">OPERA front desk</a> · <a href="http://${h}:8091/" target="_blank" rel="noopener">card processor</a> · <a href="/swagger-ui/index.html" target="_blank" rel="noopener">API</a> · <a href="/architecture.html" target="_blank" rel="noopener">How it works</a>`}
  const s=sim.body||{},external=s.opera==='EXTERNAL'; // the processor and OPERA keep their own books in this mode
  $('#c-psp').textContent=external?'see processor':(s.pspCaptures??'-');$('#c-amt').textContent=external?'see processor':money(s.pspCapturedMinor??0);$('#c-opera').textContent=external?'see OPERA':(s.operaPosts??'-');
  $('[data-testid=logs]').textContent=logs.body.slice(-40).join('\n')||'(empty)';
  const v=await api('/vault');$('[data-testid=vault-rows]').textContent=v.body.length?v.body.slice(-1).map(r=>`token       ${r.token}\nbin6/last4  ${r.bin6} ... ${r.last4}\nsealed PAN  ${r.sealedPanHex}\nwrapped key ${r.wrappedKeyHex}\nkey id      ${r.keyId}`).join('\n'):'(nothing)';
}
async function tokenise(){const r=await api('/cards','POST',{pan:$('#pan').value,expiry:$('#exp').value});
  if(r.status===201){TOKEN=r.body.token;$('[data-testid=token-note]').innerHTML=`<span class="good">Token ${r.body.token.slice(0,12)}...</span> for card ending ${r.body.last4}. The number is not stored.`}else $('[data-testid=token-note]').innerHTML=`<span class="bad">${esc((r.body&&(r.body.error||r.body.message))||'Rejected')}</span>`;
  await refresh()}
async function sendEvent(type,ref,plan,days,total){const a=new Date(TODAY+'T00:00:00Z');a.setUTCDate(a.getUTCDate()+days);
  const r=await api('/booking-events','POST',{type,bookingRef:ref,folioId:'F-'+ref,ratePlan:plan,arrival:a.toISOString().slice(0,10),totalMinor:total,currency:'USD',today:TODAY});
  $('[data-testid=ev-note]').textContent=r.body.note+(r.body.refundReview?' (refund review)':'');await refresh()}
async function payLink(id){if(!TOKEN){$('[data-testid=pay-note]').innerHTML='<span class="bad">Tokenise a card first.</span>';return}
  const r=await api('/links/'+id+'/pay','POST',{token:TOKEN});
  const o=r.body.outcome;$('[data-testid=pay-note]').innerHTML=o==='OK'?`<span class="good">OK</span>: charged and posted to the folio.`:o==='CHARGED_NOT_POSTED'?`<span class="bad">Charged, not posted</span>: the PMS did not accept the posting. It is in the dead-letter queue.`:`<span class="bad">${o||'Error'}</span>`;await refresh()}
async function replay(id){const r=await api('/postings/'+id+'/replay','POST');$('[data-testid=pay-note]').innerHTML=r.body.status==='POSTED'?'<span class="good">Replayed: posted once.</span>':`<span class="bad">${esc(r.body.failureCategory)}</span>`;await refresh()}
async function applyFaults(){await api('/sim','POST',{psp:$('#psp').value,opera:$('#opera').value});await refresh()}
async function recon(mode){const r=await api('/reconcile','POST',{mode});const b=r.body;
  const totals=Object.entries(b.totals).map(([c,t])=>`${esc(c)}: expected ${money(t.expectedMinor,c)}, settled ${money(t.settledMinor,c)}, difference <b class="${t.settledMinor===t.expectedMinor?'good':'bad'}">${money(t.settledMinor-t.expectedMinor,c)}</b>`).join('; ');
  $('[data-testid=recon-note]').innerHTML=`<b>${b.matched}</b> matched. ${totals||'Nothing captured or settled'}. Exceptions: ${Object.keys(b.exceptions).length?Object.entries(b.exceptions).map(([k,v])=>esc(k)+' '+v).join(', '):'none'}.`;
  const amt=(m,c)=>c?esc(c)+' '+money(m,c):'';
  $('[data-testid=recon-items] tbody').innerHTML=b.items.map(i=>`<tr><td><span class="pill DEAD_LETTER">${esc(i.category)}</span></td><td>${esc(i.pspRef)}</td><td>${amt(i.expectedMinor,i.expectedCurrency)}</td><td>${amt(i.settledMinor,i.settledCurrency)}</td></tr>`).join('')}
async function shadow(mode){$('[data-testid=shadow-note]').textContent='Running...';const r=await api('/shadow','POST',{bookings:200,seed:42,mode});const b=r.body;
  if(r.status!==200){$('[data-testid=shadow-note]').textContent=(b&&b.error)||'Shadow mode is not available here';return}
  $('[data-testid=shadow-note]').innerHTML=`${b.bookings} bookings: <b class="good">${b.matched} match</b>, <b class="${b.mismatched?'bad':'good'}">${b.mismatched} differ</b>${Object.keys(b.byField).length?' ('+Object.entries(b.byField).map(([k,v])=>k+' '+v).join(', ')+')':''}. Rules: tax ${b.config.rounding.toLowerCase().replace('_',' ')}, date ${b.config.dateRule.toLowerCase().replace('_',' ')}.`;
  $('[data-testid=shadow-items] tbody').innerHTML=b.samples.slice(0,6).map(s=>`<tr><td>${esc(s.bookingRef)}</td><td>${esc(s.field)}</td><td>${esc(s.legacy)}</td><td>${esc(s.candidate)}</td></tr>`).join('');await refresh()}
refresh();
const ACTIONS={tokenise,sendEvent,payLink,replay,applyFaults,recon,shadow};
document.addEventListener('click',e=>{const el=e.target.closest('[data-action]');if(!el)return;const act=ACTIONS[el.dataset.action];if(act)act(...JSON.parse(el.dataset.args||'[]'))});

