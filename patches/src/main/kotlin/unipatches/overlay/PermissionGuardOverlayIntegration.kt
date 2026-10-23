package unipatches.overlay

import app.morphe.patcher.patch.BytecodePatchContext
import unipatches.privacy.PERMISSION_GUARD_PROFILE

/** Queues Permission Guard's app-specific module until Universal Overlay publishes its bridge. */
internal object PermissionGuardOverlayIntegration {
    private var pending = false
    private var pendingContext: BytecodePatchContext? = null

    fun queue(context: BytecodePatchContext) {
        pending = true
        pendingContext = context
        attach(context)
    }

    fun attach(context: BytecodePatchContext) {
        if (!pending || pendingContext !== context) return
        val bridge = OverlayPatchRunMarker.peek(context) ?: return
        if (context.injectAppSpecificModules(bridge, PERMISSION_GUARD_PROFILE, PERMISSION_GUARD_PROFILE)) {
            pending = false
            pendingContext = null
        }
    }
}
