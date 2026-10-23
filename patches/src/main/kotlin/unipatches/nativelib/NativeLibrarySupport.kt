package unipatches.nativelib

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import helpers.bytecode.cloneMutable
import helpers.bytecode.numberOfParameterRegisters
import helpers.startup.StartupHooks
import java.io.File
import java.io.IOException

/** Native ABIs this injector can recognize from an ELF header. */
internal val SUPPORTED_NATIVE_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

/** Library names accepted by [System.loadLibrary] without touching the filesystem directly. */
private val LIBRARY_NAME_PATTERN = Regex("^[A-Za-z0-9_.-]+$")

/**
 * Reads the ELF machine field and maps it to the Android ABI directory name.
 *
 * Returns `null` for anything the platform loader cannot use: non-ELF input,
 * big-endian files, executables that are not position-independent, unknown
 * machines, or a class/machine mismatch (for example a 32-bit header claiming
 * `EM_AARCH64`).
 */
internal fun detectElfAbi(file: File): String? {
    if (!file.isFile || file.length() < ELF_HEADER_PREFIX_SIZE) return null
    val header = ByteArray(ELF_HEADER_PREFIX_SIZE)
    val read = try {
        file.inputStream().use { it.read(header) }
    } catch (_: IOException) {
        return null
    }
    if (read != header.size) return null

    if (header[0] != 0x7f.toByte() ||
        header[1] != 'E'.code.toByte() ||
        header[2] != 'L'.code.toByte() ||
        header[3] != 'F'.code.toByte()
    ) {
        return null
    }

    val elfClass = header[4].toInt() and 0xff
    val dataEncoding = header[5].toInt() and 0xff
    // Android only ships little-endian devices; a big-endian .so cannot load.
    if (dataEncoding != 1) return null

    val type = readLe16(header, 16)
    // ET_DYN covers shared objects and PIE executables. ET_EXEC is not loadable
    // with System.loadLibrary.
    if (type != 3) return null

    val machine = readLe16(header, 18)
    return when (machine) {
        183 -> if (elfClass == 2) "arm64-v8a" else null // EM_AARCH64
        40 -> if (elfClass == 1) "armeabi-v7a" else null // EM_ARM
        3 -> if (elfClass == 1) "x86" else null // EM_386
        62 -> if (elfClass == 2) "x86_64" else null // EM_X86_64
        else -> null
    }
}

private const val ELF_HEADER_PREFIX_SIZE = 20

private fun readLe16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

/**
 * Resolves the name passed to `System.loadLibrary`.
 *
 * With no override, `libTool.so` becomes `Tool`, `Tool.so` becomes `Tool`, and
 * a leading `lib` is removed only once. The override must already be the bare
 * load name: `Tool`, not `libTool` and not `libTool.so`.
 */
internal fun resolveLibraryName(source: File, override: String?): String {
    val rawOverride = override?.trim().orEmpty()
    val name = if (rawOverride.isNotEmpty()) {
        if (rawOverride.endsWith(".so", ignoreCase = true)) {
            throw PatchException(
                "Native Library Injector: load name must not end with .so. Use \"Tool\", not \"libTool.so\".",
            )
        }
        if (rawOverride.startsWith("lib")) {
            throw PatchException(
                "Native Library Injector: load name must not start with \"lib\". Use \"Tool\", not \"libTool\".",
            )
        }
        rawOverride
    } else {
        source.name
            .removeSuffix(".so")
            .removePrefix("lib")
    }

    if (name.isEmpty()) {
        throw PatchException("Native Library Injector: could not derive a library name from ${source.name}.")
    }
    if (!LIBRARY_NAME_PATTERN.matches(name) || name == "." || name == "..") {
        throw PatchException(
            "Native Library Injector: \"$name\" is not a valid load name. Use letters, digits, underscores, dots, or hyphens; it must not contain a path.",
        )
    }
    return name
}

/** APK entry name for the staged library, e.g. `lib/arm64-v8a/libTool.so`. */
internal fun libraryEntryName(abi: String, libraryName: String): String {
    require(abi in SUPPORTED_NATIVE_ABIS) { "Unsupported ABI: $abi" }
    require(LIBRARY_NAME_PATTERN.matches(libraryName)) { "Invalid library name: $libraryName" }
    return "lib/$abi/lib$libraryName.so"
}

/**
 * True for the startup `onCreate` override this injector can use.
 *
 * @param requireBundle `true` for an Activity and `false` for an Application.
 * `null` accepts either shape for callers that do not know the target type.
 */
internal fun isStartupOnCreate(method: MutableMethod, requireBundle: Boolean? = null): Boolean {
    if (method.name != "onCreate" || method.returnType != "V") return false
    if (AccessFlags.STATIC.isSet(method.accessFlags)) return false
    if (method.implementation == null) return false
    val parameters = method.parameterTypes
    val hasBundle = parameters.size == 1 && parameters[0].toString() == "Landroid/os/Bundle;"
    return when (requireBundle) {
        true -> hasBundle
        false -> parameters.isEmpty()
        null -> parameters.isEmpty() || hasBundle
    }
}

/**
 * Prepends `System.loadLibrary(libraryName)` to a startup method.
 *
 * `cloneMutable` shifts the parameter window up and copies `p0`/`p1` back into
 * the original register slots in a prologue. Injection happens before that
 * prologue, so `v0` is either a dead local or the stale first-parameter slot.
 * The clone prologue overwrites `v0` from the real `p0` before the original body
 * can read it, and keeping the load name in `v0` keeps `invoke-static` inside its
 * 4-bit register field.
 *
 * @return true when the desired load call is present, including when a previous
 * run already injected it; false when the method has no implementation.
 */
internal fun injectNativeLibraryLoad(
    owner: MutableClass,
    method: MutableMethod,
    libraryName: String,
): Boolean {
    val implementation = method.implementation ?: return false
    val instructions = implementation.instructions.toList()
    if (containsLoadCall(instructions, libraryName)) return true

    val newRegisterCount = implementation.registerCount + method.numberOfParameterRegisters
    if (newRegisterCount > 0xffff) {
        throw PatchException(
            "Native Library Injector: ${method.definingClass}->onCreate has ${implementation.registerCount} registers; " +
                "cloning it needs $newRegisterCount, above the dex limit of 65535.",
        )
    }

    // The only added registers are the moved parameter window; v0 itself stays
    // available as the load-name scratch.
    val cloned = method.cloneMutable(
        additionalRegisters = method.numberOfParameterRegisters,
    )
    cloned.addInstructions(
        0,
        """
            const-string v0, "${StartupHooks.escapeSmali(libraryName)}"
            invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
        """.trimIndent(),
    )

    val isDirect = MethodUtil.isDirect(method)
    owner.methods.remove(method)
    owner.methods.add(cloned)
    // MutableClass keeps direct/virtual views in separate lazy sets. Update the
    // view that owns this method so later patches do not see the replaced copy.
    if (isDirect) {
        owner.directMethods.remove(method)
        owner.directMethods.add(cloned)
    } else {
        owner.virtualMethods.remove(method)
        owner.virtualMethods.add(cloned)
    }
    return true
}

/**
 * Finds the exact two-instruction sequence this injector writes.
 *
 * Builder instructions do not override `toString`, so string inspection would
 * silently miss a previous injection. Match the opcodes and references instead.
 */
private fun containsLoadCall(instructions: List<Instruction>, libraryName: String): Boolean =
    instructions.indices.any { index ->
        val constString = instructions[index] as? ReferenceInstruction ?: return@any false
        if (constString.opcode != Opcode.CONST_STRING) return@any false
        val value = (constString.reference as? StringReference)?.string ?: return@any false
        if (value != libraryName) return@any false

        val load = instructions.getOrNull(index + 1) as? ReferenceInstruction ?: return@any false
        if (load.opcode != Opcode.INVOKE_STATIC) return@any false
        val target = load.reference as? MethodReference ?: return@any false
        target.definingClass == "Ljava/lang/System;" && target.name == "loadLibrary"
    }

/** State handed from the staging resource patch to the bytecode patch. */
internal data class NativeLibraryPlan(
    val source: File,
    val abi: String,
    val libraryName: String,
    val entryName: String,
)

internal object NativeLibraryPlanHolder {
    @Volatile
    var plan: NativeLibraryPlan? = null
}
