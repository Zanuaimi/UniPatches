package unipatches.compatibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

class LegacyAppCompatibilityNetworkSecurityTest {
    @Test
    fun cleartextModesRejectConflictingSelections() {
        validateCleartextModes(globalEnabled = false, domainScopedEnabled = false)
        validateCleartextModes(globalEnabled = true, domainScopedEnabled = false)
        validateCleartextModes(globalEnabled = false, domainScopedEnabled = true)
        try {
            validateCleartextModes(globalEnabled = true, domainScopedEnabled = true)
            throw AssertionError("Expected conflicting cleartext modes to fail")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("not both"))
        }
    }

    @Test
    fun cleartextDomainsAreNormalizedAndDeduplicated() {
        assertEquals(listOf("api.example.com", "xn--bcher-kva.example"), normalizeCleartextDomains(
            listOf(" API.Example.com ", "api.example.com", "bücher.example"),
        ))
    }

    @Test
    fun cleartextDomainsRejectUrlsPathsPortsWildcardsAndIpAddresses() {
        listOf("https://example.com", "example.com/path", "example.com:443", "*.example.com", "127.0.0.1", "example.com.", "a".repeat(254)).forEach { input ->
            try {
                normalizeCleartextDomains(listOf(input))
                throw AssertionError("Expected invalid hostname rejection: $input")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("invalid cleartext hostname"))
            }
        }
    }

    @Test
    fun networkSecurityResourceLookupIncludesQualifiedVariants() {
        assertEquals(
            listOf("res/xml-v24/legacy_compat_network_security_config.xml", "res/xml/legacy_compat_network_security_config.xml"),
            networkSecurityConfigPathsForResource(
                "legacy_compat_network_security_config",
                listOf(
                    "res/xml-v24/legacy_compat_network_security_config.xml",
                    "res/xml/legacy_compat_network_security_config.xml",
                    "res/xml-v24/other.xml",
                    "res/xmlx/legacy_compat_network_security_config.xml",
                ),
            ),
        )
    }

    @Test
    fun generatedConfigDisablesGlobalCleartextAndAllowsOnlySelectedHosts() {
        val document = newCleartextNetworkSecurityConfig(listOf("api.example.com"))
        val root = document.documentElement
        assertEquals("network-security-config", root.tagName)
        assertEquals("false", root.getElementsByTagName("base-config").item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
        val domains = root.getElementsByTagName("domain")
        assertEquals(1, domains.length)
        assertEquals("api.example.com", domains.item(0).textContent)
        assertEquals("false", (domains.item(0) as org.w3c.dom.Element).getAttribute("includeSubdomains"))
    }

    @Test
    fun mergePreservesTrustAndPinningRulesAndIsIdempotent() {
        val document = parse("""
            <network-security-config>
                <base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system"/></trust-anchors></base-config>
                <domain-config cleartextTrafficPermitted="false"><domain includeSubdomains="true">example.org</domain><pin-set><pin digest="SHA-256">abc</pin></pin-set></domain-config>
                <debug-overrides><trust-anchors><certificates src="user"/></trust-anchors></debug-overrides>
            </network-security-config>
        """.trimIndent())

        assertEquals(1, mergeCleartextDomains(document, listOf("api.example.com")))
        assertEquals(0, mergeCleartextDomains(document, listOf("api.example.com")))
        assertEquals(1, document.documentElement.getElementsByTagName("pin-set").length)
        assertEquals(2, document.documentElement.getElementsByTagName("trust-anchors").length)
        assertEquals("false", document.documentElement.getElementsByTagName("base-config").item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
        assertEquals("debug-overrides", document.documentElement.lastChild.previousSibling.localName ?: document.documentElement.lastChild.previousSibling.nodeName)
    }

    @Test
    fun mergeRejectsGlobalCleartextAndConflictingExactDomain() {
        val global = parse("<network-security-config><base-config cleartextTrafficPermitted=\"true\"/></network-security-config>")
        assertFails { mergeCleartextDomains(global, listOf("api.example.com")) }

        val restricted = parse("<network-security-config><base-config cleartextTrafficPermitted=\"false\"/><domain-config cleartextTrafficPermitted=\"false\"><domain>api.example.com</domain></domain-config></network-security-config>")
        assertFails { mergeCleartextDomains(restricted, listOf("api.example.com")) }
    }

    @Test
    fun mergeRejectsMalformedExistingPolicyInsteadOfRewritingIt() {
        val unknownRootElement = parse("<network-security-config><unknown /></network-security-config>")
        assertFails { mergeCleartextDomains(unknownRootElement, listOf("api.example.com")) }

        val invalidRule = parse("""
            <network-security-config>
                <base-config cleartextTrafficPermitted="false" />
                <domain-config cleartextTrafficPermitted="maybe"><domain>api.example.com</domain></domain-config>
            </network-security-config>
        """.trimIndent())
        assertFails { mergeCleartextDomains(invalidRule, listOf("api.example.com")) }
    }

    @Test
    fun mergeRespectsInheritedNestedDomainPolicy() {
        val inheritedAllow = parse("""
            <network-security-config>
                <base-config cleartextTrafficPermitted="false" />
                <domain-config cleartextTrafficPermitted="true">
                    <domain includeSubdomains="true">example.com</domain>
                    <domain-config><domain>api.example.com</domain></domain-config>
                </domain-config>
            </network-security-config>
        """.trimIndent())
        assertEquals(0, mergeCleartextDomains(inheritedAllow, listOf("api.example.com")))
        assertEquals(2, inheritedAllow.documentElement.getElementsByTagName("domain-config").length)

        val inheritedDeny = parse("""
            <network-security-config>
                <base-config cleartextTrafficPermitted="false" />
                <domain-config cleartextTrafficPermitted="true">
                    <domain includeSubdomains="true">example.com</domain>
                    <domain-config cleartextTrafficPermitted="false"><domain>api.example.com</domain></domain-config>
                </domain-config>
            </network-security-config>
        """.trimIndent())
        assertFails { mergeCleartextDomains(inheritedDeny, listOf("api.example.com")) }
    }

    @Test
    fun resourceResolverOnlyAcceptsLocalXmlReferencesAndFindsAllQualifiers() {
        val entries = listOf("res/xml/network.xml", "res/xml-v24/network.xml", "res/xml/other.xml", "assets/network.xml")
        assertEquals(listOf("res/xml-v24/network.xml", "res/xml/network.xml"), resolveNetworkSecurityConfigPaths("@xml/network", entries))
        assertFails { resolveNetworkSecurityConfigPaths("@string/network", entries) }
        assertFails { resolveNetworkSecurityConfigPaths("@com.example:xml/missing", entries) }
        try {
            resolveNetworkSecurityConfigPaths("@string/\nnetwork", entries)
            throw AssertionError("Expected control-character reference to fail")
        } catch (expected: IllegalStateException) {
            assertTrue('\n' !in expected.message.orEmpty())
        }
    }

    @Test
    fun secureParserRejectsDoctype() {
        val file = Files.createTempFile("network-security", ".xml").toFile()
        try {
            file.writeText("<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><network-security-config>&xxe;</network-security-config>")
            try {
                readSecureXml(file)
                throw AssertionError("Expected DOCTYPE rejection")
            } catch (_: Exception) {
                // Expected: APK XML must not resolve external entities.
            }
        } finally {
            file.delete()
        }
    }

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(xml.byteInputStream())

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: RuntimeException) {
            // Expected.
        }
    }
}
