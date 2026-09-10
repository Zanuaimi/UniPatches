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

The following patches are enhanced merges of community work rather than direct copies:

- **PairIP Bypass Patch (Experimental, Enhanced)** — merges PairIP bypass approaches from [Nai64Patches](https://github.com/Nai64/Nai64Patches), [Entree](https://github.com/Entree3k/Morning-Entree-Patches), [kondratjev](https://github.com/kondratjev/morphe-patches), and [rushiranpise (Doom)](https://github.com/rushiranpise/morphe-patches). It combines coverage for common legacy, V2, and V3 protection layouts, organizes every strategy by risk level, and provides automatic selection that defaults to low- and medium-risk strategies. Users can disable automatic mode and test each strategy independently, including high-risk strategies, with diagnostics and compatibility guidance.

- **Custom App Output Patch (Experimental, Enhanced)** — merges the Clone, Custom App Icon, Hide App Icon, and target-SDK ideas from [Nai64Patches](https://github.com/Nai64/Nai64Patches) into one output-customization patch. It adds safer clone validation, relative-component and common authority/permission rewriting, launcher-name/icon controls, target-SDK safeguards, and clear warnings for package- or certificate-bound services such as OAuth, Firebase, Play Games, billing, and server licensing.

- **Custom App Display Patch (Experimental, Enhanced)** — evolves Nai64’s Custom App Resolution and game-optimization work into one display-compatibility patch. It combines aspect-ratio handling, Unity window-resolution requests, FPS preference, OpenGL ES/Vulkan/ANGLE preferences, and Android game-category hints. Frame-rate, graphics-driver, ANGLE, and aspect-ratio controls are UniPatches work; Android and individual games can still ignore display requests.

- **Configure App Ads Patch (Experimental, Enhanced, Has Overlay Addon)** — merges Nai64’s No Ads and Ads Free Rewards patches with literal ad-host blocking inspired by Entree and Adobo. It unifies ad-format blocking, selectable SDK coverage, optional reward completion without an ad, and embedded ad/tracker host filters in one configurable patch. Its enhancements include independent controls for skipping rewarded ads and granting rewards, separate coverage switches for supported SDK families, safer availability handling so reward flows remain reachable after ad blocking, and an optional Universal Overlay addon with session-only runtime controls for blocking ads, managing rewards, and blocking instrumented ad/tracking hosts. Unsupported, native, dynamically generated, encrypted, or unrecognized ad paths remain unchanged.

The Universal Overlay Patch has also been submitted as a pull request to [Nai64Patches](https://github.com/Nai64/Nai64Patches), one of the largest collections of universal Morphe patches. The version in Nai64Patches is intended for stable and major releases, while the version here is kept more up to date with ongoing improvements and changes. See the [Universal Overlay pull request](https://github.com/Nai64/Nai64Patches/pull/73).

![Universal Overlay pull request evidence](images/pullrequestevidence.png)

The repository also includes my own patches. The patches without separate community credits are:

- Universal Overlay
- Bypass Forced Online Checks
- Bypass Forced Updates

Community contributions are credited in each patch description, and merged or enhanced patches retain attribution to the developers whose work influenced them.

### Add UniPatches to Morphe

Add UniPatches as a source inside the Morphe patcher.

| Method    | Link                                                                                                                   |
| :-------- | :--------------------------------------------------------------------------------------------------------------------- |
| Deep link | [morphe.software/add-source?github=Zanuaimi/UniPatches](https://morphe.software/add-source?github=Zanuaimi/UniPatches) |
| Manual    | `https://github.com/Zanuaimi/UniPatches`                                                                               |

> [!TIP]
> Tap the deep link on a device that already has Morphe installed to add the source in one step.

### How to use these patches

After adding the source, select the patches you want from Morphe. Read each patch description and its options before applying it, especially experimental patches that modify app startup, licensing, integrity checks, or runtime behavior.

### Local overlay icon builder

The repository includes a local-only icon builder for designing legacy and Multi-parts overlay
icons. It runs in the browser and does not upload icon rows, images, or settings. The builder
supports the same pipe-separated Multi-parts rows used by Universal Overlay, including layers,
gradients, opacity, strokes, text parts, and the supported legacy shapes.

#### Desktop

1. Open a terminal in the repository root.
2. Start the local server:

```bash
python3 -m http.server 4173 --directory tools/icon-builder
```

3. Open `http://localhost:4173` in a desktop browser.
4. Choose a template or add parts manually. Use the live preview to tune position, size, layer,
   rotation, colors, stroke, opacity, and text.
5. Use **Copy rows** or **Download .txt**, then paste the rows into Universal Overlay's Multi-parts
   icon editor. Use **Download .json** when you want to reopen the complete builder state later.

You can also open `tools/icon-builder/index.html` directly, but the local server gives the most
consistent browser behavior for file import and downloads.

#### Android

1. Copy the `tools/icon-builder` folder to the Android device, or download the repository source
   archive and extract that folder locally.
2. Open `index.html` with a browser or an HTML/file-preview app.
3. If the browser blocks local file features, run the same server from Termux:

```bash
pkg install python
cd /path/to/icon-builder
python3 -m http.server 4173
```

Then open `http://127.0.0.1:4173` on the device. 4. Build or import the icon, use **Download .txt** or **Copy rows**, and paste the result into the
Morphe Universal Overlay Multi-parts setting. Local image preview uses the device only; no
image is sent to a server.

The row format is:

```text
shape|x|y|width|height|rotation|fill|color1|color2|stroke|opacity|layer|text|bold|font
```

The final `text` and `bold` fields are used for `text` parts. Keep the list at twelve parts or
fewer for Universal Overlay compatibility. The builder is at [tools/icon-builder](tools/icon-builder)
and has its own detailed guide in [tools/icon-builder/README.md](tools/icon-builder/README.md).

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.17.1](https://github.com/Zanuaimi/UniPatches/releases/tag/v1.17.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;9 patches total
<details open>
<summary>📦 Hill Climb Racing&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Hill Climb Racing Example Overlay Addon](#hill-climb-racing-example-overlay-addon) | Example overlay addon for Hill Climb Racing (com.fingersoft.hillclimb), used together with UniPatches Universal Overlay.<br>        It adds six safe, mock-only app-specific preview modules to the shared overlay: A<br>        - Add Coins<br>        - Add Gems<br>        - Add Paints <br>        - Vehicle Selection<br>        - Stage Selection<br>        - Garage Selection. <br>        All these six module options are enabled by default and can be disabled independently. <br>        <br>        Module settings and Preview actions are session-only; this example addon never reads or changes currencies, vehicles, stages, garage state, purchases, save files, or game bytecode. | • App-specific modules > Add Coins preview<br>• App-specific modules > Add Gems preview<br>• App-specific modules > Add Paints preview<br>• App-specific modules > Vehicle selection preview<br>• App-specific modules > Stage selection preview<br>• App-specific modules > Garage selection preview |

</details>

<details open>
<summary>🌐 Universal&nbsp;&nbsp;•&nbsp;&nbsp;8 patches</summary>
<br>

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Bypass Emulator Detection](#bypass-emulator-detection) | Hides common emulator traces by spoofing Build info and related checks so apps are less likely<br>to identify an emulator. This patch is marked for enhancement in future updates; coverage is<br>currently limited to the checks it can safely recognize.<br><br>Compatibility: PairIP Bypass and other server-side integrity systems can still reject a<br>device when entitlement is bound to genuine device signals, package identity, or attestation.<br>Combining spoofing with those patches is app-specific and cannot safely bypass remote checks.<br><br>Credits: Nai64Patches from Nai64. | • Quick setup > Device profile to imitate |
| [Bypass Forced Updates (Experimental)](#bypass-forced-updates-experimental) | Try to bypass high-confidence client-side forced-update screens. Start with the defaults;<br>each option handles a different part of an update flow. This cannot bypass a server that<br>refuses old app versions, and an unsupported app may still require an update. | • Quick setup > Bypass update-required checks<br>• Quick setup > Allow closing update dialogs<br>• Quick setup > Block update-store redirects<br>• Quick setup > Prevent update-triggered exit |
| [Configure App Ads Patch ( Experimental, Enhanced, Has Overlay Addon )](#configure-app-ads-patch-experimental-enhanced-has-overlay-addon) | A merged ad-control patch based on Nai64's No Ads and Ads Free Rewards patches, plus<br>literal-host blocking inspired by Entree and Adobo. Block common ad formats, choose the<br>SDKs to target, optionally grant ad rewards without an ad, and redirect matching literal<br>ad/tracker hosts embedded in the APK.<br><br>Host filters are small embedded subsets inspired by uBlock Origin, EasyList, AdGuard,<br>OISD, HaGeZi Pro mini, Privacy Essentials, EasyPrivacy, and Peter Lowe's list; they are<br>not downloaded full subscriptions. Each filter is independently toggleable. They only<br>affect hosts stored as literal strings by the app; they cannot block dynamically generated<br>hosts, encrypted traffic, or requests made entirely inside Google Play services.<br><br>Compatibility: when combined with Disable Forced Online Checks, keep that patch's “Exclude<br>ad SDK and networking code” option enabled; otherwise blocked ad hosts may be treated as<br>online and retried. PairIP Firebase cleanup/removal can disable Firebase-backed reward,<br>sign-in, billing, and attribution flows, which this patch cannot restore.<br><br>This patch includes an optional Universal Overlay addon. To use the addon, patch Configure App Ads<br>together with Universal Overlay, enable “Overlay integration ><br>Enable runtime controls”, and select one or more of its three overlay addon modules:<br>“Block Ads”, “Rewards without ads”, and “Block ad/tracking hosts”. The selected modules<br>appear under “Ad control hook modules” in Universal Overlay, with initial values copied<br>from this patch's ad settings. Universal Overlay is required for the runtime policy to be<br>installed; if it is not selected, the normal static ad controls still work but these<br>runtime addon modules are not available.<br>Runtime changes last for the current app process and affect only methods and literal hosts<br>successfully instrumented by this patch. Its policy is attached to the selected overlay's<br>own startup bridge, including a manual Activity override; it does not independently choose<br>an Application or Activity. Native, dynamically generated, unsupported, or unrecognized<br>SDK paths remain unchanged.<br><br>Credits: Nai64Patches from Nai64; host-blocking approach inspired by Entree and Adobo. | • Quick setup > 1. Choose ad policy<br>• Quick setup > 2. Ad formats > Block interstitial ads<br>• Quick setup > 2. Ad formats > Block banner ads<br>• Quick setup > 2. Ad formats > Block app-open ads<br>• Quick setup > 2. Ad formats > Block MREC ads<br>• Quick setup > 2. Ad formats > Block rewarded ads<br>• Quick setup > 2. Ad formats > Block native ads<br>• Rewards without ads > Enable<br>• Rewards without ads > SDK strategy<br>• Rewards without ads > Grant reward immediately<br>• Rewards without ads > Show unavailable reward buttons<br>• SDK coverage > AppLovin MAX<br>• SDK coverage > Google Mobile Ads (AdMob)<br>• SDK coverage > Unity Ads<br>• SDK coverage > ironSource / LevelPlay<br>• SDK coverage > AppLovin legacy<br>• SDK coverage > Vungle<br>• SDK coverage > Meta Audience Network<br>• SDK coverage > Pangle<br>• SDK coverage > Huawei Ads<br>• SDK coverage > Yandex / MyTarget<br>• SDK coverage > Other supported SDKs<br>• Host filters > uBlock Origin ads<br>• Host filters > EasyList ads<br>• Host filters > AdGuard mobile ads<br>• Host filters > OISD privacy<br>• Host filters > HaGeZi Pro mini<br>• Host filters > Privacy Essentials<br>• Host filters > EasyPrivacy<br>• Host filters > Peter Lowe's ad and tracking list<br>• Host filters > Match subdomains<br>• Host filters > Custom host entries<br>• Overlay integration > Enable runtime controls<br>• Overlay integration > Runtime controls > Block Ads<br>• Overlay integration > Runtime controls > Rewards without ads<br>• Overlay integration > Runtime controls > Block ad/tracking hosts<br>• Advanced > Heuristic matching > Enable broad audio-ad heuristics |
| [Custom App Display Patch (Experimental, Enhanced)](#custom-app-display-patch-experimental-enhanced) | Adjust an APK's display compatibility: screen shape, Unity window-size request, preferred<br>refresh rate, Unity renderer preference, ANGLE preference, and Android game-category hint.<br>Start with Display scaling. Android and the app can ignore any request.<br><br>Resolution and renderer overrides currently target Unity activities. Scaling removes Android<br>aspect-ratio restrictions where present; it cannot universally rescale every game engine.<br><br>Custom App Resolution and game-optimization inspiration: Nai64Patches from Nai64.<br>Frame-rate, graphics-driver, ANGLE, and aspect-ratio controls are UniPatches work. | • Quick setup > Display scaling > Mode<br>• Optional overrides > Resolution > Enable<br>• Optional overrides > Resolution > Width (px)<br>• Optional overrides > Resolution > Height (px)<br>• Optional overrides > Frame rate > Enable FPS preference<br>• Optional overrides > Frame rate > Target FPS<br>• Optional overrides > Frame rate > Activity scope<br>• Optional overrides > Graphics > API preference<br>• Optional overrides > Game mode > Optimization preference |
| [Custom App Output Patch (Experimental, Enhanced)](#custom-app-output-patch-experimental-enhanced) | Customize an APK's install identity and launcher presentation in one patch. Start with the<br>launcher name or icon; enable Clone only when you need a side-by-side copy. Name, icon,<br>hide-icon, and target-SDK options are<br>independent. This cannot preserve original-app data when a package or signing identity<br>changes. Clone mode rewrites supported manifest identifiers only; it does not rewrite<br>bytecode strings, explicit process names, task affinities, or arbitrary SDK configuration.<br>A clone has a new Android package identity and normally a new signing identity: package- or<br>certificate-bound OAuth, Firebase, Google Play Games, billing, deep links, and server<br>licenses may therefore not work and cannot be repaired safely by this patch. If PairIP<br>Bypass is also enabled, server/package-bound PairIP enforcement can still reject the clone.<br><br>Inspired by Nai64Patches from Nai64: Clone, Custom App Icon, Hide App Icon, and target<br>SDK customization patches. | • Advanced > Clone for side-by-side install > Enable<br>• Advanced > Clone for side-by-side install > Package name mode<br>• Advanced > Clone for side-by-side install > Append package suffix<br>• Advanced > Clone for side-by-side install > Package name override<br>• Advanced > Clone compatibility > Rewrite provider authorities<br>• Advanced > Clone compatibility > Rewrite custom permissions<br>• Advanced > Clone compatibility > Preserve relative components<br>• Quick setup > Launcher presentation > App name<br>• Quick setup > Launcher presentation > Hide launcher icon<br>• Quick setup > Launcher presentation > Use custom icon<br>• Quick setup > Launcher presentation > Icon local image<br>• Quick setup > Launcher presentation > Icon Base64 or HTTPS image<br>• Advanced > Android compatibility > Override target SDK<br>• Advanced > Android compatibility > Target SDK version |
| [Disable Forced Online Checks (Experimental)](#disable-forced-online-checks-experimental) | Try to bypass high-confidence client-side “internet required” gates. Start with Auto mode.<br>This cannot bypass server-side login, entitlement, multiplayer, or game-state checks.<br><br>Compatibility: Control App Ads can intentionally block ad hosts. Keep “Exclude ad SDK and<br>networking code” enabled when both patches are selected so blocked ads are not falsely told<br>the device is online and repeatedly retried. This does not bypass server-enforced online play. | • Quick setup > Automatic engine detection<br>• Advanced > Broad Android connectivity checks<br>• Engine strategies > Unity<br>• Engine strategies > Unreal<br>• Engine strategies > Godot<br>• Advanced > Generic bytecode scan<br>• Compatibility > Exclude ad SDK and networking code |
| [PairIP Bypass Patch (Experimental, Enhanced)](#pairip-bypass-patch-experimental-enhanced) | A merged experimental PairIP bypass for common legacy, V2, and V3 protection layouts.<br><br>Automatic mode applies compatible strategies up to the selected risk level. It defaults to<br>Low and Med Risk Strategies; that setting includes medium-risk strategies, while Low, Med,<br>and High Risk Strategies also includes invasive high-risk strategies.<br><br>Turn off automatic mode to test individual strategies. Manual selections are independent of<br>the automatic risk-level setting. Firebase auto-init disabling, Firebase component removal,<br>and the LicenseClient FULL_CHECK_OK state strategy are manual-only: automatic mode ignores<br>them at every risk level, including “Low, Med, and High Risk Strategies.” Every manual<br>strategy is disabled by default.<br><br>This patch is experimental and app-dependent. It does not bypass server-side Play Integrity,<br>server-side licensing, or other server-side enforcement.<br><br>Compatibility: cloned APKs can still fail when PairIP or a server binds entitlement to the<br>original package or signing certificate. Firebase component removal can break Firebase Auth,<br>Google Play Games, billing, analytics, and ad rewards. Device spoofing can also change apps'<br>device-integrity behavior. These identity and server-side conditions cannot be fixed safely<br>by combining PairIP Bypass with Custom App Output, Control App Ads, or Emulator Detection.<br><br>This enhanced patch is a merged product of the PairIP bypass patches from the credited<br>developers, with improvements for broader functionality, safer strategy selection, and usability.<br><br>Credits: Nai64Patches from Nai64, Entree, kondratjev, and rushiranpise (Doom). | • Quick setup > Automatic mode > Enable<br>• Quick setup > Automatic mode > Risk level<br>• Quick setup > Automatic mode > Diagnostic-only mode<br>• Quick setup > Automatic mode > Manifest-only first attempt<br>• Manual-only > Firebase compatibility > Disable Firebase auto-init metadata<br>• Manual-only > Firebase compatibility > Remove Firebase measurement components<br>• Strategy controls > Manifest > Redirect Application (Low Risk)<br>• Strategy controls > Manifest > Remove PairIP entries (Low Risk)<br>• Strategy controls > User interface > Suppress LicenseClient error dialog (Low Risk)<br>• Strategy controls > User interface > Suppress LicenseActivity error dialog (Low Risk)<br>• Strategy controls > User interface > Suppress logged error dialog (Low Risk)<br>• Strategy controls > Response checks > Remove repeated-check metadata (Low Risk)<br>• Strategy controls > PairIP V2 > Disable repeated checks (Low Risk)<br>• Strategy controls > PairIP V2 > Disable repeated-check flag (Low Risk)<br>• Strategy controls > User interface > Suppress LicenseClient paywall (Medium Risk)<br>• Strategy controls > User interface > Suppress LicenseActivity paywall (Medium Risk)<br>• Strategy controls > User interface > Suppress LicenseActivity nnStart (Medium Risk)<br>• Strategy controls > User interface > Suppress LicenseActivity onStart (Medium Risk)<br>• Strategy controls > User interface > Suppress LicenseActivity closeApp (Medium Risk)<br>• Strategy controls > User interface > Suppress LicenseActivity exitApp (Medium Risk)<br>• Strategy controls > User interface > Suppress LicenseActivity closeapp (Medium Risk)<br>• Strategy controls > User interface > Suppress LicenseActivity exitapp (Medium Risk)<br>• Strategy controls > User interface > Suppress LicenseActivity closeAllTasks (Medium Risk)<br>• Strategy controls > Installer checks > Spoof local installer check (Medium Risk)<br>• Manual-only > License Client > Force FULL_CHECK_OK state (Medium Risk)<br>• Strategy controls > License Client > Bypass checkLicense (Medium Risk)<br>• Strategy controls > License Client > Bypass initializeLicenseCheck (Medium Risk)<br>• Strategy controls > License Client > Bypass service connection (Medium Risk)<br>• Strategy controls > License Client > Bypass processResponse (Medium Risk)<br>• Strategy controls > Response checks > Bypass helper validation (Medium Risk)<br>• Strategy controls > Response checks > Bypass helper signature (Medium Risk)<br>• Strategy controls > Response checks > Bypass validator validation (Medium Risk)<br>• Strategy controls > Response checks > Bypass validator signature (Medium Risk)<br>• Strategy controls > PairIP V3 > Bypass response validation (Medium Risk)<br>• Strategy controls > Application startup > Bypass attachBaseContext (High Risk)<br>• Strategy controls > Application startup > Bypass onCreate (High Risk)<br>• Strategy controls > PairIP runtime > Bypass Application static initializer (High Risk)<br>• Strategy controls > PairIP runtime > Bypass VMRunner.invoke (High Risk)<br>• Strategy controls > PairIP runtime > Bypass StartupLauncher.launch (High Risk)<br>• Strategy controls > PairIP runtime > Bypass StartupLauncher.pairip (High Risk)<br>• Strategy controls > PairIP V3 > Bypass LicenseClient activity (High Risk)<br>• Strategy controls > Installer checks > Spoof installer source (High Risk)<br>• Strategy controls > Signature integrity > Bypass signature integrity (High Risk)<br>• Strategy controls > Signature integrity > Bypass signature match (High Risk)<br>• Strategy controls > Content provider > Bypass initialization (High Risk)<br>• Strategy controls > Content provider > Bypass query (High Risk)<br>• Strategy controls > Content provider > Bypass context provider (High Risk)<br>• Strategy controls > PairIP V2 > Bypass checkLicenseInternal (High Risk)<br>• Strategy controls > PairIP V2 > Bypass response signature (High Risk)<br>• Strategy controls > Advanced > External VMRunner call sites (High Risk) |
| [UniPatches Universal Overlay Patch v2.4.0 (Experimental)](#unipatches-universal-overlay-patch-v2-4-0-experimental) | A customizable in-app overlay for Android apps and games. For a quick first build: choose a visual<br>preset, select the overlay modules you want, optionally supply an icon image, then patch. Modules<br>are excluded and disabled by default. Monitor modules show information, Activity modules control<br>the current Activity, and Hook modules make best-effort changes to app behavior. Text is the<br>default legacy icon; an optional image replaces it completely, while the advanced Multi-parts editor<br>supports custom drawn icons, and can be conveniently made in Icon Builder local website in UniPatches repo.<br><br>This is an experimental patch and may not work on all apps. UI presets can save and reuse supported appearance and advanced icon<br>settings. The title, description, repository button text, and repository button URL remain<br>controlled by the visible Morphe settings. Module selections and module behavior are excluded<br>because hook and module combinations can be app-specific. <br><br>If Control App Ads is patched with its optional runtime policy enabled, its selected ad-control modules appear here<br>automatically; Universal Overlay does not patch ad SDKs by itself. When both patches are<br>selected, Control App Ads attaches its runtime policy to this overlay's exact startup bridge,<br>including an explicit Activity override, instead of selecting a separate Activity.<br><br>Attribution: The idea and initial works of Universal Overlay Patch are from Zanuaimi / Noobite. | • Quick setup > Preset selection<br>• Quick setup > Import / export UI presets > Import UI preset<br>• Quick setup > Import / export UI presets > Export UI preset<br>• Quick setup > Import / export UI presets > Export file name<br>• Quick setup > Module settings > Statistics > Device information<br>• Quick setup > Module settings > Statistics > FPS<br>• Quick setup > Module settings > Statistics > Device temperature<br>• Quick setup > Module settings > Statistics > System time<br>• Quick setup > Module settings > Statistics > App session time<br>• Quick setup > Module settings > Statistics > Battery status<br>• Quick setup > Module settings > Statistics > App memory usage<br>• Quick setup > Module settings > Statistics > Network status<br>• Quick setup > Module settings > Activity > Keep screen awake<br>• Quick setup > Module settings > Activity > Fullscreen<br>• Quick setup > Module settings > Activity > Allow screenshots<br>• Quick setup > Module settings > Activity > App brightness<br>• Quick setup > Module settings > Activity > Rotation mode<br>• Quick setup > Module settings > Activity > App audio mute<br>• Quick setup > Module settings > Hook > Disable haptic feedback / vibrations<br>• Quick setup > Module settings > Hook > Disable app animations<br>• Quick setup > Settings to modules > Monitor behavior > Activate statistics on launch<br>• Quick setup > Settings to modules > General > Show empty-module message<br>• Quick setup > Settings to modules > Monitor behavior > Enable monitors on launch<br>• Quick setup > Settings to modules > Monitor behavior > Monitor position<br>• Quick setup > Settings to modules > Monitor behavior > Monitor panel size<br>• Quick setup > Settings to modules > Monitor behavior > Monitor columns<br>• Quick setup > Settings to modules > Monitor behavior > Temperature format<br>• Quick setup > Settings to modules > Monitor behavior > Time format<br>• UI settings > Controls > Theme<br>• UI settings > Controls > Background color<br>• UI settings > Controls > Foreground color<br>• UI settings > Bottom buttons > Style<br>• UI settings > Bottom buttons > Shape<br>• UI settings > Bottom buttons > Add spacing<br>• UI settings > Bottom buttons > Text color<br>• UI settings > Bottom buttons > Background color 1<br>• UI settings > Bottom buttons > Background color 2<br>• UI settings > Colors > Text color 1 (title, lines, scrollbar)<br>• UI settings > Colors > Text color 2 (module names)<br>• UI settings > Colors > Text color 3 (descriptions)<br>• UI settings > Colors > Text color 4 (monitor)<br>• UI settings > Colors > Text color 5 (active)<br>• UI settings > Colors > Text color 6 (module separators)<br>• UI settings > Colors > Text color 7 (control outlines)<br>• UI settings > Module separators > Background color<br>• UI settings > Module separators > Style<br>• UI settings > Menu title > Icon placement<br>• UI settings > Menu title > Alignment<br>• UI settings > Menu title > Separator<br>• UI settings > Menu layout > Corners<br>• UI settings > Menu outline > Animation<br>• UI settings > Menu outline > Animation speed<br>• UI settings > Animations > Opening animation<br>• UI settings > Animations > Closing animation<br>• UI settings > Animations > Duration (ms)<br>• UI settings > Animations > Motion curve<br>• UI settings > Menu outline > Width (dp)<br>• UI settings > Menu text > Title<br>• UI settings > Menu text > Description<br>• UI settings > Menu text > Appended description<br>• UI settings > Menu text > Description alignment<br>• UI settings > Menu text > Appended description color<br>• UI settings > Menu background > Color<br>• UI settings > Menu background > Transparency (%)<br>• UI settings > Menu outline > Color<br>• UI settings > Bottom buttons > Repository text<br>• UI settings > Bottom buttons > Repository URL<br>• UI settings > Floating button > Text<br>• UI settings > Floating button > Bold text<br>• UI settings > Floating button > Text color<br>• UI settings > Floating button > Text size (sp)<br>• UI settings > Floating button > Text icon font<br>• UI settings > Menu > Text font<br>• UI settings > Floating button > Icon type<br>• UI settings > Floating button > Multi-parts icon editor > Part list<br>• UI settings > Floating button > Multi-parts icon editor > Import legacy icon JSON<br>• UI settings > Floating button > Multi-parts icon editor > Add highlight<br>• UI settings > Floating button > Gradient background<br>• UI settings > Floating button > Background color 1<br>• UI settings > Floating button > Background color 2<br>• UI settings > Floating button > Gradient angle (degrees)<br>• UI settings > Floating button > Background style<br>• UI settings > Floating button > Background color 3<br>• UI settings > Floating button > Background color 4<br>• UI settings > Floating button > Outline<br>• UI settings > Floating button > Outline width (dp)<br>• UI settings > Floating button > Outline color<br>• UI settings > Floating button > Outline gradient<br>• UI settings > Floating button > Outline color 2<br>• UI settings > Floating button > Outline gradient angle (degrees)<br>• Quick setup > Optional icon image > Local image<br>• Quick setup > Optional icon image > Base64 or HTTPS image<br>• UI settings > Floating button > Shape<br>• UI settings > Floating button > Size (dp)<br>• UI settings > Floating button > Idle opacity (%)<br>• UI settings > Floating button > Fully visible duration (seconds)<br>• UI settings > Floating button > Initial position<br>• Advanced > Activity injection > Target Activity name<br>• Advanced > Activity injection > Strategy<br>• Advanced > Activity injection > Install banlist<br>• UI settings > Floating button > Reset icon to text |

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

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidance, including the shared overlay guide.
The generalized overlay architecture, shared core, universal modules, and app-specific overlay
template are documented in [OVERLAY.md](OVERLAY.md), [OVERLAY_MODULES.md](OVERLAY_MODULES.md), and
[OVERLAY_UI_PRESETS.md](OVERLAY_UI_PRESETS.md).

## License

UniPatches is licensed under the [GNU General Public License v3.0](LICENSE).
