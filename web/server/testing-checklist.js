import {randomUUID} from 'node:crypto';
import {isPrivateTester} from './private-test.js';

export const initialTests = [
  ['Wake-word reliability', 'Check Hey ICARUS after screen lock, backgrounding and reopening. Confirm Stop keeps listening off.'],
  ['Voice controls', 'Check chosen voice, speech speed and hands-free commands on the phone.'],
  ['Login and recovery', 'Verify private sign-in, return after restart and password recovery.'],
  ['Saved memories', 'Save a test memory, restart, recall it and remove it.'],
  ['5-minute and 2-minute warnings', 'Implement and verify both spoken warnings, including with the screen locked.'],
  ['Live maps and route line', 'While parked, check GPS, destination selection, route display and loss of connection.'],
  ['Motion fade', 'While seated, set forward, rotate the phone and confirm fade and recovery.'],
  ['XREAL head tracking', 'Implement and verify actual glasses tracking and forward anchoring separately from phone simulation.'],
  ['Lil ICARUS animations', 'Integrate the latest character and verify listening, speaking and idle motions.'],
  ['Accessible hands-free use', 'Verify TalkBack labels and a spoken workflow without relying on seeing or touching the screen.']
];
export function canUseChecklist(user, config, data) {
  return Boolean(config && isPrivateTester(user, config) && data.privateTest?.userId === user.id);
}
const fail=(status,message)=>{throw Object.assign(new Error(message),{status});};
export function testingChecklist(store) {
  return async function operate(user,config,method,id,input={}) {
    return store.update(data=>{
      if(!canUseChecklist(user,config,data))fail(404,'not_found');
      const rows=data.testingChecklist ||= [];
      if(!rows.some(x=>x.userId===user.id) && !data.testingChecklistSeeded) {
        rows.push(...initialTests.map(([title,notes])=>({id:randomUUID(),userId:user.id,title,notes,status:'todo',createdAt:new Date().toISOString(),completedAt:null})));
        data.testingChecklistSeeded=true;
      }
      if(method==='GET'&&!id)return {items:rows.filter(x=>x.userId===user.id)};
      if(method==='POST'&&!id){
        if(typeof input.title!=='string'||!input.title.trim()||input.title.length>160)fail(400,'title_required_max_160');
        if(input.notes!==undefined&&(typeof input.notes!=='string'||input.notes.length>4000))fail(400,'notes_max_4000');
        const item={id:randomUUID(),userId:user.id,title:input.title.trim(),notes:input.notes||'',status:'todo',createdAt:new Date().toISOString(),completedAt:null};
        rows.push(item);return {item};
      }
      if(method==='PATCH'&&id){
        const item=rows.find(x=>x.id===id&&x.userId===user.id);if(!item)fail(404,'not_found');
        if(!['todo','testing','done'].includes(input.status))fail(400,'invalid_status');
        item.status=input.status;item.completedAt=input.status==='done'?(item.completedAt||new Date().toISOString()):null;
        return {item};
      }
      fail(405,'method_not_allowed');
    });
  };
}
