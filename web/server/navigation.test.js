import test from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {fileURLToPath} from 'node:url';

const root=fileURLToPath(new URL('../',import.meta.url));
const bundle=await build({root,logLevel:'silent',build:{write:false,minify:false}});
const code=bundle.output.find(x=>x.type==='chunk'&&x.isEntry).code;
const waitFor=async condition=>{for(let i=0;i<100;i++){if(condition())return;await new Promise(r=>setTimeout(r,10));}assert.fail('UI did not reach expected state');};

async function mount(native=false){
  const dom=new JSDOM('<div id="root"></div>',{url:'https://icarus.test',runScripts:'outside-only'});
  const {window:w}=dom,errors=[],requests=[];
  w.addEventListener('error',e=>{errors.push(e.error);e.preventDefault();});
  w.scrollTo=()=>{};
  w.fetch=async url=>({ok:true,json:async()=>url==='/api/me'?{user:{name:'Test User',email:'test@example.invalid'}}:url==='/api/memories'?{memories:[]}:{conversations:[]}});
  if(native)w.IcarusNative={postMessage:raw=>{const p=JSON.parse(raw);requests.push(p);if(p.action)setTimeout(()=>w.ICARUS_NATIVE_RESULT?.(JSON.stringify({ok:true,requestId:p.requestId,data:{}})),0);}};
  w.eval(code);
  await waitFor(()=>w.document.querySelector('nav'));
  const click=async label=>{const b=[...w.document.querySelectorAll('button')].find(x=>x.textContent===label);assert.ok(b,`Missing ${label}`);b.click();await new Promise(r=>setTimeout(r,25));};
  return {dom,w,errors,requests,click};
}

test('Memory → Settings and every tab remain navigable without a blank screen',async()=>{
  const app=await mount();
  try{
    for(const tab of ['Memory','Settings','Chat','Settings','Voice','Settings','Command','Settings']){
      await app.click(tab);
      assert.ok(app.w.document.querySelector('nav'));
      assert.doesNotMatch(app.w.document.body.textContent,/This screen couldn't load/);
      if(tab==='Settings'){
        assert.equal(app.w.document.querySelector('h1').textContent,'Settings');
        assert.match(app.w.document.body.textContent,/Your account/);
      }
    }
    assert.deepEqual(app.errors,[]);
    assert.ok([...app.w.document.querySelectorAll('button')].find(b=>b.textContent==='Enable hands-free').disabled);
  }finally{app.dom.window.close();}
});

test('Settings native controls dispatch once and survive repeated navigation',async()=>{
  const app=await mount(true);
  try{
    await app.click('Settings');await app.click('Stop listening');
    assert.equal(app.requests.filter(p=>p.action==='wake_word').length,1);
    assert.deepEqual(app.requests.find(p=>p.action==='wake_word').arguments,{enabled:false});
    assert.match(app.w.document.body.textContent,/Request received by Android/);
    await app.click('Memory');await app.click('Settings');await app.click('Stop speaking');
    assert.equal(app.requests.filter(p=>p.action==='stop_speaking').length,1);
    assert.deepEqual(app.errors,[]);
  }finally{app.dom.window.close();}
});
