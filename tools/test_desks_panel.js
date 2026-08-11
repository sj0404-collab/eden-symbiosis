// Check that the panel renders both desks and never asks for a credential.
//
// Run: node tools/test_desks_panel.js
//
// The card is driven entirely by two files the running desks publish, so the
// GitHub API is stubbed here and loadDesks() is executed against it. That
// catches the failures this card actually had: an entry hours old still being
// advertised, and a terminal button appearing on the desk that has no
// terminal.
const fs=require('fs');
const path=require('path');
const html=fs.readFileSync(path.join(__dirname,'..','docs','index.html'),'utf8');
const js=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]).join('\n');
const start=js.indexOf('let deskSig');
const end=js.indexOf('function expandDesk');
const body=js.slice(start,end);
const els={};
global.$=id=>els[id]||(els[id]={innerHTML:'',style:{}});
global.esc=s=>String(s==null?'':s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
global.tok=()=>'t';
const now=new Date().toISOString();
global.api=async path=>{
  const slot=path.includes('linux')?'linux':'windows';
  const data=slot==='linux'
    ? {kind:'Стол Linux',url:'https://l.example',deskUrl:'https://l.example/vnc.html?autoconnect=1',terminal:'https://l.example/term/',startedAt:now,state:'live',runId:'42'}
    : {kind:'Стол Windows',url:'https://w.example',deskUrl:'https://w.example/',startedAt:now,state:'live',runId:'42'};
  return {content:Buffer.from(JSON.stringify(data),'utf8').toString('base64')};
};
global.CTL=()=>'https://api.github.com/repos/x/y';
global.DESKS={}; global.deskOpen=null;
eval(body+'\nglobal.loadDesks=loadDesks;');
(async()=>{
  await loadDesks();
  const out=els['desks'].innerHTML;
  const checks={
    'обе плитки': (out.match(/class="tile"|Linux<\/b>|>Windows<\/b>/g)||[]).length>0,
    'Linux заголовок': out.includes('>Linux<'),
    'Windows заголовок': out.includes('>Windows<'),
    'без пароля': out.includes('без пароля'),
    'iframe Linux': out.includes('https://l.example/vnc.html'),
    'iframe Windows': out.includes('https://w.example/'),
    'терминал только у Linux': out.includes('l.example/term/') && !out.includes('w.example/term/'),
    'карточка видима': els['desks-card'].style.display==='',
    'нет пароля в разметке': !/пароль[^\s]*\s*:/i.test(out)
  };
  let bad=0;
  for(const [k,v] of Object.entries(checks)){ console.log((v?'ok  ':'FAIL')+' '+k); if(!v)bad++; }
  // The bug the phone showed: refresh() every 30s rewrote innerHTML, which
  // replaces the iframes, which makes both desks reconnect - black for a
  // couple of seconds, on a timer. A second load with identical data must not
  // touch the markup at all.
  const first = els['desks'].innerHTML;
  els['desks'].innerHTML = 'SENTINEL';
  await loadDesks();
  console.log((els['desks'].innerHTML === 'SENTINEL' ? 'ok  ' : 'FAIL') +
              ' повторный опрос не пересоздаёт iframe');
  if (els['desks'].innerHTML !== 'SENTINEL') bad++;
  els['desks'].innerHTML = first;

  // A changed address must still redraw, or a restarted desk would show a
  // dead iframe forever.
  global.api = async path => {
    const slot = path.includes('linux') ? 'linux' : 'windows';
    const data = slot === 'linux'
      ? {kind:'L',url:'https://NEW.example',deskUrl:'https://NEW.example/vnc.html',terminal:'https://NEW.example/term/',startedAt:now,state:'live',runId:'42'}
      : {kind:'W',url:'https://w.example',deskUrl:'https://w.example/',startedAt:now,state:'live',runId:'42'};
    return {content:Buffer.from(JSON.stringify(data),'utf8').toString('base64')};
  };
  await loadDesks();
  const moved = els['desks'].innerHTML.includes('NEW.example');
  console.log((moved ? 'ok  ' : 'FAIL') + ' новый адрес перерисовывается');
  if (!moved) bad++;

  // stale entries must vanish
  const old=new Date(Date.now()-400*60000).toISOString();
  global.api=async()=>({content:Buffer.from(JSON.stringify({state:'live',startedAt:old,url:'x'}),'utf8').toString('base64')});
  await loadDesks();
  console.log((els['desks-card'].style.display==='none'?'ok  ':'FAIL')+' устаревшие записи скрыты');
  process.exit(bad?1:0);
})();
