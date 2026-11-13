import test from 'node:test';
import assert from 'node:assert/strict';
import {voiceDraft,sameVoice,validVoiceSettings} from '../src/native-settings.js';
const legacy={supported:true,profile:'deep_warm',voiceName:'',rate:.88,pitch:.82};
test('older app preferences migrate to English without losing delivery',()=>{
 assert.equal(voiceDraft(legacy).language,'en-US');
 assert.ok(sameVoice(legacy,voiceDraft(legacy)));
});
test('language changes are unsaved changes even with the same voice style',()=>{
 const spanish={...legacy,language:'es-MX'};
 assert.ok(validVoiceSettings(spanish));
 assert.equal(sameVoice(legacy,spanish),false);
 assert.equal(voiceDraft(spanish).language,'es-MX');
 assert.equal(validVoiceSettings({...legacy,language:'not a locale'}),false);
});
