# Overlay module guide

This guide explains how to add universal or app-specific modules to the shared UniPatches Overlay Core.

## Architecture

The Universal Overlay patch at patches/src/main/kotlin/unipatches/overlay/UniversalOverlayPatch.kt
exposes general Morphe settings and serializes them into the runtime configuration. An app-specific
patch uses the same core but keeps target fingerprints and app-specific settings in its own patch.

Built-in UI presets are defined separately in
patches/src/main/kotlin/unipatches/overlay/presets/OverlayPresetCatalog.kt. The patch entry point
only selects a catalog value, applies Custom/import precedence, and serializes the final result.
Preset definitions are build-time data and do not belong in the extension runtime.

The extension runtime starts from OverlayRuntime and owns lifecycle registration,
Activity controllers, view attachment, state restoration, update scheduling, provider registration,
and failure isolation. It is shared by ordinary Android apps, Unity games, Godot games, and apps
with multiple Activities.

Universal Overlay installs one shared startup bridge. It prefers the manifest-resolved Application
entry point, then the manifest-resolved launcher Activity, and finally a restricted application-owned
Activity fallback. The launcher resolver is namespace-safe and accepts valid framework ancestors
that are not packaged in the APK, such as `android.app.NativeActivity`. It excludes no-history and
unrelated framework or SDK Activities. The patch verifies the final `install()` or
`installActivity()` call before treating the runtime as installed.

Companion patches coordinate through the exact temporary bridge marker containing the owner, method,
return type, and parameter list. Control App Ads attaches its policy to that bridge only after it is
verified. A missing or unverified bridge produces a diagnostic and no dependent runtime module is
exposed. Providers can be bundled in the extension without appearing in the menu until their
process-local policy is configured.

Use the matching base class:

- OverlayActivityModule for temporary Activity/window behavior.
- OverlayStatisticModule for menu values and floating monitors.
- OverlayHookModule for best-effort view or runtime behavior changes.
- OverlayAppSpecificModule for target-aware modules belonging to one known application.
- OverlayModule for common labels, keys, descriptions, and contracts.

Do not make one module inherit from another module category. Each category owns its own lifecycle and
isolation behavior.

## App-specific modules

App-specific modules are a separate category because they may depend on known target classes, method
fingerprints, or app state. Extend `OverlayAppSpecificModule`, implement stable metadata, and register
the module through an `OverlayAppSpecificModuleProvider` with a unique profile ID.

An app-specific patch should inject into its known target Activity explicitly, use a controlled
universal Application/Activity fallback only when explicit injection fails, and expose universal and
app-specific modules in separate menu sections. A target mismatch disables that module rather than
the whole overlay. App-specific modules have their own guarded enable, restore, and remembered-state
lifecycle; they do not share the universal hook state namespace.

Example provider shape:

```java
public final class ExampleGameProvider implements OverlayAppSpecificModuleProvider {
    @Override public String profileId() { return "example-game"; }
    @Override public List<OverlayAppSpecificModule> create(Activity activity) {
        return Arrays.asList(new ExampleGameToolsModule(activity));
    }
}
```

## Adding a module

1. Add a Java class under the matching category directory:
   extensions/extension/src/main/java/unipatch/overlaycore/modules/activity/,
   .../statistic/, or .../hook/. App-specific implementations should use a target-specific
   package under `unipatch.overlaycore.modules` (for example `modules.appspecific`).
2. Extend the correct base class and provide a stable, unique key(), user-facing label(), and
   short description().
3. Add a disabled-by-default booleanOption in the Kotlin patch settings, grouped under
   Statistic modules, Activity modules, or Hook modules.
4. Add the option token to the serialized controls list in the Kotlin patch.
5. Decode the token in OverlayConfig.
6. Add universal modules in `OverlayRuntime.addModules()`, or register app-specific modules through
   the provider registry.
7. Use the category-specific runtime helper: statistic modules use
   addStatisticSafely() and createStatisticMonitors(); activity modules use
   addActivityModuleSafely(); hook modules use addHookModuleSafely(); app-specific modules use
   addAppSpecificModuleSafely().
8. Restore mutable state independently and tolerate incompatible Activities.
9. Update the Morphe patch description when the user-visible module list changes.

For an app-specific patch, set the shared configuration profile ID and use the
`explicitActivity` injection mode. The explicit target is attempted first; the universal strategy
is a fallback only when the target Activity or its `onCreate` method cannot be found.

## Integrated runtime providers

Integrated providers are registered by `OverlayRuntime` and are driven by process-local state rather
than ordinary Universal Overlay module-selection fields. The current Ads provider is
`modules/ads/AdsControlRuntimeProvider.java`, registered under the `adsControlRuntime` profile.
It contributes `Block Ads`, `Ads Free Rewards`, and `Block Ads / Tracking Hosts` to the
`Ad control hook modules` section when `AdsRuntimePolicy` is integrated and contains the matching
module bit.

The Control App Ads patch queues the positional policy and the overlay patch attaches it to the
same Application or Activity bridge. The policy uses these module bits: Block Ads = 1, Ads Free
Rewards = 2, and Block Ads / Tracking Hosts = 4. The first two runtime modules also require their
corresponding Ads master settings. The host module requires runtime policy only; its initial
checkbox value comes from the `Enable Block Ads / Tracking Hosts` master setting.

Integrated modules must remain safe when absent, malformed, unsupported by the current Activity,
or partially configured. Their provider should return an empty list instead of throwing, and the
runtime must isolate provider and module failures from universal modules. Session settings belong in
`OverlaySessionState`; they must not be persisted in Android storage.

## Popup windows

All runtime popup windows must use `OverlayPopupFrame` and the controller's shared helpers. This
includes module Settings, runtime log viewing, and close confirmation.
Popup windows inherit the configured overlay context, background, foreground and text colors,
corners, outline, spacing, typography, animations, and bottom-button styling.

Headers must be added through `OverlayPopupFrame.addHeader`, which honors `Show extra popup headers`.
Popup code must not create platform dialogs, system-level windows, persistent storage, or
app-specific theme assumptions. Popup creation is dispatched to the Android main thread and must
reject finishing or destroyed Activities.

Extra popup headers are enabled by default in the current Universal Overlay settings, although a
preset or imported Custom configuration may explicitly disable them. Header titles use the shared
title typography, alignment, icon placement, separator, and colors.

The InApp confirmation popup has this exact contract:

- optional header: `Emulate InApp Purchase Confirmation`;
- description using UI color 3: `Do you want to try to emulate in-app purchase for this product?`;
- checkbox: `Save purchase for skipping purchase popup`;
- bottom buttons: `No` and `Yes`.

The module Settings popup reuses the existing `OverlayActionModule` checkbox-list contract and has
no one-shot action button. Saved purchases are cleared when the app process ends or the overlay is
fully closed.

## Current built-in module inventory

Universal Overlay currently provides:

- Statistic modules: FPS, battery, memory, network, device information, temperature, system time,
  and session time, with optional floating monitors.
- Activity modules: keep awake, fullscreen, screenshots, brightness, rotation, and app audio mute.
- Hook modules: disable haptics and disable animations.
- System modules: Do Not Disturb, guarded by notification-policy access.
- Advanced modules: Overlay Runtime Logs, with optional activation at app launch.
- Integrated modules: Control App Ads runtime controls when its companion policy is configured.

The Do Not Disturb module also requires the Android notification-policy permission and a user-granted
system access setting. Universal Overlay adds the manifest declaration when the module is selected,
but it cannot grant the access itself. Battery, temperature, and DND dynamic receivers use
`RECEIVER_NOT_EXPORTED` on Android 13 and newer where they are internal or system-state observers,
while older Android releases use the two-argument compatibility overload.

## Statistic modules

Menu values update only while the menu is visible. Floating monitors update only while the menu is
closed and only when both Monitor and Active are enabled. A monitor must be click-through and
must not expand the overlay touch area.

Cache values that do not change during a session, such as device and CPU names. Avoid creating
timers or polling loops when a module is disabled, its monitor is hidden, the menu is closed, or the
overlay is fully closed.

Modules with multiple monitors must return a stable count and bind each view in a consistent order.
The runtime owns placement, columns, panel sizing, alpha, and visibility.

## Activity and hook modules

Activity modules capture original state before changing it and restore only their own state. Hook
modules handle unsupported APIs and unexpected view types without throwing into the host application.
A failure in one module must not prevent other modules from being created, toggled, or restored.

Never retain an Activity in a static field. The runtime stores controllers in a weak map and removes
them when Activities are destroyed. Do not attach system-level windows; the overlay belongs to the
current app content and uses Activity-content fallbacks.

## Configuration compatibility

Configuration fields are positional and Base64 encoded. When adding fields:

- increment `OverlayConfigPayload.VERSION` in the shared Kotlin payload helper;
- append new fields rather than inserting them in the middle;
- add version-aware defaults in OverlayConfig;
- preserve decoding for older versions;
- validate bounded numeric values and enum strings before serialization.

The current configuration payload remains schema version 1 because new values are appended to the
existing versioned positional payload and the decoder supplies safe defaults for older payloads.
The patch release name can change independently from this wire-format schema. It includes
the current UI fields alongside background transparency, split menu text colors, control styling,
separator styling, menu geometry, and animation settings.
Colors remain RGB-only; background alpha is applied by the runtime from the percentage field. The
runtime still accepts older positional payloads.

UI presets are version 2 JSON files handled by the patch-time Kotlin layer. They contain supported UI
and Advanced settings, including both custom icon inputs, but exclude the title, description,
repository button text, repository button URL, Settings to Modules, and all module toggles. Import is
Custom-only and falls back field-by-field to visible Morphe settings when values are missing or
invalid. Export is Custom-only, happens after patch work, and logs failures without failing the APK
patch.

Keep the runtime fallback safe when a setting is missing or malformed. Numeric values are clamped
to valid bounds, and patch-time warnings identify values that were clamped.

## Review checklist

- No target-package, class-name, engine, or APK-specific assumptions.
- Startup injection must prefer the manifest-resolved Application or launcher and verify the final
  bridge before dependent policies are marked attached.
- Integrated providers must return no modules until their policy is configured and the shared bridge
  has succeeded.
- Dynamic receivers must use API-appropriate flags and must not broaden export scope without a
  verified external broadcast requirement.
- No local patch build is required for normal development; the release workflow builds the patch.
- git diff --check passes.
- New UI uses the overlay context and configured colors, not the host Activity theme.
- Lifecycle, recreation, fully-close, and multi-Activity behavior remain isolated.
- Use a semantic commit such as feat: add ..., fix: handle ..., or chore: ....

The idea and initial works of Universal Overlay Patch are from Zanuaimi / Noobite. The shared core
allows future overlay variants to inherit the same tested UI and lifecycle behavior.
