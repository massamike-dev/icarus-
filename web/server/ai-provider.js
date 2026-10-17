// Stateless provider adapter. Returned tools are proposals, never executions.
// Responses is explicit opt-in so existing OpenAI-compatible providers keep working.
const invalidCall = () => ({type:'function',function:{name:'',arguments:''}});
function normalizeCall(call) {
  if (!call || (call.type && call.type !== 'function') ||
      typeof call.function?.name !== 'string' || typeof call.function?.arguments !== 'string') return invalidCall();
  return {type:'function',function:{name:call.function.name,arguments:call.function.arguments}};
}

export function normalizeAIResponse(data, mode='chat') {
  if (mode !== 'responses') {
    const message=data?.choices?.[0]?.message || {};
    const calls=message.tool_calls;
    return {content:typeof message.content==='string'?message.content:'',
      tool_calls:calls==null?[]:Array.isArray(calls)?calls.map(normalizeCall):[invalidCall()]};
  }
  // Never accept a partial tool call or partial structured command as complete.
  if (!Array.isArray(data?.output) || data.error || (data.status && data.status!=='completed')) throw new Error('invalid_provider_response');
  const content=[], calls=[];
  for (const item of data.output) {
    if (item?.type==='function_call') {
      calls.push(item.status && item.status!=='completed'?invalidCall():normalizeCall({
        type:'function',function:{name:item.name,arguments:item.arguments}
      }));
    } else if (item?.type==='message') {
      if (item.status && item.status!=='completed') throw new Error('invalid_provider_response');
      for (const part of Array.isArray(item.content)?item.content:[]) {
        if (part?.type==='output_text' && typeof part.text==='string') content.push(part.text);
        else if (part?.type==='refusal' && typeof part.refusal==='string') content.push(part.refusal);
      }
    } else if (item?.type && item.type!=='reasoning') {
      // Unexpected tool output must not be mistaken for an ordinary answer.
      calls.push(invalidCall());
    }
  }
  return {content:content.join('\n'),tool_calls:calls};
}

/** jsonSchema is {name, schema}; tools use Chat Completions function definitions.
 * Callers MUST reject multiple/unsupported tool_calls and validate their arguments.
 * No automatic retries, provider storage, action execution, or stateful response IDs.
 */
export async function requestAI({env,fetchImpl,system,messages,tools=[],jsonSchema,timeoutMs=30000,errorCode='assistant_provider_unavailable'}) {
  const responses=env.ICARUS_AI_API_MODE==='responses';
  const base=(env.ICARUS_AI_BASE_URL||'https://api.openai.com/v1').replace(/\/$/,'');
  const common={model:env.ICARUS_AI_MODEL||'gpt-5-mini',store:false};
  const payload=responses?{
    ...common,instructions:system,input:messages,
    ...(tools.length?{tools:tools.map(tool=>({type:'function',...tool.function})),parallel_tool_calls:false}:{}),
    ...(jsonSchema?{text:{format:{type:'json_schema',name:jsonSchema.name,schema:jsonSchema.schema,strict:true}}}:{})
  }:{
    ...common,messages:[{role:'system',content:system},...messages],
    ...(tools.length?{tools,parallel_tool_calls:false}:{}),
    ...(jsonSchema?{response_format:{type:'json_object'}}:{})
  };
  const response=await fetchImpl(base+(responses?'/responses':'/chat/completions'),{
    method:'POST',signal:AbortSignal.timeout(timeoutMs),
    headers:{authorization:`Bearer ${env.ICARUS_AI_API_KEY}`,'content-type':'application/json'},body:JSON.stringify(payload)
  });
  if(!response.ok) throw new Error(errorCode);
  return normalizeAIResponse(await response.json(),responses?'responses':'chat');
}
