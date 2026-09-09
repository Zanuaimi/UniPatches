# Contributing to UniPatches

## Overlay contributions

For shared Overlay Core, Universal Overlay, or app-specific overlay changes, read [OVERLAY_MODULES.md](OVERLAY_MODULES.md)
before adding or changing a module. It documents runtime boundaries, configuration wiring,
lifecycle rules, and failure isolation. For the complete architecture and file map, read
[OVERLAY.md](OVERLAY.md). For UI preset behavior and JSON import/export, read
[OVERLAY_UI_PRESETS.md](OVERLAY_UI_PRESETS.md) before adding or changing any
UI preset. It contains the preset format, import/export behavior, compatibility rules, and a
complete preset contribution example.

Keep each contribution small and reviewable: one logical change per pull request and one logical
change per commit. Use a feature branch and a semantic commit such as `feat: add ...` or
`fix: handle ...`.

When adding a universal module:

- Put it in the matching `activity/`, `statistic/`, or `hook/` directory.
- Extend the correct category base class and provide a stable, unique key and user-facing metadata.
- Add a disabled-by-default option, serialize its token, decode it, and register the module.
- Isolate failures and restore mutable Activity state independently.
- Do not add package-, class-, engine-, or APK-specific assumptions.

App-specific modules belong in an app-specific patch/provider. They may use target fingerprints, but
must use explicit target Activity injection with a controlled universal fallback and must fail closed
when the target module is unsupported.

### Adding an app-specific overlay patch

Reuse the shared Overlay Core and universal modules. Add only the app-specific provider/module
classes, target fingerprints, explicit Activity injection, fallback policy, profile ID, and patch
defaults. Give the patch its own name and description. Do not duplicate `OverlayRuntime`, UI,
configuration decoding, animations, or universal module implementations. Document the target app,
fingerprints, unsupported versions, and why each app-specific module is safe.

The shared runtime supports an app-specific profile ID and an explicit-Activity-first injection mode.
Use those shared mechanisms instead of adding a second runtime or a second copy of the universal
module system.

### Adding a UI preset

UI presets are build-time Kotlin data definitions. They belong under:

`patches/src/main/kotlin/unipatches/overlay/presets/`

Do not place preset definitions in `extensions/extension`; that directory is for code that runs
inside the patched APK.

To contribute a preset:

- Add one Kotlin file under `presets/` containing an `OverlayPresetDefinition` with a stable
  lowercase ID, display name, short description, and explicit values for every `OverlayUiPreset`
  field.
- Do not use `custom.copy(...)` for built-in presets. Explicit values prevent accidental inheritance
  of a user’s Custom icon or future settings.
- Keep Modules and Settings to Modules out of presets. Hook and module combinations can be
  app-specific and may cause a patched app to fail.
- Register the definition in `OverlayPresetCatalog.kt`. The Morphe `Selected preset` dropdown is
  generated from the catalog automatically; do not add a second manually maintained preset list.
- Choose readable colors and check the menu, text, outline, icon, opacity, and gradient together.
- Update `OVERLAY_UI_PRESETS.md` when adding a user-visible preset.
- Run the patch-list generator so `patches-list.json` contains the new dropdown value.

Before submitting, run `git diff --check` and verify the patch build. Do not manually edit generated
files such as `patches-list.json`, `patches-bundle.json`, or `CHANGELOG.md`.
