package unipatch.overlaycore;

/** Pure cleanup decision used before posting a proxy Activity finish. */
public final class LegacyProxyCleanupPolicy {
    private LegacyProxyCleanupPolicy() { }

    public static boolean shouldFinish(String className, boolean isFinishing, boolean isDestroyed) {
        return "org.onepf.openiab.UnityProxyActivity".equals(className) && !isFinishing && !isDestroyed;
    }
}
