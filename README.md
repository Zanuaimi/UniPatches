# 👋🧩 Morphe Patches template

Template repository for Morphe Patches.

## ❓ About

Curated universal patches for Morphe, including community-driven patches with enhancements and original patches.

### How to use these patches

Click here to add these patches to Morphe: https://morphe.software/add-source?github=Zanuaimi/UniPatches

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0](https://github.com/Zanuaimi/UniPatches/releases/tag/v1.0.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;2 patches total
<details open>
<summary>📦 XYZ app&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 2.0.0 | 1.0.2 |
| :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Example Patch](#example-patch) | Example patch to start with. |  |

</details>

<details open>
<summary>🌐 Universal&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Universal Overlay Patch v1.0 (Experimental)](#universal-overlay-patch-v1-0-experimental) | Universal in-app overlay for Android apps and games. Optional modules include System Time, FPS,<br>fullscreen, app brightness, and haptic controls. Modules are excluded and disabled by default;<br>select them in Morphe settings before patching. Statistic modules show information, Activity modules<br>control the current Activity, and Hook modules control internal app behavior, such as disabling<br>animations, through best-effort runtime changes. A selected local image automatically replaces<br>the legacy icon; empty or invalid image input falls back to the legacy icon. This is experimental<br>and may not work on all apps.<br><br>The idea and initial works of this Universal Overlay Patch are from Zanuaimi / Noobite. | • General - Overlay title<br>• General - Overlay description<br>• General - Repository button text<br>• General - Repository button URL<br>• General - Overlay background color<br>• General - Overlay outline color<br>• UI - Menu outline width (dp)<br>• UI - Legacy icon text<br>• UI - Legacy icon bold text<br>• UI - Legacy icon text color<br>• UI - Gradient background<br>• UI - Legacy icon background 1<br>• UI - Legacy icon background 2<br>• UI - Legacy icon gradient angle (degrees)<br>• UI - Icon outline<br>• UI - Icon outline color<br>• UI - Custom image icon<br>• UI - Overlay button shape<br>• UI - Overlay button size (dp)<br>• UI - Overlay button idle opacity (%)<br>• UI - Overlay button fully visible duration (seconds)<br>• UI - Overlay button position<br>• Advanced - Overlay Activity name override<br>• Settings to Modules - Activate statistic modules on launch<br>• Settings to Modules - Enable monitors for statistic modules on launch<br>• Settings to Modules - Statistic monitor position<br>• Settings to Modules - Monitor panel size<br>• Settings to Modules - Monitor columns<br>• Settings to Modules - Temperature stat format<br>• Settings to Modules - System time format<br>• Statistic modules - Device Information<br>• Statistic modules - FPS<br>• Statistic modules - Device Temperature<br>• Statistic modules - System Time<br>• Statistic modules - App Session Time<br>• Statistic modules - Battery Status<br>• Statistic modules - App Memory Usage<br>• Statistic modules - Network Status<br>• Activity modules - Keep screen awake<br>• Activity modules - Fullscreen<br>• Activity modules - Allow screenshots<br>• Activity modules - App brightness<br>• Activity modules - Rotation mode<br>• Activity modules - App audio mute<br>• Hook modules - Disable haptic feedback / vibrations<br>• Hook modules - Disable app animations |

</details>

<!-- PATCHES_END -->

### 🛠️ Building locally

- Run `./gradlew buildAndroid`
- The built patches .mpp file is found in `patches/build/libs/patches-*.mpp`
- Patch the mpp file using [Morphe-Desktop](https://github.com/MorpheApp/morphe-desktop)
  like any other patch bundle.

See the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation) for more information.

## 📜 License

UserXYZ Patches are licensed under the [GNU General Public License v3.0](LICENSE)
