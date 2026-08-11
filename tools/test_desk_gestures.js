// Drive the desk page's gesture logic in a fake DOM and check the events it
// would send. This is the layer that failed on the phone, so it gets tested.
// Run: node tools/test_desk_gestures.js
//
// The gesture layer is what failed on the phone - taps did nothing, dragging
// was impossible, typing produced nothing - so it is driven here against a
// fake DOM rather than trusted. Extracting the script straight out of the
// Python file means the test cannot drift from what is actually served.
const fs=require('fs');
const path=require('path');
const py=fs.readFileSync(path.join(__dirname,'win_desk_server.py'),'utf8');
const page=py.slice(py.indexOf('PAGE = """'), py.indexOf('</html>'));
const js=[...page.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m=>m[1]).join('\n')
  || page.slice(page.indexOf('<script>')+8);

let SENT=[];
const mk=()=>({listeners:{},classList:{toggle(){},add(){},remove(){}},value:'',
  clientWidth:800,clientHeight:600,width:800,height:600,
  addEventListener(t,f){(this.listeners[t]=this.listeners[t]||[]).push(f);},
  getBoundingClientRect(){return {left:0,top:0,width:800,height:600};},
  focus(){}, src:''});
const els={d:mk(),pad:mk(),kb:mk(),'b-kb':mk(),'b-drag':mk()};
global.document={getElementById:id=>els[id]||mk(),addEventListener(){}};
global.navigator={vibrate(){}};
global.fetch=(url,opt)=>{SENT.push(JSON.parse(opt.body));return Promise.resolve();};
global.setTimeout=(f,ms)=>{const h={f,ms,cleared:false};TIMERS.push(h);return h;};
global.clearTimeout=h=>{if(h)h.cleared=true;};
let TIMERS=[];
global.window={};
eval(js);

const fire=(el,type,ev)=>{(els[el].listeners[type]||[]).forEach(f=>f(ev));};
const T=(x,y,n=1)=>({touches:Array(n).fill({clientX:x,clientY:y}),
  changedTouches:[{clientX:x,clientY:y}],preventDefault(){}});
const kinds=()=>SENT.map(e=>e.t+(e.b!==undefined?':'+e.b:'')).join(',');
let bad=0;
const check=(name,cond,extra='')=>{console.log((cond?'ok  ':'FAIL')+' '+name+(extra?'  '+extra:''));if(!cond)bad++;};

// 1. a tap is a click
SENT=[];TIMERS=[];
fire('pad','touchstart',T(100,100));
fire('pad','touchend',T(100,100));
check('тап = клик', kinds()==='move,down:0,up:0', kinds());
check('координаты тапа верны', SENT[1].x===100&&SENT[1].y===100);
check('размер картинки передан', SENT[1].w===800&&SENT[1].h===600);

// 2. press and move = drag with the button held
SENT=[];TIMERS=[];
fire('pad','touchstart',T(50,50));
fire('pad','touchmove',T(200,180));
fire('pad','touchmove',T(300,240));
fire('pad','touchend',T(300,240));
const k2=kinds();
check('перетаскивание: down до движения, up в конце',
  k2.startsWith('move,down:0,move')&&k2.endsWith('up:0'), k2);

// 3. a shaky tap stays a tap
SENT=[];TIMERS=[];
fire('pad','touchstart',T(100,100));
fire('pad','touchmove',T(103,102));
fire('pad','touchend',T(103,102));
check('дрожь пальца не становится перетаскиванием', !kinds().includes('down:0,move'), kinds());

// 4. long press = right click
SENT=[];TIMERS=[];
fire('pad','touchstart',T(400,300));
const t=TIMERS.find(x=>!x.cleared&&x.ms>=500);
check('долгое нажатие ставит таймер ~550мс', !!t, t?t.ms+'ms':'нет');
if(t)t.f();
check('долгое нажатие = ПКМ', kinds().includes('down:2,up:2'), kinds());

// 5. two fingers = scroll
SENT=[];TIMERS=[];
fire('pad','touchstart',T(300,300,2));
fire('pad','touchmove',T(300,260,2));
fire('pad','touchmove',T(300,200,2));
check('два пальца = прокрутка', SENT.every(e=>e.t==='wheel')&&SENT.length>=2, kinds());
check('прокрутка не шлёт клик', !kinds().includes('down'));

// 6. typing through the hidden input
SENT=[];
els.kb.value='Привет';
fire('kb','input',{});
check('текст с экранной клавиатуры уходит посимвольно',
  SENT.length===12&&SENT[0].k==='П'&&SENT[0].d===true&&SENT[1].d===false, SENT.length+' событий');
check('поле очищается после ввода', els.kb.value==='');

// 7. named keys still work
SENT=[];
fire('kb','keydown',{key:'Enter',preventDefault(){}});
check('Enter отправляется', SENT.length===2&&SENT[0].k==='Enter', JSON.stringify(SENT[0]||{}));
SENT=[];
fire('kb','keydown',{key:'Unidentified',preventDefault(){}});
check('Unidentified игнорируется (Android шлёт его вместо буквы)', SENT.length===0);

process.exit(bad?1:0);
