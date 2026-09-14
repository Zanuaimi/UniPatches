package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import java.util.logging.Logger

/** Owns permanent No Ads work that has passed the resolved static path decision. */
internal class StaticNoAdsCoordinator(
    context: BytecodePatchContext,
    logger: Logger,
) {
    private val maxAdapters = MaxNoAdsAdapterSet(context, logger)

    fun applyMax(plan: NoAdsSdkPlan): PatchResult = maxAdapters.applyStatic(plan)
}
