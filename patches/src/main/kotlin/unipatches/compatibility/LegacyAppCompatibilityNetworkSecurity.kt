package unipatches.compatibility

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.net.IDN
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

private const val NETWORK_SECURITY_CONFIG = "network-security-config"
internal const val CLEAR_TEXT_NETWORK_SECURITY_RESOURCE = "legacy_compat_network_security_config"

internal fun validateCleartextModes(globalEnabled: Boolean, domainScopedEnabled: Boolean) {
    require(!(globalEnabled && domainScopedEnabled)) {
        "Legacy compatibility: choose either global cleartext access or selected-domain cleartext, not both."
    }
}

internal fun normalizeCleartextDomains(entries: List<String>): List<String> {
    val domains = linkedSetOf<String>()
    entries.forEach { entry ->
        val host = entry.trim()
        if (host.isEmpty()) return@forEach
        val safeHost = host.filterNot { Character.isISOControl(it) }.take(100)
        require(host.length <= 253) {
            "Legacy compatibility: invalid cleartext hostname '$safeHost'; hostname is too long."
        }
        require(!host.endsWith('.')) {
            "Legacy compatibility: invalid cleartext hostname '$safeHost'; trailing dots are not supported."
        }
        val ascii = try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        } catch (_: IllegalArgumentException) {
            ""
        }
        val validLabels = ascii.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() && label.all { it.isLetterOrDigit() || it == '-' }
        }
        require(ascii.length in 1..253 && validLabels && ascii.split('.').any { label -> label.any(Char::isLetter) }) {
            "Legacy compatibility: invalid cleartext hostname '$safeHost'; enter hostnames only, without URLs, paths, ports, wildcards, or IP addresses."
        }
        domains += ascii.lowercase(Locale.ROOT)
    }
    return domains.toList()
}

internal fun newCleartextNetworkSecurityConfig(domains: List<String>): Document {
    val document = newXmlDocument()
    val root = document.createElement(NETWORK_SECURITY_CONFIG)
    document.appendChild(root)
    root.appendChild(document.createElement("base-config").apply {
        setAttribute("cleartextTrafficPermitted", "false")
    })
    appendCleartextDomains(document, root, domains)
    return document
}

/** Merges selected hosts without changing existing trust anchors, pins, or unrelated rules. */
internal fun mergeCleartextDomains(document: Document, domains: List<String>): Int {
    val root = document.documentElement
    require(root != null && root.localNameOrTag() == NETWORK_SECURITY_CONFIG && root.namespaceURI.isNullOrEmpty()) {
        "Legacy compatibility: selected-domain cleartext requires a valid <network-security-config> root; refusing to replace existing security settings."
    }
    val rootChildren = root.childElements()
    val rootNames = rootChildren.map(Element::localNameOrTag)
    require(rootNames.all { it in setOf("base-config", "domain-config", "debug-overrides") }) {
        "Legacy compatibility: existing network security config contains an unsupported top-level element; cannot merge safely."
    }
    val baseConfigs = rootChildren.filter { it.localNameOrTag() == "base-config" }
    val debugConfigs = rootChildren.filter { it.localNameOrTag() == "debug-overrides" }
    require(baseConfigs.size <= 1 && debugConfigs.size <= 1) {
        "Legacy compatibility: existing network security config has duplicate base-config or debug-overrides elements; cannot merge safely."
    }
    val baseIndex = rootChildren.indexOfFirst { it.localNameOrTag() == "base-config" }
    val debugIndex = rootChildren.indexOfFirst { it.localNameOrTag() == "debug-overrides" }
    require((baseIndex == -1 || baseIndex == 0) && (debugIndex == -1 || debugIndex == rootChildren.lastIndex)) {
        "Legacy compatibility: existing network security config elements are out of order; cannot merge safely."
    }
    val base = baseConfigs.singleOrNull()
    val domainConfigs = root.getElementsByTagName("domain-config")
    (0 until domainConfigs.length).forEach { index ->
        val config = domainConfigs.item(index) as Element
        require(!config.hasAttribute("cleartextTrafficPermitted") || config.getAttribute("cleartextTrafficPermitted") in setOf("true", "false")) {
            "Legacy compatibility: existing domain-config has invalid cleartextTrafficPermitted value."
        }
    }
    val existingDomains = root.getElementsByTagName("domain")
    (0 until existingDomains.length).forEach { index ->
        val domain = existingDomains.item(index) as Element
        require(!domain.hasAttribute("includeSubdomains") || domain.getAttribute("includeSubdomains") in setOf("true", "false")) {
            "Legacy compatibility: existing domain has invalid includeSubdomains value."
        }
    }
    require(base?.getAttribute("cleartextTrafficPermitted") != "true") {
        "Legacy compatibility: existing network security config globally permits cleartext. Remove that broad rule before enabling selected-domain mode."
    }
    val baseChanged = when {
        base == null -> {
            val newBase = document.createElement("base-config").apply { setAttribute("cleartextTrafficPermitted", "false") }
            val firstDomainOrDebug = root.childElements().firstOrNull { it.localNameOrTag() == "domain-config" || it.localNameOrTag() == "debug-overrides" }
            root.insertBefore(newBase, firstDomainOrDebug)
            true
        }
        !base.hasAttribute("cleartextTrafficPermitted") -> {
            base.setAttribute("cleartextTrafficPermitted", "false")
            true
        }
        else -> false
    }
    require(base?.getAttribute("cleartextTrafficPermitted") == null || base.getAttribute("cleartextTrafficPermitted") in setOf("true", "false")) {
        "Legacy compatibility: existing base-config has invalid cleartextTrafficPermitted value."
    }

    val additions = domains.filterNot { host -> isAlreadyCleartextAllowed(root, host) }
    appendCleartextDomains(document, root, additions)
    return additions.size + if (baseChanged) 1 else 0
}

internal fun networkSecurityConfigPathsForResource(name: String, entries: List<String>): List<String> {
    require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) { "Legacy compatibility: invalid network security resource name." }
    return entries.filter { path -> Regex("res/xml(?:-[^/]+)?/${Regex.escape(name)}\\.xml").matches(path) }.sorted()
}

internal fun resolveNetworkSecurityConfigPaths(reference: String, entries: List<String>): List<String> {
    val safeReference = reference.filterNot { Character.isISOControl(it) }.take(160)
    val match = Regex("@(?:[A-Za-z0-9_.]+:)?xml/([A-Za-z0-9_]+)").matchEntire(reference)
        ?: error("Legacy compatibility: cannot resolve existing networkSecurityConfig '$safeReference'; use a local @xml/name resource or remove the reference before enabling selected-domain cleartext.")
    val name = match.groupValues[1]
    val paths = networkSecurityConfigPathsForResource(name, entries)
    require(paths.isNotEmpty()) {
        "Legacy compatibility: networkSecurityConfig '$safeReference' has no matching res/xml/$name.xml resource; refusing to replace existing security settings."
    }
    return paths
}

internal fun isCleartextDomainConfig(document: Document): Boolean =
    document.documentElement?.localNameOrTag() == NETWORK_SECURITY_CONFIG

internal fun readSecureXml(file: File): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    return factory.newDocumentBuilder().parse(file)
}

internal fun writeSecureXml(document: Document, file: File) {
    check(file.parentFile?.let { it.isDirectory || it.mkdirs() } != false) { "Cannot create directory for network security config $file" }
    val factory = TransformerFactory.newInstance().apply {
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
    }
    factory.newTransformer().transform(DOMSource(document), StreamResult(file))
}

internal fun ensurePublicXmlResourceId(
    file: File,
    type: String,
    name: String,
    resourceAlreadyExists: Boolean = false,
): Boolean {
    require(file.isFile) { "Legacy compatibility: cannot add @${type}/$name because res/values/public.xml is missing; refusing to guess a resource ID." }
    val document = readSecureXml(file)
    val root = document.documentElement
    require(root?.localNameOrTag() == "resources") { "Legacy compatibility: res/values/public.xml has no <resources> root." }
    val publicEntries = root.childElements().filter { it.localNameOrTag() == "public" }

    fun parseId(entry: Element): Long {
        val raw = entry.getAttribute("id")
        require(raw.startsWith("0x")) { "Legacy compatibility: malformed public resource id '$raw'." }
        return raw.substring(2).toLongOrNull(16)
            ?: error("Legacy compatibility: malformed public resource id '$raw'.")
    }
    val packageId = root.getAttribute("id").removePrefix("0x").toLongOrNull(16)
        ?: error("Legacy compatibility: malformed package id in res/values/public.xml.")
    require(packageId in 1L..0xffL) { "Legacy compatibility: invalid package id in res/values/public.xml." }
    val parsedEntries = publicEntries.map { it to parseId(it) }
    require(parsedEntries.all { (_, id) -> id <= 0xffffffffL && (id ushr 24) == packageId }) {
        "Legacy compatibility: public.xml contains a resource ID outside its declared package; refusing to allocate a new ID."
    }
    require(parsedEntries.map { it.second }.distinct().size == parsedEntries.size) {
        "Legacy compatibility: public.xml contains duplicate resource IDs; refusing to allocate a new ID."
    }
    require(publicEntries.map { it.getAttribute("type") to it.getAttribute("name") }.distinct().size == publicEntries.size) {
        "Legacy compatibility: public.xml contains duplicate resource names; refusing to allocate a new ID."
    }
    val typeIds = parsedEntries.groupBy { it.first.getAttribute("type") }
        .mapValues { (_, entries) -> entries.map { ((it.second ushr 16) and 0xff).toInt() }.distinct() }
    require(typeIds.values.all { it.size == 1 } && typeIds.values.flatten().distinct().size == typeIds.size) {
        "Legacy compatibility: public.xml has inconsistent or duplicate resource type IDs; refusing to allocate a new ID."
    }
    val matchingEntries = parsedEntries.filter { (entry, _) -> entry.getAttribute("type") == type && entry.getAttribute("name") == name }
    require(matchingEntries.size <= 1) { "Legacy compatibility: duplicate public resource entries for @${type}/$name." }
    if (matchingEntries.isNotEmpty()) return false
    require(!resourceAlreadyExists) {
        "Legacy compatibility: @${type}/$name already exists in the APK but is absent from public.xml; refusing to guess its resource ID."
    }
    val typeEntries = parsedEntries.filter { it.first.getAttribute("type") == type }
    val nextId = if (typeEntries.isNotEmpty()) {
        val current = typeEntries.maxOf { it.second }
        val low = current and 0xffff
        require(low < 0xffff) { "Legacy compatibility: no public resource IDs remain for type $type." }
        (current and 0xffff0000L) or (low + 1)
    } else {
        val maxTypeId = parsedEntries.maxOfOrNull { ((it.second shr 16) and 0xff).toInt() } ?: 0
        require(maxTypeId < 0xff) { "Legacy compatibility: no Android resource type IDs remain for $type." }
        (packageId shl 24) or ((maxTypeId + 1L) shl 16)
    }
    root.appendChild(document.createElement("public").apply {
        setAttribute("id", "0x" + nextId.toString(16))
        setAttribute("type", type)
        setAttribute("name", name)
    })
    writeSecureXml(document, file)
    return true
}

internal fun hasExistingCleartextDomainRule(document: Document): Boolean =
    document.documentElement?.getElementsByTagName("domain-config")?.let { configs ->
        (0 until configs.length).any { index ->
            (configs.item(index) as? Element)?.getAttribute("cleartextTrafficPermitted") == "true"
        }
    } ?: false

private fun appendCleartextDomains(document: Document, root: Element, domains: List<String>) {
    domains.forEach { host ->
        val config = document.createElement("domain-config").apply {
            setAttribute("cleartextTrafficPermitted", "true")
            appendChild(document.createElement("domain").apply {
                setAttribute("includeSubdomains", "false")
                textContent = host
            })
        }
        val debug = root.childElements().firstOrNull { it.localNameOrTag() == "debug-overrides" }
        root.insertBefore(config, debug)
    }
}

private fun isAlreadyCleartextAllowed(root: Element, host: String): Boolean {
    val domains = root.getElementsByTagName("domain")
    val matches = (0 until domains.length).mapNotNull { index ->
        val domain = domains.item(index) as? Element ?: return@mapNotNull null
        val existing = normalizeCleartextDomains(listOf(domain.textContent)).singleOrNull()
            ?: error("Legacy compatibility: existing network security config contains an empty or invalid domain entry; cannot merge selected cleartext safely.")
        val included = domain.getAttribute("includeSubdomains") == "true"
        if (host != existing && !(included && host.endsWith(".$existing"))) return@mapNotNull null
        val config = generateSequence(domain.parentNode) { it.parentNode }
            .filterIsInstance<Element>()
            .firstOrNull { it.localNameOrTag() == "domain-config" }
        val inheritedCleartext = generateSequence(config) { it.parentNode as? Element }
            .filter { it.localNameOrTag() == "domain-config" || it.localNameOrTag() == "base-config" }
            .firstOrNull { it.hasAttribute("cleartextTrafficPermitted") }
            ?.getAttribute("cleartextTrafficPermitted") == "true"
        Triple(existing, existing.count { it == '.' }, inheritedCleartext)
    }
    if (matches.any { (existing, _, allowed) -> host == existing && !allowed }) {
        error("Legacy compatibility: existing domain-config restricts cleartext for '$host'; resolve that rule before enabling the selected-domain option.")
    }
    val mostSpecificDepth = matches.maxOfOrNull { it.second } ?: return false
    val mostSpecific = matches.filter { it.second == mostSpecificDepth }.map { it.third }.distinct()
    require(mostSpecific.size <= 1) {
        "Legacy compatibility: existing network security config has conflicting rules for '$host'; cannot merge selected-domain cleartext safely."
    }
    return mostSpecific.singleOrNull() == true
}

private fun newXmlDocument(): Document =
    javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()

private fun Element.localNameOrTag(): String = localName ?: tagName.substringAfter(':')

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
