package unipatch.overlaycore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public final class FridaGadgetRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void escapesAllJsonControls() {
        assertEquals("\"\\u0000\\u0008\\u000c\\u001f\\n\\r\\t\\\"\\\\é\"",
                FridaGadgetRuntime.jsonString("\u0000\b\f\u001f\n\r\t\"\\é"));
    }

    @Test public void configUsesAbsoluteEscapedPathAndOptionalReload() {
        File bundle = new File("quoted\"\n\\bundle.js");
        String expected = "{\"interaction\":{\"type\":\"script\",\"path\":"
                + FridaGadgetRuntime.jsonString(bundle.getAbsolutePath());
        assertEquals(expected + "}}", FridaGadgetRuntime.configJson(bundle, true));
        assertEquals(expected + ",\"on_change\":\"reload\"}}", FridaGadgetRuntime.configJson(bundle, false));
    }

    @Test public void mapsOnlySupportedAbisAndProcessArchitectures() {
        assertEquals("arm64", FridaGadgetRuntime.assetToken("arm64-v8a"));
        assertEquals("arm", FridaGadgetRuntime.assetToken("armeabi-v7a"));
        assertEquals("x86_64", FridaGadgetRuntime.assetToken("x86_64"));
        assertEquals("x86", FridaGadgetRuntime.assetToken("x86"));
        assertNull(FridaGadgetRuntime.assetToken("riscv64"));
        assertNull(FridaGadgetRuntime.assetToken(null));
        assertTrue(FridaGadgetRuntime.is64BitArchitecture("aarch64"));
        assertTrue(FridaGadgetRuntime.is64BitArchitecture("x86_64"));
        assertFalse(FridaGadgetRuntime.is64BitArchitecture("armv7l"));
        assertFalse(FridaGadgetRuntime.is64BitArchitecture("i686"));
    }

    @Test public void interruptedWritePreservesOldFileAndCleansTemporary() throws Exception {
        File destination = temporary.newFile("bundle.js");
        Files.write(destination.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        InputStream broken = new InputStream() {
            int calls;
            public int read() throws IOException {
                if (calls++ == 0) return 'x';
                throw new IOException("interrupted");
            }
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (calls++ == 0) { bytes[offset] = 'x'; return 1; }
                throw new IOException("interrupted");
            }
        };
        try {
            FridaGadgetRuntime.writeAtomically(broken, destination);
            fail("Expected interrupted write");
        } catch (IOException expected) { assertEquals("interrupted", expected.getMessage()); }
        assertEquals("old", new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, temporary.getRoot().list().length);
        FridaGadgetRuntime.writeAtomically(new ByteArrayInputStream("new".getBytes(StandardCharsets.UTF_8)), destination);
        assertEquals("new", new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, temporary.getRoot().list().length);
    }

    @Test public void failedFirstWriteLeavesNoFinalFile() throws Exception {
        File destination = new File(temporary.getRoot(), "gadget.so");
        try {
            FridaGadgetRuntime.writeAtomically(new InputStream() {
                public int read() throws IOException { throw new IOException("interrupted"); }
            }, destination);
            fail("Expected failure");
        } catch (IOException expected) { }
        assertFalse(destination.exists());
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test public void retriesFailuresAndDeduplicatesOnlySuccessfulStartup() throws Throwable {
        FridaGadgetRuntime.State state = new FridaGadgetRuntime.State();
        try {
            state.start(() -> { throw new UnsatisfiedLinkError("bad ELF"); });
            fail("Expected load failure");
        } catch (UnsatisfiedLinkError expected) { }
        assertFalse(state.loaded);
        int[] starts = {0};
        state.start(() -> starts[0]++);
        state.start(() -> starts[0]++);
        assertTrue(state.loaded);
        assertEquals(1, starts[0]);
    }

    @Test public void feedbackRetriesReportingFailureAndShowsOnceUnlessRecovered() throws Throwable {
        FridaGadgetRuntime.State state = new FridaGadgetRuntime.State();
        state.notifyOnce(() -> { throw new IllegalStateException("no window"); });
        assertFalse(state.notified);
        int[] shown = {0};
        state.notifyOnce(() -> shown[0]++);
        state.notifyOnce(() -> shown[0]++);
        assertEquals(1, shown[0]);
        FridaGadgetRuntime.State recovered = new FridaGadgetRuntime.State();
        recovered.start(() -> { });
        recovered.notifyOnce(() -> shown[0]++);
        assertEquals(1, shown[0]);
    }
}
