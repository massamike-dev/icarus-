import test from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'vite';
import {JSDOM} from 'jsdom';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const bundle=await build({root,logLevel:'silent',define:{'process.env.NODE_ENV':JSON.stringify('production')},build:{write:false,minify:false,lib:{entry:root+'server/fixtures/checklist-entry.jsx',formats:['iife'],name:'ChecklistTest'}}});
const code=(Array.isArray(bundle)?bundle[0]:bundle).output.find(x=>x.type==='chunk'&&x.isEntry).code;
async function wait(fn){for(let i=0;i<150;i++){if(fn())return;await new Promise(r=>setTimeout(r,10))}assert.fail('UI did not settle')}
async function mount({owner=true,fail=false}={}){
 const dom=new JSDOM('<div id="root"></div>',{url:'https://icarus.test',runScripts:'outside-only'}),w=dom.window,requests=[];
 w.localStorage.setItem('icarus_companion_preferences',JSON.stringify({hidden:true}));w.scrollTo=()=>{};let item={id:'test',title:'Wake test',notes:'Verify screen lock',status:'todo'};
 w.fetch=async(path,options={})=>{requests.push(path);let value={};
 if(path==='/api/me')value={user:{id:'owner',name:'Michael',testingChecklist:owner}};
 if(path==='/api/testing-checklist')value={items:[item]};
 if(path==='/api/testing-checklist/test'){if(fail)return {ok:false,status:500,json:async()=>({error:'save_failed'})};item={...item,...JSON.parse(options.body)};value={item}}
 return {ok:true,json:async()=>value}};
 w.testOwner=owner;w.eval(code);await wait(()=>w.document.querySelector('#root').textContent);
 if(owner)await wait(()=>w.document.querySelector('.testing-items article'));
 return {dom,w,requests};
}
test('completed tasks leave active list, collapse into history and can be reopened',async()=>{
 const a=await mount();try{
 const find=text=>[...a.w.document.querySelectorAll('.testing-checklist button')].find(b=>b.textContent.startsWith(text));
 find('Mark complete').click();await wait(()=>a.w.document.querySelector('details'));
 assert.equal(a.w.document.querySelector('.testing-items article'),null);
 assert.equal(a.w.document.querySelector('details').open,false);
 assert.equal(a.w.document.activeElement.id,'testing-title');
 find('Reopen').click();await wait(()=>a.w.document.querySelector('.testing-items article'));
 assert.equal(a.w.document.querySelector('details'),null);
 }finally{a.dom.window.close()}
});
test('failed saves keep task active and explain failure',async()=>{
 const a=await mount({fail:true});try{
 [...a.w.document.querySelectorAll('button')].find(b=>b.textContent.startsWith('Mark complete')).click();
 await wait(()=>a.w.document.querySelector('[role=alert]'));
 assert.ok(a.w.document.querySelector('.testing-items article'));
 assert.match(a.w.document.querySelector('[role=alert]').textContent,/save failed/);
 }finally{a.dom.window.close()}
});
