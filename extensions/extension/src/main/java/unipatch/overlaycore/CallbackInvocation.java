package unipatch.overlaycore;

/** Injectable callback operation used to classify delivery failures. */
@FunctionalInterface
public interface CallbackInvocation {
    void invoke() throws Exception;
}
