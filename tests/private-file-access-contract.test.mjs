import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync} from 'node:fs';

const privateManifestUrl=new URL('../native/android/app/src/privateTest/AndroidManifest.xml',import.meta.url);
const mainManifest=readFileSync(new URL('../native/android/app/src/main/AndroidManifest.xml',import.meta.url),'utf8');
const bridge=readFileSync(new URL('../native/android/app/src/main/java/com/icarusalmighty/app/IcarusNativeBridge.kt',import.meta.url),'utf8');

test('All files access is private-test-only and exposed through native status',()=>{
  assert.equal(existsSync(privateManifestUrl),true);
  const privateManifest=readFileSync(privateManifestUrl,'utf8');
  assert.match(privateManifest,/android\.permission\.MANAGE_EXTERNAL_STORAGE/);
  assert.doesNotMatch(mainManifest,/android\.permission\.MANAGE_EXTERNAL_STORAGE/);
  assert.match(bridge,/"all_files_access_status"/);
  assert.match(bridge,/"open_all_files_access_settings"/);
  assert.match(bridge,/ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION/);
  assert.match(bridge,/Environment\.isExternalStorageManager\(\)/);
  assert.match(bridge,/\.put\("allFilesAccess"/);
});
