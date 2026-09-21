package helpers.manager

import java.nio.charset.StandardCharsets
import java.util.Base64
import app.morphe.patcher.patch.resourcePatch
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val UNI_MANAGER_METADATA_NAME = "com.zanuaimi.unimanager.REGISTRATION"
private const val NS_ANDROID = "http://schemas.android.com/apk/res/android"

internal fun encodeUniManagerMetadata(json: String): String =
    Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

internal fun addUniManagerMetadata(document: Document, encoded: String) {
    val root = document.documentElement ?: return
    val application = root.getElementsByTagName("application").item(0) as? Element ?: return
    val metadata = application.getElementsByTagName("meta-data")
    for (index in 0 until metadata.length) {
        val entry = metadata.item(index) as? Element ?: continue
        if (entry.getAttributeNS(NS_ANDROID, "name") == UNI_MANAGER_METADATA_NAME) {
            val existing = entry.getAttributeNS(NS_ANDROID, "value")
            entry.setAttributeNS(NS_ANDROID, "android:value", mergeMetadata(existing, encoded))
            return
        }
    }
    val entry = document.createElement("meta-data")
    entry.setAttributeNS(NS_ANDROID, "android:name", UNI_MANAGER_METADATA_NAME)
    entry.setAttributeNS(NS_ANDROID, "android:value", encoded)
    application.appendChild(entry)
}

private fun mergeMetadata(existing: String, incoming: String): String {
    if (existing.isBlank()) return incoming
    return runCatching {
        val current = JsonParser.parseString(String(Base64.getDecoder().decode(existing), StandardCharsets.UTF_8)).asJsonObject
        val next = JsonParser.parseString(String(Base64.getDecoder().decode(incoming), StandardCharsets.UTF_8)).asJsonObject
        val patches = current.getAsJsonArray("patches") ?: JsonArray().also { current.add("patches", it) }
        next.getAsJsonArray("patches")?.forEach { candidate ->
            val id = candidate.asJsonObject.get("id")?.asString
            if (id != null) {
                val existingIndex = patches.indexOfFirst { it.asJsonObject.get("id")?.asString == id }
                if (existingIndex >= 0) patches.set(existingIndex, candidate) else patches.add(candidate)
            }
        }
        val capabilities = current.getAsJsonArray("capabilities") ?: JsonArray().also { current.add("capabilities", it) }
        next.getAsJsonArray("capabilities")?.forEach { candidate ->
            if (capabilities.none { it.asString == candidate.asString }) capabilities.add(candidate)
        }
        val configuration = current.getAsJsonObject("configuration") ?: JsonObject().also { current.add("configuration", it) }
        next.getAsJsonObject("configuration")?.entrySet()?.forEach { (key, value) ->
            if (!configuration.has(key)) configuration.add(key, value)
        }
        encodeUniManagerMetadata(current.toString())
    }.getOrDefault(incoming)
}

internal fun uniManagerMetadataPatch(provider: () -> String?) = resourcePatch(
    name = null,
    // This is an internal dependency of patches that opt into UniManager.
    // The provider remains nullable, so it is a no-op when integration is disabled.
    default = true,
) {
    execute {
        provider()?.let { encoded ->
            document("AndroidManifest.xml").use { manifest ->
                addUniManagerMetadata(manifest, encoded)
            }
        }
    }
}
