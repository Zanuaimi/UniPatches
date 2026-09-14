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

internal fun hasSafeLocalRegister(registerCount: Int, parameterRegisters: Int): Boolean =
    registerCount - parameterRegisters >= 1

internal fun shouldSkipMaxUnityWrapperForRuntime(runtimeRewards: Boolean): Boolean = runtimeRewards

/** A runtime-owned path must never fall back to an unguarded permanent edit. */
internal fun shouldSkipUnplannedRuntimePatch(
    runtimeNoAds: Boolean,
    categoryPresent: Boolean,
): Boolean = runtimeNoAds && !categoryPresent

/** MAX runtime instrumentation is disabled until every hook is request-scoped and register-safe. */
internal fun shouldSkipMaxRuntimeCoverage(
    runtimeBlockAds: Boolean,
    runtimeRewards: Boolean,
    maxCoverageEnabled: Boolean,
): Boolean = maxCoverageEnabled && (runtimeBlockAds || runtimeRewards)
