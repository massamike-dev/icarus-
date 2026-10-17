import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const source=path=>readFileSync(new URL(path,import.meta.url),'utf8');

test('dashboard interaction panel has its own flow space above clipped artwork',()=>{
  const css=source('../src/companion.css');
  assert.match(css,/\.companion-figure\{[^}]*z-index:0;[^}]*isolation:isolate;[^}]*overflow:hidden;/);
  assert.match(css,/\.companion-panel\{[^}]*z-index:2;/);
  const dashboard=source('../src/dashboard.css');
  assert.match(dashboard,/\.companion-dashboard \.companion-panel \{[^}]*position: relative;[^}]*transform: none;/);
});

test('mobile content reserves opaque navigation space and scroll clearance',()=>{
  const css=source('../src/dashboard.css');
  assert.match(css,/\.app-shell \{ padding-bottom: calc\(var\(--mobile-nav-height\) \+ 24px \+ env\(safe-area-inset-bottom\)\);/);
  assert.match(css,/scroll-padding-bottom: calc\(var\(--mobile-nav-height\) \+ 24px/);
  assert.match(css,/\.app-shell > aside \{[^}]*background: #062551;/);
  assert.match(css,/\.app-header \{ height: calc\(62px \+ env\(safe-area-inset-top\)\)/);
});

test('Android owns consumed system bar, cutout, and keyboard insets for the WebView viewport',()=>{
  const activity=source('../../native/android/app/src/main/java/com/icarusalmighty/app/MainActivity.kt');
  assert.match(activity,/WindowCompat.setDecorFitsSystemWindows\(window, false\)/);
  assert.match(activity,/WindowInsetsCompat.Type.systemBars\(\) or WindowInsetsCompat.Type.displayCutout\(\)/);
  assert.match(activity,/WindowInsetsCompat.Type.ime\(\)/);
  assert.match(activity,/view.setPadding\(bars.left, bars.top, bars.right, maxOf\(bars.bottom, keyboard.bottom\)\)/);
  assert.match(activity,/WindowInsetsCompat.CONSUMED/);
  assert.match(activity,/ViewCompat.requestApplyInsets\(rootView\)/);
});
