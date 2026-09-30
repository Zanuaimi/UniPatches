package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import java.util.logging.Logger

/** Owns permanent Rewards SDK application. */
internal class StaticRewardsCoordinator(
    private val context: BytecodePatchContext,
    private val logger: Logger,
) {
    fun apply(settings: AdsPatchSettings, plan: AdsPatchPlan) {
        context.applyStaticRewardAdapters(logger, settings, plan)
    }
}
