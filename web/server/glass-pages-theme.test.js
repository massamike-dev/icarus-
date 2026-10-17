import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {transform} from 'lightningcss';
import postcss from 'postcss';

const source = readFileSync(new URL('../src/glass-pages.css', import.meta.url), 'utf8');
const sheet = postcss.parse(source);
const rulesFor = selector => {
  const matches = [];
  sheet.walkRules(rule => { if (rule.selectors.includes(selector)) matches.push(rule); });
  return matches;
};
const declares = (selector, property, value) => rulesFor(selector).some(rule =>
  rule.nodes.some(node => node.type === 'decl' && node.prop === property && node.value === value));

test('supporting-page glass compiles for Android WebView without CSS warnings', () => {
  const result = transform({filename: 'glass-pages.css', code: Buffer.from(source), targets: {chrome: 100 << 16}, errorRecovery: false});
  assert.deepEqual(result.warnings, []);
  assert.ok(result.code.length > 0);
  assert.doesNotMatch(source, /url\(|@import/);
});

test('real Chat, Memory, settings, integration, vehicle, and auth surfaces share glass', () => {
  for (const selector of ['.settings-section', '.history-panel', '.integration-card', '.hud-option', '.premium-plan', '.voice-workspace > .voice-controls', '.auth > form', '.memory-list article', '.empty']) {
    assert.ok(declares(selector, 'background', 'var(--page-glass)'), `Missing glass surface: ${selector}`);
  }
  assert.ok(declares('.messages article.user', 'border-left', '2px solid var(--gold)'));
  assert.ok(declares('.messages article.assistant', 'border-left', '2px solid var(--sky)'));
  assert.ok(declares('.workspace .secondary[aria-pressed="true"]', 'border-color', 'var(--gold)'));
});

test('native dialog visibility and scrolling stay controlled by the dialog element', () => {
  for (const rule of rulesFor('.settings-dialog')) {
    assert.ok(!rule.nodes.some(node => node.type === 'decl' && ['display', 'visibility', 'position'].includes(node.prop)));
  }
  assert.ok(declares('.settings-dialog', 'overflow', 'auto'));
  assert.ok(declares('.settings-dialog', 'max-height', 'min(80svh, calc(100dvh - 32px))'));
  assert.ok(declares('.settings-section > :last-child:not(dialog)', 'margin-bottom', '0'));
  assert.ok(declares('.settings-dialog[aria-labelledby="delete-local-model-title"] .primary', 'background', '#48212b'));
});

test('supporting controls retain touch targets, disabled cues, and accessibility fallbacks', () => {
  for (const selector of ['.history-panel button', '.message-tools button', '.memory-list article button', '.workspace .secondary', '.chat-main > label']) {
    assert.ok(declares(selector, 'min-height', '44px'), `Missing touch target: ${selector}`);
  }
  assert.ok(declares('.workspace button:disabled', 'background', '#102c4b'));
  assert.ok(declares('.workspace .settings-error', 'color', '#ffb1a1'));
  const media = [];
  sheet.walkAtRules('media', rule => media.push(rule.params));
  assert.ok(media.includes('(max-width: 420px)'));
  assert.ok(media.includes('(prefers-reduced-motion: reduce)'));
  assert.ok(media.includes('(forced-colors: active)'));
  assert.ok(declares('.history-panel button.current', 'outline', '2px solid Highlight'));
});

test('stacked Vehicle actions keep content height instead of the mobile row flex basis', () => {
  const vehicle = postcss.parse(readFileSync(new URL('../src/vehicle.css', import.meta.url), 'utf8'));
  let columnActions = false, fullTouchTarget = false;
  vehicle.walkRules(rule => {
    if (rule.selectors.includes('.hud-option .settings-actions')) {
      columnActions ||= rule.nodes.some(node => node.type === 'decl' && node.prop === 'flex-direction' && node.value === 'column');
    }
    if (rule.selectors.includes('.hud-option button')) {
      fullTouchTarget ||= rule.nodes.some(node => node.type === 'decl' && node.prop === 'min-height' && node.value === '48px');
    }
  });
  assert.ok(columnActions, 'The Vehicle action group uses a vertical flex axis');
  assert.ok(fullTouchTarget, 'Vehicle controls retain their 48px touch targets');
  assert.ok(declares('.settings-actions > button', 'flex', '1 1 160px'), 'Row groups still wrap usefully on small screens');
  assert.ok(declares('.hud-option .settings-actions > button', 'flex', '0 1 auto'), 'The more-specific Vehicle rule must reset the basis to content height');
  assert.ok(rulesFor('.hud-option .settings-actions > button').some(rule => rule.parent.type === 'root'), 'The vertical-axis reset must apply at every viewport, including 320px and 393px');
});
