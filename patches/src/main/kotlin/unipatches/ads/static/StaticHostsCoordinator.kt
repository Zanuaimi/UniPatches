package unipatches.ads

import app.morphe.patcher.patch.BytecodePatchContext
import java.util.logging.Logger

internal class StaticHostsCoordinator(
    private val context: BytecodePatchContext,
    private val logger: Logger,
) {
    fun apply(hosts: Set<String>, wildcard: Boolean): Int =
        context.redirectLiteralHosts(hosts, wildcard, logger)
}
