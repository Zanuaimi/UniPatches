package unipatches.iap

import app.morphe.patcher.patch.ResourcePatchContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.logging.Logger

private data class NativeSignature(
    val name: String,
    val pattern: ByteArray,
    val mask: ByteArray,
    val replacement: ByteArray,
)

private data class ExecutableRange(val start: Int, val end: Int)

internal enum class NativeStatus { PATCHED, SKIPPED }

internal data class NativePhaseResult(
    val abi: String,
    val status: NativeStatus,
    val message: String,
)

private fun hexBytes(value: String): ByteArray = value
    .split(Regex("\\s+"))
    .filter(String::isNotEmpty)
    .map { it.toInt(16).toByte() }
    .toByteArray()

private fun maskedBytes(value: String): Pair<ByteArray, ByteArray> {
    val bytes = mutableListOf<Byte>()
    val masks = mutableListOf<Byte>()
    for (token in value.trim().split(Regex("\\s+"))) {
        if (token == "??") {
            bytes += 0
            masks += 0
        } else {
            bytes += token.toInt(16).toByte()
            masks += 0xff.toByte()
        }
    }
    return bytes.toByteArray() to masks.toByteArray()
}

private fun maskedMatches(
    haystack: ByteArray,
    signature: NativeSignature,
    ranges: List<ExecutableRange>,
): List<Int> {
    if (signature.pattern.isEmpty() || signature.pattern.size != signature.mask.size) return emptyList()
    val matches = mutableListOf<Int>()
    for (range in ranges) {
        val lastStart = range.end - signature.pattern.size
        if (lastStart < range.start) continue
        for (offset in range.start..lastStart step 4) {
            var matched = true
            for (index in signature.pattern.indices) {
                val actual = haystack[offset + index].toInt() and 0xff
                val expected = signature.pattern[index].toInt() and 0xff
                val mask = signature.mask[index].toInt() and 0xff
                if ((actual and mask) != (expected and mask)) {
                    matched = false
                    break
                }
            }
            if (matched) matches += offset
        }
    }
    return matches
}

private fun executableRanges(bytes: ByteArray): List<ExecutableRange> {
    if (bytes.size < 0x34 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
        bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte() || bytes[5].toInt() != 1
    ) return emptyList()

    val elfClass = bytes[4].toInt() and 0xff
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val ranges = mutableListOf<ExecutableRange>()
    try {
        if (elfClass == 2) {
            if (bytes.size < 0x40) return emptyList()
            val headerOffset = buffer.getLong(0x20)
            val headerSize = buffer.getShort(0x36).toInt() and 0xffff
            val headerCount = buffer.getShort(0x38).toInt() and 0xffff
            for (index in 0 until headerCount) {
                val header = headerOffset + index.toLong() * headerSize
                if (header < 0 || header + 56 > bytes.size) continue
                val type = buffer.getInt(header.toInt())
                val flags = buffer.getInt((header + 4).toInt())
                val offset = buffer.getLong((header + 8).toInt())
                val size = buffer.getLong((header + 32).toInt())
                if (type == 1 && flags and 1 != 0 && offset >= 0 && size > 0 &&
                    offset <= bytes.size && size <= bytes.size - offset
                ) ranges += ExecutableRange(offset.toInt(), (offset + size).toInt())
            }
        } else if (elfClass == 1) {
            val headerOffset = buffer.getInt(0x1c).toLong() and 0xffffffffL
            val headerSize = buffer.getShort(0x2a).toInt() and 0xffff
            val headerCount = buffer.getShort(0x2c).toInt() and 0xffff
            for (index in 0 until headerCount) {
                val header = headerOffset + index.toLong() * headerSize
                if (header < 0 || header + 32 > bytes.size) continue
                val type = buffer.getInt(header.toInt())
                val offset = buffer.getInt((header + 4).toInt()).toLong() and 0xffffffffL
                val size = buffer.getInt((header + 16).toInt()).toLong() and 0xffffffffL
                val flags = buffer.getInt((header + 24).toInt())
                if (type == 1 && flags and 1 != 0 && offset <= bytes.size && size > 0 &&
                    size <= bytes.size - offset
                ) ranges += ExecutableRange(offset.toInt(), (offset + size).toInt())
            }
        }
    } catch (_: RuntimeException) {
        return emptyList()
    }
    return ranges
}

private fun arm64Signature(name: String, pattern: String, replacement: String): NativeSignature {
    val (bytes, mask) = maskedBytes(pattern)
    return NativeSignature(name, bytes, mask, hexBytes(replacement))
}

private val arm64Signatures = listOf(
    arm64Signature(
        "ShowPurchaseErrorPopup -> return",
        "FE 5F BD A9 F6 57 01 A9 F4 4F 02 A9 ?? ?? ?? 90 F3 03 00 2A 88 96 57 39 A8 05 00 37 ?? ?? ?? 90",
        "C0 03 5F D6 1F 20 03 D5",
    ),
    arm64Signature(
        "OnProductPurchasedCallback -> return",
        "FF 83 01 D1 FE 6F 01 A9 FA 67 02 A9 F8 5F 03 A9 F6 57 04 A9 F4 4F 05 A9 ?? ?? ?? 90 F3 03 03 2A",
        "C0 03 5F D6 1F 20 03 D5",
    ),
    arm64Signature(
        "IsProductUnlocked -> return true",
        "FF 03 02 D1 FD 7B 02 A9 FC 6F 03 A9 FA 67 04 A9 F8 5F 05 A9 F6 57 06 A9 F4 4F 07 A9 ?? ?? ?? 90",
        "20 00 80 52 C0 03 5F D6",
    ),
    arm64Signature(
        "OnPurchaseProduct -> return",
        "FE 5F BD A9 F6 57 01 A9 F4 4F 02 A9 ?? ?? ?? 90 ?? ?? ?? 90 F5 03 02 AA E8 EE 57 39 D6 3A 45 F9",
        "C0 03 5F D6 1F 20 03 D5",
    ),
)

private val nativeAbiPaths = linkedMapOf(
    "arm64-v8a" to "lib/arm64-v8a/libil2cpp.so",
    "armeabi-v7a" to "lib/armeabi-v7a/libil2cpp.so",
    "x86_64" to "lib/x86_64/libil2cpp.so",
    "x86" to "lib/x86/libil2cpp.so",
)

internal fun applyNativeIl2CppPhase(
    context: ResourcePatchContext,
    mode: String,
    logger: Logger,
): List<NativePhaseResult> {
    if (mode == "managed") return emptyList()
    val results = mutableListOf<NativePhaseResult>()

    for ((abi, path) in nativeAbiPaths) {
        val present = try { context.listApkEntries(path).any { it == path } } catch (_: Exception) { false }
        if (!present) {
            results += NativePhaseResult(abi, NativeStatus.SKIPPED, "library not present")
            continue
        }
        if (abi != "arm64-v8a") {
            results += NativePhaseResult(abi, NativeStatus.SKIPPED, "no verified signatures")
            continue
        }

        val file = try { context.get(path, true) } catch (e: Exception) {
            results += NativePhaseResult(abi, NativeStatus.SKIPPED, "could not be staged: ${e.message}")
            continue
        }
        if (!file.exists()) {
            results += NativePhaseResult(abi, NativeStatus.SKIPPED, "library unavailable")
            continue
        }

        try {
            val bytes = file.readBytes()
            val ranges = executableRanges(bytes)
            if (ranges.isEmpty()) {
                results += NativePhaseResult(abi, NativeStatus.SKIPPED, "no executable ELF ranges")
                continue
            }
            val matches = arm64Signatures.associateWith { maskedMatches(bytes, it, ranges) }
            val replacements = arm64Signatures.mapNotNull { signature ->
                val offsets = matches.getValue(signature)
                if (offsets.size == 1 && signature.replacement.size <= signature.pattern.size) {
                    signature to offsets.single()
                } else {
                    null
                }
            }
            if (replacements.isEmpty()) {
                val missing = matches.filterValues { it.isEmpty() }.keys.joinToString { it.name }
                val ambiguous = matches.filterValues { it.size > 1 }.keys.joinToString { it.name }
                val reason = buildString {
                    append("no validated targets changed")
                    if (missing.isNotEmpty()) append("; missing: $missing")
                    if (ambiguous.isNotEmpty()) append("; ambiguous: $ambiguous")
                }
                results += NativePhaseResult(abi, NativeStatus.SKIPPED, reason)
                continue
            }

            val occupied = mutableSetOf<Int>()
            if (replacements.any { (signature, offset) ->
                    (offset until offset + signature.replacement.size).any { !occupied.add(it) }
                }) {
                results += NativePhaseResult(abi, NativeStatus.SKIPPED, "overlapping native replacements")
                continue
            }
            for ((signature, offset) in replacements) {
                signature.replacement.indices.forEach { index -> bytes[offset + index] = signature.replacement[index] }
                logger.info("Emulate InApp native: $abi patched ${signature.name}")
            }
            file.writeBytes(bytes)
            val skipped = arm64Signatures.size - replacements.size
            val missing = matches.filterValues { it.isEmpty() }.keys.joinToString { it.name }
            val ambiguous = matches.filterValues { it.size > 1 }.keys.joinToString { it.name }
            val details = buildString {
                append("${replacements.size}/${arm64Signatures.size} targets patched")
                if (skipped > 0) append("; $skipped skipped")
                if (missing.isNotEmpty()) append("; missing: $missing")
                if (ambiguous.isNotEmpty()) append("; ambiguous: $ambiguous")
            }
            results += NativePhaseResult(abi, NativeStatus.PATCHED, details)
        } catch (e: Exception) {
            results += NativePhaseResult(abi, NativeStatus.SKIPPED, "failed closed: ${e.message}")
        }
    }
    return results
}
