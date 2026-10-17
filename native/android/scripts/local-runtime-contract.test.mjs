import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const read=name=>readFileSync(new URL(`../app/src/main/java/com/icarusalmighty/app/${name}.kt`,import.meta.url),'utf8');
const activity=read('MainActivity'), model=read('LocalModelManager'), voice=read('HandsFreeSession');
const gradle=readFileSync(new URL('../app/build.gradle.kts',import.meta.url),'utf8');

test('all Android variants bundle a freshly built relative-base web UI',()=>{
  assert.match(gradle,/commandLine\("npm", "run", "build", "--", "--base=\.\/"\)/);
  assert.match(gradle,/tasks\.named\("preBuild"\)\.configure \{ dependsOn\(bundleWebAssets\) \}/);
  assert.match(gradle,/sourceSets\["main"\]\.assets\.srcDir/);
  assert.match(gradle,/from\(webDirectory\.dir\("dist"\)\)/);
});

test('incremental Android builds observe shared browser/server runtime modules',()=>{
  const inputs=gradle.match(/inputs\.files\(fileTree\(webDirectory\) \{([\s\S]*?)\n    \}\)/)?.[1];
  assert.ok(inputs,'Bundled web build must declare its source inputs');
  assert.match(inputs,/include\([^\n]*"server\/\*\*"/);
  assert.match(inputs,/include\([^\n]*"src\/\*\*"/);
  // These modules are bundled into the browser; server-only input tracking
  // would otherwise leave stale native command rules and Chat context in APKs.
  for(const file of ['commands.js','assistant-context.js','assistant-behavior.js']) {
    assert.ok(readFileSync(new URL(`../../../web/server/${file}`,import.meta.url),'utf8').length>0);
  }
});

test('local defaults to appassets without requiring a configured server',()=>{
  const mode=read('RuntimeMode');
  assert.match(mode,/getString\("mode", LOCAL\)/);
  assert.match(mode,/if \(value == CLOUD\) CLOUD else LOCAL/);
  assert.match(mode,/https:\/\/appassets\.androidplatform\.net/);
  assert.match(mode,/automaticCloudFallback", false/);
  assert.match(activity,/webView\.loadUrl\(url\)/);
  assert.doesNotMatch(activity,/loadDataWithBaseURL/);
});

test('local assets cannot fall through to remote networking',()=>{
  assert.match(activity,/assetLoader\.shouldInterceptRequest\(request\.url\) \?: blockedResponse\(404/);
  assert.match(activity,/RuntimeMode\.current\(this@MainActivity\) == RuntimeMode\.LOCAL\) return blockedResponse\(403/);
  assert.match(activity,/addPathHandler\("\/brand\/"/);
  assert.match(activity,/addPathHandler\("\/models\/"/);
  assert.match(activity,/allowFileAccess = false/);
  assert.match(activity,/allowContentAccess = false/);
});

test('bridge authorization binds active origin, main frame and response generation',()=>{
  assert.match(activity,/if \(!isMainFrame \|\| !isTrustedPage\(\)/);
  assert.match(activity,/TrustedWebPolicy\.isTrustedUrl\(sourceOrigin\.toString\(\), RuntimeMode\.activeUrl\(this\)\)/);
  assert.match(activity,/requestPages\[actionRequestId\] = pageGeneration/);
  assert.match(activity,/requestPages\.remove\(requestId\) != pageGeneration/);
  assert.match(activity,/override fun onPageStarted[\s\S]*?invalidatePage\(\)/);
  assert.match(activity,/private fun invalidatePage[\s\S]*?pageGeneration\+\+[\s\S]*?requestPages\.clear\(\)/);
  assert.match(activity,/private fun dispatchNativeStatus[\s\S]*?generation != pageGeneration/);
  assert.match(activity,/override fun onResults[\s\S]*?generation != pageGeneration/);
});

test('deterministic commands run before local interpretation; cloud needs explicit mode',()=>{
  assert.match(voice,/HandsFreeCommands\.parse\(text\)[\s\S]*?if \(command != null\) accept\(ProposedAction\(command\)\) else interpret\(text\)/);
  assert.match(voice,/RuntimeMode\.current\(context\) == RuntimeMode\.LOCAL\) \{ interpretLocally\(text\); return \}/);
  assert.match(voice,/LocalModelManager\.get\(context\)\.interpretCommand/);
  assert.match(voice,/LocalCommandResult\.handsFree\(data\)[\s\S]*?accept\(ProposedAction\(command, modelInterpreted = true\)\)/);
  assert.match(voice,/check\(RuntimeMode\.current\(context\) == RuntimeMode\.CLOUD && VoiceSessionStore\.isCurrent/);
  assert.match(voice,/RuntimeMode\.current\(context\) != RuntimeMode\.CLOUD \|\| session\.temporary/);
  assert.match(voice,/HandsFreeActionPolicy\.requiresConfirmation\(c.action, proposed.modelInterpreted\)/);
  assert.match(voice,/private fun execute\(proposed: ProposedAction\)[\s\S]*?RuntimeMode\.isCurrent\(runtimeGeneration\)/);
});

test('inference does not retain a conversation or silently call a hosted provider',()=>{
  assert.match(model,/private constructor/);
  assert.match(model,/busy\.compareAndSet\(false, true\)/);
  assert.match(model,/val session = getEngine\(\)\.createConversation/);
  assert.match(model,/finally \{ runCatching \{ session\.close\(\) \} \}/);
  assert.doesNotMatch(model,/private (?:var|val) conversation/);
  assert.doesNotMatch(model,/HttpURLConnection|\/api\/chat|Authorization/);
  assert.match(model,/MessageDigest\.getInstance\("SHA-256"\)/);
});
