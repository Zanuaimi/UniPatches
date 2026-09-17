package unipatch.overlaycore;

/** Executes one callback and exposes the exact delivery phase reached. */
public final class CallbackInvoker {
    private CallbackInvoker() { }

    public static CallbackDeliveryResult invoke(boolean located, CallbackInvocation callback) {
        if (!located || callback == null) return CallbackDeliveryResult.unavailable();
        try {
            callback.invoke();
            return CallbackDeliveryResult.returned();
        } catch (Exception error) {
            return CallbackDeliveryResult.threw();
        }
    }
}
