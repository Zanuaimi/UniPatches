package unipatches.ads

/** SDK-family coverage switches shared by static and runtime ordinary ad blocking. */
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
