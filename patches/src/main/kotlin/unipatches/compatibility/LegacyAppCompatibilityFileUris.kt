package unipatches.compatibility

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import helpers.bytecode.cloneMutable
import helpers.manifest.NS_ANDROID
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory

internal const val LEGACY_FILE_PROVIDER_CLASS = "unipatch.compatcore.LegacyCompatFileProvider"
internal const val LEGACY_FILE_PROVIDER_PATHS_RESOURCE = "legacy_compat_file_paths"
private val legacyFileProviderPathsEntryPattern = Regex("res/xml(?:-[^/]+)?/${Regex.escape(LEGACY_FILE_PROVIDER_PATHS_RESOURCE)}\\.xml")
internal fun legacyFileProviderPathsEntries(entries: List<String>): List<String> =
    entries.filter(legacyFileProviderPathsEntryPattern::matches).sorted()

private const val FILE_PROVIDER_META_DATA = "android.support.FILE_PROVIDER_PATHS"
private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".legacycompat.fileprovider"
private val manifestPackagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

internal fun legacyFileProviderPathsDocument(): Document {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
    val paths = document.createElement("paths")
    document.appendChild(paths)
    paths.appendChild(document.createElement("files-path").apply {
        setAttribute("name", "files")
        setAttribute("path", ".")
    })
    paths.appendChild(document.createElement("cache-path").apply {
        setAttribute("name", "cache")
        setAttribute("path", ".")
    })
    paths.appendChild(document.createElement("external-files-path").apply {
        setAttribute("name", "external-files")
        setAttribute("path", ".")
    })
    paths.appendChild(document.createElement("external-cache-path").apply {
        setAttribute("name", "external-cache")
        setAttribute("path", ".")
    })
    return document
}

internal fun ensureLegacyFileProvider(
    manifest: Document,
    pathsFile: File,
    publicXmlFile: File,
    pathsResourceExists: Boolean,
    additionalExistingPathsFiles: List<File> = emptyList(),
): Int {
    val root = manifest.documentElement ?: error("Legacy compatibility: cannot repair file URI sharing; manifest root is missing.")
    val packageName = root.getAttribute("package")
    require(manifestPackagePattern.matches(packageName)) {
        "Legacy compatibility: cannot configure file URI provider because manifest package name is missing or invalid."
    }
    val application = root.childElements().firstOrNull { it.manifestElementName() == "application" }
        ?: error("Legacy compatibility: cannot configure file URI provider; manifest application is missing.")
    val expectedAuthority = "$packageName$FILE_PROVIDER_AUTHORITY_SUFFIX"
    val providers = application.childElements().filter { it.manifestElementName() == "provider" }
    val matchingAuthority = providers.filter { provider ->
        provider.androidAttribute("authorities").split(';').any { it.trim() == expectedAuthority }
    }
    val exactProvider = providers.singleOrNull { it.androidAttribute("name") == LEGACY_FILE_PROVIDER_CLASS }
    require(matchingAuthority.all { it === exactProvider } && (matchingAuthority.isEmpty() || exactProvider != null)) {
        "Legacy compatibility: file-provider authority '$expectedAuthority' is already used by another or duplicate provider; resolve it manually."
    }
    require(providers.none { it.androidAttribute("name") == LEGACY_FILE_PROVIDER_CLASS && it !== exactProvider }) {
        "Legacy compatibility: duplicate LegacyCompatFileProvider declarations cannot be merged safely."
    }

    if (exactProvider != null) {
        verifyProviderComponent(exactProvider, expectedAuthority, pathsFile, application)
        require(pathsResourceExists && fileProviderPathsMatch(pathsFile) && additionalExistingPathsFiles.all(::fileProviderPathsMatch)) {
            "Legacy compatibility: existing LegacyCompatFileProvider paths resource is missing or not the verified private files/cache configuration."
        }
        ensurePublicXmlResourceId(publicXmlFile, "xml", LEGACY_FILE_PROVIDER_PATHS_RESOURCE, resourceAlreadyExists = true)
        return 0
    }

    require((!pathsResourceExists || fileProviderPathsMatch(pathsFile)) && additionalExistingPathsFiles.all(::fileProviderPathsMatch)) {
        "Legacy compatibility: existing file-provider paths resource conflicts with the required private files/cache roots; refusing to overwrite it."
    }
    require(pathsResourceExists || !pathsFile.exists()) {
        "Legacy compatibility: a staged provider-path resource exists outside the input APK; refusing to overwrite another patch's output."
    }
    var changed = 0
    if (!pathsResourceExists) {
        writeSecureXml(legacyFileProviderPathsDocument(), pathsFile)
        changed++
    }
    if (ensurePublicXmlResourceId(
            publicXmlFile,
            "xml",
            LEGACY_FILE_PROVIDER_PATHS_RESOURCE,
            resourceAlreadyExists = pathsResourceExists || additionalExistingPathsFiles.isNotEmpty(),
        )) changed++

    val provider = createManifestElement(manifest, root, "provider").apply {
        setAttributeNS(NS_ANDROID, "android:name", LEGACY_FILE_PROVIDER_CLASS)
        setAttributeNS(NS_ANDROID, "android:authorities", expectedAuthority)
        setAttributeNS(NS_ANDROID, "android:exported", "false")
        setAttributeNS(NS_ANDROID, "android:grantUriPermissions", "true")
        appendChild(createManifestElement(manifest, root, "meta-data").apply {
            setAttributeNS(NS_ANDROID, "android:name", FILE_PROVIDER_META_DATA)
            setAttributeNS(NS_ANDROID, "android:resource", "@xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE")
        })
    }
    application.appendChild(provider)
    verifyProviderComponent(provider, expectedAuthority, pathsFile, application)
    changed++
    return changed
}

internal fun verifyLegacyFileProvider(manifest: Document, pathsFile: File, additionalPathsFiles: List<File> = emptyList()) {
    val root = manifest.documentElement ?: error("Legacy compatibility: final manifest has no root while verifying file-provider setup.")
    val packageName = root.getAttribute("package")
    val application = root.childElements().firstOrNull { it.manifestElementName() == "application" }
        ?: error("Legacy compatibility: final manifest has no application while verifying file-provider setup.")
    val providers = application.childElements().filter { it.manifestElementName() == "provider" }
        .filter { it.androidAttribute("name") == LEGACY_FILE_PROVIDER_CLASS }
    check(providers.size == 1) { "Legacy compatibility: final manifest readback does not contain exactly one LegacyCompatFileProvider." }
    verifyProviderComponent(providers.single(), "$packageName$FILE_PROVIDER_AUTHORITY_SUFFIX", pathsFile, application)
    check(fileProviderPathsMatch(pathsFile) && additionalPathsFiles.all(::fileProviderPathsMatch)) {
        "Legacy compatibility: final provider paths XML is not the verified private files/cache configuration."
    }
}

private fun verifyProviderComponent(provider: Element, expectedAuthority: String, pathsFile: File, application: Element) {
    check(provider.androidAttribute("authorities") == expectedAuthority &&
        provider.androidAttribute("exported") == "false" &&
        provider.androidAttribute("grantUriPermissions") == "true" &&
        provider.androidAttribute("enabled") != "false" &&
        !provider.hasAndroidAttribute("permission") &&
        !provider.hasAndroidAttribute("readPermission") &&
        !provider.hasAndroidAttribute("writePermission") &&
        !application.hasAndroidAttribute("permission") &&
        !provider.hasAndroidAttribute("process") &&
        provider.androidAttribute("isolatedProcess") != "true" &&
        provider.androidAttribute("multiprocess") != "true") {
        "Legacy compatibility: LegacyCompatFileProvider must be enabled, private, grant read access only through URI grants, and use authority '$expectedAuthority'."
    }
    val metadata = provider.childElements().filter {
        it.manifestElementName() == "meta-data" && it.androidAttribute("name") == FILE_PROVIDER_META_DATA
    }
    check(metadata.size == 1 && metadata.single().androidAttribute("resource") == "@xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE" && pathsFile.isFile) {
        "Legacy compatibility: LegacyCompatFileProvider must reference verified @xml/$LEGACY_FILE_PROVIDER_PATHS_RESOURCE metadata."
    }
}

internal fun verifyLegacyFileProviderPaths(document: Document) {
    check(fileProviderPathsMatch(document)) {
        "Legacy compatibility: final file-provider paths XML does not match the verified app-private roots."
    }
}

private fun fileProviderPathsMatch(file: File): Boolean =
    runCatching { fileProviderPathsMatch(readSecureXml(file)) }.getOrDefault(false)

private fun fileProviderPathsMatch(document: Document): Boolean {
    val root = document.documentElement ?: return false
    if (root.manifestElementName() != "paths") return false
    val paths = root.childElements().map { it.manifestElementName() to (it.getAttribute("name") to it.getAttribute("path")) }
    return paths == listOf(
        "files-path" to ("files" to "."),
        "cache-path" to ("cache" to "."),
        "external-files-path" to ("external-files" to "."),
        "external-cache-path" to ("external-cache" to "."),
    )
}

private fun createManifestElement(document: Document, root: Element, name: String): Element {
    val qualifiedName = root.prefix?.takeIf(String::isNotEmpty)?.let { "$it:$name" } ?: name
    return if (root.namespaceURI.isNullOrEmpty()) document.createElement(qualifiedName)
    else document.createElementNS(root.namespaceURI, qualifiedName)
}

private fun Element.manifestElementName(): String = localName ?: tagName.substringAfter(':')
private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

internal data class LegacyFileUriSharingResult(
    var patchedCallSites: Int = 0,
    var alreadyPatchedCallSites: Int = 0,
    var skippedCallSites: Int = 0,
)

private const val ANDROID_URI = "Landroid/net/Uri;"
private const val ANDROID_INTENT = "Landroid/content/Intent;"
private const val LEGACY_COMPAT_RUNTIME = "Lunipatch/compatcore/LegacyCompatRuntime;"

private fun invokeRegisters(instruction: Instruction): List<Int>? = when (instruction) {
    is FiveRegisterInstruction -> (0 until instruction.registerCount).map {
        when (it) {
            0 -> instruction.registerC
            1 -> instruction.registerD
            2 -> instruction.registerE
            3 -> instruction.registerF
            else -> instruction.registerG
        }
    }
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    else -> null
}

private fun Instruction.methodReference(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.stringReference(): String? =
    (this as? ReferenceInstruction)?.reference.let { it as? StringReference }?.string

private fun stringRegisterBefore(
    instructions: List<Instruction>,
    beforeIndex: Int,
    register: Int,
): String? {
    for (index in beforeIndex - 1 downTo maxOf(0, beforeIndex - 12)) {
        val instruction = instructions[index]
        if (!instructionUsesRegister(instruction, register)) continue
        val definesString = instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO
        return if (definesString && (instruction as? OneRegisterInstruction)?.registerA == register) {
            instruction.stringReference()
        } else null
    }
    return null
}

internal data class ShareConsumer(val index: Int, val intentRegister: Int)

private fun instructionWritesRegister(instruction: Instruction, register: Int): Boolean = when (instruction) {
    is FiveRegisterInstruction, is RegisterRangeInstruction -> false
    is ThreeRegisterInstruction -> !instruction.opcode.name.startsWith("APUT") && register == instruction.registerA
    is TwoRegisterInstruction -> !instruction.opcode.name.startsWith("IF_") && !instruction.opcode.name.startsWith("IPUT") && register == instruction.registerA
    is OneRegisterInstruction -> when {
        instruction.opcode.name.startsWith("CONST") || instruction.opcode.name.startsWith("MOVE_RESULT") ||
            instruction.opcode.name == "MOVE_EXCEPTION" || instruction.opcode.name.startsWith("NEW_INSTANCE") ||
            instruction.opcode.name.startsWith("SGET") -> register == instruction.registerA
        instruction.opcode.name == "CHECK_CAST" -> register == instruction.registerA
        else -> false
    }
    else -> false
}

private fun instructionReadsRegister(instruction: Instruction, register: Int): Boolean = when (instruction) {
    is FiveRegisterInstruction -> register in listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
    is RegisterRangeInstruction -> register >= instruction.startRegister && register < instruction.startRegister + instruction.registerCount
    is ThreeRegisterInstruction -> if (instruction.opcode.name.startsWith("APUT")) {
        register == instruction.registerA || register == instruction.registerB || register == instruction.registerC
    } else register == instruction.registerB || register == instruction.registerC
    is TwoRegisterInstruction -> when {
        instruction.opcode.name.startsWith("MOVE") -> register == instruction.registerB
        instruction.opcode.name.startsWith("IF_") || instruction.opcode.name.startsWith("IPUT") ->
            register == instruction.registerA || register == instruction.registerB
        instruction.opcode.name.endsWith("_2ADDR") -> register == instruction.registerA || register == instruction.registerB
        else -> register == instruction.registerB
    }
    is OneRegisterInstruction -> when {
        instruction.opcode.name.startsWith("CONST") || instruction.opcode.name.startsWith("MOVE_RESULT") ||
            instruction.opcode.name == "MOVE_EXCEPTION" || instruction.opcode.name.startsWith("NEW_INSTANCE") ||
            instruction.opcode.name.startsWith("SGET") -> false
        else -> register == instruction.registerA
    }
    else -> false
}

private fun instructionUsesRegister(instruction: Instruction, register: Int): Boolean = when (instruction) {
    is FiveRegisterInstruction -> register in listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
    is RegisterRangeInstruction -> register >= instruction.startRegister && register < instruction.startRegister + instruction.registerCount
    is ThreeRegisterInstruction -> register == instruction.registerA || register == instruction.registerB || register == instruction.registerC
    is TwoRegisterInstruction -> register == instruction.registerA || register == instruction.registerB
    is OneRegisterInstruction -> register == instruction.registerA
    else -> false
}

internal fun fileUriShareConsumer(instructions: List<Instruction>, uriResultIndex: Int): ShareConsumer? {
    if (instructions.getOrNull(uriResultIndex)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val uriRegister = (instructions[uriResultIndex] as? OneRegisterInstruction)?.registerA ?: return null
    val end = minOf(instructions.size, uriResultIndex + 33)
    for (consumerIndex in uriResultIndex + 1 until end) {
        val consumer = instructions[consumerIndex]
        val reference = consumer.methodReference()
        val registers = invokeRegisters(consumer)
        if (reference?.definingClass == ANDROID_INTENT && registers != null && registers.isNotEmpty()) {
            val usesUri = when (reference.name) {
                "setData" -> reference.parameterTypes == listOf("Landroid/net/Uri;") && registers.size == 2 && registers[1] == uriRegister
                "setDataAndType" -> reference.parameterTypes == listOf("Landroid/net/Uri;", "Ljava/lang/String;") && registers.size == 3 && registers[1] == uriRegister
                "putExtra" -> reference.parameterTypes == listOf("Ljava/lang/String;", "Landroid/os/Parcelable;") && registers.size == 3 &&
                    registers[2] == uriRegister && stringRegisterBefore(instructions, consumerIndex, registers[1]) == "android.intent.extra.STREAM"
                else -> false
            }
            if (usesUri) {
                for (later in instructions.drop(consumerIndex + 1)) {
                    if (instructionReadsRegister(later, uriRegister)) return null
                    if (instructionWritesRegister(later, uriRegister)) break
                }
                return ShareConsumer(consumerIndex, registers[0])
            }
        }
        if (instructionUsesRegister(consumer, uriRegister)) return null
        if (consumer.opcode.name.startsWith("IF_") || consumer.opcode.name.startsWith("GOTO") ||
            consumer.opcode.name.startsWith("RETURN") || consumer.opcode == Opcode.THROW ||
            consumer.opcode == Opcode.PACKED_SWITCH || consumer.opcode == Opcode.SPARSE_SWITCH) return null
    }
    return null
}

private fun runtimeFileUriCall(fileRegister: Int): String = if (fileRegister <= 15) {
    "invoke-static {v$fileRegister}, $LEGACY_COMPAT_RUNTIME->fileUriForShare(Ljava/io/File;)Landroid/net/Uri;"
} else {
    "invoke-static/range {v$fileRegister .. v$fileRegister}, $LEGACY_COMPAT_RUNTIME->fileUriForShare(Ljava/io/File;)Landroid/net/Uri;"
}

private fun runtimeGrantCall(intentRegister: Int): String = if (intentRegister <= 15) {
    "invoke-static {v$intentRegister}, $LEGACY_COMPAT_RUNTIME->grantFileUriReadPermission(Landroid/content/Intent;)V"
} else {
    "invoke-static/range {v$intentRegister .. v$intentRegister}, $LEGACY_COMPAT_RUNTIME->grantFileUriReadPermission(Landroid/content/Intent;)V"
}

internal fun legacyFileUriSharingPatch(
    result: LegacyFileUriSharingResult,
    enabledProvider: () -> Boolean,
) = bytecodePatch(
    name = null,
    description = "Internal legacy file URI sharing phase.",
    default = false,
) {
    execute {
        if (!enabledProvider()) return@execute
        val logger = Logger.getLogger("LegacyAppCompatibility")
        var found = 0
        var skipped = 0
        var already = 0
        var patched = 0
        val classes = mutableListOf<ClassDef>()
        classDefForEach { classes += it }
        for (dexClass in classes) {
            for (original in dexClass.methods.toList()) {
                val source = original.implementation?.instructions?.toList() ?: continue
                val candidates = source.indices.filter { index ->
                    val reference = source[index].methodReference()
                    reference?.definingClass == ANDROID_URI && reference.name == "fromFile" &&
                        reference.parameterTypes == listOf("Ljava/io/File;") && reference.returnType == ANDROID_URI
                }
                val existing = source.indices.filter { index ->
                    val reference = source[index].methodReference()
                    reference?.definingClass == LEGACY_COMPAT_RUNTIME && reference.name == "fileUriForShare" &&
                        reference.parameterTypes == listOf("Ljava/io/File;") && reference.returnType == ANDROID_URI
                }
                val verifiedExisting = existing.count { index ->
                    val consumer = fileUriShareConsumer(source, index + 1) ?: return@count false
                    val moveResult = source.getOrNull(consumer.index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
                    val grant = source.getOrNull(consumer.index + if (moveResult) 2 else 1) ?: return@count false
                    val reference = grant.methodReference() ?: return@count false
                    reference.definingClass == LEGACY_COMPAT_RUNTIME && reference.name == "grantFileUriReadPermission" &&
                        reference.parameterTypes == listOf(ANDROID_INTENT) && invokeRegisters(grant)?.singleOrNull() == consumer.intentRegister
                }
                check(existing.size == verifiedExisting) {
                    "Legacy compatibility: ${existing.size - verifiedExisting} existing file URI rewrites in ${dexClass.type}->${original.name} are incomplete or unsafe; refusing to keep a partial provider rewrite."
                }
                already += verifiedExisting
                if (candidates.isEmpty()) continue
                found += candidates.size
                val consumers = candidates.associateWith { fileUriShareConsumer(source, it + 1) }
                skipped += consumers.count { it.value == null }
                val supported = consumers.filterValues { it != null }
                if (supported.isEmpty()) continue
                val mutableClass = mutableClassDefByOrNull(dexClass.type)
                    ?: error("Legacy compatibility: cannot patch file URI callsites in mutable DEX class ${dexClass.type}.")
                val mutableMethod = mutableClass.methods.firstOrNull {
                    it.name == original.name && it.returnType == original.returnType && it.parameterTypes == original.parameterTypes
                } ?: error("Legacy compatibility: cannot find mutable method ${dexClass.type}->${original.name} for file URI sharing.")
                val mutable = mutableMethod.cloneMutable()
                supported.keys.sortedDescending().forEach { index ->
                    val fromFile = source[index]
                    val fromFileRegisters = invokeRegisters(fromFile)
                    val fileRegister = fromFileRegisters?.singleOrNull()
                        ?: error("Legacy compatibility: Uri.fromFile at ${dexClass.type}->${original.name} has unsupported DEX registers.")
                    val consumer = supported.getValue(index)!!
                    mutable.replaceInstruction(index, runtimeFileUriCall(fileRegister))
                    val nextIndex = consumer.index + 1
                    val moveResult = source.getOrNull(nextIndex)?.opcode == Opcode.MOVE_RESULT_OBJECT
                    mutable.addInstructions(nextIndex + if (moveResult) 1 else 0, runtimeGrantCall(consumer.intentRegister))
                    patched++
                }
                mutableClass.methods.remove(mutableMethod)
                mutableClass.methods.add(mutable)
            }
        }
        result.patchedCallSites = patched
        result.alreadyPatchedCallSites = already
        result.skippedCallSites = skipped
        if (found == 0 && already == 0) {
            logger.warning("Legacy compatibility: File URI Sharing Repair found no Uri.fromFile call sites; no provider was added.")
            return@execute
        }
        if (skipped > 0) {
            logger.warning("Legacy compatibility: skipped $skipped unrecognized Uri.fromFile call site(s); supported call sites were rewritten independently.")
        }
        logger.info("Legacy compatibility: verified and rewrote $patched file URI share call site(s); alreadyPatched=$already, skipped=$skipped, candidates=$found.")
    }
}
