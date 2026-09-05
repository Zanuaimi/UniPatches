# UniPatches Universal Overlay

## Purpose

Universal Overlay Patch is a runtime overlay for supported Android APKs. It is designed to work
without knowing the target app's package, engine, Activity names, or internal game logic. The same
architecture can run in ordinary Android apps, Unity games, Godot games, and apps that move between
multiple Activities.

The patch adds an in-app floating button and an optional menu. The menu is configured before
patching, while selected modules can be enabled, disabled, and customized at runtime. The overlay
belongs to the patched app's Activity content; it is not an Android system-level window.

## UI presets

Version 1.2 adds build-time UI presets. `Custom` uses the visible Morphe settings, including user
adjustments. `UniPatches`, `Morphe Blue`, `Dark`, `Light`, and `ZArchiver` provide predefined
readable UI values. ZArchiver uses an opaque gray menu, white text, a green outline, and a
non-gradient dark-green `Z` icon.
The UniPatches default values are intentionally identical to the default Custom values.

Presets cover every General, UI, and Advanced setting, including the custom icon input. They do not
contain the Settings to Modules group or any Statistic, Activity, or Hook module toggle. Those values
remain independent because hook and module combinations can be app-specific and may prevent an APK
from working correctly.

`Import UI preset` accepts a path to a JSON file and is used only in Custom mode. A valid supported
preset overrides the visible settings during patching; an empty, unreadable, malformed, or
unsupported file falls back to the visible Morphe settings. Morphe Manager's visible controls do
not change when a preset is imported because settings are evaluated during patch execution.

`Export UI preset` accepts an existing output folder and is used only in Custom mode. The final
effective UI configuration is exported after the patch work completes. The default filename is
`UniversalOverlay.json`; `.json` is normalized and duplicate names receive `-1`, `-2`, and so on.
Export failures are logged but never cancel APK patching. Filesystem roots and protected locations
are rejected across platforms: Android paths must be below emulated storage, Unix paths such as
`/system`, `/etc`, `/usr`, and `/var`, macOS locations such as `/Applications` and `/Library`, and
Windows locations such as `Windows`, `Program Files`, and `ProgramData` are refused. Normal user
folders such as macOS `/Users`, Linux `/home`, and Windows `C:\\Users` remain valid.

## File map

### Patch and injection

patches/src/main/kotlin/unipatches/overlay/UniversalOverlayPatch.kt

This is the Morphe patch entry point. It:

- declares the patch name, description, and Morphe settings;
- validates user configuration;
- serializes configuration into a Base64-delimited payload;
- includes the extension DEX through extensions/extension.mpe;
- finds the real Application onCreate method when possible;
- injects a small bridge call using safe temporary registers;
- falls back to a suitable Activity onCreate method when an Application entry point is unavailable.

It should contain patch-time discovery and configuration only. Runtime UI and feature behavior belong
in the extension Java code.

patches/src/main/kotlin/unipatches/overlay/presets/

This contains the shared preset model, one definition file per built-in preset, and the centralized
catalog registry. Each `OverlayPresetDefinition` contains explicit General, UI, and Advanced values.
The Morphe preset dropdown is generated from the catalog, so contributors do not maintain a second
list in the patch entry point.

patches/src/main/kotlin/helpers/startup/StartupHooks.kt

The overlay reuses StartupHooks for Application discovery, launcher Activity fallback information,
and safe Smali string escaping. This is a shared patch helper, not an overlay-specific runtime.

The overlay does not require target-specific Fingerprints. It discovers framework relationships and
uses the platform Application or Activity lifecycle so it remains universal.

## Structure at a glance

The patch is split into a small patch-time layer and a larger runtime extension layer:

```text
UniPatches
|
|-- patches/src/main/kotlin/unipatches/overlay/
|   `-- UniversalOverlayPatch.kt       Morphe settings and safe injection bridge
|   |-- presets/OverlayPreset.kt         Shared preset model and value builder
|   |-- presets/OverlayPresetCatalog.kt Central preset registry
|   |-- presets/UniPatchesPreset.kt     UniPatches preset
|   |-- presets/MorpheBluePreset.kt     Morphe Blue preset
|   |-- presets/DarkPreset.kt            Dark preset
|   |-- presets/LightPreset.kt           Light preset
|   `-- presets/ZArchiverPreset.kt       ZArchiver preset
|-- patches/src/main/kotlin/helpers/startup/
|   `-- StartupHooks.kt                Application/activity discovery and Smali helpers
|-- patches/src/main/kotlin/helpers/manifest/
|   `-- ManifestUtils.kt                Manifest helpers used by StartupHooks
|-- patches/src/main/kotlin/helpers/bytecode/
|   `-- BytecodeUtils.kt                Safe register-preserving method cloning
|
`-- extensions/extension/src/main/java/unipatch/universaloverlay/
    |-- UniversalOverlayRuntime.java   Runtime coordinator and Activity controllers
    |-- UniversalOverlayLifecycle.java Lifecycle callback adapter
    |-- UniversalOverlayConfig.java    Configuration decoder and fallbacks
    |-- UniversalOverlayViews.java     Shared view and style construction
    `-- modules/
        |-- UniversalOverlayModule.java          common contract
        |-- UniversalOverlayActivityModule.java  activity base class
        |-- UniversalOverlayStatisticModule.java statistic base class
        |-- UniversalOverlayHookModule.java      hook base class
        |-- activity/                            Activity implementations
        |-- statistic/                           statistic implementations
        `-- hook/                                hook implementations
```

Module inheritance is intentionally separated by responsibility:

```text
UniversalOverlayModule
|                         common identity and metadata
|-- UniversalOverlayActivityModule
|   `-- Activity modules    Activity state, apply, restore, isolation
|
|-- UniversalOverlayStatisticModule
|   `-- Statistic modules  values, monitors, scheduling, isolation
|
`-- UniversalOverlayHookModule
    `-- Hook modules       best-effort hooks and hook isolation
```

The runtime coordinates all three branches. Activity, statistic, and hook base classes do not
inherit from one another, so adding a module to one category does not couple it to another
category's lifecycle or failure behavior.

### Runtime entry and configuration

extensions/extension/src/main/java/unipatch/universaloverlay/UniversalOverlayRuntime.java

This is the runtime coordinator. It:

- starts from the injected Application or Activity bridge;
- registers ActivityLifecycleCallbacks;
- creates one controller for each resumed Activity;
- attaches the overlay to Activity content;
- keeps the button and menu above the Activity content;
- installs and removes statistic monitors;
- coordinates module state, lifecycle state, and restoration;
- isolates failures so an unsupported feature does not crash the host app;
- fully closes and unregisters the overlay across all Activities.

The controller is Activity-specific. Shared state is used only for intentional cross-Activity
settings such as module toggles, monitor toggles, button position, and temporary Activity feature
state.

extensions/extension/src/main/java/unipatch/universaloverlay/UniversalOverlayLifecycle.java

This is the lifecycle adapter. It forwards Activity resume, pause, and destroy events to the runtime.
The runtime creates a controller on resume, pauses work when an Activity is not visible, and removes
the controller when the Activity is destroyed.

extensions/extension/src/main/java/unipatch/universaloverlay/UniversalOverlayConfig.java

This decodes and sanitizes the payload produced by the Kotlin patch. Configuration fields are
positional, so new fields are appended and version-aware defaults preserve older payloads.
Malformed colors, enums, dimensions, and missing values receive safe fallbacks.

The current configuration uses RGB-only color values in `#RRGGBB` format. Overlay background
transparency is serialized separately as a percentage because Morphe's color picker does not
edit alpha. The default background `#300000` with 80% opacity reproduces the previous `#CC300000`
value. Menu outline and menu text are independent settings, and the icon outline width is
independent from the menu outline width. Version 1.2 also stores the configurable legacy icon text
size and supports two build-time custom icon inputs: a local image file and a String Handler input.
The local image takes priority when valid; otherwise file URI, Base64, data URI, and HTTPS inputs are
tried before falling back to the legacy icon.

The user-facing preset format is versioned JSON and is separate from the internal Base64-delimited
runtime payload. Unknown JSON fields are ignored, missing fields use the visible settings, and
recognized values are range-checked before use. This allows future UI settings to be added without
invalidating older presets.

### Shared view construction

extensions/extension/src/main/java/unipatch/universaloverlay/UniversalOverlayViews.java

This contains reusable overlay backgrounds, gradient rendering, outline rendering, and selectable
button backgrounds. It keeps visual construction separate from lifecycle and module behavior.

Overlay views use an isolated Android theme context and then apply configured colors explicitly. This
prevents a host app's legacy or custom theme from changing checkbox, spinner, slider, or overlay
button appearance.

## Module categories

The module base classes are placed in:

extensions/extension/src/main/java/unipatch/universaloverlay/modules/

Concrete implementations are placed in the matching activity/, statistic/, or hook/ subdirectory.

UniversalOverlayModule.java is the common module contract. It provides the stable identity and
user-facing metadata shared by all categories.

### Activity modules

UniversalOverlayActivityModule.java is the base class for temporary behavior tied to the current
Activity or its Window. Examples include fullscreen, screenshots, brightness, rotation, and audio
state.

The base class owns Activity-module isolation and restoration boundaries. Each module captures the
state it changes, applies only its own behavior, and restores independently. A failure in one
Activity module must not stop other modules from being toggled or restored.

### Statistic modules

UniversalOverlayStatisticModule.java is the base class for values displayed in the menu and/or
floating monitors. It manages enabled state, Monitor state, menu visibility, monitor binding, and
safe update scheduling.

Statistic modules should avoid unnecessary work:

- menu values update only while the menu is open;
- floating monitors update only while the menu is closed;
- monitor sampling runs only when both Monitor and Active are enabled;
- static or rarely changing values should be cached;
- timers and polling must stop when the module or overlay is inactive.

The runtime owns monitor placement, columns, dimensions, alpha, and click-through behavior.

### Hook modules

UniversalOverlayHookModule.java is the base class for best-effort runtime hooks that affect views or
host behavior without changing a specific app's internal business logic.

The base class owns hook isolation. Hooks must tolerate unsupported APIs, unexpected view types, and
dynamic host behavior. Hook failures are contained so they do not propagate to the app or prevent
other module categories from operating.

## Runtime flow

1. Morphe builds the patch with the selected settings and, in Custom mode, optionally imports a UI preset.
2. The patch injects a bridge into Application.onCreate, or uses an Activity fallback.
3. UniversalOverlayRuntime decodes the configuration and registers lifecycle callbacks.
4. Each resumed Activity receives its own controller and overlay content.
5. The controller creates only the selected module categories.
6. Users control module state from the menu; state can be remembered across Activities for the
   current process.
7. Menu and monitor updates follow their separate visibility rules.
8. Pause stops visible-work updates, destruction removes the controller, and full close removes all
   controllers and unregisters the runtime.
9. In Custom mode, an optional UI preset is exported after patching. Export errors do not affect the
   patched APK.

If content attachment is unavailable for an unusual Activity, setup failure is caught and the host
app continues without the overlay. The implementation does not create a system-level overlay.

## Adding future modules

For the practical module checklist, read UNIVERSAL_OVERLAY_MODULES.md. In short, a new module needs:

1. A class extending the correct category base class.
2. A disabled-by-default Morphe option.
3. A configuration token serialized by UniversalOverlayPatch.kt.
4. A decoder field in UniversalOverlayConfig.java.
5. Registration in UniversalOverlayRuntime.java in the intended category/order.
6. Independent failure handling and restoration where state is changed.
7. A description update only when the user-facing patch overview needs a new representative example.

Do not add package-specific assumptions, do not retain destroyed Activities in static fields, and do
not move runtime feature logic into generated Smali.

## Maintenance and release notes

The extension DEX is part of the patch architecture, so changes commonly involve both the Kotlin
patch and Java runtime files. The release workflow builds the patch; local patch building is not
required for the normal development workflow used by this project.

Before submitting changes:

- inspect all related files for configuration and lifecycle rewiring;
- preserve older configuration decoding when adding fields;
- run git diff --check;
- test at least one ordinary app, one game, and one multi-Activity app when practical;
- use a semantic feat:, fix:, or chore: commit so semantic release can recognize it.

The idea and initial works of Universal Overlay Patch are from Zanuaimi / Noobite.
