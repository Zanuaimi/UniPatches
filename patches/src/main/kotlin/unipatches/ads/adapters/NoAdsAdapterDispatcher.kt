package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import java.util.logging.Logger

/** Dispatches resolved No Ads work only to SDKs selected by coverage settings. */
internal class NoAdsAdapterDispatcher(
    private val context: BytecodePatchContext,
    private val logger: Logger,
) {
    private val unity = UnityNoAdsAdapter(context, logger)
    private val adMob = AdMobNoAdsAdapter(context, logger)
    private val ironSource = IronSourceNoAdsAdapter(context, logger)
    fun apply(plan: NoAdsSdkPlan, coverage: AdsSdkCoverage): Int {
        var patched = 0
        fun applySelected(adapter: NoAdsSdkAdapter, enabled: Boolean): Int {
            if (!enabled) return 0
            return when (plan.mode) {
                AdsPatchMode.STATIC -> adapter.applyStatic(plan).patched
                AdsPatchMode.RUNTIME -> adapter.applyRuntime(plan).patched
                AdsPatchMode.DISABLED -> 0
            }
        }
        patched += applySelected(unity, coverage.unity)
        patched += applySelected(adMob, coverage.adMob)
        patched += applySelected(ironSource, coverage.ironSource)
        patched += applySelected(OtherNoAdsAdapter(context, logger, coverage), true)
        return patched
    }
}
