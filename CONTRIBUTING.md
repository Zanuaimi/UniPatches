# Contributing to UniPatches

## Universal Overlay contributions

For Universal Overlay changes, read [UNIVERSAL_OVERLAY_MODULES.md](UNIVERSAL_OVERLAY_MODULES.md)
before adding or changing a module. It documents runtime boundaries, configuration wiring,
lifecycle rules, and failure isolation. For the complete architecture and file map, read
[UNIVERSAL_OVERLAY.md](UNIVERSAL_OVERLAY.md). For UI preset behavior and JSON import/export, read
[UNIVERSAL_OVERLAY_UIPRESETS.md](UNIVERSAL_OVERLAY_UIPRESETS.md) before adding or changing any
UI preset. It contains the preset format, import/export behavior, compatibility rules, and a
complete preset contribution example.

Keep each contribution small and reviewable: one logical change per pull request and one logical
change per commit. Use a feature branch and a semantic commit such as `feat: add ...` or
`fix: handle ...`.

When adding a module:

- Put it in the matching `activity/`, `statistic/`, or `hook/` directory.
- Extend the correct category base class and provide a stable, unique key and user-facing metadata.
- Add a disabled-by-default option, serialize its token, decode it, and register the module.
- Isolate failures and restore mutable Activity state independently.
- Do not add package-, class-, engine-, or APK-specific assumptions.

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
- Update `UNIVERSAL_OVERLAY_UIPRESETS.md` when adding a user-visible preset.
- Run the patch-list generator so `patches-list.json` contains the new dropdown value.

Before submitting, run `git diff --check` and verify the patch build. Do not manually edit generated
files such as `patches-list.json`, `patches-bundle.json`, or `CHANGELOG.md`.
