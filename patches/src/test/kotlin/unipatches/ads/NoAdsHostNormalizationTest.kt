package unipatches.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoAdsHostNormalizationTest {
    @Test
    fun normalizesUrlIdnAndIpv6Hosts() {
        assertEquals("example.com", normalizeHost("https://Example.COM:443/path"))
        assertEquals("xn--bcher-kva.example", normalizeHost("https://bücher.example/ads"))
        assertEquals("2001:db8::1", normalizeHost("https://[2001:db8::1]:443/ad"))
    }

    @Test
    fun rejectsMalformedOrNonHostInput() {
        assertNull(normalizeHost("not a host"))
        assertNull(normalizeHost("https://"))
    }
}
