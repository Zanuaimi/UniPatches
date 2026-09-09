# UniPatches Overlay Architecture

## Purpose

The shared Overlay Core is the runtime foundation for supported Android APKs. The Universal Overlay
Patch is its general-purpose patch; app-specific overlay patches can reuse the same core while adding
target-specific modules and injection rules. The core is designed to work
without knowing the target app's package, engine, Activity names, or internal game logic. The same
architecture can run in ordinary Android apps, Unity games, Godot games, and apps that move between
multiple Activities.

The patch adds an in-app floating button and an optional menu. The menu is configured before
patching, while selected modules can be enabled, disabled, and customized at runtime. The overlay
belongs to the patched app's Activity content; it is not an Android system-level window.

## Overlay variants

Every overlay variant uses the same Overlay Core. A variant supplies identity, configuration defaults,
module selection, and patch-time injection strategy:

```text
Overlay Core
├── UI, animations, themes, presets, configuration, and lifecycle
├── Universal Activity, statistic, and hook modules
├── OverlayAppSpecificModule contract and provider registry
└── failure isolation and Activity state restoration

Universal Overlay Patch
├── universal automatic Application/Activity discovery by default
└── exposes selected universal modules

App-Specific Overlay Patch
├── explicit target-Activity injection by default
├── controlled universal injection fallback when the target is unavailable
├── selected universal modules
└── app-specific modules for one known application
```

The current runtime package is `unipatch.overlaycore`. Universal module implementations live beside
the core because they are reusable by both variants. App-specific modules extend
`OverlayAppSpecificModule`; they are displayed in their own menu section and are isolated so a target
fingerprint or hook failure cannot prevent universal modules from loading.

App-specific patches should use a unique profile ID, patch name, description, and default overlay
title. They must not copy the core UI or runtime. A future app-specific patch can register a provider:

```java
OverlayRuntime.registerAppSpecificProvider(new OverlayAppSpecificModuleProvider() {
    @Override public String profileId() { return "example-game"; }
    @Override public List<OverlayAppSpecificModule> create(Activity activity) {
        return Arrays.asList(new ExampleGameToolsModule(activity));
    }
});
```

Its patch-time configuration selects `example-game`, injects into the known target Activity, and uses
the universal discovery strategy only as a controlled fallback.

### App-specific patch template

The following is a design template, not a copy-and-paste patch. A contributor should preserve the
shared configuration field contract when adding the first concrete app-specific patch. The shared
Kotlin serializer is `OverlayConfigPayload.kt`; it owns the wire-format version, Base64 field
encoding, profile metadata, and injection-mode validation. A concrete app-specific patch still owns
its settings and defaults, but must pass its common fields through this serializer:

```kotlin
@Suppress("unused")
val exampleGameOverlayPatch = bytecodePatch(
    name = "Example Game Overlay (Experimental)",
    description = "App-specific overlay built on the shared Overlay Core.",
    default = false,
) {
    extendWith("extensions/extension.mpe")

    // 1. Fingerprint the app's intended Activity.
    // 2. Build the common overlay fields and serialize them with OverlayConfigPayload:
    //      profileId = "example-game"
    //      injectionMode = "explicitActivity"
    //      title/description = app-specific defaults
    // 3. Register the app-specific provider and inject the bridge into the target Activity.
    // 4. If the target is unavailable, invoke the shared universal fallback strategy.
}
```

The target fingerprint must be narrow enough to avoid sign-in, billing, store, and embedded SDK
Activities. The activity banlist still applies to every overlay variant.

## UI presets

Build-time UI presets keep first-time setup simple. `Custom (UniPatches defaults)` uses the visible Morphe settings, including user
adjustments. `UniPatches`, `Morphe-inspired`, `Dark`, `Light`, and `ZArchiver-inspired` provide predefined
readable UI values. ZArchiver-inspired uses an opaque gray menu, white text, a green outline, and a
non-gradient dark-green `Z` icon. `LuckyPatcher-inspired` provides a black and yellow promotional style.
The UniPatches default values are intentionally identical to the default Custom values.

Presets cover the supported UI and Advanced settings, including the custom icon input. The overlay
title, description, repository button text, and repository button URL always come from the visible
Morphe settings and are exceptions to preset application. Presets do not contain the Settings to
Modules group or any Statistic, Activity, or Hook module toggle. Those values remain independent
because hook and module combinations can be app-specific and may prevent an APK from working
correctly.

The current UI settings include legacy or modern control themes, control background and foreground
colors, independent bottom action button styles, five menu text colors, module separator styles,
title icon placement and alignment, square or rounded menu corners, outline width and animation,
and fade, scale, or disabled menu opening and closing animations. Animation duration is shared by
opening and closing, and logarithmic easing applies only to fade and scale menu animations.
The control theme also supports a fixed Monet-style Material You appearance. Its colors come from
the configured preset values and do not depend on the host app, device wallpaper, or system theme.
The optional appended description text is shown below the main description and can be used for
credits or attribution in a UI preset.
Main and appended description text support left, center, and right alignment, with center as the
default.

### Icon parts tutorial

The icon system has two modes: the simple text icon and a Multi-parts icon built from part-list rows.
Choose `Customize appearance > Icon > Type > Multi-parts icon`, then enter one row per part in
`Advanced > Multi-parts icon editor > Part list`:

```text
shape|x|y|width|height|rotation|fill|color1|color2|stroke|opacity|layer|text
```

Coordinates and dimensions are percentages of the icon area. `x=50` and `y=50` center a part.
`fill` is `solid` or `gradient`; colors use `#RRGGBB`; stroke, opacity, and layer are numeric.
Parts are drawn from the lowest layer to the highest. Invalid rows are ignored safely, and the
list is limited to twelve parts.

Single-part example:

```text
triangle|50|50|55|55|0|solid|#4E97F0|#4E97F0|0|100|0
```

Multi-part chevron example:

```text
chevron|50|52|64|72|0|solid|#FFFFFF|#FFFFFF|6|100|0
invertedTriangle|50|37|36|30|0|gradient|#E651A0|#6564D3|0|100|1
```

The chevron is drawn first, then the inverted triangle above it. Supported part shapes include
`triangle`, `invertedTriangle`, `circle`, `square`, `roundedRect`, `chevron`, `z`, `line`, `arc`,
`diamond`, `star`, `heart`, and `text`. The final text field is required for `text` and is trimmed
to three characters. Built-in presets use this same format and remain
fully editable rather than relying on preset-only renderer behavior.

### App-specific action modules

The shared core also supports `OverlayActionModule`, which adds an optional settings button, a
one-shot action button, and a session value below the module description. Its settings dialog uses
the overlay surface and accent colors. A module provider must identify its profile and reject
Activities that are not its target app.

`Hill Climb Racing Overlay Example (Experimental)` demonstrates this API with mock-only currency
number fields and vehicle, stage, and garage checkbox lists. The example is deliberately limited to
session-local preview state: it does not inspect or modify game data, purchases, save files, or
bytecode. App-specific patches must not be selected together with `UniPatches Universal Overlay
Patch`, because both patches install the shared overlay bridge.

`Control App Ads Patch` can optionally expose the same policy through the shared core. Enable its
overlay integration and choose the runtime modules in the Ads Control settings, then select either
Universal Overlay or an app-specific overlay patch. The complete user flow is:

1. Select `Control App Ads Patch` and one overlay patch. Do not select Universal Overlay together
   with an app-specific overlay patch, because both install the same shared overlay bridge.
2. In Control App Ads, enable `Overlay integration > Enable runtime controls`.
3. Under `Overlay integration > Runtime controls`, enable `Block Ads`, `Rewards without ads`,
   and/or `Block ad/tracking hosts`. These module switches are disabled by default. The Rewards
   without ads runtime control also requires `Rewards without ads > Enable`.
4. Patch the APK. Control App Ads initializes the session policy from its ordinary settings during
   Application startup; the overlay reads that policy when its menu opens. The two patches do not
   depend on patch ordering.
5. Open the overlay. A section named `Ad control hook modules` appears only when the policy was
   initialized and at least one runtime module was selected. Use its Settings popup or checkbox to
   change the policy for the current app process.

The runtime module is an optional bridge, not a second ad patch. Its initial format, reward, and
host values copy Control App Ads, while later changes are session-local and reset when the process
restarts. Only SDK methods, availability checks, and literal hosts successfully instrumented by
Control App Ads can respond; native, encrypted, dynamically generated, or unsupported paths remain
unchanged. If no overlay patch is selected, Control App Ads still applies its normal static changes,
but no runtime menu can be displayed. The Ads Free Rewards module can change matched availability
and policy guards, but it cannot create a missing SDK-specific reward callback.

The patch-time handoff is implemented by `OverlayAdsRuntimeIntegration.kt`. Control App Ads queues
its serialized policy, and the selected overlay consumes it at the same bridge target. If the overlay
patch runs first, `OverlayInjection.kt` records the exact owner, method, and parameter signature;
Control App Ads then attaches its configuration call only when the patching context is the same.
This prevents the two patches from independently guessing different Activities. If no compatible
handoff target exists, normal static Ads changes remain safe and runtime controls are not exposed.
`AdsRuntimePolicy.java` is the process-local policy store; it is configured before an app-specific
Activity overlay is shown and is not persistent.

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

patches/src/main/kotlin/unipatches/overlay/OverlayInjection.kt

This contains the shared safe-register bridge injection and Application/Activity fallback helpers.
It also attaches a queued Ads policy to the exact overlay bridge when Control App Ads is selected.
App-specific patch entries should call these helpers rather than implementing a second injector.

patches/src/main/kotlin/unipatches/overlay/OverlayAdsRuntimeIntegration.kt

This is the patch-process-only handoff between Control App Ads and an overlay patch. It stores the
pending policy and exact bridge identity temporarily; it does not become part of the patched APK.

patches/src/main/kotlin/unipatches/overlay/OverlayConfigPayload.kt

This is the shared Kotlin wire-format helper. Universal and app-specific patch entries should use
it to append profile ID and injection mode metadata without duplicating Base64 encoding or schema
version handling.

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
|   |-- HillClimbRacingOverlayExamplePatch.kt app-specific shared-core example
|   |-- OverlayInjection.kt             shared bridge injection and fallback helpers
|   |-- OverlayAdsRuntimeIntegration.kt patch-process Ads policy handoff
|   |-- presets/OverlayPreset.kt         Shared preset model and value builder
|   |-- presets/OverlayPresetCatalog.kt Central preset registry
|   |-- presets/UniPatchesPreset.kt     UniPatches preset
|   |-- presets/MorpheBluePreset.kt     Morphe-inspired preset
|   |-- presets/DarkPreset.kt            Dark preset
|   |-- presets/LightPreset.kt           Light preset
|   |-- presets/ZArchiverPreset.kt       ZArchiver-inspired preset
|   |-- presets/LuckyPatcherPreset.kt   LuckyPatcher-inspired preset
|   `-- presets/ReVancedPreset.kt       ReVanced-inspired preset
|-- patches/src/main/kotlin/helpers/startup/
|   `-- StartupHooks.kt                Application/activity discovery and Smali helpers
|-- patches/src/main/kotlin/helpers/manifest/
|   `-- ManifestUtils.kt                Manifest helpers used by StartupHooks
|-- patches/src/main/kotlin/helpers/bytecode/
|   `-- BytecodeUtils.kt                Safe register-preserving method cloning
|
`-- extensions/extension/src/main/java/unipatch/overlaycore/
    |-- OverlayRuntime.java   Runtime coordinator and Activity controllers
    |-- OverlayLifecycle.java Lifecycle callback adapter
    |-- OverlayConfig.java    Configuration decoder and fallbacks
    |-- AdsRuntimePolicy.java process-local Ads runtime policy
    |-- OverlayViews.java     Shared view and style construction
    `-- modules/
        |-- OverlayModule.java          common contract
        |-- OverlayActionModule.java    settings and optional one-shot action contract
        |-- OverlayActivityModule.java  activity base class
        |-- OverlayStatisticModule.java statistic base class
        |-- OverlayHookModule.java      hook base class
        |-- OverlayAppSpecificModule.java target-aware module base class
        |-- OverlayAppSpecificModuleProvider.java provider contract
        |-- OverlaySessionState.java   process-session values for action-module settings
        |-- activity/                            Activity implementations
        |-- statistic/                           statistic implementations
        `-- hook/                                hook implementations
```

Module inheritance is intentionally separated by responsibility:

```text
OverlayModule
|                         common identity and metadata
|-- OverlayActivityModule
|   `-- Activity modules    Activity state, apply, restore, isolation
|
|-- OverlayStatisticModule
|   `-- Statistic modules  values, monitors, scheduling, isolation
|
|-- OverlayHookModule
|   `-- Hook modules       best-effort hooks and hook isolation
|
`-- OverlayAppSpecificModule
    `-- App-specific modules  target-aware behavior and target isolation
```

The runtime coordinates all four categories. App-specific modules have their own guarded lifecycle,
state namespace, and menu section, even when their implementation happens to modify target behavior.
Activity, statistic, general hook, and app-specific base classes do not inherit from one another, so
adding a module to one category does not couple it to another category's lifecycle or failure
behavior.

### Runtime entry and configuration

extensions/extension/src/main/java/unipatch/overlaycore/OverlayRuntime.java

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

`OverlaySessionState.java` is the shared process-session store for app-specific action-module
settings and values. It is cleared when the overlay is fully closed or the app process ends; it is
not persistent Android storage. This lets a settings value survive Activity changes in a
multi-Activity app without surviving a new app process.

extensions/extension/src/main/java/unipatch/overlaycore/OverlayLifecycle.java

This is the lifecycle adapter. It forwards Activity resume, pause, and destroy events to the runtime.
The runtime creates a controller on resume, pauses work when an Activity is not visible, and removes
the controller when the Activity is destroyed.

extensions/extension/src/main/java/unipatch/overlaycore/OverlayConfig.java

This decodes and sanitizes the payload produced by the Kotlin patch. Configuration fields are
positional, so new fields are appended and version-aware defaults preserve older payloads.
Malformed colors, enums, dimensions, and missing values receive safe fallbacks.

The current configuration uses payload version 1 and RGB-only color values in `#RRGGBB` format. Overlay background
transparency is serialized separately as a percentage because Morphe's color picker does not
edit alpha. The default background `#300000` with 80% opacity reproduces the previous `#CC300000`
value. Menu outline and menu text are independent settings, and the icon outline width is
independent from the menu outline width. The current schema also stores the configurable legacy icon text
size and supports two build-time custom icon inputs: a local image file and a String Handler input.
The local image takes priority when valid; otherwise file URI, Base64, data URI, and HTTPS inputs are
tried. A non-empty but invalid image intentionally leaves a plain icon background rather than drawing
text or Multi-parts behind it, so image input always has full visual precedence.

The user-facing preset format is versioned JSON and is separate from the internal Base64-delimited
runtime payload. Unknown JSON fields are ignored, missing fields use the visible settings, and
recognized values are range-checked before use. This allows future UI settings to be added without
invalidating older presets.

### Shared view construction

extensions/extension/src/main/java/unipatch/overlaycore/OverlayViews.java

This contains reusable overlay backgrounds, gradient rendering, animated outline rendering, and
selectable or styled button backgrounds. It keeps visual construction separate from lifecycle and
module behavior. The current runtime uses explicit configured colors for buttons, sliders,
checkboxes, and dropdowns.

Overlay views use an isolated Android theme context and then apply configured colors explicitly. This
prevents a host app's legacy or custom theme from changing checkbox, spinner, slider, or overlay
button appearance.

### Action-module settings lifecycle

`OverlayActionModule.java` is the shared contract for modules that expose a settings popup and,
optionally, a one-shot action. `hasActionButton()` controls whether the action button is shown;
Ads runtime modules use settings-only controls. A module can save values without applying them by
leaving `appliesSettingsOnConfirm()` false, or apply them immediately through
`applySavedSettings(Activity)` when it returns true. The runtime invokes this callback only after
the user confirms the styled overlay popup and catches module failures so a bad app-specific module
cannot crash the host app. Numeric inputs and checkbox lists use the same contract.

## Module categories

The module base classes are placed in:

extensions/extension/src/main/java/unipatch/overlaycore/modules/

Concrete implementations are placed in the matching activity/, statistic/, or hook/ subdirectory.

OverlayModule.java is the common module contract. It provides the stable identity and
user-facing metadata shared by all categories.

### Activity modules

OverlayActivityModule.java is the base class for temporary behavior tied to the current
Activity or its Window. Examples include fullscreen, screenshots, brightness, rotation, and audio
state.

The base class owns Activity-module isolation and restoration boundaries. Each module captures the
state it changes, applies only its own behavior, and restores independently. A failure in one
Activity module must not stop other modules from being toggled or restored.

### Statistic modules

OverlayStatisticModule.java is the base class for values displayed in the menu and/or
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

OverlayHookModule.java is the base class for best-effort runtime hooks that affect views or
host behavior without changing a specific app's internal business logic.

The base class owns hook isolation. Hooks must tolerate unsupported APIs, unexpected view types, and
dynamic host behavior. Hook failures are contained so they do not propagate to the app or prevent
other module categories from operating.

## Runtime flow

1. Morphe builds the patch with the selected settings and, in Custom mode, optionally imports a UI preset.
2. The patch injects a bridge into Application.onCreate, or uses an Activity fallback.
3. OverlayRuntime decodes the configuration and registers lifecycle callbacks.
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

For the practical module checklist, read OVERLAY_MODULES.md. In short, a new module needs:

1. A class extending the correct category base class.
2. A disabled-by-default Morphe option.
3. A configuration token serialized by the overlay patch through OverlayConfigPayload.kt.
4. A decoder field in OverlayConfig.java.
5. Registration in OverlayRuntime.java in the intended category/order.
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
