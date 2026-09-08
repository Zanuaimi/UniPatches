# UniPatches

![UniPatches profile logo](images/profilelogo.png)

Curated list of universal patches for [Morphe](https://morphe.software), including community-driven patches with enhancements and original patches, focusing on quality over quantity

## About

UniPatches brings together a concise selection of community-favorite universal APK target-level patches and contains some patches of my own. Community patches are selected from projects I find useful, including:

- [Morning-Entree-Patches by Entree3k](https://github.com/Entree3k/Morning-Entree-Patches)
- [Nai64Patches by Nai64](https://github.com/Nai64/Nai64Patches)
- [Adobo by jkennethcarino](https://github.com/jkennethcarino/adobo)
- [morphe-patches by kondratjev](https://github.com/kondratjev/morphe-patches)
- [miguel-morphe-patches by MiguelNinja19](https://github.com/MiguelNinja19/miguel-morphe-patches)
- [FTL-Patches by BlazeFTL](https://github.com/BlazeFTL/FTL-Patches)
- [morphe-patches by xob0t](https://github.com/xob0t/morphe-patches)
- [morphe-patches by rushiranpise (aka Doom)](https://github.com/rushiranpise/morphe-patches)

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
> **[v1.8.1](https://github.com/Zanuaimi/UniPatches/releases/tag/v1.8.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;10 patches total
<details open>
<summary>🌐 Universal&nbsp;&nbsp;•&nbsp;&nbsp;10 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Ads Free Rewards (Experimental)](#ads-free-rewards-experimental) | Get rewards without watching ads. Combine with No Ads for other formats, but keep No Ads' rewarded block off.<br><br>Credits: Nai64Patches from Nai64. | • Patch version<br>• Reward Strategy<br>• Instant reward<br>• Fake ad availability |
| [Bypass Emulator Detection](#bypass-emulator-detection) | Hides emulator traces by spoofing Build info and related checks so apps cannot detect an emulator.<br><br>Credits: Nai64Patches from Nai64. | • Device profile<br>• Hide Emulator Radio<br>• Spoof Build Extras |
| [Bypass Forced Updates (Experimental)](#bypass-forced-updates-experimental) | Skip forced update screens and keep using the app. | • Bypass update gate<br>• Make dialogs dismissible<br>• Block update redirects<br>• Prevent forced exit<br>• Patch Play Core updates |
| [Custom App Resolution (Experimental)](#custom-app-resolution-experimental) | Set a custom resolution for the game<br><br>Credits: Nai64Patches from Nai64. | • Enable Custom Resolution<br>• Resolution width (px)<br>• Resolution height (px) |
| [Disable Forced Online Checks (Experimental)](#disable-forced-online-checks-experimental) | Lets the app start without internet. | • Auto mode<br>• Common Android/network API<br>• Unity strategy<br>• Unreal strategy<br>• Godot strategy<br>• Generic bytecode strategy |
| [Frame Rate Preference (Experimental)](#frame-rate-preference-experimental) | Requests a preferred refresh rate like 60 or 90 Hz for the app window. The system may ignore it. | • Frame rate |
| [Graphics API Override (Experimental)](#graphics-api-override-experimental) | Forces a Unity game to use Vulkan or OpenGL via launch argument. Only for supported Unity games. | • Graphics API |
| [No Ads (Experimental)](#no-ads-experimental) | Blocks ads by type. Pick what to block. For rewarded ads use Ads Free Rewards instead.<br><br>Credits: Nai64Patches from Nai64. | • Preset<br>• Block Interstitials<br>• Block Banners<br>• Block App Open<br>• Block MREC<br>• Block Rewarded<br>• Block Native |
| [PairIP Bypass Enhanced (Experimental)](#pairip-bypass-enhanced-experimental) | A merged experimental PairIP bypass for common legacy, V2, and V3 protection layouts.<br><br>Automatic mode applies compatible strategies up to the selected risk level. It defaults to<br>Low Risk Strategies; Low and Med Risk Strategies enable medium-risk strategies, while Low, Med, and<br>High Risk Strategies also enables the invasive high-risk strategies.<br><br>Turn off automatic mode to test the individual manual strategies. Manual selections are<br>independent of the automatic risk-level setting, and every manual strategy is disabled by default.<br><br>This patch is experimental and app-dependent. It does not bypass server-side Play Integrity,<br>server-side licensing, or other server-side enforcement.<br><br>This enhanced patch is a merged product of the PairIP bypass patches from the credited<br>developers, with improvements for broader functionality, safer strategy selection, and usability.<br><br>Credits: Nai64Patches from Nai64, Entree, kondratjev, and rushiranpise (Doom). | • PairIP > Automatic > Strategy selection<br>• PairIP > Automatic > Risk level<br>• PairIP > Manifest > Redirect Application (Low Risk)<br>• PairIP > Manifest > Remove PairIP entries (Low Risk)<br>• PairIP > Manifest > Disable Firebase auto-init (Opt-in)<br>• PairIP > UI > Suppress LicenseClient error dialog (Low Risk)<br>• PairIP > UI > Suppress LicenseActivity error dialog (Low Risk)<br>• PairIP > UI > Suppress logged error dialog (Low Risk)<br>• PairIP > Response > Remove repeated-check metadata (Low Risk)<br>• PairIP > V2 > Disable repeated checks (Low Risk)<br>• PairIP > V2 > Disable repeated-check flag (Low Risk)<br>• PairIP > UI > Suppress LicenseClient paywall (Medium Risk)<br>• PairIP > UI > Suppress LicenseActivity paywall (Medium Risk)<br>• PairIP > UI > Suppress LicenseActivity nnStart (Medium Risk)<br>• PairIP > UI > Suppress LicenseActivity onStart (Medium Risk)<br>• PairIP > UI > Suppress LicenseActivity closeApp (Medium Risk)<br>• PairIP > UI > Suppress LicenseActivity exitApp (Medium Risk)<br>• PairIP > UI > Suppress LicenseActivity closeapp (Medium Risk)<br>• PairIP > UI > Suppress LicenseActivity exitapp (Medium Risk)<br>• PairIP > UI > Suppress LicenseActivity closeAllTasks (Medium Risk)<br>• PairIP > Installer > Spoof local installer check (Medium Risk)<br>• PairIP > License Client > Bypass checkLicense (Medium Risk)<br>• PairIP > License Client > Bypass initializeLicenseCheck (Medium Risk)<br>• PairIP > License Client > Bypass service connection (Medium Risk)<br>• PairIP > License Client > Bypass processResponse (Medium Risk)<br>• PairIP > Response > Bypass helper validation (Medium Risk)<br>• PairIP > Response > Bypass helper signature (Medium Risk)<br>• PairIP > Response > Bypass validator validation (Medium Risk)<br>• PairIP > Response > Bypass validator signature (Medium Risk)<br>• PairIP > V3 > Bypass response validation (Medium Risk)<br>• PairIP > Application > Bypass attachBaseContext (High Risk)<br>• PairIP > Application > Bypass onCreate (High Risk)<br>• PairIP > Runtime > Bypass Application static initializer (High Risk)<br>• PairIP > Runtime > Bypass VMRunner.invoke (High Risk)<br>• PairIP > Runtime > Bypass StartupLauncher.launch (High Risk)<br>• PairIP > Runtime > Bypass StartupLauncher.pairip (High Risk)<br>• PairIP > V3 > Bypass LicenseClient activity (High Risk)<br>• PairIP > Installer > Spoof installer source (High Risk)<br>• PairIP > Integrity > Bypass signature integrity (High Risk)<br>• PairIP > Integrity > Bypass signature match (High Risk)<br>• PairIP > Provider > Bypass initialization (High Risk)<br>• PairIP > Provider > Bypass query (High Risk)<br>• PairIP > Provider > Bypass context provider (High Risk)<br>• PairIP > V2 > Bypass checkLicenseInternal (High Risk)<br>• PairIP > V2 > Bypass response signature (High Risk)<br>• PairIP > Advanced > External VMRunner call sites (High Risk) |
| [UniPatches Universal Overlay Patch v1.3.3 (Experimental)](#unipatches-universal-overlay-patch-v1-3-3-experimental) | Universal in-app overlay for Android apps and games. Optional modules include System Time, FPS,<br>fullscreen, app brightness, and haptic controls. Modules are excluded and disabled by default;<br>select them in Morphe settings before patching. Statistic modules show information, Activity modules<br>control the current Activity, and Hook modules control internal app behavior, such as disabling<br>animations, through best-effort runtime changes. A selected local image automatically replaces<br>the legacy icon; empty or invalid image input falls back to the legacy icon. This is experimental<br>and may not work on all apps. UI presets can save and reuse supported UI and Advanced<br>settings. The title, description, repository button text, and repository button URL remain<br>controlled by the visible Morphe settings. Modules and Settings to Modules are excluded<br>because hook and module combinations can be app-specific.<br><br>The idea and initial works of this Universal Overlay Patch are from Zanuaimi / Noobite. | • Preset > Select > Preset<br>• Preset > Import > UI preset<br>• Preset > Export > UI preset<br>• Preset > Export > Output name<br>• UI > Controls > Theme<br>• UI > Controls > Background color<br>• UI > Controls > Foreground color<br>• UI > Controls > Bottom action button style<br>• UI > Controls > Bottom action button shape<br>• UI > Controls > Bottom action button padding<br>• UI > Controls > Bottom action button text color<br>• UI > Controls > Bottom action button background 1<br>• UI > Controls > Bottom action button background 2<br>• UI > Menu > Text color 1 (title and lines)<br>• UI > Menu > Text color 2 (module names)<br>• UI > Menu > Text color 3 (descriptions)<br>• UI > Menu > Text color 4 (monitor)<br>• UI > Menu > Text color 5 (active)<br>• UI > Menu > Text color 6 (module separators)<br>• UI > Menu > Separator background color<br>• UI > Menu > Module separator style<br>• UI > Menu > Title icon placement<br>• UI > Menu > Title alignment<br>• UI > Menu > Title separator<br>• UI > Menu > Corners<br>• UI > Menu > Outline animation<br>• UI > Menu > Outline gradient animation speed<br>• UI > Menu > Opening animation<br>• UI > Menu > Closing animation<br>• UI > Menu > Animation duration (ms)<br>• UI > Menu > Animation graph<br>• UI > Menu > Outline width (dp)<br>• General > Overlay > Title<br>• General > Overlay > Description<br>• General > Overlay > Appended description<br>• General > Overlay > Description alignment<br>• General > Overlay > Appended description color<br>• General > Overlay > Background color<br>• General > Overlay > Background transparency (%)<br>• General > Overlay > Outline color<br>• General > Repository button > Text<br>• General > Repository button > URL<br>• UI > Icon > Legacy text<br>• UI > Icon > Legacy bold text<br>• UI > Icon > Legacy text color<br>• UI > Icon > Legacy text size (sp)<br>• UI > Icon > Gradient background<br>• UI > Icon > Background color 1<br>• UI > Icon > Background color 2<br>• UI > Icon > Gradient angle (degrees)<br>• UI > Icon > Outline<br>• UI > Icon > Outline width (dp)<br>• UI > Icon > Outline color<br>• UI > Icon > Custom button icon (local image)<br>• UI > Icon > Custom button icon (String Handler)<br>• UI > Button > Shape<br>• UI > Button > Size (dp)<br>• UI > Button > Idle opacity (%)<br>• UI > Button > Fully visible duration (seconds)<br>• UI > Button > Position<br>• Advanced > Activity name override<br>• Advanced > Activity > Overlay install banlist<br>• Modules > Settings > Activate statistic modules on launch<br>• Modules > Settings > Show no runtime modules warning<br>• Modules > Settings > Enable monitors for statistic modules on launch<br>• Modules > Settings > Statistic monitor position<br>• Modules > Settings > Monitor panel size<br>• Modules > Settings > Monitor columns<br>• Modules > Settings > Temperature stat format<br>• Modules > Settings > System time format<br>• Modules > Statistic > Device Information<br>• Modules > Statistic > FPS<br>• Modules > Statistic > Device Temperature<br>• Modules > Statistic > System Time<br>• Modules > Statistic > App Session Time<br>• Modules > Statistic > Battery Status<br>• Modules > Statistic > App Memory Usage<br>• Modules > Statistic > Network Status<br>• Modules > Activity > Keep screen awake<br>• Modules > Activity > Fullscreen<br>• Modules > Activity > Allow screenshots<br>• Modules > Activity > App brightness<br>• Modules > Activity > Rotation mode<br>• Modules > Activity > App audio mute<br>• Modules > Hook > Disable haptic feedback / vibrations<br>• Modules > Hook > Disable app animations |

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
