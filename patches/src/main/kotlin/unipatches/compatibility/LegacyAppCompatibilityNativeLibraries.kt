package unipatches.compatibility

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.CallSiteReference
import com.android.tools.smali.dexlib2.iface.reference.MethodHandleReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.MethodHandleEncodedValue
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class ElfNativeMetadata(
    val bits: Int,
    val littleEndian: Boolean,
    val machine: Int,
    val soname: String?,
    val hasTextRelocations: Boolean,
    val neededLibraries: List<String>,
    val dependencyScanComplete: Boolean,
    val mayUseDynamicLoader: Boolean?,
)

internal enum class NativeLibraryReachability { REACHABLE, PROVEN_UNREACHABLE, UNKNOWN }

internal data class NativeLoadAnalysis(
    val libraryNames: Set<String>,
    val uncertainty: List<String>,
    val callSites: Map<String, List<String>> = emptyMap(),
)

internal data class NativeLibraryInspection(
    val path: String,
    val metadata: ElfNativeMetadata?,
    val error: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val destination: String? = null,
    val collision: Boolean? = null,
    val proxyPairPreserved: Boolean = false,
    val reachability: NativeLibraryReachability = NativeLibraryReachability.UNKNOWN,
    val reachabilityReason: String? = null,
) {
    val abi: String get() = path.split('/').dropLast(1).lastOrNull().orEmpty()
}

private data class ElfProgramHeader(
    val type: Long,
    val offset: Long,
    val virtualAddress: Long,
    val fileSize: Long,
)

private const val ELF_MAGIC = "\u007fELF"
private const val PT_LOAD = 1L
private const val PT_DYNAMIC = 2L
private const val DT_NULL = 0L
private const val DT_NEEDED = 1L
private const val DT_STRTAB = 5L
private const val DT_STRSZ = 10L
private const val DT_SONAME = 14L
private const val DT_TEXTREL = 22L
private const val DT_FLAGS = 30L
private const val DF_TEXTREL = 0x4L
private const val MAX_PROGRAM_HEADERS = 1024
private const val MAX_DYNAMIC_BYTES = 1 shl 20
private const val MAX_STRING_BYTES = 512
private const val MAX_NATIVE_LOADER_SCAN_BYTES = 64L * 1024 * 1024
internal const val MAX_DEX_SCAN_ENTRIES = 64
private const val MAX_DEX_SCAN_BYTES = 128L * 1024 * 1024
private const val MAX_DEX_METHOD_INSTRUCTIONS = 100_000
private const val MAX_NATIVE_LOAD_NAMES = 512
private const val MAX_NATIVE_LOAD_REASONS = 32

internal fun isMatchingProxyNativeLibraryPair(
    existingProxy: ElfNativeMetadata,
    obbLibrary: ElfNativeMetadata,
    fileName: String,
): Boolean {
    if (!fileName.startsWith("lib") || !fileName.endsWith(".so")) return false
    val proxySoname = "${fileName.removeSuffix(".so")}_proxy.so"
    return existingProxy.soname == proxySoname && obbLibrary.soname == fileName &&
        existingProxy.bits == obbLibrary.bits && existingProxy.littleEndian == obbLibrary.littleEndian &&
        existingProxy.machine == obbLibrary.machine
}

internal fun isVerifiedProxyAssetRoute(
    existingProxy: ElfNativeMetadata,
    obbLibrary: ElfNativeMetadata,
    proxyFile: File,
    obbEntryName: String,
): Boolean = existingProxy.mayUseDynamicLoader == true &&
    isMatchingProxyNativeLibraryPair(existingProxy, obbLibrary, obbEntryName.substringAfterLast('/')) &&
    proxyReferencesAssetLibrary(proxyFile, obbEntryName)

internal fun proxyReferencesAssetLibrary(proxyFile: File, obbEntryName: String): Boolean {
    if (proxyFile.length() !in 1..MAX_NATIVE_LOADER_SCAN_BYTES) return false
    val marker = obbEntryName.toByteArray(Charsets.US_ASCII)
    if (marker.isEmpty() || marker.size > MAX_STRING_BYTES) return false
    val prefix = IntArray(marker.size)
    var matched = 0
    for (index in 1 until marker.size) {
        while (matched > 0 && marker[index] != marker[matched]) matched = prefix[matched - 1]
        if (marker[index] == marker[matched]) matched++
        prefix[index] = matched
    }
    matched = 0
    FileInputStream(proxyFile).buffered().use { input ->
        while (true) {
            val value = input.read()
            if (value < 0) break
            val byte = value.toByte()
            while (matched > 0 && byte != marker[matched]) matched = prefix[matched - 1]
            if (byte == marker[matched]) matched++
            if (matched == marker.size) return true
        }
    }
    return false
}

internal fun inspectDexNativeLoads(dexFiles: List<Pair<String, File>>): NativeLoadAnalysis {
    val libraryNames = linkedSetOf<String>()
    val loadCallSites = linkedMapOf<String, MutableSet<String>>()
    var hasNativeMethods = false
    val classParents = mutableMapOf<String, String?>()
    val uncertainty = linkedSetOf<String>()
    fun uncertain(reason: String) {
        if (uncertainty.size < MAX_NATIVE_LOAD_REASONS) uncertainty += reason
        else if ("additional native-load analysis findings omitted" !in uncertainty) uncertainty += "additional native-load analysis findings omitted"
    }
    if (dexFiles.isEmpty()) return NativeLoadAnalysis(emptySet(), listOf("APK has no DEX entries"))
    if (dexFiles.size > MAX_DEX_SCAN_ENTRIES || dexFiles.sumOf { it.second.length() } > MAX_DEX_SCAN_BYTES) {
        return NativeLoadAnalysis(emptySet(), listOf("DEX scan exceeds bounded size limits"))
    }
    for ((entryName, file) in dexFiles) {
        val dex = try {
            DexFileFactory.loadDexFile(file, Opcodes.getDefault())
        } catch (error: Exception) {
            uncertain("cannot inspect $entryName")
            continue
        }
        val classes = dex.classes.toList()
        classes.forEach { classParents[it.type] = it.superclass }
        for (classDef in classes) {
            for (method in classDef.methods) {
                if ((method.accessFlags and 0x100) != 0) hasNativeMethods = true
                if (method.name == "findLibrary" && method.parameterTypes == listOf("Ljava/lang/String;") && method.returnType == "Ljava/lang/String;") {
                    uncertain("custom ClassLoader.findLibrary in ${classDef.type}")
                }
                val implementation = method.implementation ?: continue
                val instructions = implementation.instructions.take(MAX_DEX_METHOD_INSTRUCTIONS + 1).toList()
                if (instructions.size > MAX_DEX_METHOD_INSTRUCTIONS) {
                    uncertain("method too large to analyze safely: ${classDef.type}->${method.name}")
                    continue
                }
                instructions.forEachIndexed { index, instruction ->
                    val rawReference = (instruction as? ReferenceInstruction)?.reference ?: return@forEachIndexed
                    if (instruction.opcode.name.startsWith("INVOKE_CUSTOM")) {
                        val callSite = rawReference as? CallSiteReference
                        if (callSite == null || callSiteMayLoadNativeLibrary(callSite)) {
                            uncertain("unresolved invoke-custom native-load path in ${classDef.type}->${method.name}")
                        }
                        return@forEachIndexed
                    }
                    val reference = rawReference as? MethodReference ?: return@forEachIndexed
                    val owner = reference.definingClass
                    val isSystem = owner == "Ljava/lang/System;"
                    val isRuntime = owner == "Ljava/lang/Runtime;"
                    val libraryPathLoader = owner in setOf(
                        "Ljava/lang/ClassLoader;",
                        "Ldalvik/system/BaseDexClassLoader;",
                        "Ldalvik/system/DexClassLoader;",
                        "Ldalvik/system/PathClassLoader;",
                        "Ldalvik/system/DelegateLastClassLoader;",
                    )
                    if (libraryPathLoader &&
                        (reference.name.contains("load", ignoreCase = true) || reference.name == "findLibrary" ||
                            owner != "Ljava/lang/ClassLoader;" && reference.name == "<init>")
                    ) {
                        uncertain("custom class-loader call ${reference.definingClass}->${reference.name}")
                        return@forEachIndexed
                    }
                    if ((owner == "Ljava/lang/reflect/Method;" && reference.name == "invoke") ||
                        (owner == "Ljava/lang/invoke/MethodHandle;" && reference.name.startsWith("invoke"))
                    ) {
                        uncertain("reflective or method-handle call in ${classDef.type}->${method.name}")
                    }
                    if (!isSystem && !isRuntime) return@forEachIndexed
                    if (!reference.name.startsWith("load")) return@forEachIndexed
                    if (reference.name !in setOf("loadLibrary", "load") ||
                        reference.parameterTypes != listOf("Ljava/lang/String;") || reference.returnType != "V"
                    ) {
                        uncertain("unsupported ${owner}->${reference.name} call shape")
                        return@forEachIndexed
                    }
                    val registers = invokeRegisters(instruction)
                    val stringRegister = registers?.getOrNull(if (isRuntime) 1 else 0)
                    val rawName = stringRegister?.let { stringConstantBefore(instructions, index, it) }
                    if (reference.name == "load") {
                        uncertain("direct ${owner}->load file path is unresolved in ${classDef.type}->${method.name}")
                        return@forEachIndexed
                    }
                    val libraryName = rawName?.let(::nativeLibraryNameFromLoad)
                    if (libraryName == null) {
                        uncertain("dynamic or unresolved ${owner}->${reference.name} argument in ${classDef.type}->${method.name}")
                    } else if (libraryName in libraryNames || libraryNames.size < MAX_NATIVE_LOAD_NAMES) {
                        libraryNames += libraryName
                        val sites = loadCallSites.getOrPut(libraryName) { linkedSetOf() }
                        if (sites.size < 3) sites += "${classDef.type}->${method.name} ${owner}->${reference.name}"
                    } else {
                        uncertain("native load-name limit exceeded")
                    }
                }
            }
        }
    }
    classParents.keys.filter { inheritsNativeActivity(it, classParents) }.take(32)
        .forEach { uncertain("NativeActivity subclass $it") }
    if (hasNativeMethods) {
        uncertain("native methods exist; JNI registration-to-library mapping is unverified")
    }
    return NativeLoadAnalysis(libraryNames, uncertainty.toList(), loadCallSites.mapValues { it.value.toList() })
}

private fun callSiteMayLoadNativeLibrary(callSite: CallSiteReference): Boolean {
    val bootstrap = callSite.methodHandle.memberReference as? MethodReference
    val bootstrapOwner = bootstrap?.definingClass
    if (bootstrapOwner !in setOf("Ljava/lang/invoke/LambdaMetafactory;", "Ljava/lang/invoke/StringConcatFactory;")) return true
    val handles = buildList {
        add(callSite.methodHandle)
        callSite.extraArguments.filterIsInstance<MethodHandleEncodedValue>().map { it.value }.forEach(::add)
    }
    return handles.any { handle ->
        val target = handle.memberReference as? MethodReference ?: return@any false
        target.definingClass in setOf("Ljava/lang/System;", "Ljava/lang/Runtime;") && target.name.startsWith("load")
    }
}

private fun inheritsNativeActivity(classType: String, parents: Map<String, String?>): Boolean {
    val visited = mutableSetOf<String>()
    var current = classType
    while (visited.add(current)) {
        val parent = parents[current] ?: return false
        if (parent == "Landroid/app/NativeActivity;") return true
        current = parent
    }
    return false
}

private fun nativeLibraryNameFromLoad(value: String): String? {
    val name = value.trim()
    if (name.isEmpty() || name.startsWith("lib") || name.endsWith(".so") || name.contains('/') || name.contains('\\')) return null
    val fileName = "lib$name.so"
    return fileName.takeIf { Regex("lib[A-Za-z0-9_.+-]+\\.so").matches(it) }
}

private fun invokeRegisters(instruction: Instruction): List<Int>? = when (instruction) {
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    else -> null
}

private fun stringConstantBefore(instructions: List<Instruction>, callIndex: Int, argumentRegister: Int): String? {
    var register = argumentRegister
    var steps = 0
    for (index in callIndex - 1 downTo maxOf(0, callIndex - 32)) {
        if (++steps > 32) return null
        val instruction = instructions[index]
        if (instruction.opcode.name.startsWith("IF_") || instruction.opcode.name.startsWith("GOTO") ||
            instruction.opcode.name.startsWith("RETURN") || instruction.opcode == Opcode.THROW ||
            instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH
        ) return null
        if (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) {
            val constant = instruction as? OneRegisterInstruction
            if (constant?.registerA == register) return (instruction as? ReferenceInstruction)?.reference
                .let { it as? StringReference }?.string
        }
        if (instruction is TwoRegisterInstruction && instruction.opcode.name.startsWith("MOVE_OBJECT") && instruction.registerA == register) {
            register = instruction.registerB
            continue
        }
        if (instructionTouchesRegister(instruction, register)) return null
    }
    return null
}

private fun instructionTouchesRegister(instruction: Instruction, register: Int): Boolean {
    val registers = when (instruction) {
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
        is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
        is ThreeRegisterInstruction -> listOf(instruction.registerA, instruction.registerB, instruction.registerC)
        is TwoRegisterInstruction -> listOf(instruction.registerA, instruction.registerB)
        is OneRegisterInstruction -> listOf(instruction.registerA)
        else -> emptyList()
    }
    return register in registers || (instruction.opcode.name.contains("WIDE") && registers.any { it + 1 == register })
}

internal fun inspectElf(bytes: ByteArray): ElfNativeMetadata =
    inspectElf(bytes.size.toLong()) { offset, size ->
        require(offset >= 0 && size >= 0 && offset <= bytes.size.toLong() - size) { "ELF read outside fixture" }
        bytes.copyOfRange(offset.toInt(), offset.toInt() + size)
    }

internal fun inspectElfFile(file: File): ElfNativeMetadata = RandomAccessFile(file, "r").use { input ->
    inspectElf(input.length()) { offset, size ->
        val bytes = ByteArray(size)
        input.seek(offset)
        input.readFully(bytes)
        bytes
    }
}

internal fun inspectElfZipEntry(zip: ZipFile, entry: ZipEntry): ElfNativeMetadata =
    withNativeEntryFile(zip, entry, ::inspectElfFile)

private fun inspectElf(size: Long, readAt: (Long, Int) -> ByteArray): ElfNativeMetadata {
    require(size >= 52) { "truncated ELF header" }
    val ident = readAt(0, 16)
    require(ident.copyOfRange(0, 4).toString(Charsets.ISO_8859_1) == ELF_MAGIC) { "invalid ELF magic" }
    val elfClass = ident[4].toInt() and 0xff
    val dataEncoding = ident[5].toInt() and 0xff
    require(elfClass == 1 || elfClass == 2) { "unsupported ELF class $elfClass" }
    require(dataEncoding == 1 || dataEncoding == 2) { "unsupported ELF byte order $dataEncoding" }
    val bits = if (elfClass == 1) 32 else 64
    val littleEndian = dataEncoding == 1
    val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    val headerSize = if (bits == 32) 52 else 64
    require(size >= headerSize) { "truncated ELF${bits} header" }
    val header = ByteBuffer.wrap(readAt(0, headerSize)).order(order)
    require(unsignedShort(header.getShort(16)) == 3) { "ELF file is not a shared object" }
    val machine = unsignedShort(header.getShort(18))
    val programOffset = if (bits == 32) unsignedInt(header.getInt(28)) else unsignedLong(header.getLong(32))
    val elfHeaderSize = unsignedShort(header.getShort(if (bits == 32) 40 else 52))
    val programEntrySize = unsignedShort(header.getShort(if (bits == 32) 42 else 54))
    val programCount = unsignedShort(header.getShort(if (bits == 32) 44 else 56))
    val minimumEntrySize = if (bits == 32) 32 else 56
    require(elfHeaderSize >= headerSize && programCount in 1..MAX_PROGRAM_HEADERS) { "invalid ELF program-header table" }
    require(programEntrySize in minimumEntrySize..4096) { "invalid ELF program-header entry size" }
    val tableSize = programEntrySize.toLong() * programCount
    require(tableSize <= MAX_DYNAMIC_BYTES) { "oversized ELF program-header table" }
    requireRange(programOffset, tableSize, size, "program-header table")
    val table = ByteBuffer.wrap(readAt(programOffset, tableSize.toInt())).order(order)
    val headers = (0 until programCount).map { index ->
        val base = index * programEntrySize
        val type = unsignedInt(table.getInt(base))
        val offset = if (bits == 32) unsignedInt(table.getInt(base + 4)) else unsignedLong(table.getLong(base + 8))
        val virtualAddress = if (bits == 32) unsignedInt(table.getInt(base + 8)) else unsignedLong(table.getLong(base + 16))
        val fileSize = if (bits == 32) unsignedInt(table.getInt(base + 16)) else unsignedLong(table.getLong(base + 32))
        if (type == PT_LOAD || type == PT_DYNAMIC) requireRange(offset, fileSize, size, "program segment")
        ElfProgramHeader(type, offset, virtualAddress, fileSize)
    }
    val dynamicHeaders = headers.filter { it.type == PT_DYNAMIC }
    require(dynamicHeaders.size == 1) { "ELF has missing or multiple PT_DYNAMIC segments" }
    val dynamic = dynamicHeaders.single()
    val entrySize = if (bits == 32) 8 else 16
    require(dynamic.fileSize in entrySize.toLong()..MAX_DYNAMIC_BYTES.toLong() && dynamic.fileSize % entrySize == 0L) {
        "invalid or oversized ELF dynamic segment"
    }
    val dynamicBytes = ByteBuffer.wrap(readAt(dynamic.offset, dynamic.fileSize.toInt())).order(order)
    var textRelocations = false
    var sonameIndex: Long? = null
    val neededIndexes = mutableListOf<Long>()
    var stringTableAddress: Long? = null
    var stringTableSize: Long? = null
    var terminated = false
    for (offset in 0 until dynamicBytes.capacity() step entrySize) {
        val tag = if (bits == 32) unsignedInt(dynamicBytes.getInt(offset)) else unsignedLong(dynamicBytes.getLong(offset))
        val value = if (bits == 32) unsignedInt(dynamicBytes.getInt(offset + 4)) else unsignedLong(dynamicBytes.getLong(offset + 8))
        when (tag) {
            DT_NULL -> { terminated = true; break }
            DT_NEEDED -> neededIndexes += value
            DT_TEXTREL -> textRelocations = true
            DT_FLAGS -> if (value and DF_TEXTREL != 0L) textRelocations = true
            DT_SONAME -> sonameIndex = value
            DT_STRTAB -> stringTableAddress = value
            DT_STRSZ -> stringTableSize = value
        }
    }
    require(terminated) { "unterminated ELF dynamic segment" }
    val stringsSize = stringTableSize
    val stringTable = if (stringTableAddress != null && stringsSize != null && stringsSize in 1..MAX_DYNAMIC_BYTES.toLong()) {
        val load = headers.firstOrNull { segment ->
            segment.type == PT_LOAD && stringTableAddress >= segment.virtualAddress &&
                stringTableAddress - segment.virtualAddress < segment.fileSize
        }
        if (load == null) null else {
            val stringDelta = stringTableAddress - load.virtualAddress
            if (stringsSize > load.fileSize - stringDelta) null else {
                val tableOffset = load.offset + stringDelta
                if (tableOffset < load.offset) null else {
                    requireRange(tableOffset, stringsSize, size, "string table")
                    readAt(tableOffset, stringsSize.toInt())
                }
            }
        }
    } else null
    fun dynamicString(index: Long): String? {
        val table = stringTable ?: return null
        if (index !in 0 until table.size.toLong()) return null
        val start = index.toInt()
        val terminator = (start until minOf(table.size, start + MAX_STRING_BYTES)).firstOrNull { table[it] == 0.toByte() }
            ?: return null
        if (terminator == start) return null
        return table.copyOfRange(start, terminator).toString(Charsets.UTF_8)
    }
    val soname = sonameIndex?.let(::dynamicString)
    val neededLibraries = neededIndexes.mapNotNull(::dynamicString)
    val dependencyScanComplete = neededLibraries.size == neededIndexes.size
    val mayUseDynamicLoader = stringTable?.let { table ->
        if (listOf("dlopen", "android_dlopen_ext", "dlsym").any { hasNullTerminatedString(table, it) }) true else null
    }
    return ElfNativeMetadata(
        bits,
        littleEndian,
        machine,
        soname,
        textRelocations,
        neededLibraries,
        dependencyScanComplete,
        mayUseDynamicLoader,
    )
}

internal fun inspectNativeLibrary(
    path: String,
    file: File,
    destination: String = path,
    collision: Boolean? = false,
): NativeLibraryInspection {
    val size = file.length()
    val sha256 = try {
        expansionSha256(file)
    } catch (error: Exception) {
        return NativeLibraryInspection(path, null, safeNativeError("SHA-256 failed: ${error.message}"), size, destination = destination, collision = collision)
    }
    return try {
        NativeLibraryInspection(path, inspectElfFile(file), sizeBytes = size, sha256 = sha256, destination = destination, collision = collision)
    } catch (error: Exception) {
        NativeLibraryInspection(path, null, safeNativeError(error.message), size, sha256, destination, collision)
    }
}

internal fun inspectNativeLibrary(
    zip: ZipFile,
    entry: ZipEntry,
    destination: String? = expansionNativeDestination(entry.name),
    collision: Boolean? = null,
): NativeLibraryInspection = try {
    withNativeEntryFile(zip, entry) { file ->
        NativeLibraryInspection(
            entry.name,
            inspectElfFile(file),
            sizeBytes = file.length(),
            sha256 = expansionSha256(file),
            destination = destination,
            collision = collision,
        )
    }
} catch (error: Exception) {
    NativeLibraryInspection(entry.name, null, safeNativeError(error.message), entry.size.takeIf { it >= 0 }, destination = destination, collision = collision)
}

private fun <T> withNativeEntryFile(zip: ZipFile, entry: ZipEntry, action: (File) -> T): T {
    require(entry.size >= 0) { "ELF ZIP entry has unknown length" }
    val temporary = Files.createTempFile("unipatch-native-scan-", ".so").toFile()
    try {
        var copied = 0L
        zip.getInputStream(entry).use { input ->
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(read.toLong() <= entry.size - copied) { "ELF ZIP entry exceeds its declared length" }
                    copied += read
                    output.write(buffer, 0, read)
                }
            }
        }
        require(copied == entry.size) { "ELF ZIP entry length does not match its declaration" }
        return action(temporary)
    } finally {
        if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit()
    }
}

internal fun nativeTargetSdkForInspection(
    sourceTargetSdk: Int?,
    requestedTargetSdk: Int?,
    spoofEnabled: Boolean,
    diagnosticsOnly: Boolean,
): Int? {
    if (!spoofEnabled || diagnosticsOnly) return sourceTargetSdk
    val selected = requestedTargetSdk ?: 24
    return selected.takeIf { it in MIN_SUPPORTED_TARGET_SDK..MAX_SUPPORTED_TARGET_SDK } ?: sourceTargetSdk
}

internal fun plannedNativeTargetSdk(spoofEnabled: Boolean, requestedTargetSdk: Int?): Int? {
    if (!spoofEnabled) return null
    val selected = requestedTargetSdk ?: 24
    return selected.takeIf { it in MIN_SUPPORTED_TARGET_SDK..MAX_SUPPORTED_TARGET_SDK }
}

internal fun classifyNativeLibraryReachability(
    inspections: List<NativeLibraryInspection>,
    dexLoadAnalysis: NativeLoadAnalysis,
    manifestNativeActivity: Boolean,
): List<NativeLibraryInspection> = inspections.groupBy { it.abi }.values.flatMap { libraries ->
    val reachable = linkedMapOf<String, String>()
    val uncertainty = dexLoadAnalysis.uncertainty.toMutableSet()
    if (manifestNativeActivity) uncertainty += "manifest declares NativeActivity native loading"
    val queue = ArrayDeque<Pair<NativeLibraryInspection, String>>()
    fun markReachable(library: NativeLibraryInspection, reason: String) {
        if (reachable.putIfAbsent(library.path, reason) == null) queue += library to reason
    }

    fun candidatesFor(name: String): List<NativeLibraryInspection> = libraries.filter { library ->
        library.path.substringAfterLast('/') == name || library.metadata?.soname == name
    }

    dexLoadAnalysis.libraryNames.forEach { name ->
        val candidates = libraries.filter { library ->
            library.path.startsWith("lib/") && library.path.substringAfterLast('/') == name
        }
        if (candidates.isEmpty()) uncertainty += "unresolved DEX load target $name"
        if (candidates.size > 1) uncertainty += "ambiguous DEX load target $name"
        val callSite = dexLoadAnalysis.callSites[name].orEmpty().take(3).joinToString(",")
        candidates.forEach { markReachable(it, "DEX load root $name${if (callSite.isEmpty()) "" else " at $callSite"}") }
    }

    while (queue.isNotEmpty()) {
        val library = queue.removeFirst().first
        val metadata = library.metadata
        if (metadata == null) {
            uncertainty += "ELF metadata unavailable for ${safeNativeText(library.path)}"
            continue
        }
        if (!metadata.dependencyScanComplete) uncertainty += "DT_NEEDED scan incomplete for ${safeNativeText(library.path)}"
        when (metadata.mayUseDynamicLoader) {
            null -> uncertainty += "dynamic-loader scan incomplete for ${safeNativeText(library.path)}"
            true -> uncertainty += "reachable library may load additional native libraries dynamically: ${safeNativeText(library.path)}"
            false -> Unit
        }
        val dependencies = metadata.neededLibraries
        dependencies.forEach { dependency ->
            val candidates = candidatesFor(dependency)
            if (candidates.size > 1) uncertainty += "ambiguous DT_NEEDED target $dependency"
            candidates.forEach { markReachable(it, "DT_NEEDED ${library.path} -> $dependency") }
        }
        if (library.path.startsWith("lib/")) {
            val name = library.path.substringAfterLast('/')
            libraries.filter { it.proxyPairPreserved && it.path.startsWith("assets/libs/") && it.path.substringAfterLast('/') == name }
                .forEach { markReachable(it, "verified proxy ${library.path} -> OBB asset ${it.path}") }
        }
    }

    libraries.map { library ->
        val state = when {
            library.path in reachable -> NativeLibraryReachability.REACHABLE
            uncertainty.isEmpty() -> NativeLibraryReachability.PROVEN_UNREACHABLE
            else -> NativeLibraryReachability.UNKNOWN
        }
        val reason = when (state) {
            NativeLibraryReachability.REACHABLE -> (reachable[library.path] ?: "load path identified") +
                if (uncertainty.isEmpty()) "" else "; additional load paths are unresolved: ${uncertainty.take(3).joinToString("; ")}"
            NativeLibraryReachability.PROVEN_UNREACHABLE -> "complete DEX/ELF analysis found no load path"
            NativeLibraryReachability.UNKNOWN -> uncertainty.take(3).joinToString("; ")
        }
        library.copy(reachability = state, reachabilityReason = reason)
    }
}

internal fun nativeLibraryCompatibilityIssues(
    inspections: List<NativeLibraryInspection>,
    targetSdk: Int?,
): List<String> = inspections.mapNotNull { inspection ->
    if (inspection.reachability == NativeLibraryReachability.PROVEN_UNREACHABLE) return@mapNotNull null
    val details = nativeLibraryDetails(inspection, targetSdk)
    val metadata = inspection.metadata
    when {
        metadata == null && (targetSdk == null || targetSdk >= 23) -> "$details ELF inspection failed: ${safeNativeText(inspection.error ?: "unknown format")}"
        metadata != null && metadata.hasTextRelocations && (targetSdk == null || targetSdk >= 23) ->
            "$details SONAME=${safeNativeText(metadata.soname ?: "unknown")} DT_TEXTREL is incompatible with targetSdk ${targetSdk ?: "unknown"} on Android 6.0/API 23 and newer."
        else -> null
    }
}

internal fun formatNativeLibraryInspection(inspection: NativeLibraryInspection, targetSdk: Int?): String {
    val details = "Legacy compatibility native library: ${nativeLibraryDetails(inspection, targetSdk)}"
    val metadata = inspection.metadata ?: return "$details ELF=unknown reason=${safeNativeText(inspection.error ?: "unknown format")}."
    return "$details bits=${metadata.bits} SONAME=${safeNativeText(metadata.soname ?: "unknown")} DT_TEXTREL=${metadata.hasTextRelocations}."
}

private fun nativeDependencyDetails(metadata: ElfNativeMetadata?): String {
    if (metadata == null) return "DT_NEEDED=unknown dependencyScanComplete=false mayUseDynamicLoader=unknown"
    val shown = metadata.neededLibraries.take(8).joinToString(",") { safeNativeText(it).take(100) }.ifEmpty { "none" }
    val more = if (metadata.neededLibraries.size > 8) ",+${metadata.neededLibraries.size - 8}" else ""
    return "DT_NEEDED=$shown$more dependencyScanComplete=${metadata.dependencyScanComplete} mayUseDynamicLoader=${metadata.mayUseDynamicLoader ?: "unknown"}"
}

private fun nativeLibraryDetails(inspection: NativeLibraryInspection, targetSdk: Int?): String {
    val collision = if (inspection.proxyPairPreserved) "resolved-proxy-pair" else when (inspection.collision) {
        true -> "unresolved"
        false -> "no"
        null -> "not-evaluated"
    }
    return buildString {
        append("source=").append(safeNativeText(inspection.path))
        append(" destination=").append(safeNativeText(inspection.destination ?: "unknown"))
        append(" abi=").append(safeNativeText(inspection.abi))
        append(" sizeBytes=").append(inspection.sizeBytes ?: "unknown")
        append(" sha256=").append(safeNativeText(inspection.sha256 ?: "unknown"))
        append(" relocationDestinationCollision=").append(collision)
        append(" loadability=").append(inspection.reachability.name.lowercase())
        append(" loadabilityReason=").append(safeNativeText(inspection.reachabilityReason ?: "not-analyzed"))
        append(' ').append(nativeDependencyDetails(inspection.metadata))
        append(" targetSdk=").append(targetSdk ?: "unknown")
    }
}

private fun hasNullTerminatedString(table: ByteArray, value: String): Boolean {
    val target = value.toByteArray(Charsets.US_ASCII)
    var start = 0
    table.forEachIndexed { index, byte ->
        if (byte == 0.toByte()) {
            if (index - start == target.size && target.indices.all { table[start + it] == target[it] }) return true
            start = index + 1
        }
    }
    return false
}

private fun requireRange(offset: Long, length: Long, total: Long, label: String) {
    require(offset >= 0 && length >= 0 && offset <= total && length <= total - offset) { "ELF $label is outside file bounds" }
}

private fun unsignedInt(value: Int): Long = Integer.toUnsignedLong(value)
private fun unsignedShort(value: Short): Int = value.toInt() and 0xffff
private fun unsignedLong(value: Long): Long {
    require(value >= 0) { "ELF uses an unsupported unsigned 64-bit offset" }
    return value
}

private fun safeNativeError(message: String?): String = safeNativeText(message.orEmpty()).take(160).ifEmpty { "invalid ELF" }

private fun safeNativeText(value: String): String = value.map { if (Character.isISOControl(it)) ' ' else it }.joinToString("").take(300)
