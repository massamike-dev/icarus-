import { integrationAuth } from './integration-auth.js';

// Every operation below is explicitly read-only. Never accept arbitrary URLs,
// HTTP methods, scopes, provider tool names, or tokens from a browser/model.
export const integrationCatalog = Object.freeze([
  {id:'gmail',label:'Gmail',capabilities:['Search message headers and snippets']},
  {id:'google-calendar',label:'Google Calendar',capabilities:['Search primary-calendar events (bounded date range)']},
  {id:'google-drive',label:'Google Drive',capabilities:['Search file names and metadata']},
  {id:'outlook-email',label:'Outlook Email',capabilities:['Search message headers and previews']},
  {id:'outlook-calendar',label:'Outlook Calendar',capabilities:['Read calendar events (bounded date range)']},
  {id:'teams',label:'Microsoft Teams',capabilities:['List and filter joined teams (not chat messages)']},
  {id:'sharepoint',label:'SharePoint',capabilities:['Search site names and metadata (not document contents)']},
  {id:'dropbox',label:'Dropbox',capabilities:['Search file names and metadata']},
  {id:'github',label:'GitHub',capabilities:['Search public issues and pull requests']},
  {id:'slack',label:'Slack',capabilities:['Search accessible messages using a user token']},
].map(x=>Object.freeze({...x,readOnly:true,supported:true})));
const failure=(message,status=400)=>Object.assign(new Error(message),{status});
const text=(value,max=1200)=>String(value??'').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g,'').slice(0,max);
const link=value=>{try{if(typeof value!=='string'||value.length>2048)return undefined;const url=new URL(value);return url.protocol==='https:'&&!url.username&&!url.password?url.href:undefined;}catch{return undefined;}};
const item=(id,title,body,url)=>({id:text(id,200),title:text(title,240),text:text(body),...(link(url)?{url:link(url)}:{})});
const endpoint=(base,params)=>{const u=new URL(base);for(const [k,v]of Object.entries(params))if(v!==undefined)u.searchParams.set(k,String(v));return u.href;};
const collection=(data,key,optional=false)=>{const value=data?.[key];if(value===undefined&&optional)return [];if(!Array.isArray(value))throw failure('integration_invalid_response',502);return value;};
const quoteDrive=q=>q.replace(/\\/g,'\\\\').replace(/'/g,"\\'");

async function readJson(fetchImpl,url,token,{method='GET',body,headers={}}={}) {
  let response;
  try{response=await fetchImpl(url,{method,redirect:'error',signal:AbortSignal.timeout(20000),headers:{authorization:`Bearer ${token}`,accept:'application/json',...headers,...(body?{'content-type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});}
  catch{throw failure('integration_provider_unavailable',502);}
  if(!response.ok)throw failure(response.status===401?'integration_reauthorization_required':response.status===403?'integration_permission_denied':response.status===429?'integration_rate_limited':'integration_provider_unavailable',response.status===401?409:502);
  // Limit untrusted provider responses before decoding. No provider error bodies
  // are sent to the browser or logs: they can contain account data or secrets.
  let raw='';
  if(response.body?.getReader){const reader=response.body.getReader();let size=0;const chunks=[];while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;if(size>1_000_000){await reader.cancel();throw failure('integration_response_too_large',502);}chunks.push(Buffer.from(value));}raw=Buffer.concat(chunks).toString('utf8');}
  else raw=await response.text();
  if(raw.length>1_000_000)throw failure('integration_response_too_large',502);
  try{const value=JSON.parse(raw);if(!value||typeof value!=='object'||Array.isArray(value))throw Error();return value;}catch{throw failure('integration_invalid_response',502);}
}

export function integrationService({store,env=process.env,fetchImpl=fetch,auth=integrationAuth({store,env,fetchImpl}),now=()=>Date.now()}) {
  const known=id=>{const definition=integrationCatalog.find(x=>x.id===id);if(!definition)throw failure('integration_not_supported',404);return definition;};
  const dates=input=>{const start=new Date(input.timeMin||now()),end=new Date(input.timeMax||start.getTime()+30*86400000);if(!Number.isFinite(start.getTime())||!Number.isFinite(end.getTime())||end<=start||end-start>366*86400000)throw failure('integration_invalid_date_range');return {start:start.toISOString(),end:end.toISOString()};};
  return {
    async status(userId){const data=await store.read();return Promise.all(integrationCatalog.map(async definition=>{const state=await auth.status(userId,definition.id);const grant=(data.integrationCredentials||[]).find(x=>x.userId===userId&&x.integrationId===definition.id);const record=(data.integrationVerification||[]).find(x=>x.userId===userId&&x.integration===definition.id&&x.grantId===grant?.grantId);return {...definition,...state,liveVerified:Boolean(state.connected&&grant?.grantId&&record),...(state.connected&&grant?.grantId&&record?{lastVerifiedAt:record.at}:{})};}));},
    start(userId,id){known(id);return auth.start(userId,id);},
    async finish(userId,id,input){known(id);const result=await auth.finish(userId,id,input);await store.update(d=>{d.integrationVerification=(d.integrationVerification||[]).filter(x=>x.userId!==userId||x.integration!==id);});return result;},
    async disconnect(userId,id){known(id);await auth.disconnect(userId,id);await store.update(d=>{d.integrationVerification=(d.integrationVerification||[]).filter(x=>x.userId!==userId||x.integration!==id);});return {disconnected:true};},
    async query(userId,id,input={}){
      const definition=known(id);const query=text(input.query,1001).trim();if(query.length>1000)throw failure('integration_query_too_long');
      if(!query&&!['teams','google-calendar','outlook-calendar'].includes(id))throw failure('integration_query_required');
      if(input.limit!==undefined&&(!Number.isInteger(input.limit)||input.limit<1||input.limit>10))throw failure('integration_invalid_limit');
      const limit=input.limit||5;const range=['google-calendar','outlook-calendar'].includes(id)?dates(input):null;
      const snapshot=await store.read();const grantId=(snapshot.integrationCredentials||[]).find(x=>x.userId===userId&&x.integrationId===id)?.grantId;
      if(!snapshot.users?.some(x=>x.id===userId)||!grantId)throw failure('integration_not_connected',409);
      const token=await auth.accessToken(userId,id,grantId);
      const invalidate=async()=>{await auth.invalidate(userId,id,grantId,token);await store.update(d=>{d.integrationVerification=(d.integrationVerification||[]).filter(x=>x.userId!==userId||x.integration!==id||x.grantId!==grantId);});};
      const get=async(url,options)=>{try{return await readJson(fetchImpl,url,token,options);}catch(error){if(error.message==='integration_reauthorization_required')await invalidate();throw error;}};
      let results=[],truncated=false;
      if(id==='gmail'){
        const data=await get(endpoint('https://gmail.googleapis.com/gmail/v1/users/me/messages',{q:query,maxResults:limit}));truncated=Boolean(data.nextPageToken);
        results=await Promise.all(collection(data,'messages',true).slice(0,limit).map(async message=>{
          const m=await get(endpoint(`https://gmail.googleapis.com/gmail/v1/users/me/messages/${encodeURIComponent(message.id)}`,{format:'metadata'}));
          const header=name=>(m.payload?.headers||[]).find(h=>h.name?.toLowerCase()===name)?.value||'';
          return item(m.id,header('subject'),`${header('from')} | ${header('date')}\n${m.snippet||''}`);
        }));
      }else if(id==='google-calendar'){
        const data=await get(endpoint('https://www.googleapis.com/calendar/v3/calendars/primary/events',{q:query||undefined,timeMin:range.start,timeMax:range.end,singleEvents:true,orderBy:'startTime',maxResults:limit}));truncated=Boolean(data.nextPageToken);
        results=collection(data,'items',id==='google-calendar').slice(0,limit).map(x=>item(x.id,x.summary,`${x.start?.dateTime||x.start?.date||''} to ${x.end?.dateTime||x.end?.date||''}\n${x.location||''}\n${x.description||''}`,x.htmlLink));
      }else if(id==='google-drive'){
        const data=await get(endpoint('https://www.googleapis.com/drive/v3/files',{q:`trashed = false and name contains '${quoteDrive(query)}'`,pageSize:limit,fields:'nextPageToken,files(id,name,mimeType,modifiedTime,webViewLink,description)'}));truncated=Boolean(data.nextPageToken);
        results=collection(data,'files',true).slice(0,limit).map(x=>item(x.id,x.name,`${x.mimeType||''} | ${x.modifiedTime||''}\n${x.description||''}`,x.webViewLink));
      }else if(id==='outlook-email'){
        const data=await get(endpoint('https://graph.microsoft.com/v1.0/me/messages',{'$search':JSON.stringify(query),'$top':limit,'$select':'id,subject,from,receivedDateTime,bodyPreview,webLink'}));truncated=Boolean(data['@odata.nextLink']);
        results=collection(data,'value').slice(0,limit).map(x=>item(x.id,x.subject,`${x.from?.emailAddress?.address||''} | ${x.receivedDateTime||''}\n${x.bodyPreview||''}`,x.webLink));
      }else if(id==='outlook-calendar'){
        const data=await get(endpoint('https://graph.microsoft.com/v1.0/me/calendarView',{startDateTime:range.start,endDateTime:range.end,'$top':100,'$select':'id,subject,start,end,location,webLink','$orderby':'start/dateTime'}));
        const events=collection(data,'value').filter(x=>!query||`${x.subject||''} ${x.location?.displayName||''}`.toLowerCase().includes(query.toLowerCase()));truncated=Boolean(data['@odata.nextLink'])||events.length>limit;
        results=events.slice(0,limit).map(x=>item(x.id,x.subject,`${x.start?.dateTime||''} (${x.start?.timeZone||''}) to ${x.end?.dateTime||''}\n${x.location?.displayName||''}`,x.webLink));
      }else if(id==='teams'){
        const data=await get('https://graph.microsoft.com/v1.0/me/joinedTeams');const teams=collection(data,'value').filter(x=>!query||`${x.displayName||''} ${x.description||''}`.toLowerCase().includes(query.toLowerCase()));truncated=Boolean(data['@odata.nextLink'])||teams.length>limit;
        results=teams.slice(0,limit).map(x=>item(x.id,x.displayName,x.description));
      }else if(id==='sharepoint'){
        const data=await get(endpoint('https://graph.microsoft.com/v1.0/sites',{search:query,'$select':'id,displayName,description,webUrl'}));truncated=Boolean(data['@odata.nextLink'])||collection(data,'value').length>limit;
        results=collection(data,'value').slice(0,limit).map(x=>item(x.id,x.displayName,x.description,x.webUrl));
      }else if(id==='dropbox'){
        const data=await get('https://api.dropboxapi.com/2/files/search_v2',{method:'POST',body:{query,options:{max_results:limit,filename_only:true}}});truncated=Boolean(data.has_more);
        results=collection(data,'matches').slice(0,limit).map(x=>{const m=x.metadata?.metadata||{};return item(m.id,m.name,`${m.path_display||''}\n${m.server_modified||''}`);});
      }else if(id==='github'){
        const data=await get(endpoint('https://api.github.com/search/issues',{q:`${query} is:public`,per_page:limit}),{headers:{'x-github-api-version':'2022-11-28','user-agent':'ICARUS-readonly-integrations'}});truncated=data.incomplete_results===true||data.total_count>limit;
        results=collection(data,'items',id==='google-calendar').slice(0,limit).map(x=>item(x.id,x.title,`${x.state||''} | ${x.user?.login||''}\n${x.body||''}`,x.html_url));
      }else if(id==='slack'){
        const data=await get(endpoint('https://slack.com/api/search.messages',{query,count:limit,highlight:false}));if(data.ok!==true){if(['invalid_auth','token_revoked','token_expired','account_inactive'].includes(data.error)){await invalidate();throw failure('integration_reauthorization_required',409);}throw failure('integration_provider_unavailable',502);}truncated=data.messages?.paging?.pages>1||data.messages?.total>limit;
        results=collection(data.messages,'matches').slice(0,limit).map(x=>item(x.ts,`#${x.channel?.name||'conversation'} · ${x.user||''}`,x.text,x.permalink));
      }
      // Defensive redaction if a provider accidentally echoes its bearer token.
      results=results.map(result=>Object.fromEntries(Object.entries(result).map(([key,value])=>[key,typeof value==='string'&&token?value.split(token).join('[redacted]'):value])));
      const at=new Date(now()).toISOString();await store.update(d=>{if(!d.users?.some(x=>x.id===userId)||!d.integrationCredentials?.some(x=>x.userId===userId&&x.integrationId===id&&x.grantId===grantId))throw failure('integration_connection_changed',409);d.integrationVerification=(d.integrationVerification||[]).filter(x=>x.userId!==userId||x.integration!==id);d.integrationVerification.push({userId,integration:id,grantId,at});});
      const sources=results.filter(x=>x.url).map(x=>({title:x.title,url:x.url}));
      const scope=definition.capabilities[0];
      const reply=`${definition.label}: ${results.length} result${results.length===1?'':'s'}${range?` between ${range.start} and ${range.end}`:''}. ${scope}.${truncated?' This is a limited page, not an exhaustive search.':''}\n\n${results.map((x,i)=>`${i+1}. ${x.title||'(Untitled)'}\n${x.text}`).join('\n\n')||'No matching results were returned in this search window.'}`;
      return {integration:id,results,sources,reply,readOnly:true,liveVerified:true,checkedAt:at,truncated};
    }
  };
}
