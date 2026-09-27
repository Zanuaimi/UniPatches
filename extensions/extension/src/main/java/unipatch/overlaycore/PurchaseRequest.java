package unipatch.overlaycore;

import android.app.Activity;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Backend-neutral state for one emulated purchase request. */
public final class PurchaseRequest {
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    public enum State {
        RECEIVED,
        VALIDATED,
        WAITING_FOR_POPUP,
        DELIVERING,
        COMPLETED,
        CANCELLED,
        TIMED_OUT
    }

    public final long requestId;
    public final String productId;
    public final String productType;
    public final String developerPayload;
    public final Object listener;
    public final WeakReference<Activity> sourceActivity;
    public final PurchaseBackend backend;
    public final boolean overlayMode;
    public final long timeoutMillis;
    private final AtomicReference<State> state = new AtomicReference<>(State.RECEIVED);
    private final AtomicBoolean terminal = new AtomicBoolean(false);
    private final AtomicBoolean callbackAttempted = new AtomicBoolean(false);

    public PurchaseRequest(String productId, String productType, String developerPayload,
                           Object listener, Activity sourceActivity, PurchaseBackend backend,
                           boolean overlayMode, long timeoutMillis) {
        this.requestId = NEXT_ID.getAndIncrement();
        this.productId = productId;
        this.productType = productType;
        this.developerPayload = developerPayload == null ? "" : developerPayload;
        this.listener = listener;
        this.sourceActivity = new WeakReference<>(sourceActivity);
        this.backend = backend;
        this.overlayMode = overlayMode;
        this.timeoutMillis = timeoutMillis;
    }

    public State state() { return state.get(); }

    public boolean transition(State expected, State next) {
        return state.compareAndSet(expected, next);
    }

    /** Claims the request for its only terminal completion. */
    public boolean finish(State terminalState) {
        if (terminalState != State.COMPLETED && terminalState != State.CANCELLED && terminalState != State.TIMED_OUT) {
            throw new IllegalArgumentException("Not a terminal purchase state: " + terminalState);
        }
        if (!terminal.compareAndSet(false, true)) return false;
        state.set(terminalState);
        return true;
    }

    public boolean isFinished() { return terminal.get(); }

    /** Ensures a listener is invoked at most once for this request. */
    public boolean claimCallback() { return callbackAttempted.compareAndSet(false, true); }

    public boolean callbackAttempted() { return callbackAttempted.get(); }
}
