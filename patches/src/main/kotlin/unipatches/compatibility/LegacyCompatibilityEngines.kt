package unipatches.compatibility

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import java.util.logging.Logger

internal data class LegacyDetectedEngines(var unity: Boolean = false, var openIab: Boolean = false)

/** Exact engine descriptors, not app names, assets, or fuzzy package matches. */
internal fun detectLegacyEngines(types: Set<String>): LegacyDetectedEngines = LegacyDetectedEngines(
    unity = "Lcom/unity3d/player/UnityPlayer;" in types && "Lcom/unity3d/player/UnityPlayerActivity;" in types,
    openIab = "Lorg/onepf/oms/OpenIabHelper;" in types,
)

internal fun BytecodePatchContext.detectLegacyEngines(): LegacyDetectedEngines {
    val types = mutableSetOf<String>()
    classDefForEach { types.add(it.type) }
    return detectLegacyEngines(types)
}

internal fun legacyEngineDetectionPatch(result: LegacyDetectedEngines) = bytecodePatch(
    name = null, description = "Internal exact legacy engine detection.", default = false,
) {
    execute {
        val detected = detectLegacyEngines()
        result.unity = detected.unity
        result.openIab = detected.openIab
        Logger.getLogger(this::class.java.name).info("Legacy compatibility: exact Unity fingerprint=${result.unity}, exact OpenIAB fingerprint=${result.openIab}; unmatched engine-specific mutations are skipped.")
    }
}
