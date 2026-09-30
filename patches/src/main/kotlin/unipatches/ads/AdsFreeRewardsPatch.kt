package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import java.util.logging.Logger

internal var adsFreeRewardsRuntimeGuardEnabled = false

/** Orchestrates the resolved Rewards plan. SDK bytecode operations live in adapters. */
internal fun BytecodePatchContext.applyAdsFreeRewards(
    logger: Logger,
    settings: AdsPatchSettings,
    plan: AdsPatchPlan,
) {
    adsFreeRewardsRuntimeGuardEnabled = plan.rewards.mode == AdsPatchMode.RUNTIME
    logger.info("Ads Free Rewards: adapter detection and dispatch started")
    if (detectedRewardAdapters(logger).isEmpty()) {
        logger.warning("Ads Free Rewards: no supported ad SDK found - no changes applied")
        return
    }
    if (plan.rewards.mode == AdsPatchMode.RUNTIME) {
        RuntimeRewardsCoordinator(this, logger).apply(settings, plan)
    } else {
        StaticRewardsCoordinator(this, logger).apply(settings, plan)
    }
}
