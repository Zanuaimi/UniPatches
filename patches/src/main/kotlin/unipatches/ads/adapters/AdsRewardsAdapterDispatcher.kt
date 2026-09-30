package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import java.util.logging.Logger

internal fun BytecodePatchContext.rewardAdapters(
    logger: Logger,
): List<AdsSdkAdapter> = listOf(
    UnityAdsAdapter(this, logger),
    AdMobAdsAdapter(this, logger),
    IronSourceAdsAdapter(this, logger),
    InMobiAdsAdapter(this, logger),
    MaxAdsAdapter(this, logger),
    YandexAdsAdapter(this, logger),
    HuaweiAdsAdapter(this, logger),
)

/** Single detection/ownership registry consumed by both reward execution paths. */
internal fun BytecodePatchContext.detectedRewardAdapters(logger: Logger): List<DetectionResult> =
    rewardAdapters(logger).map { it.detect() }.filter { it.detected }

internal fun BytecodePatchContext.applyStaticRewardAdapters(
    logger: Logger,
    settings: AdsPatchSettings,
    plan: AdsPatchPlan,
) {
    val staticPlan = StaticSdkPlan(settings, false)
    rewardAdapters(logger).forEach { adapter ->
        if (adapter is YandexAdsAdapter && !settings.instantReward) return@forEach
        adapter.applyStatic(staticPlan.forAdapter(adapter, plan.sdkCoverage))
    }
}

internal fun BytecodePatchContext.applyRuntimeRewardAdapters(
    logger: Logger,
    settings: AdsPatchSettings,
    plan: AdsPatchPlan,
) {
    val runtimePlan = RuntimeSdkPlan(settings, false)
    rewardAdapters(logger).forEach { adapter ->
        adapter.applyRuntime(runtimePlan.forAdapter(adapter, plan.sdkCoverage))
    }
}

private fun StaticSdkPlan.forAdapter(adapter: AdsSdkAdapter, coverage: AdsSdkCoverage): StaticSdkPlan =
    copy(coverageEnabled = when (adapter) {
        is UnityAdsAdapter -> coverage.unity
        is AdMobAdsAdapter -> coverage.adMob
        is IronSourceAdsAdapter -> coverage.ironSource
        is InMobiAdsAdapter -> coverage.inMobi
        is MaxAdsAdapter -> coverage.max
        is YandexAdsAdapter -> coverage.yandex
        is HuaweiAdsAdapter -> coverage.huawei
        else -> false
    })

private fun RuntimeSdkPlan.forAdapter(adapter: AdsSdkAdapter, coverage: AdsSdkCoverage): RuntimeSdkPlan =
    copy(coverageEnabled = when (adapter) {
        is UnityAdsAdapter -> coverage.unity
        is AdMobAdsAdapter -> coverage.adMob
        is IronSourceAdsAdapter -> coverage.ironSource
        is InMobiAdsAdapter -> coverage.inMobi
        is MaxAdsAdapter -> coverage.max
        is YandexAdsAdapter -> coverage.yandex
        is HuaweiAdsAdapter -> coverage.huawei
        else -> false
    })
