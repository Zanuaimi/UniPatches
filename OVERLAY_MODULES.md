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
Activity controllers, view attachment, state restoration, update scheduling, and failure isolation.
It is shared by ordinary Android apps, Unity games, Godot games, and apps with multiple Activities.

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

The current configuration payload uses schema version 1 for the current UniPatches overlay patch.
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
- No local patch build is required for normal development; the release workflow builds the patch.
- git diff --check passes.
- New UI uses the overlay context and configured colors, not the host Activity theme.
- Lifecycle, recreation, fully-close, and multi-Activity behavior remain isolated.
- Use a semantic commit such as feat: add ..., fix: handle ..., or chore: ....

The idea and initial works of Universal Overlay Patch are from Zanuaimi / Noobite. The shared core
allows future overlay variants to inherit the same tested UI and lifecycle behavior.
