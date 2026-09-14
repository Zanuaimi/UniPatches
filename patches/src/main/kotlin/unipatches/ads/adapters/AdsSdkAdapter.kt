package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import java.util.logging.Logger

internal data class DetectionResult(
    val sdkName: String,
    val detected: Boolean,
    val supportedFormats: Set<String> = emptySet(),
    val supportsRewards: Boolean = false,
    val supportsAvailability: Boolean = false,
    /** Adapter-owned safety metadata used before any bytecode operation is attempted. */
    val minimumLocalRegisters: Int = 1,
    val preservesParameterRegisters: Boolean = true,
    val callbackStrategy: String? = null,
    val deduplicatesByMethodIdentity: Boolean = true,
)

internal data class StaticSdkPlan(
    val settings: AdsPatchSettings,
    val coverageEnabled: Boolean,
)

internal data class RuntimeSdkPlan(
    val settings: AdsPatchSettings,
    val coverageEnabled: Boolean,
)

internal data class PatchResult(
    val patched: Int = 0,
    val skipped: Int = 0,
)

/**
 * Adapter boundary for one SDK. Detection and bytecode application belong to the adapter;
 * planners decide which plan is passed to it.
 */
internal interface AdsSdkAdapter {
    fun detect(): DetectionResult
    fun applyStatic(plan: StaticSdkPlan): PatchResult
    fun applyRuntime(plan: RuntimeSdkPlan): PatchResult
}

internal abstract class ContextAdsSdkAdapter(
    protected val context: BytecodePatchContext,
    protected val logger: Logger,
) : AdsSdkAdapter

/** Strict No Ads adapter boundary. The resolved path mode selects exactly one entry point. */
internal interface NoAdsSdkAdapter {
    fun detect(): DetectionResult
    fun applyStatic(plan: NoAdsSdkPlan): PatchResult
    fun applyRuntime(plan: NoAdsSdkPlan): PatchResult
}

internal abstract class ContextNoAdsSdkAdapter(
    protected val noAdsContext: BytecodePatchContext,
    protected val noAdsLogger: Logger,
) : NoAdsSdkAdapter {
    final override fun applyStatic(plan: NoAdsSdkPlan): PatchResult =
        if (plan.mode == AdsPatchMode.STATIC) applyResolved(plan) else PatchResult()

    final override fun applyRuntime(plan: NoAdsSdkPlan): PatchResult =
        if (plan.mode == AdsPatchMode.RUNTIME) applyRuntimeResolved(plan) else PatchResult()

    protected abstract fun applyResolved(plan: NoAdsSdkPlan): PatchResult

    /**
     * Runtime dispatch must be explicitly implemented by an adapter. A missing runtime
     * implementation is safer as a no-op than silently reusing permanent static edits.
     */
    protected open fun applyRuntimeResolved(plan: NoAdsSdkPlan): PatchResult =
        PatchResult(skipped = 1)
}
