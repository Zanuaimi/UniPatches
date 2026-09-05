# Universal Overlay UI presets

Universal Overlay UI presets are build-time configurations for the overlay’s General, UI, and
Advanced settings. They do not run inside the patched APK and do not contain statistic, Activity, or
Hook modules.

## Available presets

The patch currently includes:

- Custom: uses the visible Morphe settings and optional imported JSON.
- UniPatches: the default UniPatches red appearance.
- Morphe Blue: the Morphe-style appearance based on the Nai64Patches fork.
- Dark: dark menu with light controls.
- Light: light menu with dark controls.
- ZArchiver: opaque gray menu, white text, green outline, and a dark-green `Z` icon.

Built-in presets define every UI value explicitly. They do not inherit the user’s Custom icon,
button position, title, or other manual settings.

## Importing a preset

Import is available only when `Presets - Selected preset` is set to `Custom`.

1. Set `Presets - Import UI preset` to an existing `.json` file.
2. Patch the APK.
3. The imported values override the visible Morphe UI settings for that patch run.

The Morphe Manager controls do not visually change after import because settings are evaluated while
the patch is running. If the path is empty, unreadable, malformed, unsupported, or contains invalid
values, the patch falls back to the visible Morphe settings. Older unversioned presets and older
`#AARRGGBB` color values are migrated when possible.

`Custom Overlay Button Icon ( Local Image )` takes priority over
`Custom Overlay Button Icon Input ( String Handler )` when the local image is valid. The String
Handler accepts a file URI, data URI, raw Base64, URL-safe Base64, or HTTPS image URL. If the local
input is empty or invalid, the String Handler is tried. If both are invalid or empty, the legacy
text icon is used.

## Exporting a preset

Export is available only in Custom mode.

1. Set `Presets - Export UI preset` to an existing writable folder.
2. Optionally change `Presets - Exported UI preset output name`.
3. Patch the APK.
4. The final effective UI settings are written after the patch work completes.

The default filename is `UniversalOverlay.json`. The patch removes a supplied `.json` suffix before
adding one. If the name already exists, the patch creates `UniversalOverlay-1.json`, then
`UniversalOverlay-2.json`, and so on.

Export failures never cancel APK patching. The patch logs the reason and continues without exporting.
Filesystem roots and protected locations are rejected across platforms. Android paths must be below
emulated storage; Unix and macOS system locations, and Windows locations such as `Windows`,
`Program Files`, and `ProgramData`, are refused. Normal user folders remain valid.

## Preset JSON format

Exports use a versioned format:

```json
{
  "format": "unipatches-universal-overlay-preset",
  "version": 1,
  "settings": {
    "backgroundColor": "#300000",
    "backgroundTransparency": 80,
    "outlineColor": "#FF5656",
    "textColor": "#FF5656",
    "iconText": "U",
    "iconTextSize": 18,
    "customIconImageLocal": "",
    "customIconImageInput": ""
  }
}
```

The actual export contains all supported General, UI, and Advanced values. Modules and Settings to
Modules values are intentionally absent. Unknown fields are ignored, missing fields use the current
manual values, and future schema versions are rejected safely instead of being applied partially.

## Example: adding a preset

Create a separate file under
`patches/src/main/kotlin/unipatches/overlay/presets/`. For example:

```kotlin
package unipatches.overlay.presets

internal val examplePreset = OverlayPresetDefinition(
    "example", "Example", "A readable example UI preset.",
    explicitOverlayPreset(
        buttonText = "E",
        background = "#202020",
        backgroundTransparency = 90,
        outline = "#00AEEF",
        overlayTextColor = "#FFFFFF",
        buttonTextColor = "#FFFFFF",
        gradientBackground = false,
        buttonBackground = "#303030",
        iconBackground2 = "#303030",
        iconGradientAngle = 0,
        iconOutlineColor = "#FFFFFF",
    ),
)
```

Then register it in `OverlayPresetCatalog.kt`:

```kotlin
val definitions: List<OverlayPresetDefinition> = listOf(
    uniPatchesPreset,
    morpheBluePreset,
    darkPreset,
    lightPreset,
    zArchiverPreset,
    examplePreset,
)
```

The Morphe dropdown is generated from this list automatically. Do not add module toggles, Settings
to Modules values, or app-specific behavior to a preset. After adding it, update this guide if the
preset is user-facing, regenerate `patches-list.json`, and run the project build.
