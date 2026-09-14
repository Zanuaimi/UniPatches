package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import java.util.logging.Logger

/** Owns guarded Rewards SDK application. It never invokes a static coordinator. */
internal class RuntimeRewardsCoordinator(
    private val context: BytecodePatchContext,
    private val logger: Logger,
) {
    fun apply(settings: AdsPatchSettings, plan: AdsPatchPlan) {
        context.applyRuntimeRewardAdapters(logger, settings, plan)
    }
}
