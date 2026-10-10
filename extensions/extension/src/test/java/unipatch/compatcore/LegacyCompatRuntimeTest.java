package unipatch.compatcore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;

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

    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private File temporaryDirectory() throws IOException {
        File directory = Files.createTempDirectory("legacy-obb-runtime-").toFile();
        directory.deleteOnExit();
        return directory;
    }

    private LegacyCompatRuntime.ExpansionMetadata abcMetadata() {
        return LegacyCompatRuntime.parseExpansionMetadata("3 " + ABC_SHA256);
    }

    @Test
    public void markerDoesNotTrustSameLengthCorruptedExpansion() throws Exception {
        File directory = temporaryDirectory();
        File target = new File(directory, "main.123.example.obb");
        File marker = new File(directory, ".main.123.example.obb.legacy-compat");
        Files.write(target.toPath(), "xyz".getBytes("UTF-8"));
        Files.write(marker.toPath(), abcMetadata().encode().getBytes("UTF-8"));
        target.setLastModified(1_000L);
        marker.setLastModified(2_000L);

        Method markerMatches = LegacyCompatRuntime.class.getDeclaredMethod(
                "markerMatches", File.class, File.class, LegacyCompatRuntime.ExpansionMetadata.class
        );
        markerMatches.setAccessible(true);
        assertFalse((Boolean) markerMatches.invoke(null, marker, target, abcMetadata()));
    }

    @Test
    public void stagesVerifiedEmbeddedObbFromLocalAssetWithoutNetwork() throws Exception {
        File directory = temporaryDirectory();
        File target = new File(directory, "main.123.example.obb");
        File temporary = new File(directory, ".stage.tmp");
        File backup = new File(directory, ".stage.bak");
        Files.write(target.toPath(), "old".getBytes("UTF-8"));

        assertTrue(LegacyCompatRuntime.stageVerifiedFile(
                new ByteArrayInputStream("abc".getBytes("UTF-8")),
                temporary,
                target,
                backup,
                abcMetadata()
        ));

        assertEquals("abc", new String(Files.readAllBytes(target.toPath()), "UTF-8"));
    }

    @Test
    public void corruptOrStaleObbIsRejectedWithoutReplacingExistingTarget() throws Exception {
        File directory = temporaryDirectory();
        File target = new File(directory, "main.123.example.obb");
        File temporary = new File(directory, ".stage.tmp");
        File backup = new File(directory, ".stage.bak");
        Files.write(target.toPath(), "old".getBytes("UTF-8"));
        LegacyCompatRuntime.ExpansionMetadata metadata = abcMetadata();

        assertFalse(LegacyCompatRuntime.fileMatchesExpected(target, metadata));
        assertFalse(LegacyCompatRuntime.stageVerifiedFile(
                new ByteArrayInputStream("abd".getBytes("UTF-8")), temporary, target, backup, metadata));
        assertFalse(LegacyCompatRuntime.stageVerifiedFile(
                new ByteArrayInputStream("a".getBytes("UTF-8")), temporary, target, backup, metadata));

        assertEquals("old", new String(Files.readAllBytes(target.toPath()), "UTF-8"));
        assertFalse(temporary.exists());
        assertNull(LegacyCompatRuntime.parseExpansionMetadata("3 invalid"));
    }

    @Test
    public void interruptedCopyDeletesTemporaryFileAndKeepsExistingObb() throws Exception {
        File directory = temporaryDirectory();
        File target = new File(directory, "main.123.example.obb");
        File temporary = new File(directory, ".stage.tmp");
        File backup = new File(directory, ".stage.bak");
        Files.write(target.toPath(), "old".getBytes("UTF-8"));
        InputStream interrupted = new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                if (reads++ == 0) return 'a';
                throw new IOException("interrupted");
            }
        };
        try {
            LegacyCompatRuntime.stageVerifiedFile(interrupted, temporary, target, backup, abcMetadata());
            fail("Expected interrupted copy to propagate");
        } catch (IOException expected) {
            assertEquals("interrupted", expected.getMessage());
        }
        assertFalse(temporary.exists());
        assertEquals("old", new String(Files.readAllBytes(target.toPath()), "UTF-8"));
    }
}
