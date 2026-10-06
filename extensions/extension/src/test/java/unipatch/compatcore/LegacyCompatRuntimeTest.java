package unipatch.compatcore;

import static org.junit.Assert.assertNotNull;

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
}
