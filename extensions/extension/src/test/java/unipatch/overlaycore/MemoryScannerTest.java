package unipatch.overlaycore;

import org.junit.Test;
import static org.junit.Assert.*;

public class MemoryScannerTest {
    @Test public void mapsPermissionsUseFourthPrivateFlag() {
        assertTrue(MemoryScanner.isReadableWritablePrivate("rw-p"));
        assertFalse(MemoryScanner.isReadableWritablePrivate("r--p"));
        assertFalse(MemoryScanner.isReadableWritablePrivate("rw-s"));
        assertFalse(MemoryScanner.isReadableWritablePrivate("r-xp"));
        assertFalse(MemoryScanner.isReadableWritablePrivate(""));
    }

    @Test public void parsesMapsAddressesWithoutApi26() {
        assertEquals(0L, MemoryScanner.parseAddress("0"));
        assertEquals(0x7f0000000000L, MemoryScanner.parseAddress("7f0000000000"));
        assertEquals(0xffffffffL, MemoryScanner.parseAddress("ffffffff"));
        assertEquals(0x7fffffffffffffffL, MemoryScanner.parseAddress("7fffffffffffffff"));
    }

    @Test public void rejectsMalformedMapsAddresses() {
        assertThrows(NumberFormatException.class, () -> MemoryScanner.parseAddress(""));
        assertThrows(NumberFormatException.class, () -> MemoryScanner.parseAddress("12g4"));
        assertThrows(NumberFormatException.class, () -> MemoryScanner.parseAddress("00000000000000000"));
        assertThrows(NumberFormatException.class, () -> MemoryScanner.parseAddress(null));
    }
}
