import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const manifest = readFileSync(new URL('../patches/src/main/kotlin/unipatches/compatibility/LegacyAppCompatibilityManifest.kt', import.meta.url), 'utf8');
test('preset is first declared option and defaults to Custom', () => {
  assert.match(manifest.match(/val \w+ by \w+Option/)[0], /val preset by stringOption/);
  assert.match(manifest, /key = "legacyCompatibilityPreset"/);
});
test('Unity native mutation is independently fingerprint gated', () => {
  assert.match(manifest, /detectedEngines\.unity/);
  assert.match(manifest, /legacyEngineDetectionPatch/);
});
test('Custom conservative defaults avoid bulk changes', () => {
  for (const name of ['legacyReviverRaw', 'allScreensRaw'])
    assert.match(manifest, new RegExp('val ' + name + ' by booleanOption\\([\\s\\S]*?default = false'));
});
test('all option delegates feed the effective preset boundary', () => {
  const ordinary = [...manifest.matchAll(/val (\w+)Raw by \w+Option/g)];
  assert.equal(ordinary.length, 34);
  for (const [, name] of ordinary) {
    if (name === 'playStorePackageVisibility') continue;
    assert.match(manifest, new RegExp('val ' + name + ' = .*' + name + 'Raw'));
  }
  assert.match(manifest, /val playStorePackageVisibility = LegacyCompatibilityPresets\.playStorePackageVisibility\([\s\S]*?playStorePackageVisibilityRaw,\s*detectedEngines\.openIab/);
});
