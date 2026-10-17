// One versioned snapshot is committed with a single WebView storage write. There
// is no server, account token, or automatic migration from hosted cloud data.
export const LOCAL_STORAGE_KEY='icarus_local_data_v1';
export const localError=(code,message,status=400)=>Object.assign(new Error(message),{code,status,local:true});
const empty=()=>({version:1,memories:[],conversations:[],turns:[],actions:[]});
const object=value=>Boolean(value)&&typeof value==='object'&&!Array.isArray(value);
const validSnapshot=data=>object(data)&&data.version===1&&['memories','conversations','turns','actions'].every(key=>Array.isArray(data[key]))&&
  data.memories.every(m=>object(m)&&typeof m.id==='string'&&typeof m.content==='string')&&
  data.conversations.every(c=>object(c)&&typeof c.id==='string'&&typeof c.title==='string'&&Array.isArray(c.messages)&&c.messages.every(m=>object(m)&&['user','assistant'].includes(m.role)&&typeof m.content==='string'))&&
  data.turns.every(t=>object(t)&&typeof t.clientTurnId==='string'&&(t.deleted===true||(typeof t.fingerprint==='string'&&object(t.response))))&&
  data.actions.every(a=>object(a)&&typeof a.id==='string'&&typeof a.conversationId==='string'&&(a.result===null||object(a.result)));

export function createLocalStore(storage) {
  const read=()=>{
    let raw;
    try{raw=storage.getItem(LOCAL_STORAGE_KEY);}catch{throw localError('local_storage_unavailable','Device storage is unavailable. Reopen ICARUS before trying again.',503);}
    if(raw===null)return empty();
    let value;
    try{value=JSON.parse(raw);}catch{/* Preserve the original data for recovery. */}
    if(!validSnapshot(value))throw localError('local_storage_invalid','Saved device data could not be read. Do not clear app storage if you need to recover it.',503);
    return value;
  };
  const update=change=>{
    const data=read(),result=change(data);
    try{storage.setItem(LOCAL_STORAGE_KEY,JSON.stringify(data));}
    catch{throw localError('local_storage_full','Your device could not save this change. Free up storage, then retry. Nothing was sent to the cloud.',507);}
    return result;
  };
  return {read,update};
}
