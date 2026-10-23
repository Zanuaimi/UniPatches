package unipatches.compatibility

/** Additive profiles describe the APK's original era, not the device running it. */
internal object LegacyCompatibilityPresets {
    const val CUSTOM = "custom"
    val eraLabels = linkedMapOf(
        "Android 4.x origin" to "android_4_x",
        "Android 5–6 origin" to "android_5_6",
        "Android 7–9 origin" to "android_7_9",
        "Android 10 origin" to "android_10",
        "Android 11 origin" to "android_11",
        "Android 12/12L origin" to "android_12",
        "Android 13 origin" to "android_13",
        "Android 14+ origin" to "android_14_plus",
    )
    val eras = linkedMapOf(
        "android_4_x" to 20,
        "android_5_6" to 23,
        "android_7_9" to 28,
        "android_10" to 29,
        "android_11" to 30,
        "android_12" to 32,
        "android_13" to 33,
        "android_14_plus" to 34,
    )
    val options = linkedMapOf("Custom" to CUSTOM).apply { putAll(eraLabels) }
    val ids = options.values.toSet()
    val removedIds = setOf("unity", "openiab", "unity_openiab")

    fun validate(id: String): String {
        if (id in removedIds) return CUSTOM
        require(id in ids) { "Unknown legacy compatibility preset: $id" }
        return id
    }

    fun boolean(id: String, option: String, custom: Boolean?): Boolean {
        val era = eras[validate(id)]
        val presetEnabled = era != null && when (option) {
            "legacyReviver", "receiverFixAppWide", "repairExportFlags" -> true
            "apacheLegacy", "foregroundService" -> era <= 28
            "bluetooth" -> era < 31
            "playStorePackageVisibility" -> era >= 30
            // All other settings, including explicit opt-ins, remain user-controlled.
            else -> false
        }
        return custom == true || presetEnabled
    }
}
