package unipatches.compatibility

import app.morphe.patcher.patch.bytecodePatch
import helpers.spoof.foldStringGetterConst
import java.util.logging.Logger

/** Bytecode compatibility for legacy device-identity APIs. */
private val validImei = Regex("^[0-9]{15}$")

internal fun legacyImeiPatch(imeiProvider: () -> Pair<Boolean, String>) = bytecodePatch(
    name = null,
    description = "Internal legacy device compatibility phase.",
    default = false,
) {
    execute {
        val (enabled, imei) = imeiProvider()
        if (!enabled) return@execute
        val logger = Logger.getLogger(this::class.java.name)
        if (!validImei.matches(imei)) {
            logger.warning("Legacy compatibility: IMEI must contain exactly 15 digits. No IMEI changes applied.")
            return@execute
        }
        val patched = foldStringGetterConst(
            definingClass = "Landroid/telephony/TelephonyManager;",
            methodNames = setOf("getDeviceId", "getImei"),
            value = imei,
        )
        if (patched == 0) {
            logger.warning("Legacy compatibility: no recognized TelephonyManager IMEI calls found.")
        } else {
            logger.info("Legacy compatibility: spoofed IMEI at $patched call site(s).")
        }
    }
}

