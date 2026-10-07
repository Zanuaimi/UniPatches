package unipatch.compatcore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;

import org.junit.Test;

public class LegacyCompatRuntimeTest {
    @Test
    public void trustAllRequiresExplicitAcknowledgementArgument() throws Exception {
        Method guardedEntryPoint = LegacyCompatRuntime.class.getMethod("trustAllCertificates", boolean.class);
        assertNotNull(guardedEntryPoint);
    }

    @Test
    public void embeddedExpansionEntryPointIsAvailable() throws Exception {
        Method entryPoint = LegacyCompatRuntime.class.getMethod("prepareEmbeddedExpansion", android.content.Context.class);
        assertNotNull(entryPoint);
    }

    @Test
    public void hiddenApiExemptionsAreSkippedBeforeAndroidP() throws Throwable {
        LegacyCompatRuntime.HiddenApiExemptionOnce once = new LegacyCompatRuntime.HiddenApiExemptionOnce();
        final int[] calls = {0};

        assertEquals(
                LegacyCompatRuntime.HiddenApiExemptionOutcome.UNSUPPORTED,
                once.apply(android.os.Build.VERSION_CODES.P - 1, prefix -> {
                    calls[0]++;
                    return true;
                })
        );
        assertEquals(0, calls[0]);
    }

    @Test
    public void appliesTheBroadPrefixOnlyOnceAcrossRepeatedStartupHooks() throws Throwable {
        LegacyCompatRuntime.HiddenApiExemptionOnce once = new LegacyCompatRuntime.HiddenApiExemptionOnce();
        final int[] calls = {0};

        assertEquals(
                LegacyCompatRuntime.HiddenApiExemptionOutcome.APPLIED,
                once.apply(android.os.Build.VERSION_CODES.P, prefix -> {
                    calls[0]++;
                    assertEquals("L", prefix);
                    return true;
                })
        );
        assertEquals(
                LegacyCompatRuntime.HiddenApiExemptionOutcome.ALREADY_APPLIED,
                once.apply(android.os.Build.VERSION_CODES.P, prefix -> {
                    calls[0]++;
                    return true;
                })
        );
        assertEquals(1, calls[0]);
    }

    @Test
    public void rejectedOrFailedExemptionCanBeRetried() throws Throwable {
        LegacyCompatRuntime.HiddenApiExemptionOnce once = new LegacyCompatRuntime.HiddenApiExemptionOnce();

        assertEquals(
                LegacyCompatRuntime.HiddenApiExemptionOutcome.REJECTED,
                once.apply(android.os.Build.VERSION_CODES.P, prefix -> false)
        );
        try {
            once.apply(android.os.Build.VERSION_CODES.P, prefix -> {
                throw new IllegalStateException("test failure");
            });
            fail("Expected the failed exemption attempt to propagate to its non-fatal runtime boundary");
        } catch (IllegalStateException expected) {
            assertEquals("test failure", expected.getMessage());
        }
        assertEquals(
                LegacyCompatRuntime.HiddenApiExemptionOutcome.APPLIED,
                once.apply(android.os.Build.VERSION_CODES.P, prefix -> true)
        );
    }
}
