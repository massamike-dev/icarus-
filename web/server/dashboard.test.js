import test from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {readFileSync,existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const bundle=await build({root,logLevel:'silent',build:{write:false,minify:false}});
const code=bundle.output.find(x=>x.type==='chunk'&&x.isEntry).code;
const pause=()=>new Promise(r=>setTimeout(r,20));
const waitFor=async fn=>{for(let i=0;i<100;i++){if(fn())return;await pause()}assert.fail('Dashboard did not reach the expected state')};
async function mount(){
 const dom=new JSDOM('<div id="root"></div>',{url:'https://icarus.test',runScripts:'outside-only'}),w=dom.window,errors=[],requests=[];
 w.scrollTo=()=>{};w.addEventListener('error',event=>{errors.push(event.error);event.preventDefault()});
 w.fetch=async(url,options)=>{requests.push({url,options});return{ok:true,json:async()=>url==='/api/me'?{user:{name:'Alex Rivera',email:'alex@example.invalid'}}:url==='/api/capabilities'?{}:{conversations:[],memories:[]}}};
 w.eval(code);await waitFor(()=>w.document.querySelector('.command-dashboard'));
 const button=label=>[...w.document.querySelectorAll('button')].find(b=>b.textContent===label||b.getAttribute('aria-label')===label);
 const click=async label=>{assert.ok(button(label),`Missing ${label}`);button(label).click();await pause()};
 return {dom,w,errors,requests,button,click};
}
test('reference-led dashboard centers one functioning companion and retains every destination',async()=>{
 const app=await mount();try{
  assert.equal(app.w.document.querySelector('#dashboard-title').textContent,'Speak with ICARUS');
  assert.match(app.w.document.body.textContent,/What are we working on, Alex/);
  assert.equal(app.w.document.querySelectorAll('.icarus-companion').length,1);
  assert.ok(app.w.document.querySelector('.companion-dashboard'));
  assert.equal(app.w.document.querySelector('.core'),null);
  assert.equal(app.w.document.querySelector('.dashboard-tools').open,false);
  assert.deepEqual([...app.w.document.querySelectorAll('nav button')].map(b=>b.dataset.destination),['home','drive','voice','chat','memory','settings']);
  assert.equal(app.w.document.querySelector('nav button[data-destination=home]').getAttribute('aria-current'),'page');
  for(const label of ['Home','Vehicle','Voice','Chat','Memory','Settings'])assert.ok(app.button(label));
  assert.equal(app.button('Continue in Chat').disabled,true);
  assert.doesNotMatch(app.w.document.body.textContent,/Base44|MIGRATED|Michael/);
  assert.deepEqual(app.errors,[]);
 }finally{app.dom.window.close()}
});
test('suggestions transfer draft without sending and normal chat navigation clears consumed draft',async()=>{
 const app=await mount();try{
  await app.click('Plan my day');await waitFor(()=>app.w.document.querySelector('.composer textarea'));
  assert.equal(app.w.document.querySelector('textarea').value,'Plan my day');
  assert.equal(app.requests.filter(r=>r.url==='/api/chat').length,0);
  await app.click('Home');await app.click('Chat');await waitFor(()=>app.w.document.querySelector('textarea'));
  assert.equal(app.w.document.querySelector('textarea').value,'');
  assert.deepEqual(app.errors,[]);
 }finally{app.dom.window.close()}
});
test('home reply field transfers typed text without executing a command',async()=>{
 const app=await mount();try{
  const field=app.w.document.querySelector('#dashboard-draft');
  Object.getOwnPropertyDescriptor(app.w.HTMLInputElement.prototype,'value').set.call(field,'Help me plan tomorrow');
  field.dispatchEvent(new app.w.Event('input',{bubbles:true}));await pause();
  await app.click('Continue in Chat');await waitFor(()=>app.w.document.querySelector('textarea'));
  assert.equal(app.w.document.querySelector('textarea').value,'Help me plan tomorrow');
  assert.equal(app.requests.filter(r=>r.url==='/api/chat').length,0);
  assert.deepEqual(app.errors,[]);
 }finally{app.dom.window.close()}
});
test('connection check tells browser users the real limitation',async()=>{
 const app=await mount();try{
  app.w.document.querySelector('.dashboard-tools summary').click();await pause();
  await app.click('Check connection');await waitFor(()=>app.button('Check connection'));
  assert.match(app.w.document.querySelector('.dashboard-device').textContent,/Available in the Android app/);
  assert.doesNotMatch(app.w.document.querySelector('.dashboard-device').textContent,/Android connected/);
  assert.equal(app.button('Check connection').disabled,false);
  assert.deepEqual(app.errors,[]);
 }finally{app.dom.window.close()}
});
test('local visual assets and responsive accessibility contracts are present',()=>{
 const css=readFileSync(new URL('../src/dashboard.css',import.meta.url),'utf8');
 assert.match(css,/max-width:420px/);assert.match(css,/prefers-reduced-motion:reduce/);
 assert.match(css,/forced-colors:active/);
 assert.match(css,/grid-template-columns: repeat\(4, minmax\(0, 1fr\)\)/);
 assert.match(css,/data-destination="drive"/);
 assert.match(css,/data-destination="voice"/);
 assert.match(css,/height: clamp\(236px, calc\(100svh - 535px\), 410px\)/);
 assert.equal((css.match(/:root\s*\{/g)||[]).length,1);assert.match(css,/\.dashboard-composer:focus-within/);
 const base=readFileSync(new URL('../src/styles.css',import.meta.url),'utf8');assert.doesNotMatch(base,/fonts.googleapis.com/);
 assert.ok(existsSync(new URL('../public/brand/icarus-companion.png',import.meta.url)));
});

test('clear reply resets and refocuses the home field without sending or navigating',async()=>{
 const app=await mount();try{
  const field=app.w.document.querySelector('#dashboard-draft');
  assert.equal(app.button('Clear message').disabled,true);
  Object.getOwnPropertyDescriptor(app.w.HTMLInputElement.prototype,'value').set.call(field,'Do not send this');
  field.dispatchEvent(new app.w.Event('input',{bubbles:true}));await pause();
  assert.equal(app.button('Clear message').disabled,false);
  await app.click('Clear message');
  assert.equal(field.value,'');
  assert.equal(app.w.document.activeElement,field);
  assert.equal(app.button('Continue in Chat').disabled,true);
  assert.ok(app.w.document.querySelector('.command-dashboard'));
  assert.equal(app.requests.filter(r=>r.url==='/api/chat').length,0);
  assert.deepEqual(app.errors,[]);
 }finally{app.dom.window.close()}
});
test('secondary home controls preserve Vehicle and Voice navigation with one companion',async()=>{
 const app=await mount();try{
  const tools=app.w.document.querySelector('.dashboard-tools');
  tools.querySelector('summary').click();await pause();
  assert.equal(tools.open,true);
  await app.click('Open vehicle controls');
  assert.equal(app.w.document.querySelector('.app-shell').dataset.page,'drive');
  assert.equal(app.w.document.querySelectorAll('.icarus-companion').length,1);
  await app.click('Home');
  assert.equal(app.w.document.querySelector('.dashboard-tools').open,false);
  app.w.document.querySelector('.dashboard-tools summary').click();await pause();
  await app.click('Open voice controls');
  assert.equal(app.w.document.querySelector('.app-shell').dataset.page,'voice');
  assert.equal(app.w.document.querySelectorAll('.icarus-companion').length,1);
  assert.deepEqual(app.errors,[]);
 }finally{app.dom.window.close()}
});
