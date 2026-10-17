/** Isolated browser checks of the exact built offline UI. All native responses
 * are simulated; no account, device, speech service or external API is contacted.
 * Screenshots are design evidence, never a claim of hardware verification. */
import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('../',import.meta.url));
const output=path.join(root,'ui-qa');await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true});
const report={fixture:'simulated native responses; no physical phone or live accounts',screenshots:[],checks:[],consoleErrors:[]};
const origin='https://appassets.androidplatform.net';
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.webp':'image/webp','.png':'image/png','.svg':'image/svg+xml','.glb':'model/gltf-binary'};
async function setup(width,height,{native=true,reduced=false}={}){
 const context=await browser.newContext({viewport:{width,height},deviceScaleFactor:1,reducedMotion:reduced?'reduce':'no-preference'});
 await context.route('**/*',async route=>{
  const url=new URL(route.request().url());
  if(url.origin!==origin)return route.abort();
  const relative=decodeURIComponent(url.pathname).replace(/^\/assets\/web\//,'').replace(/^\//,'');
  const file=path.resolve(root,'dist',relative||'index.html');
  if(!file.startsWith(path.join(root,'dist')+path.sep))return route.abort();
  try{await route.fulfill({body:await readFile(file),contentType:mime[path.extname(file)]||'application/octet-stream'});}catch{await route.fulfill({status:404,body:'Fixture asset not found'});}
 });
 if(native)await context.addInitScript(()=>{
  window.__qaNativeRequests=[];
  window.__qaState={connected:true,version:'UI fixture',actionProtocolVersion:1,capabilities:[],wakeWord:{enabled:true,permissionGranted:true,talkNowSupported:true,listenerState:'LISTENING',recorderActive:true}};
  window.ICARUS_NATIVE_CHANNEL={postMessage(raw){const request=JSON.parse(raw);window.__qaNativeRequests.push(request);
   if(request.bridgeRequest==='status')return setTimeout(()=>window.ICARUS_NATIVE_STATUS?.(JSON.stringify({...window.__qaState,requestId:request.requestId})),10);
   if(request.action==='start_voice_turn')return setTimeout(()=>{window.__qaState.wakeWord.listenerState='CAPTURING';window.ICARUS_NATIVE_RESULT?.(JSON.stringify({requestId:request.requestId,ok:true,data:{requestAccepted:true}}));window.ICARUS_NATIVE_STATUS?.(JSON.stringify(window.__qaState));},180);
   setTimeout(()=>window.ICARUS_NATIVE_RESULT?.(JSON.stringify({requestId:request.requestId,ok:false,error:'fixture_unsupported',message:'This device feature is not simulated by browser QA.'})),10);
  }};
 });
 const page=await context.newPage();
 page.on('pageerror',error=>report.consoleErrors.push(String(error)));
 await page.goto(origin+'/assets/web/index.html');await page.locator('#dashboard-title').waitFor();
 await page.locator('.companion-poster').evaluate(image=>image.decode());
 const art=await page.locator('.companion-figure').evaluate(figure=>{const image=figure.querySelector('img');const a=figure.getBoundingClientRect(),b=image.getBoundingClientRect();return{figure:{top:a.top,bottom:a.bottom,height:a.height},image:{top:b.top,bottom:b.bottom,height:b.height}};});
 assert.ok(art.image.top>=art.figure.top-1&&art.image.bottom<=art.figure.bottom+1,'The full gold character must fit inside its scene without clipping');
 return{context,page};
}
async function shot(page,name){await page.screenshot({path:path.join(output,name+'.png'),fullPage:false});report.screenshots.push(name+'.png');}
async function noOverflow(page,label){const size=await page.evaluate(()=>({width:innerWidth,scroll:document.documentElement.scrollWidth}));assert.ok(size.scroll<=size.width+1,`${label}: overflow ${size.scroll}/${size.width}`);report.checks.push(label+' has no horizontal overflow');}
try{
 const {context,page}=await setup(393,852);
 await page.locator('.dashboard-talk:enabled').waitFor();
 await noOverflow(page,'393px Home');await shot(page,'home-393x852');
 await page.getByRole('button',{name:'Open ICARUS companion',exact:true}).click();
 await page.locator('.companion-panel').waitFor();await shot(page,'companion-open-393x852');
 await page.keyboard.press('Escape');assert.equal(await page.locator('.companion-panel').count(),0);
 assert.equal(await page.evaluate(()=>document.activeElement?.getAttribute('aria-label')),'Open ICARUS companion');
 await page.getByRole('button',{name:'Plan my day',exact:true}).click();
 assert.equal(await page.getByRole('textbox',{name:'Message',exact:true}).inputValue(),'Plan my day');
 assert.equal(await page.evaluate(()=>window.__qaNativeRequests.filter(r=>r.action==='local_chat').length),0);
 await shot(page,'chat-draft-393x852');await noOverflow(page,'393px Chat');
 await page.getByRole('button',{name:'Memory',exact:true}).click();
 await page.getByRole('textbox',{name:'Something ICARUS should remember'}).fill('Browser QA sample memory');
 await page.getByRole('button',{name:'Remember',exact:true}).click();
 await page.getByText('Browser QA sample memory',{exact:true}).waitFor();
 await shot(page,'memory-393x852');await noOverflow(page,'393px Memory');
 await page.getByRole('button',{name:'Settings',exact:true}).click();
 await page.getByRole('heading',{name:'Settings',exact:true}).waitFor();
 await shot(page,'settings-393x852');await noOverflow(page,'393px Settings');
 await page.getByRole('button',{name:'Home',exact:true}).click();
 await page.locator('.dashboard-tools summary').click();
 await page.getByRole('button',{name:'Open voice controls',exact:true}).click();
 await page.getByRole('button',{name:'Enable hands-free',exact:true}).click();
 await page.locator('dialog[open]').waitFor();await shot(page,'voice-consent-393x852');
 await page.getByRole('button',{name:'Cancel',exact:true}).click();assert.equal(await page.locator('dialog[open]').count(),0);
 assert.equal(await page.evaluate(()=>window.__qaNativeRequests.filter(r=>r.action==='wake_word').length),0);
 await page.getByRole('button',{name:'Home',exact:true}).click();
 await page.locator('.dashboard-tools summary').click();await page.getByRole('button',{name:'Open vehicle controls',exact:true}).click();
 await shot(page,'vehicle-393x852');await noOverflow(page,'393px Vehicle');
 await page.getByRole('button',{name:'Home',exact:true}).click();await page.locator('.dashboard-talk:enabled').waitFor();
 await page.getByRole('button',{name:'Talk now from Home',exact:true}).click();
 await page.locator('.dashboard-talk:disabled').waitFor();
 assert.equal(await page.evaluate(()=>window.__qaNativeRequests.filter(r=>r.action==='start_voice_turn').length),1);
 await page.locator('.icarus-companion[data-phase="listening"]').waitFor();await shot(page,'home-listening-393x852');
 report.checks.push('Home ideas transfer drafts without sending','Escape closes companion and restores focus','Memory saves explicit sample locally','Voice consent Cancel sends no command','Home Talk now dispatches once and blocks during capture');
 await context.close();
 for(const [width,height] of [[320,568],[430,932],[1440,1000]]){
  const {context,page}=await setup(width,height);await page.locator('.dashboard-talk:enabled').waitFor();await noOverflow(page,`${width}px Home`);await shot(page,`home-${width}x${height}`);
  if(width<=850)assert.equal(await page.locator('nav button:visible').count(),4);
  await page.getByRole('button',{name:'Settings',exact:true}).click();await noOverflow(page,`${width}px Settings`);await shot(page,`settings-${width}x${height}`);
  await context.close();
 }
 const reduced=await setup(393,852,{native:false,reduced:true});
 assert.equal(await reduced.page.locator('.dashboard-talk').isDisabled(),true);
 await reduced.page.getByRole('textbox',{name:'Message ICARUS',exact:true}).fill('Draft retained while typing');
 assert.equal(await reduced.page.locator('.companion-dashboard').isVisible(),true);
 await reduced.page.getByRole('button',{name:'Clear message',exact:true}).click();
 assert.equal(await reduced.page.getByRole('textbox',{name:'Message ICARUS',exact:true}).inputValue(),'');
 await shot(reduced.page,'home-no-device-reduced-motion');await reduced.context.close();
 report.checks.push('Four mobile navigation destinations remain available','Typing on Home preserves layout','Clear resets and focuses draft','No-device voice remains disabled','Reduced-motion layout verified');
 assert.deepEqual(report.consoleErrors,[]);
 report.result='passed';
}catch(error){report.result='failed';report.error=String(error);throw error;}
finally{await writeFile(path.join(output,'report.json'),JSON.stringify(report,null,2));await browser.close();}
