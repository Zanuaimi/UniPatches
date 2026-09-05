# UniPatches

![UniPatches profile logo](images/profilelogo.png)

Curated list of universal patches for [Morphe](https://morphe.software), including community-driven patches with enhancements and original patches, focusing on quality over quantity

## About

UniPatches brings together a concise selection of community-favorite universal APK target-level patches and contains some patches of my own. Community patches are selected from projects I find useful, including:

- [Entree](https://github.com/Entree3k/Morning-Entree-Patches)
- [Nai64](https://github.com/Zanuaimi/Nai64Patches)
- [Adobo](https://github.com/jkennethcarino/adobo)
- [kondratjev](https://github.com/kondratjev/morphe-patches)
- [MiguelNinja19](https://github.com/MiguelNinja19/miguel-morphe-patches)
- [BlazeFTL](https://github.com/BlazeFTL/FTL-Patches)
- [xob0t](https://github.com/xob0t/morphe-patches)
- [rushiranpise (Doom)](https://github.com/rushiranpise/morphe-patches)

These patches may be merged, refined, or enhanced where practical to improve compatibility, functionality, configuration, and usability.

### Enhanced patches

The following patch is an enhanced merge of community work rather than a direct copy:

- **PairIP Bypass Enhanced (Experimental)** — merges PairIP bypass approaches from [Nai64Patches](https://github.com/Nai64/Nai64Patches), [Entree](https://github.com/Entree3k/Morning-Entree-Patches), [kondratjev](https://github.com/kondratjev/morphe-patches), and [rushiranpise (Doom)](https://github.com/rushiranpise/morphe-patches). It combines coverage for common legacy, V2, and V3 protection layouts, organizes every strategy by risk level, and provides automatic selection that defaults to low- and medium-risk strategies. Users can disable automatic mode and test each strategy independently, including the high-risk strategies, with clearer configuration and failure reporting.

The Universal Overlay Patch has also been submitted as a pull request to [Nai64Patches](https://github.com/Nai64/Nai64Patches), one of the largest collections of universal Morphe patches. The version in Nai64Patches is intended for stable and major releases, while the version here is kept more up to date with ongoing improvements and changes. See the [Universal Overlay pull request](https://github.com/Nai64/Nai64Patches/pull/73).

![Universal Overlay pull request evidence](images/pullrequestevidence.png)

The repository also includes my own patches. The patches without separate community credits are:

- Universal Overlay
- Graphics API Override
- Bypass Forced Online Checks
- Bypass Forced Updates
- Frame Rate Preference

Community contributions are credited in each patch description, and merged or enhanced patches retain attribution to the developers whose work influenced them.

### Add UniPatches to Morphe

Add UniPatches as a source inside the Morphe patcher.

| Method | Link |
| :--- | :--- |
| Deep link | [morphe.software/add-source?github=Zanuaimi/UniPatches](https://morphe.software/add-source?github=Zanuaimi/UniPatches) |
| Manual | `https://github.com/Zanuaimi/UniPatches` |

> [!TIP]
> Tap the deep link on a device that already has Morphe installed to add the source in one step.

### How to use these patches

After adding the source, select the patches you want from Morphe. Read each patch description and its options before applying it, especially experimental patches that modify app startup, licensing, integrity checks, or runtime behavior.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.4.0](https://github.com/Zanuaimi/UniPatches/releases/tag/v1.4.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;12 patches total
<details open>
<summary>🌐 Universal&nbsp;&nbsp;•&nbsp;&nbsp;12 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Ads Free Rewards (Experimental)](#ads-free-rewards-experimental) | Get rewards without watching ads. Combine with No Ads for other formats, but keep No Ads' rewarded block off.<br><br>Credits: Nai64Patches from Nai64. | • Patch version<br>• Reward Strategy<br>• Instant reward<br>• Fake ad availability |
| [Bypass Emulator Detection](#bypass-emulator-detection) | Hides emulator traces by spoofing Build info and related checks so apps cannot detect an emulator.<br><br>Credits: Nai64Patches from Nai64. | • Device profile<br>• Hide Emulator Radio<br>• Spoof Build Extras |
| [Bypass Forced Updates (Experimental)](#bypass-forced-updates-experimental) | Skip forced update screens and keep using the app. | • Bypass update gate<br>• Make dialogs dismissible<br>• Block update redirects<br>• Prevent forced exit<br>• Patch Play Core updates |
| [Custom App Resolution (Experimental)](#custom-app-resolution-experimental) | Set a custom resolution for the game<br><br>Credits: Nai64Patches from Nai64. | • Enable Custom Resolution<br>• Resolution width (px)<br>• Resolution height (px) |
| [Disable Forced Online Checks (Experimental)](#disable-forced-online-checks-experimental) | Lets the app start without internet. | • Auto mode<br>• Common Android/network API<br>• Unity strategy<br>• Unreal strategy<br>• Godot strategy<br>• Generic bytecode strategy |
| [Frame Rate Preference (Experimental)](#frame-rate-preference-experimental) | Requests a preferred refresh rate like 60 or 90 Hz for the app window. The system may ignore it. | • Frame rate |
| [Free In-app Purchases (Experimental)](#free-in-app-purchases-experimental) | Get paid items for free. Best for offline games.<br><br>Credits: Nai64Patches from Nai64. |  |
| [Graphics API Override (Experimental)](#graphics-api-override-experimental) | Forces a Unity game to use Vulkan or OpenGL via launch argument. Only for supported Unity games. | • Graphics API |
| [No Ads (Experimental)](#no-ads-experimental) | Blocks ads by type. Pick what to block. For rewarded ads use Ads Free Rewards instead.<br><br>Credits: Nai64Patches from Nai64. | • Preset<br>• Block Interstitials<br>• Block Banners<br>• Block App Open<br>• Block MREC<br>• Block Rewarded<br>• Block Native |
| [PairIP Bypass Enhanced (Experimental)](#pairip-bypass-enhanced-experimental) | A merged experimental PairIP bypass for common legacy, V2, and V3 protection layouts.<br><br>Automatic mode applies compatible strategies up to the selected risk level. It defaults to<br>Low and Med Risk Strategies; Low Risk applies only low-risk strategies, while Low, Med, and<br>High Risk Strategies also enables the invasive high-risk strategies.<br><br>Turn off automatic mode to test the individual manual strategies. Manual selections are<br>independent of the automatic risk-level setting, and every manual strategy is disabled by default.<br><br>This patch is experimental and app-dependent. It does not bypass server-side Play Integrity,<br>server-side licensing, or other server-side enforcement.<br><br>This enhanced patch is a merged product of the PairIP bypass patches from the credited<br>developers, with improvements for broader functionality, safer strategy selection, and usability.<br><br>Credits: Nai64Patches from Nai64, Entree, kondratjev, and rushiranpise (Doom). | • Automatic strategy selection<br>• Automatic mode applying<br>• Redirect PairIP Application (Low Risk)<br>• Remove PairIP manifest entries (Low Risk)<br>• UI - Suppress LicenseClient error dialog (Low Risk)<br>• UI - Suppress LicenseActivity error dialog (Low Risk)<br>• UI - Suppress logged error dialog (Low Risk)<br>• Response - Remove repeated-check metadata (Low Risk)<br>• V2 - Disable repeated checks (Low Risk)<br>• V2 - Disable repeated-check flag (Low Risk)<br>• UI - Suppress LicenseClient paywall (Medium Risk)<br>• UI - Suppress LicenseActivity paywall (Medium Risk)<br>• UI - Suppress LicenseActivity nnStart (Medium Risk)<br>• UI - Suppress LicenseActivity onStart (Medium Risk)<br>• UI - Suppress LicenseActivity closeApp (Medium Risk)<br>• UI - Suppress LicenseActivity exitApp (Medium Risk)<br>• UI - Suppress LicenseActivity closeapp (Medium Risk)<br>• UI - Suppress LicenseActivity exitapp (Medium Risk)<br>• UI - Suppress LicenseActivity closeAllTasks (Medium Risk)<br>• Installer - Spoof local installer check (Medium Risk)<br>• License Client - Bypass checkLicense (Medium Risk)<br>• License Client - Bypass initializeLicenseCheck (Medium Risk)<br>• License Client - Bypass service connection (Medium Risk)<br>• License Client - Bypass processResponse (Medium Risk)<br>• Response - Bypass helper validation (Medium Risk)<br>• Response - Bypass helper signature (Medium Risk)<br>• Response - Bypass validator validation (Medium Risk)<br>• Response - Bypass validator signature (Medium Risk)<br>• V3 - Bypass response validation (Medium Risk)<br>• Application - Bypass attachBaseContext (High Risk)<br>• Application - Bypass onCreate (High Risk)<br>• Runtime - Bypass Application static initializer (High Risk)<br>• Runtime - Bypass VMRunner.invoke (High Risk)<br>• Runtime - Bypass StartupLauncher.launch (High Risk)<br>• Runtime - Bypass StartupLauncher.pairip (High Risk)<br>• V3 - Bypass LicenseClient activity (High Risk)<br>• Installer - Spoof installer source (High Risk)<br>• Integrity - Bypass signature integrity (High Risk)<br>• Integrity - Bypass signature match (High Risk)<br>• Provider - Bypass initialization (High Risk)<br>• Provider - Bypass query (High Risk)<br>• Provider - Bypass context provider (High Risk)<br>• V2 - Bypass checkLicenseInternal (High Risk)<br>• V2 - Bypass response signature (High Risk)<br>• Advanced - External VMRunner call sites (High Risk) |
| [Skip Splash Screen (Experimental)](#skip-splash-screen-experimental) | Skip or shorten splash screen delays<br><br>Credits: Nai64Patches from Nai64. |  |
| [UniPatches Universal Overlay Patch v1.2 (Experimental)](#unipatches-universal-overlay-patch-v1-2-experimental) | Universal in-app overlay for Android apps and games. Optional modules include System Time, FPS,<br>fullscreen, app brightness, and haptic controls. Modules are excluded and disabled by default;<br>select them in Morphe settings before patching. Statistic modules show information, Activity modules<br>control the current Activity, and Hook modules control internal app behavior, such as disabling<br>animations, through best-effort runtime changes. A selected local image automatically replaces<br>the legacy icon; empty or invalid image input falls back to the legacy icon. This is experimental<br>and may not work on all apps. UI presets can save and reuse every General, UI, and Advanced<br>setting, but intentionally exclude Modules and Settings to Modules because hook and module<br>combinations can be app-specific.<br><br>The idea and initial works of this Universal Overlay Patch are from Zanuaimi / Noobite. | • Presets - Selected preset<br>• Presets - Import UI preset<br>• Presets - Export UI preset<br>• Presets - Exported UI preset output name<br>• General - Overlay title<br>• General - Overlay description<br>• General - Repository button text<br>• General - Repository button URL<br>• General - Overlay background color<br>• General - Overlay Background Transparency (%)<br>• General - Overlay outline color<br>• General - Overlay text color<br>• UI - Menu outline width (dp)<br>• UI - Legacy icon text<br>• UI - Legacy icon bold text<br>• UI - Legacy icon text color<br>• UI - Legacy icon text size (sp)<br>• UI - Gradient background<br>• UI - Legacy icon background 1<br>• UI - Legacy icon background 2<br>• UI - Legacy icon gradient angle (degrees)<br>• UI - Icon outline<br>• UI - Icon outline width (dp)<br>• UI - Icon outline color<br>• UI - Custom Overlay Button Icon ( Local Image )<br>• UI - Custom Overlay Button Icon Input ( String Handler )<br>• UI - Overlay button shape<br>• UI - Overlay button size (dp)<br>• UI - Overlay button idle opacity (%)<br>• UI - Overlay button fully visible duration (seconds)<br>• UI - Overlay button position<br>• Advanced - Overlay Activity name override<br>• Settings to Modules - Activate statistic modules on launch<br>• Settings to Modules - Enable monitors for statistic modules on launch<br>• Settings to Modules - Statistic monitor position<br>• Settings to Modules - Monitor panel size<br>• Settings to Modules - Monitor columns<br>• Settings to Modules - Temperature stat format<br>• Settings to Modules - System time format<br>• Statistic modules - Device Information<br>• Statistic modules - FPS<br>• Statistic modules - Device Temperature<br>• Statistic modules - System Time<br>• Statistic modules - App Session Time<br>• Statistic modules - Battery Status<br>• Statistic modules - App Memory Usage<br>• Statistic modules - Network Status<br>• Activity modules - Keep screen awake<br>• Activity modules - Fullscreen<br>• Activity modules - Allow screenshots<br>• Activity modules - App brightness<br>• Activity modules - Rotation mode<br>• Activity modules - App audio mute<br>• Hook modules - Disable haptic feedback / vibrations<br>• Hook modules - Disable app animations |

</details>

<!-- PATCHES_END -->

### 🛠️ Building locally

- Run `./gradlew buildAndroid`
- The built patches .mpp file is found in `patches/build/libs/patches-*.mpp`
- Patch the mpp file using [Morphe-Desktop](https://github.com/MorpheApp/morphe-desktop)
  like any other patch bundle.

See the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation) for more information.

## Contributing

Suggestions, fixes, compatibility improvements, and carefully selected community patches are welcome. Contributions should preserve the repository’s quality-over-quantity goal, include appropriate credits, and use clear semantic commit messages such as `feat:`, `fix:`, or `chore:`.

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidance, including the Universal Overlay guide.

## License

UniPatches is licensed under the [GNU General Public License v3.0](LICENSE).
