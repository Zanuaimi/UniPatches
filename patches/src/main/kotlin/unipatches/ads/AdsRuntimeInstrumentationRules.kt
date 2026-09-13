package unipatches.ads

/**
 * Runtime instrumentation must not replace Unity Ads v4's shared show entry point.
 * The same method serves rewarded and non-rewarded placements.
 */
internal fun shouldPatchUnityAdsV4Permanently(
    runtimeHooksEnabled: Boolean,
    unitySdkEnabled: Boolean,
    blockInterstitials: Boolean,
    blockRewarded: Boolean,
): Boolean = !runtimeHooksEnabled && unitySdkEnabled && blockInterstitials && blockRewarded

/** Returns a valid smali boolean return sequence for an injected guard. */
internal fun booleanReturnInstructions(value: Boolean): String =
    "const/4 v0, 0x${if (value) "1" else "0"}\nreturn v0"
