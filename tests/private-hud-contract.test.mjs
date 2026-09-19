import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const read=path=>readFileSync(new URL(`../${path}`,import.meta.url),'utf8');
const gradle=read('native/android/app/build.gradle.kts');
const bridge=read('native/android/app/src/main/java/com/icarusalmighty/app/IcarusNativeBridge.kt');
const driving=read('native/android/app/src/main/java/com/icarusalmighty/app/driving/DrivingHudView.kt');
const volumetric=read('native/android/app/src/main/java/com/icarusalmighty/app/driving/VolumetricHudSurface.kt');
const manifest=read('native/android/app/src/main/AndroidManifest.xml');

test('HUD update is newer than Test 1.6.9 build 40 and preserves the private app identity',()=>{
  assert.ok(Number(gradle.match(/versionCode\s*=\s*(\d+)/)?.[1])>=41);
  assert.equal(gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1],'1.6.10');
  assert.match(gradle,/applicationId\s*=\s*"com\.icarusalmighty\.app"/);
  const variant=gradle.split('create("privateTest")')[1]?.split('compileOptions')[0];
  assert.ok(variant,'the existing privateTest variant must remain');
  assert.match(variant,/applicationIdSuffix\s*=\s*"\.test"/);
  assert.match(variant,/versionNameSuffix\s*=\s*"-test"/);
  assert.match(variant,/buildConfigField\("String",\s*"ICARUS_WEB_URL",\s*javaString\(testWebUrl\)\)/);
  assert.match(variant,/buildConfigField\("boolean",\s*"PRIVATE_TEST",\s*"true"\)/);
  assert.match(variant,/"icarus-test"/);
  assert.match(variant,/"UPDATE_NOTES_URL",\s*javaString\(""\)/,'a private APK must not use the public update feed');
});

test('the real Android bridge advertises the HUD protocol and registers the web controls',()=>{
  assert.match(bridge,/\.put\("hudControlVersion",\s*1\)/);
  for(const [action,handler] of [['open_driving_hud','openDrivingHud'],['xreal_status','xrealStatus'],['open_xreal_hud','openXrealHud'],['close_xreal_hud','closeXrealHud']]){
    assert.match(bridge,new RegExp(`"${action}"\\s*->\\s*${handler}\\(requestId,\\s*args\\)`));
  }
  assert.match(bridge,/"open_navigation_access_settings"\s*->\s*openNavigationAccessSettings\(requestId\)/);
});

test('driving HUD is event-driven and can receive real navigation guidance',()=>{
  assert.match(volumetric,/RENDERMODE_WHEN_DIRTY/);
  assert.match(volumetric,/requestRender\(\)/);
  assert.doesNotMatch(driving,/postInvalidateOnAnimation/);
  assert.match(driving,/navigationInstruction/);
  assert.match(driving,/GOOGLE MAPS OR WAZE|Google Maps or Waze/);
  assert.match(manifest,/NavigationNotificationService/);
  assert.match(manifest,/BIND_NOTIFICATION_LISTENER_SERVICE/);
});
