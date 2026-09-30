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
internal const val UNI_MANAGER_ADS_METADATA_NAME = "com.zanuaimi.unimanager.REGISTRATION.ADS_BLOCK"
internal const val UNI_MANAGER_PACKAGE = "com.zanuaimi.unimanager"
internal const val UNI_MANAGER_BRIDGE_PERMISSION = "com.zanuaimi.unimanager.permission.BRIDGE"
private const val NS_ANDROID = "http://schemas.android.com/apk/res/android"

internal fun encodeUniManagerMetadata(json: String): String =
    Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

internal fun addUniManagerMetadata(
    document: Document,
    encoded: String,
    metadataName: String = UNI_MANAGER_METADATA_NAME,
) {
    val root = document.documentElement ?: return
    val application = root.getElementsByTagName("application").item(0) as? Element ?: return
    val metadata = application.getElementsByTagName("meta-data")
    for (index in 0 until metadata.length) {
        val entry = metadata.item(index) as? Element ?: continue
        if (entry.getAttributeNS(NS_ANDROID, "name") == metadataName) {
            val existing = entry.getAttributeNS(NS_ANDROID, "value")
            entry.setAttributeNS(NS_ANDROID, "android:value", mergeMetadata(existing, encoded))
            return
        }
    }
    val entry = document.createElement("meta-data")
    entry.setAttributeNS(NS_ANDROID, "android:name", metadataName)
    entry.setAttributeNS(NS_ANDROID, "android:value", encoded)
    application.appendChild(entry)
}

/** Adds the declarations required for a patched APK to call UniManager's Binder bridge. */
internal fun addUniManagerBridgeAccess(document: Document) {
    val root = document.documentElement ?: return
    val permissions = root.getElementsByTagName("uses-permission")
    val permissionPresent = (0 until permissions.length).any { index ->
        (permissions.item(index) as? Element)?.getAttributeNS(NS_ANDROID, "name") == UNI_MANAGER_BRIDGE_PERMISSION
    }
    if (!permissionPresent) {
        val permission = document.createElement("uses-permission")
        permission.setAttributeNS(NS_ANDROID, "android:name", UNI_MANAGER_BRIDGE_PERMISSION)
        val application = root.getElementsByTagName("application").item(0)
        if (application != null) root.insertBefore(permission, application) else root.appendChild(permission)
    }

    val queries = (0 until root.getElementsByTagName("queries").length)
        .asSequence()
        .mapNotNull { root.getElementsByTagName("queries").item(it) as? Element }
        .firstOrNull()
        ?: document.createElement("queries").also {
            val application = root.getElementsByTagName("application").item(0)
            if (application != null) root.insertBefore(it, application) else root.appendChild(it)
        }
    val packages = queries.getElementsByTagName("package")
    val packagePresent = (0 until packages.length).any { index ->
        (packages.item(index) as? Element)?.getAttributeNS(NS_ANDROID, "name") == UNI_MANAGER_PACKAGE
    }
    if (!packagePresent) {
        val packageElement = document.createElement("package")
        packageElement.setAttributeNS(NS_ANDROID, "android:name", UNI_MANAGER_PACKAGE)
        queries.appendChild(packageElement)
    }
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
        val schema = current.getAsJsonArray("configuration_schema") ?: JsonArray().also { current.add("configuration_schema", it) }
        next.getAsJsonArray("configuration_schema")?.forEach { candidate ->
            if (!candidate.isJsonObject) return@forEach
            val candidateKey = candidate.asJsonObject.get("key")?.asString
            if (candidateKey.isNullOrBlank()) return@forEach
            val existingIndex = schema.indexOfFirst { it.isJsonObject && it.asJsonObject.get("key")?.asString == candidateKey }
            if (existingIndex >= 0) schema.set(existingIndex, candidate) else schema.add(candidate)
        }
        encodeUniManagerMetadata(current.toString())
    }.getOrDefault(incoming)
}

internal fun uniManagerMetadataPatch(
    name: String = "UniManager registration metadata (internal)",
    metadataName: String = UNI_MANAGER_METADATA_NAME,
    provider: () -> String?,
) = resourcePatch(
    name = name,
    // This is an internal dependency of patches that opt into UniManager.
    // Parent patches request it explicitly through dependsOn; keeping it disabled
    // by default avoids Morphe treating it as a standalone universal patch.
    default = false,
) {
    execute {
        provider()?.let { encoded ->
            document("AndroidManifest.xml").use { manifest ->
                addUniManagerBridgeAccess(manifest)
                addUniManagerMetadata(manifest, encoded, metadataName)
            }
        }
    }
}
