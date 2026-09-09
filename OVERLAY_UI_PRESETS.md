# Overlay UI presets

Overlay UI presets are build-time configurations for the shared overlay UI and Advanced
settings. They do not run inside the patched APK and do not contain statistic, Activity, or Hook
modules. The overlay title, description, repository button text, and repository button URL always
come from the visible Morphe settings and are not preset values.

## Available presets

The patch currently includes:

- Custom (UniPatches defaults): uses the visible Morphe settings and optional imported JSON.
- UniPatches: the default UniPatches red appearance.
- Morphe-inspired: the Morphe-style appearance based on the Nai64Patches fork.
- Dark: dark menu with light controls.
- Light: light menu with dark controls.
- ZArchiver-inspired: opaque gray menu, white text, green outline, and a dark-green `Z` icon.
- LuckyPatcher-inspired: black square menu, yellow outline, green text accents, and padded gradient action buttons.
- ReVanced-inspired: near-black menu, blue and purple text accents, Monet controls, and a blue-pink gradient outline.

Built-in presets define every supported UI value explicitly. They do not inherit the user’s Custom
icon or button position. The title, description, repository button text, and repository button URL
are exceptions and always remain from the visible Morphe settings.

## Importing a preset

Import is available only when `Quick setup > 1. Choose preset` is set to `Custom (UniPatches defaults)`.

1. Use `Advanced > Import / export > Import UI preset` to choose an existing `.json` file.
2. Patch the APK.
3. The imported values override the visible Morphe UI settings for that patch run.

The Morphe Manager controls do not visually change after import because settings are evaluated while
the patch is running. If the path is empty, unreadable, malformed, unsupported, or contains invalid
values, the patch falls back to the visible Morphe settings. Older unversioned presets and older
`#AARRGGBB` color values are migrated when possible.

`Quick setup > 3. Optional icon image > Local image` takes priority over the Base64 or HTTPS input
when valid. The Base64 or HTTPS input accepts a data URI, raw Base64, URL-safe Base64, or HTTPS image
URL. If the local image is empty or invalid, that input is tried. When both are empty, the text or
Multi-parts icon is used; a non-empty but invalid image leaves a plain icon background so image input
never falls through to a different icon design.

## Exporting a preset

Export is available only in Custom mode.

1. Set `Advanced > Import / export > Export UI preset` to an existing writable folder.
2. Optionally change `Advanced > Import / export > Export file name`.
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
  "version": 5,
  "settings": {
    "appendDescription": "",
    "descriptionAlignment": "center",
    "appendDescriptionColor": "#FF5656",
    "backgroundColor": "#300000",
    "backgroundTransparency": 80,
    "outlineColor": "#FF5656",
    "textColor": "#FF5656",
    "iconText": "U",
    "iconTextSize": 18,
    "controlTheme": "modern",
    "controlBackground": "#300000",
    "controlForeground": "#FF5656",
    "bottomButtonStyle": "text",
    "bottomButtonShape": "square",
    "bottomButtonPadding": false,
    "bottomButtonTextColor": "#FFFFFF",
    "bottomButtonBackground1": "#500000",
    "bottomButtonBackground2": "#AA0000",
    "menuTextColor1": "#FF5656",
    "menuTextColor2": "#FF5656",
    "menuTextColor3": "#FF5656",
    "menuTextColor4": "#FF5656",
    "menuTextColor5": "#FF5656",
    "menuTextColor6": "#FF5656",
    "separatorBackgroundColor": "#300000",
    "separatorStyle": "ascii",
    "titleIconPlacement": "none",
    "titleAlignment": "left",
    "titleSeparator": false,
    "menuCorners": "rounded",
    "menuOutlineAnimation": "static",
    "outlineAnimationSpeed": 1,
    "openingAnimation": "fade",
    "closingAnimation": "fade",
    "animationDuration": 180,
    "animationEasing": "linear",
    "iconStyle": "text",
    "iconShape": "triangle",
    "iconShapeColor1": "#FFFFFF",
    "iconShapeColor2": "#FFFFFF",
    "iconShapeGradient": false,
    "iconShapeGradientAngle": 0,
    "iconShapeStrokeWidth": 3,
    "iconShapeScale": 70,
    "iconHighlight": false,
    "iconShadow": false,
    "iconOutlineGradient": false,
    "iconOutlineColor2": "#FFFFFF",
    "iconOutlineGradientAngle": 0,
    "iconBackgroundStyle": "flat",
    "iconBackgroundColor3": "#300000",
    "iconBackgroundColor4": "#500000",
    "iconParts": [],
    "customIconImageLocal": "",
    "customIconImageInput": ""
  }
}
```

The actual export uses schema version 5 and contains all supported UI and Advanced values. Title, description, repository
button text, and repository button URL are intentionally absent, as are Modules and Settings to
Modules values. Unknown fields are ignored, missing fields use the current manual values, and future
schema versions are rejected safely instead of being applied partially.

`iconStyle` is `text` by default or `parts` for the Multi-parts icon mode. When it is `parts`,
`iconParts` contains one part-list string per row; the icon renderer ignores the list while text
mode is selected. Use the multi-parts instructions in [OVERLAY.md](OVERLAY.md) for the row syntax,
available shapes, rotation, scaling, colors, and text-part limits. A non-empty custom image input
has priority over both text and Multi-parts rendering; invalid non-empty image input intentionally
leaves a plain icon background rather than silently switching to a legacy icon.

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
