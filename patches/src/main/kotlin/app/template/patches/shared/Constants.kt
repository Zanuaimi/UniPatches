package app.template.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

object Constants {
    val COMPATIBILITY_EXAMPLE = Compatibility(
        name = "XYZ app", // App name as it appears in the Android launcher.
        packageName = "com.example.app",
        apkFileType = ApkFileType.APK, // Preferred or recommended file type.
        appIconColor = 0xFF0045, // Icon color in Morphe Manager. Usually the same color as the icon background.
        targets = listOf(
            // "version = null" means the patch works with the latest app target
            // and is expected to work with all future app targets.
            //
            // It is highly recommended to always include the exact app version developed
            // your patches for or the last version you have confirmed as 100% working.
            //
            // It is highly preferred to use app versions that are
            // available on apkmirror.com or uptodown.com, as Morphe web-search will
            // redirect users to these sites. If an app version is not available
            // on ApkMirror or UpToDown, then the user will be sent to Google search.
            AppTarget(
                version = "2.0.0"
            ),
            AppTarget(
                version = "1.0.2"
            )
        )
    )

    val COMPATIBILITY_EXAMPLE_2 = Compatibility(
        name = "XYZ app",
        packageName = "com.example.app",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x00FF45, // Icon color in Morphe Manager. Usually the same color as the icon background.
        targets = listOf(
            // 'any' version supported experimentally.
            AppTarget(
                version = null,
                isExperimental = true
            ),
            // App version confirmed 100% working.
            AppTarget(
                version = "1.0.2"
            )
        )
    )

    // Version code restriction.
    // Required for certain apps that can have multiple architecture releases with the same
    // version name (1.0.1) but different version codes (584009457).
    val COMPATIBILITY_EXAMPLE_3 = Compatibility(
        name = "XYZ app",
        packageName = "com.example.app",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x00FF45,
        targets = listOf(
            AppTarget(
                version = "1.0.5",
                // Required version code. If the user tries to patch a different version code
                // then Manager will warn they have the wrong original apk/apkm.
                //
                // This declaration is only required when multiple architecture releases have the
                // same version code but only 1 specific version code works or has been well tested.
                // If there is only 1 release for each architecture file type, then it's best
                // not to declare this.
                versionCodes = mapOf(
                    SupportedAbi.ARM64_V8A to 584009457,
                    SupportedAbi.ARMEABI_V7A to 584119423
                )
            )
        )
    )
}
