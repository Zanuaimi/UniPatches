import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const manifest = readFileSync(new URL('../patches/src/main/kotlin/unipatches/compatibility/LegacyAppCompatibilityManifest.kt', import.meta.url), 'utf8');
test('preset is first declared option and defaults to Custom', () => {
  assert.match(manifest.match(/val \w+ by \w+Option/)[0], /val preset by stringOption/);
  assert.match(manifest, /key = "legacyCompatibilityPreset"/);
});
test('Unity downloader mutation stays fingerprint gated', () => {
  assert.match(manifest, /bypassExpansionDownloader == true && detectedEngines\.unity/);
  assert.match(manifest, /legacyEngineDetectionPatch/);
});
test('every Custom boolean defaults off; OpenIAB defaults to automatic', () => {
  const booleans = [...manifest.matchAll(/val (\w+) by booleanOption\([\s\S]*?default = (true|false)/g)];
  assert.ok(booleans.length > 20);
  for (const [, name, value] of booleans) assert.equal(value, 'false', name);
  assert.match(manifest, /val openIabReceiverRegistrationModeRaw by stringOption\([\s\S]*?default = OPEN_IAB_AUTOMATIC/);
});
test('target uses only toggle and numeric input', () => {
  assert.equal((manifest.match(/key = "legacyCompatibilitySpoofTargetSdk"/g) || []).length, 1);
  assert.match(manifest, /val targetSdkRaw by intOption\([\s\S]*?default = 24/);
  assert.doesNotMatch(manifest, /targetSdkProfile|TARGET_PROFILE|selectLegacyTargetSdk/);
});
test('settings resolve at execution time without caching construction defaults', () => {
  assert.match(manifest, /ReadOnlyProperty<Any\?, T> \{ _, _ -> read\(\) \}/);
  const declarations = [...manifest.matchAll(/val (\w+)Raw by \w+Option/g)];
  for (const [, name] of declarations) {
    if (name === 'playStorePackageVisibility') continue; // resolved inside execute
    assert.match(manifest, new RegExp('val ' + name + ' by option \\{.*' + name + 'Raw'));
  }
  assert.doesNotMatch(manifest, /if \(shouldApplyLegacyCompatibilityMutations/);
  assert.match(manifest, /legacyCompatibilityBytecodePreflightPatch \{ diagnosticsOnly \}/);
});
test('OBB validation dependency precedes downloader bypass hooks', () => {
  const expansion = manifest.indexOf('dependsOn(legacyExpansionFilesPatch {');
  const runtime = manifest.indexOf('dependsOn(legacyRuntimeHooksPatch {');
  assert.ok(expansion >= 0 && runtime > expansion);
});
test('repair missing exported flags is gated by final target API', () => {
  assert.match(manifest, /shouldRepairMissingExportFlags\(repairExportFlags == true, update\.finalTarget\)/);
});
test('logs contain no duplicate raw/effective settings or preset override claims', () => {
  assert.doesNotMatch(manifest, /appendLine\("  (raw|effective)/);
  assert.doesNotMatch(manifest, /overrides (raw|the saved)/);
  assert.match(manifest, /removed preset \$preset migrated to Custom/);
});
