package unipatch.overlaycore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CatalogProductTrackerTest {
    @Test
    public void returnsOnlyUnambiguousCatalogProduct() {
        CatalogProductTracker tracker = new CatalogProductTracker();
        tracker.remember(" coins_100 ");
        assertEquals("coins_100", tracker.onlyProduct());
        tracker.remember("coins_500");
        assertEquals("", tracker.onlyProduct());
    }

    @Test
    public void ignoresEmptyCatalogIds() {
        CatalogProductTracker tracker = new CatalogProductTracker();
        tracker.remember(null);
        tracker.remember(" ");
        assertEquals("", tracker.onlyProduct());
    }
}
