// The "who is who" page: every party in one hotel card payment, and what passes between them, step by step.
// Plain script, no inline handlers (the content security policy allows only scripts from this site). The page's state is one attribute,
// body[data-step]: a step id ("auth"), "actor:<id>" for a party, or absent for the overview. The buttons set it, and so does the narrated
// video (demo/overlay.js), so what the voice says and what the page shows cannot drift apart.
(() => {
  const NS = 'http://www.w3.org/2000/svg';
  const $ = s => document.querySelector(s);
  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));

  // Geometry of the board (viewBox 1200 x 312): seven tiles in a row, four below; a message bus under the row, a money lane under that.
  const TOP = 170, BUS = 196, LANE = 214, ROW2 = 232;
  const cx = i => 91 + 170 * i;
  const COLOR = {guest: '#7c3aed', pms: '#9f1239', bridge: '#2563eb', processor: '#0f766e', acquirer: '#0e7490', network: '#4338ca',
    issuer: '#a16207', hotelsys: '#db2777', finance: '#15803d', kms: '#d97706', bank: '#166534', ok: '#15803d', money: '#16a34a'};

  // Each party: where it sits, what it is in real life, what it ever sees of the card, and what plays it in this demo.
  const ACTORS = {
    guest: {i: 0, icon: 'phone', name: 'Guest', sub: 'the cardholder', role: ['books a stay, then pays', 'from a link on a phone'], badge: ['you', 'YOU, IN THE DEMO'],
      life: 'The person staying at the hotel. They book directly or through a booking site, and pay by card, often from a payment link on their phone.',
      card: 'their own card', demo: 'you, in the bridge console: "Tokenise test card", then "Pay". Public test card numbers only.'},
    pms: {i: 1, icon: 'hotel', name: 'Hotel + OPERA', sub: 'front desk and PMS', role: ['reservations, rooms,', 'the guest\'s bill (folio)'], badge: ['sim', 'SIMULATOR'],
      life: 'The hotel\'s property management system (PMS), here Oracle OPERA, holds the reservations, the rooms and each guest\'s bill, the folio. The front desk checks guests in and out; the night audit closes the hotel\'s day.',
      card: 'only the amount, a reference and the last four digits', demo: 'the OPERA front-desk simulator (port 8090), built on Oracle\'s published contract. It is not Oracle software.'},
    bridge: {i: 2, icon: 'bridge', name: 'Payment bridge', sub: 'this project', role: ['charges once, posts once,', 'tells, then reconciles'], badge: ['built', 'BUILT HERE'],
      life: 'The bridge sits between the hotel and the card world. It turns a booking into a payment request, charges the card exactly once, writes the payment on the folio exactly once, tells the hotel\'s systems, and checks the settlement.',
      card: 'the number once: encrypted on arrival, opened only for the moment it goes to the processor, never logged', demo: 'the real code in this repository: Java, Spring Boot and PostgreSQL (port 8081).'},
    processor: {i: 3, icon: 'card', name: 'Processor', sub: 'payment gateway (PSP)', role: ['takes card payments', 'online for the hotel'], badge: ['sim', 'SIMULATOR'],
      life: 'The company the hotel signs with to take cards online. It receives the bridge\'s requests, passes them on to the acquirer and returns the answer. Many processors are acquirers as well.',
      card: 'the full number, to route the payment', demo: 'the card-processor simulator (port 8091). It also answers for the acquirer, the card network and the issuer, which the demo does not run.'},
    acquirer: {i: 4, icon: 'bank', name: 'Acquirer', sub: 'the hotel\'s card bank', role: ['sends the hotel\'s payments', 'on; pays the hotel out'], badge: ['inside', 'IN THE SIMULATOR'],
      life: 'A bank licensed by the card networks to accept card payments for businesses. It sends the hotel\'s payments into the network, and pays the hotel what was collected, less fees, with a statement of what it paid.',
      card: 'the full number', demo: 'not run: the processor simulator answers for it. Its statement is the settlement file the bridge reconciles.'},
    network: {i: 5, icon: 'globe', name: 'Card network', sub: 'Visa, Mastercard, ...', role: ['finds the guest\'s bank,', 'settles between banks'], badge: ['inside', 'IN THE SIMULATOR'],
      life: 'The rails between every acquirer and every issuer. It routes each request to the guest\'s bank by the first digits of the card number, moves the money between the banks each day, and sets the rules, disputes included.',
      card: 'the full number', demo: 'not run: the processor simulator names it from the card\'s first digit (4 is Visa, 5 is Mastercard).'},
    issuer: {i: 6, icon: 'bank', name: 'Issuer', sub: 'the guest\'s bank', role: ['says yes or no,', 'holds the money'], badge: ['inside', 'IN THE SIMULATOR'],
      life: 'The bank that gave the guest the card. It decides yes or no: is the card valid, is there money or credit, does it look like fraud? On yes it holds the amount on the guest\'s account. A booking site\'s virtual card has an issuer too: the bank behind that card.',
      card: 'the full number: it is its own card', demo: 'not run: the processor simulator approves with a code, or declines when you set "Make it fail".'},
    hotelsys: {x: 16, icon: 'bell', name: 'Hotel systems', sub: 'told by webhooks', badge: ['sim', 'SIMULATED RECEIVER'],
      life: 'The hotel\'s own software: its middleware, booking engine or messaging app. The bridge tells them what happened (paid, declined, posted, stuck) in signed messages called webhooks.',
      card: 'never the number', demo: 'the OPERA simulator receives them, under "Events from the bridge".'},
    finance: {x: 186, icon: 'scale', name: 'Finance', sub: 'checks the books', badge: ['you', 'YOU, IN THE DEMO'],
      life: 'The hotel\'s accountants. They check that what was charged, what was paid out and what is on the guests\' bills all agree.',
      card: 'never the number', demo: 'the "Reconcile" buttons in the bridge console: the result names every difference.'},
    kms: {x: 356, icon: 'key', name: 'Key service', sub: 'AWS KMS', badge: ['not', 'LOCAL KEY IN DEMO'],
      life: 'A locked box for encryption keys in Amazon\'s cloud. Its master key never leaves it; the bridge asks it to lock and unlock the small key that encrypts each card.',
      card: 'never the number: only the small key', demo: 'a local key. The KMS code is tested against a stand-in, not yet in a real AWS account.'},
    bank: {x: 696, icon: 'coins', name: 'Hotel\'s bank', sub: 'money lands here', badge: ['not', 'NOT IN THE DEMO'],
      life: 'The hotel\'s bank account. The money arrives a day or more after the guest paid, less the fees of the acquirer, the network and the issuer.',
      card: 'never the number', demo: 'not shown: the demo shows the statement that lists the money.'},
  };

  // Routes between parties, as points on the board. A route that stops at a tile's lower edge (y = TOP) is a visit: that tile lights up.
  const bus = (a, b) => [[cx(a), TOP], [cx(a), BUS], [cx(b), BUS], [cx(b), TOP]];
  const hops = (a, b) => {
    const points = [[cx(a), TOP], [cx(a), BUS]];
    for (let i = a + Math.sign(b - a); ; i += Math.sign(b - a)) {
      points.push([cx(i), BUS], [cx(i), TOP]);
      if (i === b) return points;
      points.push([cx(i), BUS]);
    }
  };
  const down = (a, x) => [[cx(a), TOP], [cx(a), BUS], [x, BUS], [x, ROW2]];
  const back = route => [...route].reverse();
  const KEY = [[471, TOP], [471, ROW2]];
  const MONEY = [[1141, TOP], [1141, LANE], [971, LANE], [971, TOP], [971, LANE], [801, LANE], [801, TOP], [801, LANE], [801, ROW2]];

  // The payment, step by step: the parties involved, what passes between them, and how long it takes in real life.
  const STEPS = [
    {id: 'book', short: 'Book', time: 'seconds', who: ['guest', 'pms', 'bridge'], title: 'A guest books a stay',
      text: 'The hotel\'s PMS records the reservation and tells the bridge. The bridge applies the hotel\'s rule: for a non-refundable rate, the full amount within 24 hours.',
      demo: 'OPERA front desk, "New reservation"', wrong: 'the same booking can arrive twice: the bridge keeps one payment request per booking',
      seq: [[bus(0, 1), 'booking', 'guest'], [bus(1, 2), 'booking event', 'pms']]},
    {id: 'link', short: 'Ask', time: 'seconds', who: ['bridge', 'guest'], title: 'The bridge asks the guest to pay',
      text: 'It creates a payment request for 800 dollars and the guest opens it as a link.',
      demo: 'the bridge console lists the request', wrong: 'a link being paid by one card cannot be paid by a second',
      seq: [[bus(2, 0), 'payment link · $800', 'bridge']]},
    {id: 'card', short: 'Card', time: 'seconds', who: ['guest', 'bridge', 'kms'], title: 'The guest enters the card',
      text: 'The bridge encrypts the number the moment it arrives, with a key it gets from the key service, and keeps only a token. The number is never stored in plain, logged or sent back.',
      demo: '"Tokenise test card": only a token comes back', wrong: 'a card number that slips into a log line anyway is masked',
      seq: [[bus(0, 2), 'card number', 'guest'], [KEY, 'new key', 'kms'], [back(KEY), 'key', 'kms'], [bus(2, 0), 'token tok_…', 'bridge']]},
    {id: 'auth', short: 'Authorise', time: 'about two seconds', who: ['bridge', 'processor', 'acquirer', 'network', 'issuer'], title: 'Authorisation: can this card pay 800 dollars?',
      text: 'The question goes from the bridge to the processor, the acquirer, the card network and the issuer, the guest\'s bank. The bank checks the account, holds the money, and its yes comes back the same way with an approval code.',
      demo: 'the processor ledger shows AUTH, the network and the approval code', wrong: 'the bank can say no, a decline: the link opens again for another card',
      seq: [[hops(2, 6), '$800 OK?', 'bridge'], [hops(6, 2), 'yes · held · code', 'ok']]},
    {id: 'capture', short: 'Capture', time: 'about two seconds', who: ['bridge', 'processor', 'acquirer', 'network', 'issuer'], title: 'Capture: take the money that is held',
      text: 'Along the same route. The hold becomes a charge: the hotel is now owed the money, but it has not arrived yet.',
      demo: 'the processor ledger shows CAPTURE', wrong: 'if the answer is lost, the bridge asks again with the same key, and the processor gives the first answer instead of charging twice',
      seq: [[hops(2, 6), 'take $800', 'bridge'], [hops(6, 2), 'captured', 'ok']]},
    {id: 'post', short: 'Post', time: 'seconds', who: ['bridge', 'pms'], title: 'The payment goes on the guest\'s bill',
      text: 'The bridge writes the payment onto the folio in OPERA, so the front desk sees the stay as paid.',
      demo: 'the folio shows the payment, its cashier and both references', wrong: 'if OPERA is down, the payment waits in a queue with its reason, and is posted once when OPERA is back: never lost, never twice',
      seq: [[bus(2, 1), 'payment on folio', 'bridge']]},
    {id: 'tell', short: 'Tell', time: 'seconds', who: ['bridge', 'hotelsys'], title: 'The hotel\'s systems are told',
      text: 'The bridge sends signed messages, webhooks: payment captured, payment posted. The receiver checks the signature before it trusts one.',
      demo: '"Events from the bridge" on the OPERA page', wrong: 'a receiver that is down gets the message again later, with growing waits',
      seq: [[down(2, 131), 'payment.captured', 'hotelsys']]},
    {id: 'settle', short: 'Settle', time: '1 to 3 days later', money: true, who: ['issuer', 'network', 'acquirer', 'bank', 'processor', 'bridge'], title: 'Settlement: the money really moves',
      text: 'From the guest\'s bank, through the card network, to the acquirer, which pays the hotel\'s bank account less the fees. A statement lists what was paid.',
      demo: 'the bridge downloads the settlement file', wrong: 'a payment can be missing from the statement, or settled short',
      seq: [[MONEY, '$800 less fees', 'money'], [hops(4, 2), 'statement', 'processor']]},
    {id: 'reconcile', short: 'Reconcile', time: 'each day', money: true, who: ['bridge', 'finance', 'processor'], title: 'Finance checks that it all agrees',
      text: 'What was charged, what was paid out and what is on the guests\' bills must match. The bridge matches the statement against its own records and names every difference.',
      demo: '"Reconcile": five captures, five matches, then a messy statement', wrong: 'each difference has a name finance can act on: missing, short, unknown',
      seq: [[hops(3, 2), 'statement', 'processor'], [down(2, 301), 'all matched', 'ok']]},
  ];

  // Draw the tiles.
  const svg = (tag, attrs, parent) => {
    const el = document.createElementNS(NS, tag);
    Object.entries(attrs).forEach(([k, v]) => el.setAttribute(k, v));
    if (parent) parent.appendChild(el);
    return el;
  };
  const tiles = $('#tiles');
  Object.entries(ACTORS).forEach(([id, a]) => {
    const row2 = a.i === undefined;
    const [x, y, w, h] = row2 ? [a.x, ROW2, 150, 76] : [16 + 170 * a.i, 40, 150, 130];
    const g = svg('g', {class: 'tile', 'data-actor': id, 'data-testid': 'actor-' + id, transform: `translate(${x} ${y})`, tabindex: '0', role: 'button',
      'aria-label': `${a.name}, ${a.sub}`, style: `--k:${COLOR[id]}`}, tiles);
    svg('rect', {class: 'c', width: w, height: h, rx: 12}, g);
    const s = 28;
    const ico = svg('g', {class: 'ico'}, g);
    svg('rect', {x: 10, y: 10, width: s, height: s, rx: 8, fill: COLOR[id]}, ico);
    svg('use', {href: `/icons.svg#i-${a.icon}`, x: 15, y: 15, width: 18, height: 18}, ico);
    svg('text', {class: 't', x: 46, y: row2 ? 23 : 29}, g).textContent = a.name;
    // a row-1 tile has the width for its subtitle under the icon; a row-2 tile keeps it beside the icon, short
    svg('text', {class: 's', x: row2 ? 46 : 12, y: row2 ? 40 : 58}, g).textContent = a.sub;
    (a.role || []).forEach((line, n) => { svg('text', {class: 'r', x: 12, y: 77 + 16 * n}, g).textContent = line; });
    const [kind, label] = a.badge;
    const bd = svg('g', {class: 'bd ' + kind, transform: `translate(10 ${row2 ? 52 : 106})`}, g);
    svg('rect', {width: w - 20, height: 16, rx: 8}, bd);
    svg('text', {x: (w - 20) / 2, y: 11.5}, bd).textContent = label;
  });

  // The step chips and the panel.
  $('.steps').innerHTML = STEPS.map((s, n) => `<button data-step="${s.id}" class="${s.money ? 'money' : ''}" data-testid="step-${s.id}"><b>${n + 1}</b>${esc(s.short)}</button>`).join('');
  const panel = (kick, title, text, facts) => {
    $('[data-testid=panel-kick]').textContent = kick;
    $('[data-testid=panel-title]').textContent = title;
    $('[data-testid=panel-text]').textContent = text;
    $('[data-testid=panel-facts]').innerHTML = facts.map(([k, v]) => `<span><b>${esc(k)}</b> ${esc(v)}</span>`).join('');
  };

  // Packets: a pill with a label, moved along its route frame by frame; the sequence repeats while its step is shown.
  let run = 0;
  const layer = $('#packets');
  const length = r => r.slice(1).reduce((sum, p, k) => sum + Math.hypot(p[0] - r[k][0], p[1] - r[k][1]), 0);
  const pointAt = (r, d) => {
    for (let k = 1; k < r.length; k++) {
      const seg = Math.hypot(r[k][0] - r[k - 1][0], r[k][1] - r[k - 1][1]);
      if (d <= seg) return [r[k - 1][0] + (r[k][0] - r[k - 1][0]) * d / seg, r[k - 1][1] + (r[k][1] - r[k - 1][1]) * d / seg];
      d -= seg;
    }
    return r[r.length - 1];
  };
  const tileAt = (x, y) => {
    if (Math.abs(y - TOP) < 3) return Object.keys(ACTORS).find(id => ACTORS[id].i !== undefined && x >= 16 + 170 * ACTORS[id].i && x <= 166 + 170 * ACTORS[id].i);
    if (Math.abs(y - ROW2) < 3) return Object.keys(ACTORS).find(id => ACTORS[id].i === undefined && x >= ACTORS[id].x && x <= ACTORS[id].x + 150);
    return null;
  };
  function play(seq) {
    const id = ++run;
    layer.innerHTML = '';
    document.querySelectorAll('.tile.hit').forEach(t => t.classList.remove('hit'));
    if (!seq) return;
    let t = 0;
    const plan = seq.map(([route, label, color]) => {
      const len = length(route), dur = Math.max(1100, len * 2.6), start = t;
      t += dur + 350;
      const g = svg('g', {class: 'pkt', opacity: 0}, layer);
      const w = 18 + label.length * 6.9;
      svg('rect', {x: -w / 2, y: -11, width: w, height: 22, rx: 11, fill: COLOR[color]}, g);
      svg('text', {x: 0, y: 0}, g).textContent = label;
      return {route, len, dur, start, g};
    });
    const total = t + 1400;
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) { // no motion: every message shown where it arrives
      plan.forEach(m => { const [x, y] = pointAt(m.route, m.len); m.g.setAttribute('transform', `translate(${x} ${y})`); m.g.setAttribute('opacity', 1); });
      return;
    }
    const t0 = performance.now();
    const frame = now => {
      if (id !== run) return;
      const at = (now - t0) % total;
      const hit = new Set();
      plan.forEach(m => {
        const p = (at - m.start) / m.dur;
        if (p < 0 || p > 1.25) { m.g.setAttribute('opacity', 0); return; }
        const [x, y] = pointAt(m.route, Math.min(1, p) * m.len);
        m.g.setAttribute('transform', `translate(${x} ${y})`);
        m.g.setAttribute('opacity', 1);
        const tile = tileAt(x, y);
        if (tile) hit.add(tile);
      });
      document.querySelectorAll('.tile').forEach(el => el.classList.toggle('hit', hit.has(el.dataset.actor)));
      requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  }

  // Render whatever body[data-step] says.
  function render() {
    const state = document.body.dataset.step || '';
    const step = STEPS.find(s => s.id === state);
    const actor = state.startsWith('actor:') ? state.slice(6) : null;
    const lit = step ? step.who : actor ? [actor] : [];
    document.querySelectorAll('.tile').forEach(el => el.classList.toggle('on', lit.includes(el.dataset.actor)));
    document.querySelectorAll('.steps button').forEach(b => b.classList.toggle('on', b.dataset.step === state));
    document.body.classList.toggle('chain-on', ['acquirer', 'network', 'issuer'].some(id => lit.includes(id)));
    if (step) {
      const n = STEPS.indexOf(step) + 1;
      panel(`STEP ${n} OF ${STEPS.length} · ${step.time.toUpperCase()}`, step.title, step.text, [['In the demo:', step.demo], ['If it goes wrong:', step.wrong]]);
      play(step.seq);
    } else if (actor && ACTORS[actor]) {
      const a = ACTORS[actor];
      panel(`WHO IS WHO · ${a.badge[1]}`, `${a.name}: ${a.sub}`, a.life, [['Sees of the card:', a.card], ['In this demo:', a.demo]]);
      play(null);
    } else {
      panel('NINE STEPS · ELEVEN PARTIES', 'One payment, from booking to the books',
        'Steps 1 to 7 are messages: they take seconds. Steps 8 and 9 are the money and the checking: they take days. Press Play, or click a party to see who it is.',
        [['Messages:', 'seconds'], ['Money:', '1 to 3 days'], ['In the demo:', 'one simulator plays the processor and the three parties behind it']]);
      play(null);
    }
  }
  new MutationObserver(render).observe(document.body, {attributes: true, attributeFilter: ['data-step']});

  // Controls: chips, Back, Play, Next, a click on a party, and the arrow keys.
  let timer = null;
  const go = id => { if (id) document.body.dataset.step = id; else delete document.body.dataset.step; };
  const index = () => STEPS.findIndex(s => s.id === document.body.dataset.step);
  const stop = () => { clearInterval(timer); timer = null; $('[data-testid=play]').textContent = '▶ Play'; };
  const next = () => { const i = index(); if (i === STEPS.length - 1) { stop(); return; } go(STEPS[i + 1].id); };
  const ACT = {
    back: () => { stop(); const i = index(); go(i > 0 ? STEPS[i - 1].id : null); },
    next: () => { stop(); next(); },
    play: () => {
      if (timer) { stop(); return; }
      if (index() === -1 || index() === STEPS.length - 1) go(STEPS[0].id);
      $('[data-testid=play]').textContent = '❚❚ Pause';
      timer = setInterval(next, 6500);
    },
  };
  document.addEventListener('click', e => {
    const chip = e.target.closest('.steps button');
    if (chip) { stop(); go(chip.dataset.step); return; }
    const act = e.target.closest('[data-act]');
    if (act) { ACT[act.dataset.act](); return; }
    const tile = e.target.closest('.tile');
    if (tile) { stop(); go('actor:' + tile.dataset.actor); }
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'ArrowRight') ACT.next();
    else if (e.key === 'ArrowLeft') ACT.back();
    else if (e.key === 'Enter' && e.target.classList && e.target.classList.contains('tile')) { stop(); go('actor:' + e.target.dataset.actor); }
  });
  render();
})();
