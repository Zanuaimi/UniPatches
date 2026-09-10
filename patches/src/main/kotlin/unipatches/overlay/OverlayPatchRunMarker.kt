package unipatches.overlay

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import java.io.File
import java.util.Base64

internal object OverlayPatchRunMarker {
    internal data class Bridge(
        val context: Any,
        val ownerType: String,
        val methodName: String,
        val returnType: String,
        val parameterTypes: List<String>,
    )

    private var marker: Bridge? = null
    private var markerFile: File? = null

    fun publish(context: BytecodePatchContext, owner: MutableClass, method: MutableMethod) {
        val file = runCatching { File.createTempFile("unipatches-overlay-", ".bridge") }.getOrNull() ?: return
        val bridge = Bridge(
            context = context,
            ownerType = owner.type,
            methodName = method.name,
            returnType = method.returnType,
            parameterTypes = method.parameterTypes.map { it.toString() },
        )
        val encode = { value: String ->
            Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        }
        runCatching {
            file.writeText(
                listOf(bridge.ownerType, bridge.methodName, bridge.returnType, bridge.parameterTypes.joinToString("\u0001"))
                    .joinToString("\n", transform = encode),
                Charsets.UTF_8,
            )
            markerFile?.delete()
            markerFile = file
            marker = bridge
        }.onFailure { file.delete() }
    }

    fun take(context: Any): Bridge? {
        val current = marker?.takeIf { it.context === context } ?: return null
        val file = markerFile?.takeIf { it.isFile } ?: return null
        val encoded = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: return null
        val decode = { value: String -> String(Base64.getDecoder().decode(value), Charsets.UTF_8) }
        val values = runCatching { encoded.map(decode) }.getOrNull() ?: return null
        if (values.size != 4 || values[0] != current.ownerType || values[1] != current.methodName ||
            values[2] != current.returnType || values[3] != current.parameterTypes.joinToString("\u0001")
        ) return null
        marker = null
        file.delete()
        markerFile = null
        return current
    }
}