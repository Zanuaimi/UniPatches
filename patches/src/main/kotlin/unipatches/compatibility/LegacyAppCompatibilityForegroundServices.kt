package unipatches.compatibility

import helpers.manifest.NS_ANDROID
import org.w3c.dom.Document
import org.w3c.dom.Element

internal data class ForegroundServiceTypeMapping(val component: String, val types: List<String>)

private val foregroundServiceTypePermissions = mapOf(
    "dataSync" to "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "mediaPlayback" to "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "phoneCall" to "android.permission.FOREGROUND_SERVICE_PHONE_CALL",
    "location" to "android.permission.FOREGROUND_SERVICE_LOCATION",
    "connectedDevice" to "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "mediaProjection" to "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "camera" to "android.permission.FOREGROUND_SERVICE_CAMERA",
    "microphone" to "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "health" to "android.permission.FOREGROUND_SERVICE_HEALTH",
    "remoteMessaging" to "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING",
    "specialUse" to "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "systemExempted" to "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
    "mediaProcessing" to "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
)
private val supportedForegroundServiceTypes = foregroundServiceTypePermissions.keys + "shortService"
private val foregroundServiceTypeMinimumApi = mapOf(
    "camera" to 30,
    "microphone" to 30,
    "health" to 34,
    "shortService" to 34,
    "specialUse" to 34,
    "systemExempted" to 34,
    "remoteMessaging" to 35,
    "mediaProcessing" to 35,
)
private val javaComponentName = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*""")

internal fun minimumForegroundServiceFrameworkApi(mappings: List<ForegroundServiceTypeMapping>): Int? =
    mappings.flatMap { it.types }.maxOfOrNull { foregroundServiceTypeMinimumApi[it] ?: 28 }

internal fun validateForegroundServiceMappingSelection(enabled: Boolean, mappings: List<ForegroundServiceTypeMapping>) {
    require(!enabled || mappings.isNotEmpty()) {
        "Legacy compatibility: FGS type repair is enabled but no service/type mappings were supplied."
    }
}

internal fun normalizeForegroundServiceTypeMappings(
    entries: List<String>,
    manifestPackage: String,
    declaredPermissions: Set<String> = emptySet(),
): List<ForegroundServiceTypeMapping> {
    val seen = mutableSetOf<String>()
    fun safe(value: String) = value.filterNot { Character.isISOControl(it) }.take(100)

    return entries.mapNotNull { raw ->
        val entry = raw.trim()
        if (entry.isEmpty()) return@mapNotNull null
        require(entry.length <= 512) {
            "Legacy compatibility: FGS mapping '${safe(entry)}' is too long; use component=type with a short explicit service name."
        }
        val separator = entry.indexOf('=')
        require(separator > 0 && separator == entry.lastIndexOf('=')) {
            "Legacy compatibility: invalid FGS mapping '${safe(entry)}'; use component=type, for example com.example.SyncService=dataSync."
        }
        val rawComponent = entry.substring(0, separator).trim()
        val needsPackage = rawComponent.startsWith('.') || '.' !in rawComponent
        require(!needsPackage || manifestPackage.isNotBlank()) {
            "Legacy compatibility: manifest package is missing; cannot resolve service '${safe(rawComponent)}'."
        }
        val component = when {
            rawComponent.startsWith('.') -> manifestPackage + rawComponent
            '.' !in rawComponent -> "$manifestPackage.$rawComponent"
            else -> rawComponent
        }
        require(javaComponentName.matches(component)) {
            "Legacy compatibility: invalid service component '${safe(rawComponent)}' in FGS mapping."
        }
        require(seen.add(component)) {
            "Legacy compatibility: duplicate FGS mapping for '${safe(component)}'; combine its types into one entry."
        }
        val rawTypes = entry.substring(separator + 1).split('|').map(String::trim)
        val types = rawTypes.distinct()
        require(types.isNotEmpty() && rawTypes.none(String::isEmpty) && types.all(supportedForegroundServiceTypes::contains)) {
            val unsupported = types.filterNot(supportedForegroundServiceTypes::contains)
            "Legacy compatibility: invalid or unsupported FGS type(s) ${unsupported.map(::safe).joinToString().ifEmpty { "<empty>" }} for '${safe(component)}'. Supported: ${supportedForegroundServiceTypes.filterNot { it == "specialUse" || it == "systemExempted" }.sorted().joinToString()}"
        }
        require("specialUse" !in types && "systemExempted" !in types) {
            "Legacy compatibility: specialUse and systemExempted require app-specific eligibility/configuration that cannot be verified from a service-to-type mapping."
        }
        if ("phoneCall" in types) {
            require("android.permission.MANAGE_OWN_CALLS" in declaredPermissions) {
                "Legacy compatibility: phoneCall FGS type requires a predeclared MANAGE_OWN_CALLS permission; default-dialer role is not inferred."
            }
        }
        ForegroundServiceTypeMapping(component, types)
    }
}

internal fun applyForegroundServiceTypeMappings(
    document: Document,
    mappings: List<ForegroundServiceTypeMapping>,
): Int {
    val root = document.documentElement ?: error("Legacy compatibility: cannot set FGS types; manifest root is missing.")
    val packageName = root.getAttribute("package")
    val services = applicationServices(root)
    var changes = 0

    mappings.forEach { mapping ->
        val matches = services.filter { normalizedComponentName(it.androidAttribute("name"), packageName) == mapping.component }
        require(matches.size == 1) {
            if (matches.isEmpty()) {
                "Legacy compatibility: FGS mapping '${mapping.component}' does not match a manifest service; refusing to add a guessed component."
            } else {
                "Legacy compatibility: FGS mapping '${mapping.component}' matches ${matches.size} manifest services; resolve duplicate declarations first."
            }
        }
        val service = matches.single()
        val existing = service.androidAttribute("foregroundServiceType").trim()
        val existingTypes = if (existing.isEmpty()) emptyList() else existing.split('|').map(String::trim)
        require(existingTypes.all(supportedForegroundServiceTypes::contains)) {
            "Legacy compatibility: service '${mapping.component}' has an unrecognized existing foregroundServiceType '$existing'; refusing to overwrite it."
        }
        val mergedTypes = (existingTypes + mapping.types).distinct()
        if (mergedTypes != existingTypes) {
            service.setAttributeNS(NS_ANDROID, "android:foregroundServiceType", mergedTypes.joinToString("|"))
            changes++
        }
    }

    if (mappings.any { "phoneCall" in it.types }) {
        val ownCalls = manifestPermissionDeclarations(root, "android.permission.MANAGE_OWN_CALLS")
        require(ownCalls.any { it.manifestElementName() == "uses-permission" && !it.hasAndroidAttribute("maxSdkVersion") }) {
            "Legacy compatibility: phoneCall FGS type requires an unbounded MANAGE_OWN_CALLS declaration."
        }
    }
    val requiredPermissions = mappings.flatMap { it.types }
        .mapNotNull(foregroundServiceTypePermissions::get)
        .toSet() + "android.permission.FOREGROUND_SERVICE"
    requiredPermissions.forEach { permission ->
        val declarations = manifestPermissionDeclarations(root, permission)
        require(declarations.none { it.manifestElementName() != "uses-permission" || it.hasAndroidAttribute("maxSdkVersion") }) {
            "Legacy compatibility: required permission '$permission' has a version/maxSdk-limited declaration; make it an unbounded uses-permission before enabling FGS mappings."
        }
        if (declarations.isEmpty()) {
            val namespace = root.namespaceURI
            val qualifiedName = root.prefix?.takeIf(String::isNotEmpty)?.let { "$it:uses-permission" } ?: "uses-permission"
            val permissionElement = if (namespace.isNullOrEmpty()) document.createElement(qualifiedName) else document.createElementNS(namespace, qualifiedName)
            permissionElement.setAttributeNS(NS_ANDROID, "android:name", permission)
            val application = (0 until root.childNodes.length)
                .mapNotNull { root.childNodes.item(it) as? Element }
                .firstOrNull { it.manifestElementName() == "application" }
            if (application == null) root.appendChild(permissionElement) else root.insertBefore(permissionElement, application)
            changes++
        }
    }
    return changes
}

internal fun verifyForegroundServiceTypeMappings(
    document: Document,
    mappings: List<ForegroundServiceTypeMapping>,
) {
    val root = document.documentElement ?: error("Legacy compatibility: FGS manifest readback has no root.")
    val services = applicationServices(root)
    val packageName = root.getAttribute("package")
    mappings.forEach { mapping ->
        val matches = services.filter { normalizedComponentName(it.androidAttribute("name"), packageName) == mapping.component }
        check(matches.size == 1 && mapping.types.all { it in matches.single().androidAttribute("foregroundServiceType").split('|') }) {
            "Legacy compatibility: final manifest readback failed for FGS mapping '${mapping.component}=${mapping.types.joinToString("|")}'."
        }
    }
    val requiredPermissions = mappings.flatMap { it.types }
        .mapNotNull(foregroundServiceTypePermissions::get)
        .toSet() + "android.permission.FOREGROUND_SERVICE"
    requiredPermissions.forEach { permission ->
        check(manifestPermissionDeclarations(root, permission).any { it.manifestElementName() == "uses-permission" && !it.hasAndroidAttribute("maxSdkVersion") }) {
            "Legacy compatibility: final manifest readback is missing unbounded permission '$permission' for FGS mappings."
        }
    }
}

internal fun declaredManifestPermissionNames(document: Document): Set<String> =
    document.documentElement?.let { root ->
        manifestDescendants(root).filter {
            it.manifestElementName() in setOf("uses-permission", "uses-permission-sdk-23")
        }.mapNotNull { it.androidAttribute("name").takeIf(String::isNotBlank) }.toSet()
    }.orEmpty()

private fun applicationServices(root: Element): List<Element> {
    val application = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
        .firstOrNull { it.manifestElementName() == "application" }
        ?: error("Legacy compatibility: cannot set FGS types; manifest application is missing.")
    return (0 until application.childNodes.length).mapNotNull { application.childNodes.item(it) as? Element }
        .filter { it.manifestElementName() == "service" }
}

private fun normalizedComponentName(name: String, packageName: String): String = when {
    name.startsWith('.') -> packageName + name
    '.' !in name -> "$packageName.$name"
    else -> name
}

private fun manifestPermissionDeclarations(root: Element, name: String): List<Element> =
    manifestDescendants(root).filter {
        it.manifestElementName() in setOf("uses-permission", "uses-permission-sdk-23") && it.androidAttribute("name") == name
    }

private fun manifestDescendants(root: Element): List<Element> {
    val nodes = root.getElementsByTagName("*")
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

private fun Element.manifestElementName(): String = localName ?: tagName.substringAfter(':')
