package unipatch.overlaycore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.Test;

import unipatch.overlaycore.modules.OverlayAdvancedModule;
import unipatch.overlaycore.modules.OverlayAdvancedModuleRegistry;
import unipatch.overlaycore.modules.OverlayActivityModule;
import unipatch.overlaycore.modules.OverlayActivityModuleRegistry;
import unipatch.overlaycore.modules.OverlayAppSpecificModule;
import unipatch.overlaycore.modules.OverlayAppSpecificModuleRegistry;
import unipatch.overlaycore.modules.OverlayHookModule;
import unipatch.overlaycore.modules.OverlayHookModuleRegistry;
import unipatch.overlaycore.modules.OverlayStatisticsModuleRegistry;
import unipatch.overlaycore.modules.OverlaySystemModule;
import unipatch.overlaycore.modules.OverlaySystemModuleRegistry;
import unipatch.overlaycore.modules.advanced.OverlayRuntimeLogger;

public class OverlayV250ModuleTest {
    private static OverlayActivityModule activityModule(final String key) {
        return new OverlayActivityModule() {
            public String key() { return key; }
            public String label() { return key; }
            public String description() { return ""; }
            protected boolean readEnabled(Activity activity, int flags, int systemUi) { return false; }
            protected void applyEnabled(Activity activity, int flags, int systemUi) { }
            protected void restoreOriginal(Activity activity, int flags, int systemUi) { }
        };
    }

    private static OverlayHookModule hookModule(final String key) {
        return new OverlayHookModule() {
            public String key() { return key; }
            public String label() { return key; }
            public String description() { return ""; }
            protected boolean readEnabled(Activity activity, int flags, int systemUi) { return false; }
            protected void applyEnabled(Activity activity, int flags, int systemUi) { }
            protected void restoreOriginal(Activity activity, int flags, int systemUi) { }
        };
    }

    private static OverlayAppSpecificModule appSpecificModule(final String key) {
        return new OverlayAppSpecificModule() {
            public String key() { return key; }
            public String label() { return key; }
            public String description() { return ""; }
            protected boolean readEnabled(Activity activity, int flags, int systemUi) { return false; }
            protected void applyEnabled(Activity activity, int flags, int systemUi) { }
            protected void restoreOriginal(Activity activity, int flags, int systemUi) { }
        };
    }

    @Test public void allSixRegistriesRemainIndependent() {
        OverlayStatisticsModuleRegistry statistics = new OverlayStatisticsModuleRegistry();
        OverlayActivityModuleRegistry activities = new OverlayActivityModuleRegistry();
        OverlayHookModuleRegistry hooks = new OverlayHookModuleRegistry();
        OverlayAppSpecificModuleRegistry appSpecific = new OverlayAppSpecificModuleRegistry();
        OverlaySystemModuleRegistry systems = new OverlaySystemModuleRegistry();
        OverlayAdvancedModuleRegistry advanced = new OverlayAdvancedModuleRegistry();
        activities.register(activityModule("activity"));
        hooks.register(hookModule("hook"));
        appSpecific.register(appSpecificModule("app"));
        systems.register(systemModule("system"));
        advanced.register(advancedModule("advanced"));
        // Statistic modules own Android Handler state and are covered by their existing
        // module tests; this still verifies that their registry is independently constructible.
        assertEquals(0, statistics.snapshot().size());
        assertEquals(1, activities.snapshot().size());
        assertEquals(1, hooks.snapshot().size());
        assertEquals(1, appSpecific.snapshot().size());
        assertEquals(1, systems.snapshot().size());
        assertEquals(1, advanced.snapshot().size());
        assertEquals("system", systemModule("check").category());
        assertEquals("advanced", advancedModule("check").category());
        systems.register(systemModule("second"));
        assertEquals("system", systems.snapshot().get(0).key());
        assertEquals("second", systems.snapshot().get(1).key());
        systems.clear();
        assertEquals(0, systems.snapshot().size());
        assertEquals(1, advanced.snapshot().size());
    }

    @Test public void advancedFailureDoesNotBlockHealthyModuleLifecycle() {
        final boolean[] stopped = {false};
        OverlayAdvancedModule failing = new OverlayAdvancedModule() {
            public String key() { return "failing"; }
            public String label() { return "Failing"; }
            public String description() { return ""; }
            protected boolean onAdvancedModuleStart(unipatch.overlaycore.modules.AdvancedModuleContext context) {
                throw new IllegalStateException("expected test failure");
            }
            public void onAdvancedModuleStop() { }
        };
        OverlayAdvancedModule healthy = new OverlayAdvancedModule() {
            public String key() { return "healthy"; }
            public String label() { return "Healthy"; }
            public String description() { return ""; }
            protected boolean onAdvancedModuleStart(unipatch.overlaycore.modules.AdvancedModuleContext context) {
                return true;
            }
            public void onAdvancedModuleStop() { stopped[0] = true; }
        };
        assertFalse(failing.startSafely(null));
        assertTrue(healthy.startSafely(null));
        healthy.stopSafely();
        assertTrue(stopped[0]);
    }

    private static OverlaySystemModule systemModule(final String key) {
        return new OverlaySystemModule() {
            public String key() { return key; }
            public String label() { return key; }
            public String description() { return ""; }
            protected boolean readInitialState(Activity activity) { return false; }
            protected boolean enable(Activity activity) { return true; }
            protected boolean disable(Activity activity) { return true; }
            protected void onSystemModuleStart(unipatch.overlaycore.modules.SystemModuleContext context) { }
            public void onSystemModuleStop() { }
        };
    }

    private static OverlayAdvancedModule advancedModule(final String key) {
        return new OverlayAdvancedModule() {
            public String key() { return key; }
            public String label() { return key; }
            public String description() { return ""; }
            protected boolean onAdvancedModuleStart(unipatch.overlaycore.modules.AdvancedModuleContext context) { return true; }
            public void onAdvancedModuleStop() { }
        };
    }

    @Test public void systemAndAdvancedRegistriesDeduplicateAndIsolate() {
        OverlaySystemModule system = new OverlaySystemModule() {
            public String key() { return "system"; }
            public String label() { return "System"; }
            public String description() { return ""; }
            protected boolean readInitialState(Activity activity) { return false; }
            protected boolean enable(Activity activity) { return true; }
            protected boolean disable(Activity activity) { return true; }
            protected void onSystemModuleStart(unipatch.overlaycore.modules.SystemModuleContext context) { }
            public void onSystemModuleStop() { }
        };
        OverlayAdvancedModule advanced = new OverlayAdvancedModule() {
            public String key() { return "advanced"; }
            public String label() { return "Advanced"; }
            public String description() { return ""; }
            protected boolean onAdvancedModuleStart(unipatch.overlaycore.modules.AdvancedModuleContext context) { return true; }
            public void onAdvancedModuleStop() { }
        };
        OverlaySystemModuleRegistry systems = new OverlaySystemModuleRegistry();
        OverlayAdvancedModuleRegistry advancedModules = new OverlayAdvancedModuleRegistry();
        systems.register(system);
        systems.register(system);
        advancedModules.register(advanced);
        advancedModules.register(advanced);
        assertEquals(1, systems.snapshot().size());
        assertEquals(1, advancedModules.snapshot().size());
        systems.clear();
        assertEquals(0, systems.snapshot().size());
        assertEquals(1, advancedModules.snapshot().size());
    }

    @Test public void runtimeLoggerIsOptInAndBounded() {
        OverlayRuntimeLogger.clear();
        OverlayRuntimeLogger.setActive(false);
        OverlayRuntimeLogger.log("INFO", "Test", "ignored");
        assertTrue(OverlayRuntimeLogger.snapshot().isEmpty());
        OverlayRuntimeLogger.setActive(true);
        for (int i = 0; i < 510; i++) {
            OverlayRuntimeLogger.log("info", "Test", "token=secret https://example.test/" + i);
        }
        assertEquals(500, OverlayRuntimeLogger.snapshot().size());
        assertFalse(OverlayRuntimeLogger.snapshot().get(0).message.contains("secret"));
        assertFalse(OverlayRuntimeLogger.snapshot().get(0).message.contains("https://"));
        try {
            OverlayRuntimeLogger.snapshot().clear();
            throw new AssertionError("snapshot must be immutable");
        } catch (UnsupportedOperationException expected) { }
        OverlayRuntimeLogger.clear();
        assertTrue(OverlayRuntimeLogger.snapshot().isEmpty());
        OverlayRuntimeLogger.setActive(false);
    }
}
