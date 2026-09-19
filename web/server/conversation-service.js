import {createHash,randomUUID} from 'node:crypto';
import {validateCommand} from './commands.js';

const error=(message,status)=>Object.assign(new Error(message),{status});
const digest=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
const findTurn=(d,userId,clientTurnId)=>d.turns?.find(t=>t.userId===userId&&t.clientTurnId===clientTurnId);

// A completed turn is saved atomically. Provider errors cannot leave an orphaned
// user message; retries reuse the same response instead of duplicating the turn.
export function conversationService(store){
  const inFlight=new Map(),queues=new Map();
  const currentResponse=async(response,userId)=>{
    if(!response.proposal?.id)return response;
    const d=await store.read(),action=d.actionRequests?.find(a=>a.id===response.proposal.id&&a.userId===userId);
    if(!action)throw error('turn_deleted',410);
    if(!action.result)return response;
    const {proposal,proposalId,...rest}=response;
    return {...rest,...('action' in rest?{action:'unknown'}:{}),executionStatus:action.result.status,
      reply:`Previously recorded device report: ${action.result.summary} This request will not propose the action again.`,actionResult:action.result};
  };
  const serial=(key,fn)=>{
    const previous=queues.get(key)||Promise.resolve();
    const next=previous.catch(()=>{}).then(fn);queues.set(key,next);
    next.finally(()=>{if(queues.get(key)===next)queues.delete(key)}).catch(()=>{});
    return next;
  };
  const savedTurn=async({userId,input,text,channel,produce})=>{
    const clientTurnId=String(input.clientTurnId||randomUUID());
    if(!/^[a-zA-Z0-9_-]{1,100}$/.test(clientTurnId))throw error('invalid_turn_id',400);
    const conversationId=input.conversationId?String(input.conversationId):null;
    const fingerprint=digest({text,conversationId,channel,search:input.search===true,native:input.native===true});
    const key=`${userId}:${clientTurnId}`;
    if(inFlight.has(key)){
      const pending=inFlight.get(key);
      if(pending.fingerprint!==fingerprint)throw error('turn_id_reused',409);
      return currentResponse(await pending.promise,userId);
    }
    const promise=serial(`${userId}:${conversationId||clientTurnId}`,async()=>{
      const d=await store.read();
      const completed=findTurn(d,userId,clientTurnId);
      if(completed){if(completed.fingerprint!==fingerprint)throw error('turn_id_reused',409);if(completed.deleted)throw error('turn_deleted',410);return completed.response;}
      const existing=conversationId?d.conversations.find(c=>c.id===conversationId&&c.userId===userId):null;
      if(conversationId&&!existing)throw error('conversation_not_found',404);
      const id=existing?.id||randomUUID();
      const history=d.messages.filter(m=>m.conversationId===id&&m.userId===userId);
      const turn=await produce([...history,{role:'user',content:text}],d.memories.filter(m=>m.userId===userId));
      const now=new Date().toISOString();
      const proposal=turn.proposal?{...validateCommand(turn.proposal),id:randomUUID()}:null;
      if(proposal?.action==='unknown')throw error('invalid_proposal',400);
      const response={...turn,conversationId:id,...(proposal?{proposal,proposalId:proposal.id}:{})};
      await store.update(data=>{
        if(!data.users.some(u=>u.id===userId))throw error('authentication_required',401);
        const current=data.conversations.find(c=>c.id===id&&c.userId===userId);
        if(existing&&!current)throw error('conversation_deleted',409);
        if(current)current.updatedAt=now;
        else data.conversations.push({id,userId,title:text.slice(0,60),createdAt:now,updatedAt:now});
        data.messages.push({id:randomUUID(),conversationId:id,userId,role:'user',content:text,channel,createdAt:now});
        const content=proposal?`Proposed ${proposal.action.replaceAll('_',' ')}${proposal.value?' '+proposal.value:''}. No execution result has been reported yet.`:turn.reply;
        data.messages.push({id:randomUUID(),conversationId:id,userId,role:'assistant',content,sources:turn.sources||[],channel,createdAt:now});
        if(proposal){data.actionRequests??=[];data.actionRequests.push({id:proposal.id,userId,conversationId:id,action:proposal.action,value:proposal.value,createdAt:now,result:null});}
        data.turns??=[];data.turns.push({userId,clientTurnId,fingerprint,response});
      });
      return response;
    });
    inFlight.set(key,{fingerprint,promise});
    try{return await currentResponse(await promise,userId);}finally{if(inFlight.get(key)?.promise===promise)inFlight.delete(key);}
  };
  const reportResult=async(userId,id,input)=>{
    const status=input.status;
    if(!['reported','cancelled','unknown'].includes(status))throw error('invalid_result_status',400);
    const summary=String(input.summary||'').trim().slice(0,1000);
    if(!summary)throw error('result_summary_required',400);
    let result;
    await store.update(d=>{
      const action=d.actionRequests?.find(a=>a.id===id&&a.userId===userId);
      const conversation=action&&d.conversations.find(c=>c.id===action.conversationId&&c.userId===userId);
      if(!action||!conversation)throw error('action_not_found',404);
      if(action.result){result=action.result;return;}
      const createdAt=new Date().toISOString();
      const prefix=status==='cancelled'?'Action cancelled':status==='unknown'?'Action outcome unknown':'Device report (not independently verified by the server)';
      result={status,summary,createdAt};action.result=result;conversation.updatedAt=createdAt;
      d.messages.push({id:randomUUID(),conversationId:action.conversationId,userId,role:'assistant',channel:'device_report',actionId:id,content:`${prefix}: ${summary}`,createdAt});
    });
    return {ok:true,result};
  };
  return {savedTurn,reportResult};
}
