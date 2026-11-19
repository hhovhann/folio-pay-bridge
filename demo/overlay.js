// The narrated video's overlay. demo/recorder.py adds it to every page before the page's own scripts: a top bar that names the system on
// screen and the chapter, captions, a cursor with click ripples, and full-screen cards. It is drawn into the page, so it is in the video,
// and it never takes a click (pointer-events: none), so the script clicks exactly what a person would.
(() => {
  if (window.__demo) return;
  const ICON = {
    bridge: '<path d="M2 17h20M4 17v-5M20 17v-5M2 12h20"/><path d="M4 12c2.5-4 13.5-4 16 0"/><path d="M9 12v5M15 12v5"/>',
    hotel: '<path d="M4 21V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17"/><path d="M16 9h3a1 1 0 0 1 1 1v11M2 21h20"/><path d="M8 7h1M11 7h1M8 11h1M11 11h1M8 15h1M11 15h1"/>',
    card: '<rect x="2" y="5" width="20" height="14" rx="2.5"/><path d="M2 10h20M6 15h4"/>',
    check: '<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>',
    layers: '<path d="m12 3 9 5-9 5-9-5z"/><path d="m3 13 9 5 9-5"/>',
    code: '<path d="m8 7-5 5 5 5M16 7l5 5-5 5M14 4l-4 16"/>',
    shield: '<path d="M12 3 20 6v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"/><path d="m9 12 2 2 4-4"/>',
    alert: '<path d="M12 3 2 20h20z"/><path d="M12 9v5M12 17h.01"/>',
  };
  // PostgreSQL's elephant, from Simple Icons 13.21.0 (CC0-1.0); the name and logo are the PostgreSQL Community Association's.
  const PG = '<path d="M23.5594 14.7228a.5269.5269 0 0 0-.0563-.1191c-.139-.2632-.4768-.3418-1.0074-.2321-1.6533.3411-2.2935.1312-2.5256-.0191 1.342-2.0482 2.445-4.522 3.0411-6.8297.2714-1.0507.7982-3.5237.1222-4.7316a1.5641 1.5641 0 0 0-.1509-.235C21.6931.9086 19.8007.0248 17.5099.0005c-1.4947-.0158-2.7705.3461-3.1161.4794a9.449 9.449 0 0 0-.5159-.0816 8.044 8.044 0 0 0-1.3114-.1278c-1.1822-.0184-2.2038.2642-3.0498.8406-.8573-.3211-4.7888-1.645-7.2219.0788C.9359 2.1526.3086 3.8733.4302 6.3043c.0409.818.5069 3.334 1.2423 5.7436.4598 1.5065.9387 2.7019 1.4334 3.582.553.9942 1.1259 1.5933 1.7143 1.7895.4474.1491 1.1327.1441 1.8581-.7279.8012-.9635 1.5903-1.8258 1.9446-2.2069.4351.2355.9064.3625 1.39.3772a.0569.0569 0 0 0 .0004.0041 11.0312 11.0312 0 0 0-.2472.3054c-.3389.4302-.4094.5197-1.5002.7443-.3102.064-1.1344.2339-1.1464.8115-.0025.1224.0329.2309.0919.3268.2269.4231.9216.6097 1.015.6331 1.3345.3335 2.5044.092 3.3714-.6787-.017 2.231.0775 4.4174.3454 5.0874.2212.5529.7618 1.9045 2.4692 1.9043.2505 0 .5263-.0291.8296-.0941 1.7819-.3821 2.5557-1.1696 2.855-2.9059.1503-.8707.4016-2.8753.5388-4.1012.0169-.0703.0357-.1207.057-.1362.0007-.0005.0697-.0471.4272.0307a.3673.3673 0 0 0 .0443.0068l.2539.0223.0149.001c.8468.0384 1.9114-.1426 2.5312-.4308.6438-.2988 1.8057-1.0323 1.5951-1.6698zM2.371 11.8765c-.7435-2.4358-1.1779-4.8851-1.2123-5.5719-.1086-2.1714.4171-3.6829 1.5623-4.4927 1.8367-1.2986 4.8398-.5408 6.108-.13-.0032.0032-.0066.0061-.0098.0094-2.0238 2.044-1.9758 5.536-1.9708 5.7495-.0002.0823.0066.1989.0162.3593.0348.5873.0996 1.6804-.0735 2.9184-.1609 1.1504.1937 2.2764.9728 3.0892.0806.0841.1648.1631.2518.2374-.3468.3714-1.1004 1.1926-1.9025 2.1576-.5677.6825-.9597.5517-1.0886.5087-.3919-.1307-.813-.5871-1.2381-1.3223-.4796-.839-.9635-2.0317-1.4155-3.5126zm6.0072 5.0871c-.1711-.0428-.3271-.1132-.4322-.1772.0889-.0394.2374-.0902.4833-.1409 1.2833-.2641 1.4815-.4506 1.9143-1.0002.0992-.126.2116-.2687.3673-.4426a.3549.3549 0 0 0 .0737-.1298c.1708-.1513.2724-.1099.4369-.0417.156.0646.3078.26.3695.4752.0291.1016.0619.2945-.0452.4444-.9043 1.2658-2.2216 1.2494-3.1676 1.0128zm2.094-3.988-.0525.141c-.133.3566-.2567.6881-.3334 1.003-.6674-.0021-1.3168-.2872-1.8105-.8024-.6279-.6551-.9131-1.5664-.7825-2.5004.1828-1.3079.1153-2.4468.079-3.0586-.005-.0857-.0095-.1607-.0122-.2199.2957-.2621 1.6659-.9962 2.6429-.7724.4459.1022.7176.4057.8305.928.5846 2.7038.0774 3.8307-.3302 4.7363-.084.1866-.1633.3629-.2311.5454zm7.3637 4.5725c-.0169.1768-.0358.376-.0618.5959l-.146.4383a.3547.3547 0 0 0-.0182.1077c-.0059.4747-.054.6489-.115.8693-.0634.2292-.1353.4891-.1794 1.0575-.11 1.4143-.8782 2.2267-2.4172 2.5565-1.5155.3251-1.7843-.4968-2.0212-1.2217a6.5824 6.5824 0 0 0-.0769-.2266c-.2154-.5858-.1911-1.4119-.1574-2.5551.0165-.5612-.0249-1.9013-.3302-2.6462.0044-.2932.0106-.5909.019-.8918a.3529.3529 0 0 0-.0153-.1126 1.4927 1.4927 0 0 0-.0439-.208c-.1226-.4283-.4213-.7866-.7797-.9351-.1424-.059-.4038-.1672-.7178-.0869.067-.276.1831-.5875.309-.9249l.0529-.142c.0595-.16.134-.3257.213-.5012.4265-.9476 1.0106-2.2453.3766-5.1772-.2374-1.0981-1.0304-1.6343-2.2324-1.5098-.7207.0746-1.3799.3654-1.7088.5321a5.6716 5.6716 0 0 0-.1958.1041c.0918-1.1064.4386-3.1741 1.7357-4.4823a4.0306 4.0306 0 0 1 .3033-.276.3532.3532 0 0 0 .1447-.0644c.7524-.5706 1.6945-.8506 2.802-.8325.4091.0067.8017.0339 1.1742.081 1.939.3544 3.2439 1.4468 4.0359 2.3827.8143.9623 1.2552 1.9315 1.4312 2.4543-1.3232-.1346-2.2234.1268-2.6797.779-.9926 1.4189.543 4.1729 1.2811 5.4964.1353.2426.2522.4522.2889.5413.2403.5825.5515.9713.7787 1.2552.0696.087.1372.1714.1885.245-.4008.1155-1.1208.3825-1.0552 1.717-.0123.1563-.0423.4469-.0834.8148-.0461.2077-.0702.4603-.0994.7662zm.8905-1.6211c-.0405-.8316.2691-.9185.5967-1.0105a2.8566 2.8566 0 0 0 .135-.0406 1.202 1.202 0 0 0 .1342.103c.5703.3765 1.5823.4213 3.0068.1344-.2016.1769-.5189.3994-.9533.6011-.4098.1903-1.0957.333-1.7473.3636-.7197.0336-1.0859-.0807-1.1721-.151zm.5695-9.2712c-.0059.3508-.0542.6692-.1054 1.0017-.055.3576-.112.7274-.1264 1.1762-.0142.4368.0404.8909.0932 1.3301.1066.887.216 1.8003-.2075 2.7014a3.5272 3.5272 0 0 1-.1876-.3856c-.0527-.1276-.1669-.3326-.3251-.6162-.6156-1.1041-2.0574-3.6896-1.3193-4.7446.3795-.5427 1.3408-.5661 2.1781-.463zm.2284 7.0137a12.3762 12.3762 0 0 0-.0853-.1074l-.0355-.0444c.7262-1.1995.5842-2.3862.4578-3.4385-.0519-.4318-.1009-.8396-.0885-1.2226.0129-.4061.0666-.7543.1185-1.0911.0639-.415.1288-.8443.1109-1.3505.0134-.0531.0188-.1158.0118-.1902-.0457-.4855-.5999-1.938-1.7294-3.253-.6076-.7073-1.4896-1.4972-2.6889-2.0395.5251-.1066 1.2328-.2035 2.0244-.1859 2.0515.0456 3.6746.8135 4.8242 2.2824a.908.908 0 0 1 .0667.1002c.7231 1.3556-.2762 6.2751-2.9867 10.5405zm-8.8166-6.1162c-.025.1794-.3089.4225-.6211.4225a.5821.5821 0 0 1-.0809-.0056c-.1873-.026-.3765-.144-.5059-.3156-.0458-.0605-.1203-.178-.1055-.2844.0055-.0401.0261-.0985.0925-.1488.1182-.0894.3518-.1226.6096-.0867.3163.0441.6426.1938.6113.4186zm7.9305-.4114c.0111.0792-.049.201-.1531.3102-.0683.0717-.212.1961-.4079.2232a.5456.5456 0 0 1-.075.0052c-.2935 0-.5414-.2344-.5607-.3717-.024-.1765.2641-.3106.5611-.352.297-.0414.6111.0088.6356.1851z"/>';
  const icon = (name, size = 18) => name === 'pg'
    ? `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="currentColor">${PG}</svg>`
    : `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${ICON[name]}</svg>`;
  const COLOR = {bridge: '#2563eb', arch: '#4f46e5', pms: '#9f1239', psp: '#0f766e', tests: '#15803d', db: '#336791'};
  const system = () => {
    if (location.protocol === 'file:') return {k: 'tests', name: 'Test report', tag: 'JaCoCo, from the last build', icon: 'check'};
    if (location.port === '8090') return {k: 'pms', name: 'OPERA front desk', tag: 'PMS simulator, OHIP-shaped', icon: 'hotel'};
    if (location.port === '8091') return {k: 'psp', name: 'Card processor', tag: 'acquirer simulator', icon: 'card'};
    if (location.pathname.endsWith('architecture.html')) return {k: 'arch', name: 'Architecture', tag: 'how it fits together', icon: 'layers'};
    return {k: 'bridge', name: 'Folio Pay Bridge', tag: 'operator console', icon: 'bridge'};
  };
  const FONT = '-apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif';
  const CSS = `
html{padding-top:46px!important;scroll-padding-top:58px}
#demo-bar,#demo-caption,#demo-cursor,.demo-ripple,#demo-card{pointer-events:none;font-family:${FONT};box-sizing:border-box}
#demo-bar{position:fixed;top:0;left:0;right:0;height:46px;z-index:2147483600;display:flex;align-items:center;gap:14px;padding:0 14px 0 8px;
  background:linear-gradient(90deg,#0a0f1f,#121b36);color:#e5e9f5;font-size:14px;font-weight:600;line-height:1;box-shadow:0 2px 14px rgba(0,0,0,.28)}
#demo-bar .sys{display:flex;align-items:center;gap:9px;padding:5px 12px 5px 5px;border-radius:9px;background:var(--demo-c);color:#fff;white-space:nowrap}
#demo-bar .sys i{display:grid;place-items:center;width:28px;height:28px;border-radius:7px;background:rgba(255,255,255,.2)}
#demo-bar .sys small{font-weight:500;opacity:.88;font-size:12px}
#demo-bar .chap{display:flex;align-items:center;gap:10px;white-space:nowrap;overflow:hidden;min-width:0}
#demo-bar .chap b{color:#8f9dc2;font-size:11.5px;letter-spacing:.08em;font-weight:700}
#demo-bar .prog{margin-left:auto;display:flex;gap:3px}
#demo-bar .prog span{width:13px;height:5px;border-radius:3px;background:#28324f}
#demo-bar .prog span.done{background:#56648c}
#demo-bar .prog span.on{background:var(--demo-c);box-shadow:0 0 8px var(--demo-c)}
#demo-bar .brand{display:flex;align-items:center;gap:7px;margin-left:10px;color:#fff;font-weight:700;letter-spacing:.01em;white-space:nowrap}
#demo-bar .brand i{display:grid;place-items:center;width:24px;height:24px;border-radius:6px;background:linear-gradient(135deg,#2563eb,#7c3aed)}
#demo-caption{position:fixed;z-index:2147483601;left:0;right:0;margin:0 auto;width:max-content;max-width:1080px;bottom:20px;display:none;
  background:rgba(9,13,26,.9);color:#fff;font-size:21px;font-weight:600;line-height:1.38;padding:10px 22px 10px 18px;border-radius:12px;
  border-left:5px solid var(--demo-c);text-align:center;white-space:pre-line;box-shadow:0 10px 30px rgba(0,0,0,.3)}
#demo-cursor{position:fixed;z-index:2147483605;width:24px;height:24px;margin:-12px 0 0 -12px;border-radius:50%;left:-60px;top:-60px;
  background:rgba(255,255,255,.35);border:3px solid var(--demo-c);box-shadow:0 0 0 2px rgba(255,255,255,.85),0 4px 14px rgba(0,0,0,.35);
  transition:left .45s cubic-bezier(.3,.7,.2,1),top .45s cubic-bezier(.3,.7,.2,1)}
.demo-ripple{position:fixed;z-index:2147483604;width:18px;height:18px;margin:-9px 0 0 -9px;border-radius:50%;border:3px solid var(--demo-c);
  animation:demo-ripple .75s ease-out forwards}
@keyframes demo-ripple{to{transform:scale(4.5);opacity:0}}
#demo-card{position:fixed;inset:0;z-index:2147483599;display:grid;place-items:center;opacity:0;transition:opacity .6s;color:#e8ecf8;
  background:radial-gradient(1100px 620px at 18% 8%,#1d2b62 0%,#0b1124 55%,#060912 100%)}
#demo-card.on{opacity:1}
#demo-card.panel{inset:auto 18px auto auto;top:64px;width:520px;border-radius:18px;padding:22px 24px;background:rgba(9,13,26,.94);
  box-shadow:0 20px 60px rgba(0,0,0,.45);border:1px solid rgba(255,255,255,.1);place-items:stretch}
#demo-card .kick{font-size:12.5px;letter-spacing:.16em;font-weight:700;color:#8ea0d6;margin-bottom:10px}
#demo-card h1{display:flex;align-items:center;gap:16px;margin:0;font-size:52px;font-weight:800;letter-spacing:-.02em;color:#fff}
#demo-card h1 i{display:grid;place-items:center;width:64px;height:64px;border-radius:16px;background:linear-gradient(135deg,#2563eb,#7c3aed);box-shadow:0 12px 30px -8px #4f46e5}
#demo-card h2{margin:0 0 14px;font-size:22px;color:#fff}
#demo-card .sub{margin:14px 0 30px;font-size:20px;color:#b8c3e2;max-width:900px;line-height:1.45}
#demo-card .topo{display:grid;grid-template-columns:250px 60px 250px 60px 250px;grid-template-rows:auto 34px auto;align-items:center}
#demo-card .n{display:flex;align-items:center;gap:12px;padding:14px 16px;border-radius:14px;background:rgba(255,255,255,.05);
  border:1px solid rgba(255,255,255,.12);opacity:.16;transform:translateY(10px) scale(.97);transition:all .7s cubic-bezier(.3,.7,.2,1)}
#demo-card .n.on{opacity:1;transform:none;border-color:var(--k);box-shadow:0 0 0 1px var(--k),0 14px 34px -12px var(--k)}
#demo-card .n i{flex:none;display:grid;place-items:center;width:46px;height:46px;border-radius:12px;background:var(--k);color:#fff}
#demo-card .n b{display:block;font-size:16.5px;color:#fff}#demo-card .n small{color:#a9b4d0;font-size:12.5px}
#demo-card .ln{height:3px;margin:0 6px;border-radius:2px;background:repeating-linear-gradient(90deg,#6d7fb3 0 8px,transparent 8px 14px);
  background-size:28px 3px;animation:demo-flow 1s linear infinite;opacity:.7}
#demo-card .lv{width:3px;height:100%;justify-self:center;background:repeating-linear-gradient(180deg,#6d7fb3 0 8px,transparent 8px 14px);
  background-size:3px 28px;animation:demo-flowv 1s linear infinite;opacity:.7}
@keyframes demo-flow{to{background-position:28px 0}}@keyframes demo-flowv{to{background-position:0 28px}}
#demo-card .foot{margin-top:30px;display:flex;gap:10px;flex-wrap:wrap}
#demo-card .chip{display:inline-flex;align-items:center;gap:7px;padding:7px 13px;border-radius:99px;background:rgba(255,255,255,.07);
  border:1px solid rgba(255,255,255,.14);font-size:14.5px;color:#dfe5f7;transition:all .5s;opacity:1}
#demo-card .chip[data-r]{opacity:.15;transform:translateY(6px)}#demo-card .chip.on{opacity:1;transform:none;border-color:#22c55e;background:rgba(34,197,94,.12)}
#demo-card .chip svg{color:#4ade80}
#demo-card .stats{display:grid;grid-template-columns:1fr 1fr;gap:12px}
#demo-card .st{padding:14px 16px;border-radius:14px;background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.1);opacity:.15;transition:all .6s}
#demo-card .st.on{opacity:1;border-color:var(--k);box-shadow:0 10px 26px -14px var(--k)}
#demo-card .st b{display:block;font-size:34px;font-weight:800;color:#fff;letter-spacing:-.01em}
#demo-card .st span{display:block;font-size:14px;color:#dfe5f7;font-weight:600}#demo-card .st small{color:#94a3c7;font-size:12px}
#demo-card .bar{height:6px;border-radius:3px;background:rgba(255,255,255,.1);margin:8px 0 6px;overflow:hidden}
#demo-card .bar u{display:block;height:100%;width:0;background:var(--k);transition:width 1.4s cubic-bezier(.3,.7,.2,1)}
#demo-card .st.on .bar u{width:var(--w)}
#demo-card .note{margin-top:18px;font-size:15px;color:#9fb0d6;max-width:860px;line-height:1.5}
#demo-card .pr{display:grid;grid-template-columns:1fr 40px 1fr;gap:10px 0;align-items:center;width:1000px}
#demo-card .pr>div{display:flex;align-items:center;gap:12px;padding:12px 16px;border-radius:12px;font-size:16.5px;opacity:.12;transform:translateY(8px);transition:all .6s}
#demo-card .pr .p{background:rgba(239,68,68,.1);border:1px solid rgba(239,68,68,.4);color:#fecaca}
#demo-card .pr .s{background:rgba(34,197,94,.1);border:1px solid rgba(34,197,94,.4);color:#dcfce7}
#demo-card .pr .ar{justify-content:center;padding:0;border:0;background:none;color:#64748b;font-size:22px}
#demo-card .pr .on{opacity:1;transform:none}#demo-card .pr .p svg{color:#f87171}#demo-card .pr .s svg{color:#4ade80}
#demo-card .pr small{display:block;font-size:12.5px;opacity:.75}
#demo-card .repo{margin-top:22px;display:inline-flex;align-items:center;gap:10px;font:600 19px ui-monospace,Menlo,monospace;color:#fff;
  padding:10px 16px;border-radius:12px;background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.14)}`;

  let state = {caption: '', chapter: null};
  const el = id => document.getElementById(id);
  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
  const node = (k, ic, title, sub, r) => `<div class="n" data-r="${r}" style="--k:${COLOR[k]}"><i>${icon(ic, 26)}</i><div><b>${title}</b><small>${sub}</small></div></div>`;

  function render() {
    const bar = el('demo-bar');
    if (!bar) return;
    const s = system(), c = COLOR[s.k];
    for (const id of ['demo-bar', 'demo-caption', 'demo-cursor']) el(id).style.setProperty('--demo-c', c);
    const ch = state.chapter;
    const prog = ch ? Array.from({length: ch[1]}, (_, i) => `<span class="${i + 1 < ch[0] ? 'done' : i + 1 === ch[0] ? 'on' : ''}"></span>`).join('') : '';
    bar.innerHTML = `<div class="sys"><i>${icon(s.icon)}</i>${esc(s.name)} <small>${esc(s.tag)}</small></div>`
      + (ch ? `<div class="chap"><b>${String(ch[0]).padStart(2, '0')} / ${ch[1]}</b>${esc(ch[2])}</div>` : '<div class="chap"></div>')
      + `<div class="prog">${prog}</div><div class="brand"><i>${icon('bridge', 15)}</i>Folio Pay Bridge</div>`;
    const cap = el('demo-caption');
    cap.textContent = state.caption;
    cap.style.display = state.caption ? 'block' : 'none';
  }

  function mount() {
    if (el('demo-bar')) return;
    const style = document.createElement('style');
    style.id = 'demo-style';
    style.textContent = CSS;
    document.head.appendChild(style);
    for (const id of ['demo-bar', 'demo-caption', 'demo-cursor']) {
      const d = document.createElement('div');
      d.id = id;
      document.documentElement.appendChild(d);
    }
    render();
  }

  const CARDS = {
    // The four containers, lit one by one as the voice names them.
    title: () => `<div><div class="kick">A WORK SAMPLE · SYNTHETIC DATA · REAL NETWORK CALLS</div>
      <h1><i>${icon('bridge', 38)}</i>Folio Pay Bridge</h1>
      <p class="sub">Hotel payments end to end: a booking in the PMS, the guest's card, the processor, and the payment on the guest's folio.</p>
      <div class="topo">${node('pms', 'hotel', 'OPERA PMS', 'simulator, OHIP-shaped', 3)}<div class="ln"></div>${node('bridge', 'bridge', 'Payment bridge', 'Java 17 · Spring Boot', 1)}
        <div class="ln"></div>${node('psp', 'card', 'Card processor', 'simulator', 4)}<div></div><div></div><div class="lv"></div><div></div><div></div>
        <div></div><div></div>${node('db', 'pg', 'PostgreSQL 16', 'ciphertext only', 2)}<div></div><div></div></div></div>`,
    // Numbers read by the recorder from the build's own reports, then shown here.
    proof: d => `<div class="kick">MEASURED BY THE BUILD, NOT ESTIMATED</div><h2>The proof</h2><div class="stats">
      ${[['tests', d.tests, 'backend tests', d.testsNote, 100], ['lines', d.lines + '%', 'line coverage', 'gate: 99%', d.lines],
         ['branches', d.branches + '%', 'branch coverage', 'gate: 90%', d.branches], ['mutations', d.mutations, 'bugs put back on purpose', 'every one caught by its test', 100]]
        .map(([k, v, t, s, w], i) => `<div class="st" data-r="${i + 1}" style="--k:${['#22c55e', '#38bdf8', '#a78bfa', '#f59e0b'][i]};--w:${w}%"><b>${esc(v)}</b><span>${t}</span><div class="bar"><u></u></div><small>${s}</small></div>`).join('')}
      </div>`,
    // The problem each part of the bridge exists for, lit as the voice names it, next to the fix the video then shows.
    problems: d => `<div><div class="kick">THE PROBLEM · THREE SYSTEMS MUST AGREE ON ONE PAYMENT</div><h2 style="font-size:34px;margin-bottom:22px">What goes wrong without a bridge</h2>
      <div class="pr">${d.rows.map(([p, ps, s, ss], i) => `<div class="p" data-r="${i + 1}">${icon('alert', 22)}<div>${esc(p)}<small>${esc(ps)}</small></div></div>`
        + `<div class="ar" data-r="${i + 1}">→</div><div class="s" data-r="${i + 1}">${icon('check', 22)}<div>${esc(s)}<small>${esc(ss)}</small></div></div>`).join('')}</div></div>`,
    // The close: every case the video showed, ticked as the voice lists it.
    end: d => `<div><div class="kick">WHAT YOU JUST SAW, AGAINST SEPARATE SYSTEMS OVER HTTP</div>
      <h1><i>${icon('bridge', 38)}</i>Folio Pay Bridge</h1><div class="foot">
      ${d.cases.map((c, i) => `<span class="chip" data-r="${i + 1}">${icon('check', 17)}${esc(c)}</span>`).join('')}</div>
      <p class="note">The PMS is a simulator written in the shape of Oracle's published OHIP contract: it is not Oracle software, and the
      real thing needs Oracle's sandbox. The processor is a simulator too. All data is synthetic.</p>
      <div class="repo">${icon('code', 20)}${esc(d.repo)}</div></div>`,
  };

  window.__demo = {
    set(s) { state = s; render(); },
    cursor(x, y) { const c = el('demo-cursor'); if (c) { c.style.left = x + 'px'; c.style.top = y + 'px'; } },
    ripple() {
      const c = el('demo-cursor');
      if (!c) return;
      const r = document.createElement('div');
      r.className = 'demo-ripple';
      r.style.cssText = `left:${c.style.left};top:${c.style.top}`;
      r.style.setProperty('--demo-c', getComputedStyle(c).getPropertyValue('--demo-c'));
      document.documentElement.appendChild(r);
      setTimeout(() => r.remove(), 900);
    },
    card(kind, data, panel) {
      let card = el('demo-card');
      if (!card) {
        card = document.createElement('div');
        card.id = 'demo-card';
        document.documentElement.appendChild(card);
      }
      card.className = panel ? 'panel' : '';
      card.innerHTML = CARDS[kind](data || {});
      requestAnimationFrame(() => requestAnimationFrame(() => card.classList.add('on')));
    },
    reveal(n) { document.querySelectorAll(`#demo-card [data-r="${n}"]`).forEach(e => e.classList.add('on')); },
    hideCard() { const c = el('demo-card'); if (c) c.classList.remove('on'); },
    // architecture.html highlights one step of the flow while the voice explains it (its CSS reads body[data-step]).
    step(n) { if (n) document.body.dataset.step = n; else delete document.body.dataset.step; },
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
})();
