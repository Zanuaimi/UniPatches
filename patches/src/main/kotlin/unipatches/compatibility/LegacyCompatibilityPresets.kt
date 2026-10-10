package unipatches.compatibility

/** Fixed profiles describe the APK's original target era, not the device running it. */
internal object LegacyCompatibilityPresets {
    const val CUSTOM = "custom"
    val eras = linkedMapOf("android_7_9" to 28, "android_10" to 29, "android_11" to 30, "android_12" to 32, "android_13" to 33, "android_14_plus" to 34)
    val ids = setOf(CUSTOM) + eras.keys + setOf("unity", "openiab", "unity_openiab")

    fun validate(id: String): String = id.also { require(it in ids) { "Unknown legacy compatibility preset: $it" } }

    fun boolean(id: String, option: String, custom: Boolean?): Boolean? {
        validate(id)
        if (id == CUSTOM) return custom
        return when (option) {
            "diagnosticsOnly" -> false
            "spoofTargetSdk" -> false
            "legacyReviver" -> id in eras.keys
            "apacheLegacy" -> (eras[id] ?: 28) <= 28
            "foregroundService" -> (eras[id] ?: 28) <= 28
            "exactAlarms" -> false
            "bluetooth" -> (eras[id] ?: 28) < 31
            "bypassHiddenApi" -> false
            "trustCertificates" -> false
            "acknowledgeTrustCertificates" -> false
            "redirectLegacyStorage" -> false
            "receiverFixAppWide" -> true
            "repairExportFlags" -> true
            "exportAllActivityComponents" -> false
            "allowCleartext" -> false
            "relaxLibraries" -> false
            "playStorePackageVisibility" -> id == "openiab" || id == "unity_openiab"
            "bypassPackageVisibility" -> false
            "manifestCompatAttributes" -> false
            "legacyStorage" -> false
            "relocateExpansionNativeLibraries" -> false
            "removeRelocatedNativeLibrariesFromObb" -> false
            "embedExpansionObb" -> false
            "bypassExpansionDownloader" -> false
            "allScreens" -> false
            "extractNativeLibs" -> id == "unity" || id == "unity_openiab"
            "disableHeapTagging" -> false
            "vmSafeMode" -> false
            "spoofImei" -> false
            else -> error("Unknown legacy compatibility boolean: $option")
        }
    }

    fun playStorePackageVisibility(id: String, custom: Boolean?, openIabDetected: Boolean): Boolean? {
        val requested = boolean(id, "playStorePackageVisibility", custom)
        return if (validate(id) == CUSTOM) requested else requested == true && openIabDetected
    }

    fun target(id: String, custom: Int?): Int? = if (validate(id) == CUSTOM) custom else eras[id] ?: 28
    fun targetProfile(id: String, custom: String?): String? = if (validate(id) == CUSTOM) custom else TARGET_PROFILE_AUTOMATIC
    fun openIabMode(id: String, custom: String?): String? = if (validate(id) == CUSTOM) custom else
        if (id == "openiab" || id == "unity_openiab") OPEN_IAB_AUTOMATIC else OPEN_IAB_DISABLED
}
