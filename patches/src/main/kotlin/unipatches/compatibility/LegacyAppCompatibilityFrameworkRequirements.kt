package unipatches.compatibility

import org.w3c.dom.Document
import org.w3c.dom.Element

internal data class ManifestFrameworkRequirement(val feature: String, val minimumApi: Int)

internal fun declaredInputFrameworkApis(document: Document): Map<String, Int> {
    val root = document.documentElement ?: return emptyMap()
    val versions = linkedMapOf<String, Int>()
    root.getAttribute("platformBuildVersionCode").toIntOrNull()?.let { versions["platformBuildVersionCode"] = it }
    root.androidAttribute("compileSdkVersion").toIntOrNull()?.let { versions["compileSdkVersion"] = it }
    val sdkElements = (0 until root.childNodes.length)
        .mapNotNull { root.childNodes.item(it) as? Element }
        .filter { (it.localName ?: it.tagName.substringAfter(':')) == "uses-sdk" }
    require(sdkElements.size <= 1) {
        "Legacy compatibility: manifest contains multiple <uses-sdk> elements; cannot determine the resource framework safely."
    }
    sdkElements.singleOrNull()?.let { sdk ->
        sdk.androidAttribute("targetSdkVersion").toIntOrNull()?.let { versions["targetSdkVersion"] = it }
        sdk.androidAttribute("compileSdkVersion").toIntOrNull()?.let { versions["compileSdkVersion"] = it }
    }
    return versions
}

internal fun inputManifestFrameworkFailure(document: Document, requirements: List<ManifestFrameworkRequirement>): String? {
    if (requirements.isEmpty()) return null
    require(requirements.all { it.minimumApi in 1..100 }) {
        "Legacy compatibility: invalid Android framework requirement."
    }
    val versions = declaredInputFrameworkApis(document)
    val buildFrameworkApis = listOfNotNull(versions["platformBuildVersionCode"], versions["compileSdkVersion"])
    val frameworkApi = (buildFrameworkApis.ifEmpty { listOfNotNull(versions["targetSdkVersion"]) }).minOrNull()
    val unsupported = requirements.filter { frameworkApi == null || frameworkApi < it.minimumApi }
    if (unsupported.isEmpty()) return null
    val declared = if (versions.isEmpty()) "no numeric framework API is declared" else
        "input resource-framework markers ${versions.entries.joinToString { "${it.key}=${it.value}" }} select as low as $frameworkApi"
    return "Legacy compatibility: ${unsupported.joinToString { "${safeFrameworkFeature(it.feature)} requires API ${it.minimumApi}" }}; $declared. Stock Patcher uses the input framework and cannot safely encode newer manifest attributes; disable these options for this APK."
}

internal fun validateInputManifestFramework(document: Document, requirements: List<ManifestFrameworkRequirement>) {
    inputManifestFrameworkFailure(document, requirements)?.let { throw IllegalArgumentException(it) }
}

private fun safeFrameworkFeature(value: String): String =
    value.filterNot { Character.isISOControl(it) }.take(80)
