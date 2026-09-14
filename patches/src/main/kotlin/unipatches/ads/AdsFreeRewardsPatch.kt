package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import java.util.logging.Logger

internal var adsFreeRewardsRuntimeGuardEnabled = false

internal data class AdsSdkCoverage(
    val max: Boolean = true,
    val adMob: Boolean = true,
    val unity: Boolean = true,
    val ironSource: Boolean = true,
    val appLovin: Boolean = true,
    val vungle: Boolean = true,
    val meta: Boolean = true,
    val pangle: Boolean = true,
    val huawei: Boolean = true,
    val yandex: Boolean = true,
    val startApp: Boolean = true,
    val moPub: Boolean = true,
    val chartboost: Boolean = true,
    val inMobi: Boolean = true,
    val mintegral: Boolean = true,
)

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
