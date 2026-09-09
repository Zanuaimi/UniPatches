package unipatches.overlay

import java.util.Base64

/**
 * Shared serializer for the runtime overlay payload.
 *
 * The version field and the common fields through iconBackgroundColor4 form the shared payload.
 * Existing variant metadata is followed by optional trailing fields so older payloads remain
 * readable by OverlayConfig.
 * App-specific patch entries should build the common fields with their own defaults and use this
 * serializer instead of copying the wire format into another patch file.
 */
internal object OverlayConfigPayload {
    const val VERSION = "1"
    const val UNIVERSAL_PROFILE = "universal"
    const val UNIVERSAL_INJECTION_MODE = "universal"
    const val EXPLICIT_ACTIVITY_INJECTION_MODE = "explicitActivity"

    private fun encode(value: String): String =
        Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun serialize(
        commonFields: List<String>,
        profileId: String = UNIVERSAL_PROFILE,
        injectionMode: String = UNIVERSAL_INJECTION_MODE,
        trailingFields: List<String> = emptyList(),
    ): String {
        require(commonFields.firstOrNull() == VERSION) {
            "Overlay payload must start with schema version $VERSION"
        }
        require(profileId.isNotBlank() && !profileId.contains('|')) {
            "Overlay profile ID must be non-empty and must not contain '|'."
        }
        require(injectionMode == UNIVERSAL_INJECTION_MODE || injectionMode == EXPLICIT_ACTIVITY_INJECTION_MODE) {
            "Unsupported overlay injection mode: $injectionMode"
        }
        return (commonFields + profileId + injectionMode + trailingFields).joinToString("|") { encode(it) }
    }
}
