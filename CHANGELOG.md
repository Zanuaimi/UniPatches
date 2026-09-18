## [1.29.0](https://github.com/Zanuaimi/UniPatches/compare/v1.28.0...v1.29.0) (2026-09-18)

### 🐛 Bug Fixes

* add in-app emulation regression coverage ([0357e32](https://github.com/Zanuaimi/UniPatches/commit/0357e32cccdc20349e7622c4e483f2b1d72c78ce))
* add in-app runtime seam tests ([f2c2303](https://github.com/Zanuaimi/UniPatches/commit/f2c2303e60c7188d0d9d764c71e94164ccde3cb4))
* **ads:** Enable runtime policy from selected modules ([34cec7f](https://github.com/Zanuaimi/UniPatches/commit/34cec7f9d79690e7eae53caef7ca5d48b87c99a9))
* attach overlay from application startup ([8ce826d](https://github.com/Zanuaimi/UniPatches/commit/8ce826d7536c190c6cbb06f8a8bb52d92ddf2806))
* centralize emulated purchase delivery ([c8d735b](https://github.com/Zanuaimi/UniPatches/commit/c8d735bec27ec70e46b9850baedf8bf0402ed104))
* clean patch formatting ([0808519](https://github.com/Zanuaimi/UniPatches/commit/0808519490a64501be19f3d8c8acfd81a11632f1))
* **compatibility:** Look for Intent Filter and Add Action as well for OpenIAB compatibility fix. ([2b099c5](https://github.com/Zanuaimi/UniPatches/commit/2b099c5828655c1393a56add95773ea08741272f))
* complete in-app purchase emulation callbacks ([f5d4e42](https://github.com/Zanuaimi/UniPatches/commit/f5d4e42d4dc10bbc58a6748b334b4aac7a713f1b))
* fix incorrect action match for universal overlay and openiab compatibility fix ([1808a10](https://github.com/Zanuaimi/UniPatches/commit/1808a103d827f2adfc1b4d57c1f5f22635a00ee6))
* harden in-app emulation validation ([df23661](https://github.com/Zanuaimi/UniPatches/commit/df236617f2fd8721cf6969be8227095bdd51e036))
* harden in-app purchase runtime ([78cf77e](https://github.com/Zanuaimi/UniPatches/commit/78cf77eb14eda15ceb1d1e0aecf55a076431a72e))
* **iap:** preserve unsafe legacy purchase flows ([8308e7b](https://github.com/Zanuaimi/UniPatches/commit/8308e7b668916edf2da28924421a594342fe76dc))
* **inap:** Fix timeout causing verifier error ([da12b55](https://github.com/Zanuaimi/UniPatches/commit/da12b55f37ed749f06852068206a376d538d4877))
* **inapp:** bypass legacy proxy in non-overlay mode ([7e998e7](https://github.com/Zanuaimi/UniPatches/commit/7e998e702458098ecbe15ddf9f97654ec537bb6f))
* **inapp:** Ensure activity is opened in legacy apps and ensure one confirm layer popup ([a7929a3](https://github.com/Zanuaimi/UniPatches/commit/a7929a35224a343b50424307c71e618cbbee05af))
* **inapp:** Fix incorrect constructor for legacy ([e6af92a](https://github.com/Zanuaimi/UniPatches/commit/e6af92ac170d32b43174dddc8f0f9efda6488ecb))
* **inapp:** Fix missing catalog in legacy SDKs, and add a new patch option ([9709eac](https://github.com/Zanuaimi/UniPatches/commit/9709eac7426db7ddc3bd41e4c9d2090b52deec8a))
* **inapp:** harden legacy purchase callback flow ([26489c2](https://github.com/Zanuaimi/UniPatches/commit/26489c261eee0fe37b9e3378118930c9e115c8ba))
* **inapp:** Harden purchase callback delivery ([2073291](https://github.com/Zanuaimi/UniPatches/commit/207329162fc8239d766d1f902c08f7d0c4156fce))
* **inapp:** Route legacy OpenIAB purchases via proxy ([7bce4cb](https://github.com/Zanuaimi/UniPatches/commit/7bce4cb497531f5b5813446cce69b3321d5f2542))
* **legacy:** harden OpenIAB receiver compatibility ([627cdde](https://github.com/Zanuaimi/UniPatches/commit/627cdde0c857fc606f3ddcd531b791ed5492ca90))
* Make proper resolve for overlay injection, and add UnityPlugin hooks ([b93c9c3](https://github.com/Zanuaimi/UniPatches/commit/b93c9c344f6b6b1475e4489987801433676a3577))
* mark dev validation ([8f88753](https://github.com/Zanuaimi/UniPatches/commit/8f88753333bd5edfe349465208ac23f3466a819f))
* **output:** centralize target SDK compatibility ([5a3eb27](https://github.com/Zanuaimi/UniPatches/commit/5a3eb27007a428ef1448e9b2d26642051d6ee9f8))
* **overlay:** Harden launcher result forwarding ([057afcb](https://github.com/Zanuaimi/UniPatches/commit/057afcbfd0095376f264f468382005e3239fa661))
* **overlay:** Improve injection strategy for some legacy APKs ([8ec6835](https://github.com/Zanuaimi/UniPatches/commit/8ec6835771d71ef3c45f5e472bc1319dbefb3f34))
* **overlay:** Make runtime lifecycle attachment safe ([43eb7bd](https://github.com/Zanuaimi/UniPatches/commit/43eb7bd38ca596c01912e41b4e735932eae58dc5))
* **overlay:** preserve shared startup bridge ([f4d7e13](https://github.com/Zanuaimi/UniPatches/commit/f4d7e1371e613858c0a16d129bb6d6d0fb1f5f56))
* **overlay:** Update Overlay MD files to be up to date ([c2589f0](https://github.com/Zanuaimi/UniPatches/commit/c2589f017b548af8588adc153ca8c8a6970b5f28))
* **overlay:** verify startup bridge and receiver safety ([56a3a58](https://github.com/Zanuaimi/UniPatches/commit/56a3a58e8fc7c6215602104918c15e157cadcb45))
* preserve legacy catalog and harden purchase flow ([a9b79d1](https://github.com/Zanuaimi/UniPatches/commit/a9b79d1f92d228381f42b9adb448df36a2183436))
* restore legacy purchase activity lifecycle ([26bdb2b](https://github.com/Zanuaimi/UniPatches/commit/26bdb2bad4a726c36968120c2b415dda4e129124))
* separate billing compatibility strategies ([0beac8c](https://github.com/Zanuaimi/UniPatches/commit/0beac8ca65bcbf6bc0107004b937580e90ba985f))

### ✨ New Features

* **compatibility:** add exported component controls ([8268ef7](https://github.com/Zanuaimi/UniPatches/commit/8268ef753ff17e19454920b617a3b9146c513a45))
* **compatibility:** add legacy app support patch ([c0b4a77](https://github.com/Zanuaimi/UniPatches/commit/c0b4a7726d3529f41b41b8a740a8583b16643830))
* **compatibility:** Add OpenIAB parameter handling fix for older android, especially when used with Target SDK spoof. ([2fb98b2](https://github.com/Zanuaimi/UniPatches/commit/2fb98b2a454ed745aee6c0265b3b3872268780e8))
* **compatibility:** split into multiple files ([297d7f8](https://github.com/Zanuaimi/UniPatches/commit/297d7f80b74a0d5b6513e36473e4473cf22f2b27))
* **controlembedded:** New enhanced patch called Control Embedded Auth / Stores patch. This is where you can bypass LVL checks, or use MicroG instead of Google Play, or use zero google play services, and spoof what store is available to app. ([6c2c245](https://github.com/Zanuaimi/UniPatches/commit/6c2c24577096f77f5c1511243a8a17c2728b1a30))
* **controlembedded:** Organize auth and store settings ([4bf7ea0](https://github.com/Zanuaimi/UniPatches/commit/4bf7ea0332daa0f9849d907e95e24df081212599))
* **inapp:** Add attribution to module description ([9df450e](https://github.com/Zanuaimi/UniPatches/commit/9df450e903e705950028126b4aba14198443efcf))
* **inapp:** Add InApp Emulation overlay purchase controls ([ccf8574](https://github.com/Zanuaimi/UniPatches/commit/ccf8574befad5291a0bbf969e0a7ad292e901109))
* **inapp:** add legacy OpenIAB overlay bridge ([72ce177](https://github.com/Zanuaimi/UniPatches/commit/72ce1772bd3f173c8371188cf8e71bacaa020176))
* **inapp:** Add timeout to both non-overlay and overlay mode ([fd8345f](https://github.com/Zanuaimi/UniPatches/commit/fd8345f59f160806a71c2223d545c6cfa45ebb5f))
* **inapp:** Also affect launchSubscriptionPurchaseFlow ([46e86fc](https://github.com/Zanuaimi/UniPatches/commit/46e86fce388a5f4fa71230708fbc399d1cc51232))
* **inapp:** New Patch added to UniPatches, called Emulate InApp Patch. This is based on Nai64's FreeIAP patch, but merged MiguelNinja19's Cocos2D and GameMaker and ILL2CPP native HEX patch into it, and enhanced it for compatibility, stability and it has a new patch setting for choosing between Auto Mode, Managed Mode and Native Mode. ([34c6c2a](https://github.com/Zanuaimi/UniPatches/commit/34c6c2a4f79fbbfa6b99eaa3e2b45d252941f920))
* **inapp:** This patch is now removed, because I give up. ([214e7a1](https://github.com/Zanuaimi/UniPatches/commit/214e7a1558cebfb0e5bf325ce3623adf40513164))
* organize inapp coverage strategies ([562ae94](https://github.com/Zanuaimi/UniPatches/commit/562ae94d535a4e935f39afcfeb4590b234d97b60))
* **output:** Add patch option to preserve app data after uninstall ([c39c411](https://github.com/Zanuaimi/UniPatches/commit/c39c4113728feb8730210b52d14be1198cc2d5ef))
* **overlay:** Enable bold icon text ([308edd8](https://github.com/Zanuaimi/UniPatches/commit/308edd87d8ec4947e8c3373b2ec42dc1b89aa099))
* **overlay:** Enable header boxes in popups by default ([9e61a8a](https://github.com/Zanuaimi/UniPatches/commit/9e61a8a0a5d59b06bbf55c081dff3218cb831841))
* update overlay and patch documentation ([3fbc755](https://github.com/Zanuaimi/UniPatches/commit/3fbc755d81276e8795e46cf2d40a549536a6737b))

### 🔧 Improvements

* **inapp:** Improve UI overlay module of inapp emulation ([4f9cba8](https://github.com/Zanuaimi/UniPatches/commit/4f9cba8826fa8911c9b443037049356d6133a676))
* **inapp:** Optimize patching process of InApp emulation to reduce patch total time ([2864cbf](https://github.com/Zanuaimi/UniPatches/commit/2864cbf8c432fa5707204180907c5f54ec294234))

## [1.29.0-dev.35](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.34...v1.29.0-dev.35) (2026-09-18)

### ✨ New Features

* **inapp:** This patch is now removed, because I give up. ([214e7a1](https://github.com/Zanuaimi/UniPatches/commit/214e7a1558cebfb0e5bf325ce3623adf40513164))

## [1.29.0-dev.34](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.33...v1.29.0-dev.34) (2026-09-17)

### 🐛 Bug Fixes

* add in-app emulation regression coverage ([0357e32](https://github.com/Zanuaimi/UniPatches/commit/0357e32cccdc20349e7622c4e483f2b1d72c78ce))
* preserve legacy catalog and harden purchase flow ([a9b79d1](https://github.com/Zanuaimi/UniPatches/commit/a9b79d1f92d228381f42b9adb448df36a2183436))

## [1.29.0-dev.33](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.32...v1.29.0-dev.33) (2026-09-17)

### 🐛 Bug Fixes

* add in-app runtime seam tests ([f2c2303](https://github.com/Zanuaimi/UniPatches/commit/f2c2303e60c7188d0d9d764c71e94164ccde3cb4))
* harden in-app purchase runtime ([78cf77e](https://github.com/Zanuaimi/UniPatches/commit/78cf77eb14eda15ceb1d1e0aecf55a076431a72e))
* separate billing compatibility strategies ([0beac8c](https://github.com/Zanuaimi/UniPatches/commit/0beac8ca65bcbf6bc0107004b937580e90ba985f))

## [1.29.0-dev.32](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.31...v1.29.0-dev.32) (2026-09-17)

### 🐛 Bug Fixes

* centralize emulated purchase delivery ([c8d735b](https://github.com/Zanuaimi/UniPatches/commit/c8d735bec27ec70e46b9850baedf8bf0402ed104))
* clean patch formatting ([0808519](https://github.com/Zanuaimi/UniPatches/commit/0808519490a64501be19f3d8c8acfd81a11632f1))

### ✨ New Features

* organize inapp coverage strategies ([562ae94](https://github.com/Zanuaimi/UniPatches/commit/562ae94d535a4e935f39afcfeb4590b234d97b60))
* update overlay and patch documentation ([3fbc755](https://github.com/Zanuaimi/UniPatches/commit/3fbc755d81276e8795e46cf2d40a549536a6737b))

## [1.29.0-dev.31](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.30...v1.29.0-dev.31) (2026-09-17)

### 🐛 Bug Fixes

* **inapp:** bypass legacy proxy in non-overlay mode ([7e998e7](https://github.com/Zanuaimi/UniPatches/commit/7e998e702458098ecbe15ddf9f97654ec537bb6f))
* **inapp:** harden legacy purchase callback flow ([26489c2](https://github.com/Zanuaimi/UniPatches/commit/26489c261eee0fe37b9e3378118930c9e115c8ba))

## [1.29.0-dev.30](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.29...v1.29.0-dev.30) (2026-09-17)

### 🐛 Bug Fixes

* **inapp:** Fix incorrect constructor for legacy ([e6af92a](https://github.com/Zanuaimi/UniPatches/commit/e6af92ac170d32b43174dddc8f0f9efda6488ecb))

### ✨ New Features

* **overlay:** Enable bold icon text ([308edd8](https://github.com/Zanuaimi/UniPatches/commit/308edd87d8ec4947e8c3373b2ec42dc1b89aa099))

## [1.29.0-dev.30](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.29...v1.29.0-dev.30) (2026-09-17)

### 🐛 Bug Fixes

* **inapp:** Fix incorrect constructor for legacy ([e6af92a](https://github.com/Zanuaimi/UniPatches/commit/e6af92ac170d32b43174dddc8f0f9efda6488ecb))

## [1.29.0-dev.29](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.28...v1.29.0-dev.29) (2026-09-17)

### 🐛 Bug Fixes

* **inap:** Fix timeout causing verifier error ([da12b55](https://github.com/Zanuaimi/UniPatches/commit/da12b55f37ed749f06852068206a376d538d4877))

## [1.29.0-dev.28](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.27...v1.29.0-dev.28) (2026-09-17)

### ✨ New Features

* **inapp:** Add timeout to both non-overlay and overlay mode ([fd8345f](https://github.com/Zanuaimi/UniPatches/commit/fd8345f59f160806a71c2223d545c6cfa45ebb5f))

## [1.29.0-dev.27](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.26...v1.29.0-dev.27) (2026-09-17)

### 🐛 Bug Fixes

* complete in-app purchase emulation callbacks ([f5d4e42](https://github.com/Zanuaimi/UniPatches/commit/f5d4e42d4dc10bbc58a6748b334b4aac7a713f1b))

## [1.29.0-dev.26](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.25...v1.29.0-dev.26) (2026-09-17)

### 🔧 Improvements

* **inapp:** Improve UI overlay module of inapp emulation ([4f9cba8](https://github.com/Zanuaimi/UniPatches/commit/4f9cba8826fa8911c9b443037049356d6133a676))

## [1.29.0-dev.25](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.24...v1.29.0-dev.25) (2026-09-17)

### 🐛 Bug Fixes

* attach overlay from application startup ([8ce826d](https://github.com/Zanuaimi/UniPatches/commit/8ce826d7536c190c6cbb06f8a8bb52d92ddf2806))
* harden in-app emulation validation ([df23661](https://github.com/Zanuaimi/UniPatches/commit/df236617f2fd8721cf6969be8227095bdd51e036))

## [1.29.0-dev.24](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.23...v1.29.0-dev.24) (2026-09-17)

### 🐛 Bug Fixes

* **inapp:** Route legacy OpenIAB purchases via proxy ([7bce4cb](https://github.com/Zanuaimi/UniPatches/commit/7bce4cb497531f5b5813446cce69b3321d5f2542))

## [1.29.0-dev.23](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.22...v1.29.0-dev.23) (2026-09-17)

### 🐛 Bug Fixes

* **inapp:** Ensure activity is opened in legacy apps and ensure one confirm layer popup ([a7929a3](https://github.com/Zanuaimi/UniPatches/commit/a7929a35224a343b50424307c71e618cbbee05af))

## [1.29.0-dev.22](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.21...v1.29.0-dev.22) (2026-09-17)

### 🐛 Bug Fixes

* restore legacy purchase activity lifecycle ([26bdb2b](https://github.com/Zanuaimi/UniPatches/commit/26bdb2bad4a726c36968120c2b415dda4e129124))

## [1.29.0-dev.21](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.20...v1.29.0-dev.21) (2026-09-17)

### ✨ New Features

* **compatibility:** split into multiple files ([297d7f8](https://github.com/Zanuaimi/UniPatches/commit/297d7f80b74a0d5b6513e36473e4473cf22f2b27))

## [1.29.0-dev.20](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.19...v1.29.0-dev.20) (2026-09-17)

### ✨ New Features

* **inapp:** Also affect launchSubscriptionPurchaseFlow ([46e86fc](https://github.com/Zanuaimi/UniPatches/commit/46e86fce388a5f4fa71230708fbc399d1cc51232))

## [1.29.0-dev.19](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.18...v1.29.0-dev.19) (2026-09-17)

### 🐛 Bug Fixes

* mark dev validation ([8f88753](https://github.com/Zanuaimi/UniPatches/commit/8f88753333bd5edfe349465208ac23f3466a819f))

## [1.29.0-dev.18](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.17...v1.29.0-dev.18) (2026-09-16)

### 🐛 Bug Fixes

* **inapp:** Harden purchase callback delivery ([2073291](https://github.com/Zanuaimi/UniPatches/commit/207329162fc8239d766d1f902c08f7d0c4156fce))
* **overlay:** Harden launcher result forwarding ([057afcb](https://github.com/Zanuaimi/UniPatches/commit/057afcbfd0095376f264f468382005e3239fa661))
* **overlay:** Make runtime lifecycle attachment safe ([43eb7bd](https://github.com/Zanuaimi/UniPatches/commit/43eb7bd38ca596c01912e41b4e735932eae58dc5))

## [1.29.0-dev.17](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.16...v1.29.0-dev.17) (2026-09-16)

### 🐛 Bug Fixes

* **overlay:** Improve injection strategy for some legacy APKs ([8ec6835](https://github.com/Zanuaimi/UniPatches/commit/8ec6835771d71ef3c45f5e472bc1319dbefb3f34))

## [1.29.0-dev.16](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.15...v1.29.0-dev.16) (2026-09-16)

### 🐛 Bug Fixes

* Make proper resolve for overlay injection, and add UnityPlugin hooks ([b93c9c3](https://github.com/Zanuaimi/UniPatches/commit/b93c9c344f6b6b1475e4489987801433676a3577))

## [1.29.0-dev.15](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.14...v1.29.0-dev.15) (2026-09-16)

### 🔧 Improvements

* **inapp:** Optimize patching process of InApp emulation to reduce patch total time ([2864cbf](https://github.com/Zanuaimi/UniPatches/commit/2864cbf8c432fa5707204180907c5f54ec294234))

## [1.29.0-dev.14](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.13...v1.29.0-dev.14) (2026-09-16)

### 🐛 Bug Fixes

* fix incorrect action match for universal overlay and openiab compatibility fix ([1808a10](https://github.com/Zanuaimi/UniPatches/commit/1808a103d827f2adfc1b4d57c1f5f22635a00ee6))

## [1.29.0-dev.13](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.12...v1.29.0-dev.13) (2026-09-16)

### 🐛 Bug Fixes

* **overlay:** Update Overlay MD files to be up to date ([c2589f0](https://github.com/Zanuaimi/UniPatches/commit/c2589f017b548af8588adc153ca8c8a6970b5f28))

## [1.29.0-dev.12](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.11...v1.29.0-dev.12) (2026-09-16)

### 🐛 Bug Fixes

* **iap:** preserve unsafe legacy purchase flows ([8308e7b](https://github.com/Zanuaimi/UniPatches/commit/8308e7b668916edf2da28924421a594342fe76dc))
* **legacy:** harden OpenIAB receiver compatibility ([627cdde](https://github.com/Zanuaimi/UniPatches/commit/627cdde0c857fc606f3ddcd531b791ed5492ca90))
* **overlay:** verify startup bridge and receiver safety ([56a3a58](https://github.com/Zanuaimi/UniPatches/commit/56a3a58e8fc7c6215602104918c15e157cadcb45))

## [1.29.0-dev.11](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.10...v1.29.0-dev.11) (2026-09-16)

### 🐛 Bug Fixes

* **compatibility:** Look for Intent Filter and Add Action as well for OpenIAB compatibility fix. ([2b099c5](https://github.com/Zanuaimi/UniPatches/commit/2b099c5828655c1393a56add95773ea08741272f))

## [1.29.0-dev.10](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.9...v1.29.0-dev.10) (2026-09-16)

### ✨ New Features

* **compatibility:** Add OpenIAB parameter handling fix for older android, especially when used with Target SDK spoof. ([2fb98b2](https://github.com/Zanuaimi/UniPatches/commit/2fb98b2a454ed745aee6c0265b3b3872268780e8))

## [1.29.0-dev.9](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.8...v1.29.0-dev.9) (2026-09-16)

### ✨ New Features

* **output:** Add patch option to preserve app data after uninstall ([c39c411](https://github.com/Zanuaimi/UniPatches/commit/c39c4113728feb8730210b52d14be1198cc2d5ef))

## [1.29.0-dev.8](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.7...v1.29.0-dev.8) (2026-09-16)

### 🐛 Bug Fixes

* **output:** centralize target SDK compatibility ([5a3eb27](https://github.com/Zanuaimi/UniPatches/commit/5a3eb27007a428ef1448e9b2d26642051d6ee9f8))

### ✨ New Features

* **compatibility:** add exported component controls ([8268ef7](https://github.com/Zanuaimi/UniPatches/commit/8268ef753ff17e19454920b617a3b9146c513a45))

## [1.29.0-dev.7](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.6...v1.29.0-dev.7) (2026-09-16)

### ✨ New Features

* **overlay:** Enable header boxes in popups by default ([9e61a8a](https://github.com/Zanuaimi/UniPatches/commit/9e61a8a0a5d59b06bbf55c081dff3218cb831841))

## [1.29.0-dev.6](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.5...v1.29.0-dev.6) (2026-09-16)

### ✨ New Features

* **inapp:** Add attribution to module description ([9df450e](https://github.com/Zanuaimi/UniPatches/commit/9df450e903e705950028126b4aba14198443efcf))

## [1.29.0-dev.5](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.4...v1.29.0-dev.5) (2026-09-16)

### 🐛 Bug Fixes

* **overlay:** preserve shared startup bridge ([f4d7e13](https://github.com/Zanuaimi/UniPatches/commit/f4d7e1371e613858c0a16d129bb6d6d0fb1f5f56))

### ✨ New Features

* **compatibility:** add legacy app support patch ([c0b4a77](https://github.com/Zanuaimi/UniPatches/commit/c0b4a7726d3529f41b41b8a740a8583b16643830))
* **inapp:** add legacy OpenIAB overlay bridge ([72ce177](https://github.com/Zanuaimi/UniPatches/commit/72ce1772bd3f173c8371188cf8e71bacaa020176))

## [1.29.0-dev.4](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.3...v1.29.0-dev.4) (2026-09-16)

### 🐛 Bug Fixes

* **ads:** Enable runtime policy from selected modules ([34cec7f](https://github.com/Zanuaimi/UniPatches/commit/34cec7f9d79690e7eae53caef7ca5d48b87c99a9))

### ✨ New Features

* **controlembedded:** Organize auth and store settings ([4bf7ea0](https://github.com/Zanuaimi/UniPatches/commit/4bf7ea0332daa0f9849d907e95e24df081212599))
* **inapp:** Add InApp Emulation overlay purchase controls ([ccf8574](https://github.com/Zanuaimi/UniPatches/commit/ccf8574befad5291a0bbf969e0a7ad292e901109))

## [1.29.0-dev.3](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.2...v1.29.0-dev.3) (2026-09-16)

### 🐛 Bug Fixes

* **inapp:** Fix missing catalog in legacy SDKs, and add a new patch option ([9709eac](https://github.com/Zanuaimi/UniPatches/commit/9709eac7426db7ddc3bd41e4c9d2090b52deec8a))

## [1.29.0-dev.2](https://github.com/Zanuaimi/UniPatches/compare/v1.29.0-dev.1...v1.29.0-dev.2) (2026-09-16)

### ✨ New Features

* **controlembedded:** New enhanced patch called Control Embedded Auth / Stores patch. This is where you can bypass LVL checks, or use MicroG instead of Google Play, or use zero google play services, and spoof what store is available to app. ([6c2c245](https://github.com/Zanuaimi/UniPatches/commit/6c2c24577096f77f5c1511243a8a17c2728b1a30))

## [1.29.0-dev.1](https://github.com/Zanuaimi/UniPatches/compare/v1.28.0...v1.29.0-dev.1) (2026-09-16)

### ✨ New Features

* **inapp:** New Patch added to UniPatches, called Emulate InApp Patch. This is based on Nai64's FreeIAP patch, but merged MiguelNinja19's Cocos2D and GameMaker and ILL2CPP native HEX patch into it, and enhanced it for compatibility, stability and it has a new patch setting for choosing between Auto Mode, Managed Mode and Native Mode. ([34c6c2a](https://github.com/Zanuaimi/UniPatches/commit/34c6c2a4f79fbbfa6b99eaa3e2b45d252941f920))

## [1.28.0](https://github.com/Zanuaimi/UniPatches/compare/v1.27.6...v1.28.0) (2026-09-15)

### 🐛 Bug Fixes

* **ads:** replace MAX callback smali injection ([f20b00c](https://github.com/Zanuaimi/UniPatches/commit/f20b00c8b13661fac777390b7c67285a28fc4643))

### ✨ New Features

* **ads:** add safe MAX runtime bridge ([968a4ba](https://github.com/Zanuaimi/UniPatches/commit/968a4baa0b233ff7d72fec79e8ea3fdf46da8a1f))

## [1.27.6](https://github.com/Zanuaimi/UniPatches/compare/v1.27.5...v1.27.6) (2026-09-14)

### 🐛 Bug Fixes

* **overlay + ads:** fix morphe patch errors part 6 ([e80a4bc](https://github.com/Zanuaimi/UniPatches/commit/e80a4bcb9cd6e47aa9e309214a66ca147385519e))

## [1.27.5](https://github.com/Zanuaimi/UniPatches/compare/v1.27.4...v1.27.5) (2026-09-14)

### 🐛 Bug Fixes

* **overlay + ads:** Fix morphe patch errors part 5 ([04255ed](https://github.com/Zanuaimi/UniPatches/commit/04255ed22119c6d8cbd761a7dacea637d85972d4))

## [1.27.4](https://github.com/Zanuaimi/UniPatches/compare/v1.27.3...v1.27.4) (2026-09-14)

### 🐛 Bug Fixes

* **ads + overlay:** fix morphe patch errors part 4 ([4f3a0b9](https://github.com/Zanuaimi/UniPatches/commit/4f3a0b93f00cec37e4da72a5c1a254daa1cfa19d))

## [1.27.3](https://github.com/Zanuaimi/UniPatches/compare/v1.27.2...v1.27.3) (2026-09-14)

### 🐛 Bug Fixes

* **overlay + ads :** fix morphe patch errors part 3 ([e6d2bca](https://github.com/Zanuaimi/UniPatches/commit/e6d2bca1469bf66d70dbede801ec09dd6be899c5))

## [1.27.2](https://github.com/Zanuaimi/UniPatches/compare/v1.27.1...v1.27.2) (2026-09-14)

### 🐛 Bug Fixes

* **ads + overlay:** fix morphe patch errors for runtime part 2 ([c26e63a](https://github.com/Zanuaimi/UniPatches/commit/c26e63a01f037d6c78634b0ee460868deb043733))

## [1.27.1](https://github.com/Zanuaimi/UniPatches/compare/v1.27.0...v1.27.1) (2026-09-14)

### 🐛 Bug Fixes

* **ads + overlay:** Fix missing callback done and return void, which caused morphe patch error because of its absence ([9f34e08](https://github.com/Zanuaimi/UniPatches/commit/9f34e0864719979f05f010a56e0d87b479d7f619))

## [1.27.0](https://github.com/Zanuaimi/UniPatches/compare/v1.26.1...v1.27.0) (2026-09-14)

### 🐛 Bug Fixes

* **ads:** verify MAX runtime hook boundaries ([60fab17](https://github.com/Zanuaimi/UniPatches/commit/60fab17c357b2bb3daa23be17d13b74571b58c2d))

### ✨ New Features

* **ads:** add request-scoped MAX reward controls ([6e452ed](https://github.com/Zanuaimi/UniPatches/commit/6e452edddab646dfd1a1db4555259b3e2c46855b))
* **overlay:** release v2.5.3 ([1df7924](https://github.com/Zanuaimi/UniPatches/commit/1df7924cc876f8a9b6093c1dd0e1ecc11828a254))

## [1.26.1](https://github.com/Zanuaimi/UniPatches/compare/v1.26.0...v1.26.1) (2026-09-14)

### 🐛 Bug Fixes

* **overlay:** fix problems with drag, header and overlay menu size ([d3b43ad](https://github.com/Zanuaimi/UniPatches/commit/d3b43ad38c82319c8cfecf585cb5809d3ec55f8b))

## [1.26.0](https://github.com/Zanuaimi/UniPatches/compare/v1.25.0...v1.26.0) (2026-09-14)

### 🐛 Bug Fixes

* **overlay:** keep controls inside Activity bounds ([7d6776f](https://github.com/Zanuaimi/UniPatches/commit/7d6776fec4cdd531d632f8b0a663c2af2775e1b1))

### ✨ New Features

* **overlay:** add configurable adaptive menu limits ([a2fe7fe](https://github.com/Zanuaimi/UniPatches/commit/a2fe7fe3cfc812de4bf71276d92d408aac7ee528))

## [1.25.0](https://github.com/Zanuaimi/UniPatches/compare/v1.24.1...v1.25.0) (2026-09-14)

### 🐛 Bug Fixes

* **overlay:** add v2.5 regression coverage ([dc5d7fa](https://github.com/Zanuaimi/UniPatches/commit/dc5d7fa8e1525228d94de64442aa86a25be6453a))

### ✨ New Features

* **overlay:** add system and advanced module families ([7c044e2](https://github.com/Zanuaimi/UniPatches/commit/7c044e2b70c249d003ee7fdb66343ab06987d1d2))

## [1.24.1](https://github.com/Zanuaimi/UniPatches/compare/v1.24.0...v1.24.1) (2026-09-14)

### 🐛 Bug Fixes

* **ads:** preserve smali descriptors during label isolation ([95b0d4f](https://github.com/Zanuaimi/UniPatches/commit/95b0d4f26d17791101ae3c9df6d29953d2748bba))

## [1.24.0](https://github.com/Zanuaimi/UniPatches/compare/v1.23.0...v1.24.0) (2026-09-14)

### 🐛 Bug Fixes

* **ads:** cover runtime guard regressions ([ab89925](https://github.com/Zanuaimi/UniPatches/commit/ab89925779b5967e1c8c1f467b8d332738028959))
* **ads:** harden runtime reward instrumentation ([ca6d206](https://github.com/Zanuaimi/UniPatches/commit/ca6d206ff9097d38902b9c60653f5997a2640ec2))

### ✨ New Features

* **overlay:** update patch version to v2.4.9 ([0d10c63](https://github.com/Zanuaimi/UniPatches/commit/0d10c635a5a8794870be4bae890ed3b01794ecef))

## [1.23.0](https://github.com/Zanuaimi/UniPatches/compare/v1.22.1...v1.23.0) (2026-09-14)

### 🐛 Bug Fixes

* **ads:** add runtime policy regression verification ([a6f5988](https://github.com/Zanuaimi/UniPatches/commit/a6f59880832b35831feba8af84fa33acd03c8f8a))
* **overlay:** reset runtime policy state between sessions ([050ea21](https://github.com/Zanuaimi/UniPatches/commit/050ea21beaea7bca0959b48d0d11fc26a6a8058d))

### ✨ New Features

* **ads:** rewrite control patch around resolved runtime plans ([aa0294e](https://github.com/Zanuaimi/UniPatches/commit/aa0294ed6805906434e0a17b0a8431bc6863969b))

## [1.22.1](https://github.com/Zanuaimi/UniPatches/compare/v1.22.0...v1.22.1) (2026-09-13)

### 🐛 Bug Fixes

* **ads:** add register safety regression coverage ([d460559](https://github.com/Zanuaimi/UniPatches/commit/d460559cbda99511d0d968144e29527226f84ed4))
* **ads:** protect MAX reward readiness instrumentation ([978821c](https://github.com/Zanuaimi/UniPatches/commit/978821c9e8e675d92fb73d29fd559c741020a646))

## [1.22.0](https://github.com/Zanuaimi/UniPatches/compare/v1.21.0...v1.22.0) (2026-09-13)

### 🐛 Bug Fixes

* **ads:** preserve typed runtime boolean guards ([ece0fe8](https://github.com/Zanuaimi/UniPatches/commit/ece0fe800fe98012726d502b5b9fae5236f282b0))

### ✨ New Features

* **overlay:** release Universal Overlay v2.4.7 ([572c1c8](https://github.com/Zanuaimi/UniPatches/commit/572c1c89640be17589fcd491a2e7b6667621159c))

## [1.21.0](https://github.com/Zanuaimi/UniPatches/compare/v1.20.2...v1.21.0) (2026-09-13)

### 🐛 Bug Fixes

* **ads:** add runtime instrumentation regression coverage ([3cdba35](https://github.com/Zanuaimi/UniPatches/commit/3cdba358663fa95ac4fde9265b1d867a851c6742))
* **ads:** remove strategy coupling and protect runtime startup ([a5455ca](https://github.com/Zanuaimi/UniPatches/commit/a5455ca2049c09354f2331fe1499edeb9e83bf4c))

### ✨ New Features

* **overlay:** release Universal Overlay v2.4.6 ([8c8fdb4](https://github.com/Zanuaimi/UniPatches/commit/8c8fdb48c4bc06498dff39974400a2c26a35acef))

## [1.20.2](https://github.com/Zanuaimi/UniPatches/compare/v1.20.1...v1.20.2) (2026-09-12)

### 🐛 Bug Fixes

* **ads:** simplify reward versions and split SDK controls ([405ccd8](https://github.com/Zanuaimi/UniPatches/commit/405ccd8f03dd2fad89d2d4a771e587c39d332188))
* **overlay:** constrain settings popup width ([e84c20a](https://github.com/Zanuaimi/UniPatches/commit/e84c20a16ebb0caff54fa72375fd7e49f40bdc39))

## [1.20.1](https://github.com/Zanuaimi/UniPatches/compare/v1.20.0...v1.20.1) (2026-09-12)

### 🐛 Bug Fixes

* **ads:** add runtime safety regression coverage ([5e11651](https://github.com/Zanuaimi/UniPatches/commit/5e11651a19fdbadab8266720da57f87230dba9d4))
* **ads:** prevent runtime media readiness interference ([d4f3509](https://github.com/Zanuaimi/UniPatches/commit/d4f3509f86f6e42888959f9920f71e759380655f))

## [1.20.0](https://github.com/Zanuaimi/UniPatches/compare/v1.19.0...v1.20.0) (2026-09-12)

### ✨ New Features

* **unipatches:** Categorized patches ([bd8bdbb](https://github.com/Zanuaimi/UniPatches/commit/bd8bdbbe0b535e589407abd16d49b4a3f160e6ed))

## [1.19.0](https://github.com/Zanuaimi/UniPatches/compare/v1.18.0...v1.19.0) (2026-09-12)

### 🐛 Bug Fixes

* **ads:** harden runtime policy instrumentation ([9908658](https://github.com/Zanuaimi/UniPatches/commit/99086580f4d968ad2838625dc76a28f61bbee9f8))
* **overlay:** align runtime bridge startup ([eeea72b](https://github.com/Zanuaimi/UniPatches/commit/eeea72b3c19b3bb1e8c220f8184d4954855e1722))

### ✨ New Features

* **docs:** document overlay runtime architecture ([4439f33](https://github.com/Zanuaimi/UniPatches/commit/4439f3368a4027bd0b52448f9e85e2b3378075bf))

## [1.18.0](https://github.com/Zanuaimi/UniPatches/compare/v1.17.4...v1.18.0) (2026-09-12)

### 🐛 Bug Fixes

* **docs:** align ads runtime guidance ([0daff87](https://github.com/Zanuaimi/UniPatches/commit/0daff8760ac50515d0fd2aa43d28a21aca70aeec))
* **tests:** cover ads runtime defaults ([9ced2e8](https://github.com/Zanuaimi/UniPatches/commit/9ced2e8e4d60bec39453ba4779a92858ff784118))

### ✨ New Features

* **ads:** rework rewards runtime controls ([8bff967](https://github.com/Zanuaimi/UniPatches/commit/8bff967a38edf74b13ba270a33372687bd63c5e8))

## [1.17.4](https://github.com/Zanuaimi/UniPatches/compare/v1.17.3...v1.17.4) (2026-09-11)

### 🐛 Bug Fixes

* **ads:** keep runtime rewards startup-safe ([8d46ce9](https://github.com/Zanuaimi/UniPatches/commit/8d46ce9206585dd38730829da8a540ea00e02fc9))

## [1.17.3](https://github.com/Zanuaimi/UniPatches/compare/v1.17.2...v1.17.3) (2026-09-11)

### 🐛 Bug Fixes

* **ads:** expose rewards control in overlay ([0842968](https://github.com/Zanuaimi/UniPatches/commit/0842968c8863790dd448892e990893519e304634))

## [1.17.2](https://github.com/Zanuaimi/UniPatches/compare/v1.17.1...v1.17.2) (2026-09-11)

### 🐛 Bug Fixes

* dismiss overlay settings with Back ([253ab3a](https://github.com/Zanuaimi/UniPatches/commit/253ab3aba524deaf33487216aef6503a11585a9d))
* harden Configure App Ads runtime controls ([82a9b4a](https://github.com/Zanuaimi/UniPatches/commit/82a9b4ad47efe18922823c3510abf381aecb6b85))

## [1.17.1](https://github.com/Zanuaimi/UniPatches/compare/v1.17.0...v1.17.1) (2026-09-10)

### 🐛 Bug Fixes

* **overlay:** update version to v2.4.0 ([c13dd05](https://github.com/Zanuaimi/UniPatches/commit/c13dd05317a6f28ef3c96d57a7da276daeaa10a2))

## [1.17.0](https://github.com/Zanuaimi/UniPatches/compare/v1.16.0...v1.17.0) (2026-09-10)

### 🐛 Bug Fixes

* **ads:** honor SDK coverage in reward hooks ([2c4244c](https://github.com/Zanuaimi/UniPatches/commit/2c4244c811206499223a1ea4f3896bf80e8a9a69))
* **ads:** make runtime reward controls reversible ([acf5828](https://github.com/Zanuaimi/UniPatches/commit/acf58288cb3939cc1adc8320bf6f825b2a603550))
* **ads:** separate instant rewards from ad skipping ([8f1ba43](https://github.com/Zanuaimi/UniPatches/commit/8f1ba434c90261d04ab78e625b7dd958cc81fffc))

### ✨ New Features

* **overlay:** add descriptive runtime ad controls ([5ad5c11](https://github.com/Zanuaimi/UniPatches/commit/5ad5c11fc3404ae4b0723d416d48525cd9875641))

## [1.16.0](https://github.com/Zanuaimi/UniPatches/compare/v1.15.1...v1.16.0) (2026-09-10)

### ✨ New Features

* **Hill Climb Racing Example:** clarify that app-specific modules are example and does nothing ([57a4802](https://github.com/Zanuaimi/UniPatches/commit/57a4802ce1d1de6b8528a4b08838d6b86039284c))

## [1.15.1](https://github.com/Zanuaimi/UniPatches/compare/v1.15.0...v1.15.1) (2026-09-10)

### 🐛 Bug Fixes

* **overlay:** preserve dropdown outline and popup bounds ([95f0d88](https://github.com/Zanuaimi/UniPatches/commit/95f0d886df1df33133c441f816369d4a08629eb2))
* **overlay:** restore Lucky action button text ([4fc42f2](https://github.com/Zanuaimi/UniPatches/commit/4fc42f250784f6d89051d16844fc1e0b98f27fa1))

## [1.15.0](https://github.com/Zanuaimi/UniPatches/compare/v1.14.3...v1.15.0) (2026-09-10)

### 🐛 Bug Fixes

* **icon-builder:** reset template state and align geometry ([7edc063](https://github.com/Zanuaimi/UniPatches/commit/7edc0631d8a2382ca500e814ae68307a8d15e0f3))
* **overlay:** improve control styling and popup sizing ([d59e395](https://github.com/Zanuaimi/UniPatches/commit/d59e395a405c4b308da6ae0dee8dafe0abe3d93d))

### ✨ New Features

* **overlay:** update presets and patch version ([184547a](https://github.com/Zanuaimi/UniPatches/commit/184547a787449b1b741a403dc5662b42a0584106))

## [1.14.3](https://github.com/Zanuaimi/UniPatches/compare/v1.14.2...v1.14.3) (2026-09-10)

### 🐛 Bug Fixes

* trigger semantic release attestation ([8d49411](https://github.com/Zanuaimi/UniPatches/commit/8d494112b44059ffa7a6e43eaebff5b3e18f7505))

### 🔧 Improvements

* **ads:** reduce fallback patch heap usage ([eda705d](https://github.com/Zanuaimi/UniPatches/commit/eda705df61e647ac83151f09e34ab8fb9be1c0a8))
* **bytecode:** avoid redundant instruction snapshots ([5dc2068](https://github.com/Zanuaimi/UniPatches/commit/5dc206858bce09b4d5e91b89be41cd5b9d061428))
* **pairip:** narrow external VMRunner scan ([bdda520](https://github.com/Zanuaimi/UniPatches/commit/bdda52091cee752c4b6cecf698c75c81f80a15b7))

## [1.14.2](https://github.com/Zanuaimi/UniPatches/compare/v1.14.1...v1.14.2) (2026-09-10)

### 🐛 Bug Fixes

* register fixes ([8af8e59](https://github.com/Zanuaimi/UniPatches/commit/8af8e59d11b669b1a0144a2403fc7faf7b0c37d6))

## [1.14.1](https://github.com/Zanuaimi/UniPatches/compare/v1.14.0...v1.14.1) (2026-09-10)

### 🐛 Bug Fixes

* Update Zarchiver icon and update descriptions ([b7cc9b3](https://github.com/Zanuaimi/UniPatches/commit/b7cc9b33a3a106c0ac730dbe0d71362c81edb929))

## [1.14.0](https://github.com/Zanuaimi/UniPatches/compare/v1.13.0...v1.14.0) (2026-09-10)

### ✨ New Features

* **addons:** integrate HCR and Ads overlays ([d4d5445](https://github.com/Zanuaimi/UniPatches/commit/d4d54458a0ebc5d6f8e8372fd780d2f452661337))
* **overlay:** expand runtime icon customization ([e4b7510](https://github.com/Zanuaimi/UniPatches/commit/e4b7510cc7d0d7084f478d2bcd2873b339c79d6f))

## [1.13.0](https://github.com/Zanuaimi/UniPatches/compare/v1.12.0...v1.13.0) (2026-09-10)

### ✨ New Features

* **overlay:** extend runtime configuration and icon parsing ([2ac99b5](https://github.com/Zanuaimi/UniPatches/commit/2ac99b5a8d343bf4f0046e8da504e73cb80d1985))
* **overlay:** refine built-in preset visuals ([cb1cf3f](https://github.com/Zanuaimi/UniPatches/commit/cb1cf3f453e36db18239444d1a687842ae1b698d))

## [1.12.0](https://github.com/Zanuaimi/UniPatches/compare/v1.11.1...v1.12.0) (2026-09-09)

### ✨ New Features

* **overlay:** refine configuration and preset behavior ([2bee3b9](https://github.com/Zanuaimi/UniPatches/commit/2bee3b94cd896924e99e884b3e962d29a6470a72))
* **overlay:** strengthen shared runtime module lifecycle ([f8b0579](https://github.com/Zanuaimi/UniPatches/commit/f8b0579dfb10133312f3e351ad28e2a2ea91ec73))
* **patches:** improve compatibility and user-facing controls ([e0fe90e](https://github.com/Zanuaimi/UniPatches/commit/e0fe90eec468ed75b489cce9e31cb9d7fd5db8ae))

## [1.11.1](https://github.com/Zanuaimi/UniPatches/compare/v1.11.0...v1.11.1) (2026-09-09)

### 🐛 Bug Fixes

* **overlay:** restrict Hill Climb example to target package ([3452b53](https://github.com/Zanuaimi/UniPatches/commit/3452b534024110b707d2d196c4e4c292a76c67ae))

## [1.11.0](https://github.com/Zanuaimi/UniPatches/compare/v1.10.3...v1.11.0) (2026-09-09)

### ✨ New Features

* **ads:** add runtime policy controls ([d2ea7cc](https://github.com/Zanuaimi/UniPatches/commit/d2ea7cc450d308d89bc35045d7cf6aa629e653f9))
* **overlay:** extend shared core for integrated modules ([3d1d030](https://github.com/Zanuaimi/UniPatches/commit/3d1d030e0ad426476a99af9167bc7b0cc0316838))
* **overlay:** release universal overlay v2.0 ([4984b27](https://github.com/Zanuaimi/UniPatches/commit/4984b27cb1e8b55da66369c755fa941f82cbabf8))

## [1.10.3](https://github.com/Zanuaimi/UniPatches/compare/v1.10.2...v1.10.3) (2026-09-09)

### 🐛 Bug Fixes

* **overlay:** finalize shared core release ([6b525ec](https://github.com/Zanuaimi/UniPatches/commit/6b525ecd8317177cdafa7c8ebaab90f7b9ad6226))

## [1.10.2](https://github.com/Zanuaimi/UniPatches/compare/v1.10.1...v1.10.2) (2026-09-08)

### 🐛 Bug Fixes

* correct credits ([29d8759](https://github.com/Zanuaimi/UniPatches/commit/29d87595b9209e7d6ac63fa91330a5e8f0529d42))

## [1.10.1](https://github.com/Zanuaimi/UniPatches/compare/v1.10.0...v1.10.1) (2026-09-08)

### 🐛 Bug Fixes

* **network:** avoid forcing ad traffic online ([1f169e0](https://github.com/Zanuaimi/UniPatches/commit/1f169e0630a8e5d539ca1780aebd4c1d4633a528))
* **pairip:** synchronize overlay and compatibility diagnostics ([37997e9](https://github.com/Zanuaimi/UniPatches/commit/37997e900ac7fbc7ae0e3b297c60f33606e4a2d5))

## [1.10.0](https://github.com/Zanuaimi/UniPatches/compare/v1.9.0...v1.10.0) (2026-09-08)

### 🐛 Bug Fixes

* **bypass:** harden experimental bypass strategies ([703eaeb](https://github.com/Zanuaimi/UniPatches/commit/703eaeb242190a8f1c47b64928331dcd3e618568))

### ✨ New Features

* **ads:** consolidate configurable ad controls ([3b3030d](https://github.com/Zanuaimi/UniPatches/commit/3b3030d599d9c9e71e77e17ed7c502f0c1fdbc65))
* **display:** add configurable app display controls ([0dbea11](https://github.com/Zanuaimi/UniPatches/commit/0dbea11d24ed3bcad8afd0eb88d2dee722110e11))
* **output:** add safe custom app output controls ([0dae4a4](https://github.com/Zanuaimi/UniPatches/commit/0dae4a4069dda5e0b414b9cab4c52fb068a949f8))
* **overlay:** enhance universal overlay customization ([3ee6262](https://github.com/Zanuaimi/UniPatches/commit/3ee62629b7966656bf998ec500d8e0a6db00772f))

## [1.9.0](https://github.com/Zanuaimi/UniPatches/compare/v1.8.1...v1.9.0) (2026-09-08)

### ✨ New Features

* **overlay:** add configurable icons and vertical outline animation ([c4a7c6e](https://github.com/Zanuaimi/UniPatches/commit/c4a7c6ec491b3a0360cc925be50bb496ebe385f7))

## [1.8.1](https://github.com/Zanuaimi/UniPatches/compare/v1.8.0...v1.8.1) (2026-09-07)

### 🐛 Bug Fixes

* cleanup patch list and change patch source name to Zanuaimi's UniPatches ([5002181](https://github.com/Zanuaimi/UniPatches/commit/50021813bf76e369125cdd2bed7161e5363cd66c))

## [1.8.0](https://github.com/Zanuaimi/UniPatches/compare/v1.7.0...v1.8.0) (2026-09-07)

### ✨ New Features

* **overlay:** add activity install banlist ([2cc9a8c](https://github.com/Zanuaimi/UniPatches/commit/2cc9a8ce681a094cddd60609529894797a2592ac))
* **pairip:** add Firebase and installer fallback strategies ([a4d95d7](https://github.com/Zanuaimi/UniPatches/commit/a4d95d76914544e7936086e5d2c5ed884cd878fb))

## [1.7.0](https://github.com/Zanuaimi/UniPatches/compare/v1.6.0...v1.7.0) (2026-09-07)

### ✨ New Features

* **overlay:** add configurable module separator colors ([98442c3](https://github.com/Zanuaimi/UniPatches/commit/98442c376c2b11c9a22c2a196c6336886c99a3c0))
* **overlay:** expose separator color settings ([fdd3058](https://github.com/Zanuaimi/UniPatches/commit/fdd305803dceff8bc37ca7f95296dce2da07e341))
* **overlay:** update preset separator themes ([159b3f6](https://github.com/Zanuaimi/UniPatches/commit/159b3f600b838e230acc415fe881f85bcf4ba5b3))

## [1.6.0](https://github.com/Zanuaimi/UniPatches/compare/v1.5.0...v1.6.0) (2026-09-07)

### ✨ New Features

* **overlay:** add directional menu animations ([437d913](https://github.com/Zanuaimi/UniPatches/commit/437d9136a48f4581210e1d70cee012352359aaa8))
* **overlay:** organize settings and preset schema ([e49f8df](https://github.com/Zanuaimi/UniPatches/commit/e49f8df1c39fd8ef3a97880a2163f28f011b7741))
* **overlay:** update themed preset animations ([1f5204b](https://github.com/Zanuaimi/UniPatches/commit/1f5204b57f9f77ad00edf793c3c5794c6b13f27d))

## [1.5.0](https://github.com/Zanuaimi/UniPatches/compare/v1.4.0...v1.5.0) (2026-09-07)

### 🐛 Bug Fixes

* **ci:** preserve README patch generation markers ([8b35ae1](https://github.com/Zanuaimi/UniPatches/commit/8b35ae16b93ff2a930272cd07c37df550fae5db9))

### ✨ New Features

* **overlay:** add themed UI presets ([90737ec](https://github.com/Zanuaimi/UniPatches/commit/90737ecd862b6387703d85184d29efbf1400984f))
* **overlay:** expand configurable UI themes and animations ([b757b59](https://github.com/Zanuaimi/UniPatches/commit/b757b59ac0ca78c148c995a617a88d7d54a2fccf))

## [1.4.0](https://github.com/Zanuaimi/UniPatches/compare/v1.3.0...v1.4.0) (2026-09-05)

### ✨ New Features

* harden UI preset export paths ([aea34fe](https://github.com/Zanuaimi/UniPatches/commit/aea34fe359a9b68626b57c1d81983fe2d403ba58))

## [1.3.0](https://github.com/Zanuaimi/UniPatches/compare/v1.2.0...v1.3.0) (2026-09-05)

### ✨ New Features

* add Universal Overlay UI presets ([9b7f13b](https://github.com/Zanuaimi/UniPatches/commit/9b7f13bbe82583f66a4f77eaee09301baf94b3eb))
* organize PairIP strategies by risk ([2b9ae59](https://github.com/Zanuaimi/UniPatches/commit/2b9ae59ac5afa3de1c67b59fc51984971e4b12f4))

## [1.2.0](https://github.com/Zanuaimi/UniPatches/compare/v1.1.3...v1.2.0) (2026-09-05)

### ✨ New Features

* add configurable overlay appearance settings ([e0e75a3](https://github.com/Zanuaimi/UniPatches/commit/e0e75a36811a2c5e2f7c9ef3fec4d3d8a8236fc6))
* separate overlay text and icon outline styling ([d747f40](https://github.com/Zanuaimi/UniPatches/commit/d747f40e7948b6efd1feb515a9c65b0008644ea1))

## [1.1.3](https://github.com/Zanuaimi/UniPatches/compare/v1.1.2...v1.1.3) (2026-09-04)

### 🐛 Bug Fixes

* retry semantic release workflow ([a7c35ac](https://github.com/Zanuaimi/UniPatches/commit/a7c35acecb974b64899f8ee287588d7d42f089f8))

## [1.1.2](https://github.com/Zanuaimi/UniPatches/compare/v1.1.1...v1.1.2) (2026-09-04)

### 🐛 Bug Fixes

* harden universal patch injections ([bd8a120](https://github.com/Zanuaimi/UniPatches/commit/bd8a120facb20a04cc47f77b5f070499c7d80c95))
* make reward availability strategy-aware ([6e9cbe2](https://github.com/Zanuaimi/UniPatches/commit/6e9cbe2a8bcdf2ef4059f48320fbff4d60a94d5d))
* report purchase strategy failures ([dabb73b](https://github.com/Zanuaimi/UniPatches/commit/dabb73b35f151e54360142a54ba10ca25e55ac94))

## [1.1.1](https://github.com/Zanuaimi/UniPatches/compare/v1.1.0...v1.1.1) (2026-09-04)

### 🐛 Bug Fixes

* update README ([0b8c8ca](https://github.com/Zanuaimi/UniPatches/commit/0b8c8cacbc777ff02b56d23706ca7e16c4379617))

## [1.1.0](https://github.com/Zanuaimi/UniPatches/compare/v1.0.2...v1.1.0) (2026-09-04)

### ✨ New Features

* updated defaults of universal overlay ([7f3c92a](https://github.com/Zanuaimi/UniPatches/commit/7f3c92a973ec2376067499a396a97c519639fdcd))

## [1.0.2](https://github.com/Zanuaimi/UniPatches/compare/v1.0.1...v1.0.2) (2026-09-04)

### 🐛 Bug Fixes

* retry semantic workflow ([df73d96](https://github.com/Zanuaimi/UniPatches/commit/df73d96e73218e71ec0eb28d884cc6a9c246630f))

## [1.0.1](https://github.com/Zanuaimi/UniPatches/compare/v1.0.0...v1.0.1) (2026-09-04)

### 🐛 Bug Fixes

* update PairIP bypass description and reorganize patch packages and helpers ([1efb120](https://github.com/Zanuaimi/UniPatches/commit/1efb120859c458b20e2cefc7d3ea081255c306d8))

## 1.0.0 (2026-09-04)

### ✨ New Features

* import Universal Overlay v1.0 from Zanuaimi's Fork of Nai64Patches ([675a934](https://github.com/Zanuaimi/UniPatches/commit/675a9345fc7fc807c35c923a3aa77f0628023fcd))
