/*
 * Shared startup entry-point resolution for runtime patches.
 *
 * Lifted from Universal Overlay's injection helpers so every patch that needs a
 * startup hook target (Application onCreate preferred, launcher Activity
 * fallback) resolves it the same way instead of each guessing separately.
 */
package helpers.startup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import java.util.logging.Logger

/** A resolved runtime hook target. [isApplication] distinguishes the process Application path. */
internal data class StartupEntryPoint(
    val owner: MutableClass,
    val onCreate: MutableMethod,
    val isApplication: Boolean,
)

/** First mutable method matching name, return type, and parameter types of [match]. */
private fun MutableClass.findMutableMethod(match: Method): MutableMethod? = methods.firstOrNull {
    it.name == match.name && it.returnType == match.returnType &&
        it.parameterTypes == match.parameterTypes
}

/** Walks a superclass chain looking for a recognized Activity ancestor. */
internal fun hasRecognizedActivityAncestor(
    start: String,
    superclassOf: (String) -> String?,
): Boolean {
    val seen = mutableSetOf<String>()
    var current: String? = start
    while (current != null && seen.add(current)) {
        if (current == "Landroid/app/Activity;" || current == "Landroid/app/NativeActivity;") {
            return true
        }
        current = superclassOf(current)
    }
    return false
}

internal fun acceptsLauncherOwnership(manifestResolved: Boolean, packageOwned: Boolean): Boolean =
    manifestResolved || packageOwned

/**
 * Finds the implementation of Application.onCreate, including an implementation inherited by
 * the manifest-declared Application class. Mutating a bundled application superclass is safe here:
 * it is still the process Application entry point, whereas selecting an arbitrary Activity or SDK
 * class can leave the actual game screen without a hook.
 */
internal fun BytecodePatchContext.findInheritedApplicationOnCreate(
    start: ClassDef,
): Pair<ClassDef, Method>? {
    val seen = mutableSetOf<String>()
    var current: ClassDef? = start
    while (current != null && seen.add(current.type)) {
        val method = current.methods.firstOrNull {
            it.name == "onCreate" && it.returnType == "V" && it.parameterTypes.isEmpty()
        }
        if (method != null) return current to method

        val superclass = current.superclass ?: return null
        if (superclass == "Landroid/app/Application;" || superclass == "Ljava/lang/Object;") return null
        current = classDefByOrNull(superclass)
    }
    return null
}

/**
 * Creates an Application-owned onCreate override when the manifest Application only inherits
 * onCreate from an SDK superclass. The override calls the immediate superclass so SDK startup
 * remains intact, while keeping the runtime injection out of the shared SDK class.
 */
internal fun createApplicationOnCreateOverride(
    owner: MutableClass,
): MutableMethod {
    val superclass = owner.superclass
        ?: error("Application ${owner.type} has no superclass")
    require(superclass != "Ljava/lang/Object;") {
        "Application ${owner.type} has no safe superclass"
    }
    val method = ImmutableMethod(
        owner.type,
        "onCreate",
        emptyList(),
        "V",
        AccessFlags.PUBLIC.value,
        emptySet(),
        emptySet(),
        ImmutableMethodImplementation(
            1,
            emptyList(),
            emptyList(),
            emptyList(),
        ),
    ).toMutable()
    method.addInstructionsWithLabels(
        0,
        "invoke-super {p0}, $superclass->onCreate()V\nreturn-void",
    )
    owner.methods.add(method)
    return method
}

/**
 * Resolves the Application hook target: the manifest-declared Application class with its own or
 * inherited onCreate, synthesizing an override when only the SDK superclass implements it.
 */
internal fun BytecodePatchContext.resolveApplicationEntryPoint(
    logger: Logger,
): StartupEntryPoint? {
    val appDescriptor = StartupHooks.resolvedApplicationDescriptor ?: return null
    val appClass = classDefByOrNull(appDescriptor) ?: return null
    val (inheritedOwner, inheritedOnCreate) = findInheritedApplicationOnCreate(appClass) ?: return null
    val targetClass = mutableClassDefByOrNull(appClass.type) ?: run {
        logger.warning("Startup entry resolver: could not resolve mutable Application class ${appClass.type}")
        return null
    }
    return if (inheritedOwner.type == targetClass.type) {
        val mutableOnCreate = targetClass.findMutableMethod(inheritedOnCreate)
        if (mutableOnCreate == null) {
            logger.warning("Startup entry resolver: could not resolve mutable Application.onCreate in ${targetClass.type}")
            null
        } else {
            StartupEntryPoint(targetClass, mutableOnCreate, isApplication = true)
        }
    } else {
        val direct = try {
            createApplicationOnCreateOverride(targetClass)
        } catch (error: Exception) {
            logger.warning("Startup entry resolver: could not create direct Application.onCreate override in ${targetClass.type}: ${error.message}")
            null
        } ?: return null
        logger.info("Startup entry resolver: created direct Application.onCreate override in ${targetClass.type}; inherited implementation remains untouched in ${inheritedOwner.type}")
        StartupEntryPoint(targetClass, direct, isApplication = true)
    }
}

/**
 * Validates the manifest-resolved launcher before generic Activity discovery:
 * package ownership, no-history exclusion, a recognized Activity ancestor
 * (including framework ancestors not packaged in the APK), and onCreate(Bundle).
 */
internal fun BytecodePatchContext.resolveLauncherEntryPoint(
    descriptor: String?,
    logger: Logger,
): StartupEntryPoint? {
    if (descriptor.isNullOrBlank()) {
        logger.info("Startup entry resolver: launcher validation skipped, no resolved launcher")
        return null
    }
    val classDef = classDefByOrNull(descriptor)
    if (classDef == null) {
        logger.warning("Startup entry resolver: launcher rejected $descriptor, class lookup failed")
        return null
    }
    val manifestResolved = descriptor == StartupHooks.resolvedLauncherActivityDescriptor

    val chain = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var current: String? = descriptor
    while (current != null && seen.add(current)) {
        chain += current
        current = if (current == descriptor) classDef.superclass else classDefByOrNull(current)?.superclass
    }
    val hasActivityAncestor = hasRecognizedActivityAncestor(descriptor) { type ->
        when {
            type == descriptor -> classDef.superclass
            type == "Landroid/app/Activity;" || type == "Landroid/app/NativeActivity;" -> null
            else -> classDefByOrNull(type)?.superclass
        }
    }
    logger.info("Startup entry resolver: launcher superclass chain ${chain.joinToString(" -> ")}")
    logger.info("Startup entry resolver: launcher=$descriptor manifestResolved=$manifestResolved activityAncestor=$hasActivityAncestor")

    val binaryName = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
    val packageName = StartupHooks.resolvedPackageName
    val packageOwned = !packageName.isNullOrBlank() &&
        (binaryName == packageName || binaryName.startsWith("$packageName."))
    val ownershipAccepted = acceptsLauncherOwnership(manifestResolved, packageOwned)
    if (!ownershipAccepted) {
        logger.warning("Startup entry resolver: launcher rejected $descriptor, class is outside the application package")
        return null
    }

    val noHistory = descriptor in StartupHooks.resolvedNoHistoryActivityDescriptors
    if (noHistory) {
        logger.warning("Startup entry resolver: launcher rejected $descriptor, activity is marked noHistory")
        return null
    }
    if (!hasActivityAncestor) {
        logger.warning("Startup entry resolver: launcher rejected $descriptor, no recognized Activity ancestor was resolved")
        return null
    }
    val onCreateExists = classDef.methods.any {
        it.name == "onCreate" && it.returnType == "V" &&
            it.parameterTypes == listOf("Landroid/os/Bundle;") && it.implementation != null
    }
    if (!onCreateExists) {
        logger.warning("Startup entry resolver: launcher rejected $descriptor, onCreate(Bundle) was not found")
        return null
    }
    val owner = try {
        mutableClassDefByOrNull(classDef.type)
    } catch (error: Exception) {
        logger.warning("Startup entry resolver: launcher rejected $descriptor, mutable class lookup failed: ${error.message}")
        null
    } ?: return null
    val onCreate = owner.methods.firstOrNull {
        it.name == "onCreate" && it.returnType == "V" &&
            it.parameterTypes == listOf("Landroid/os/Bundle;") && it.implementation != null
    }
    if (onCreate == null) {
        logger.warning("Startup entry resolver: launcher rejected $descriptor, mutable onCreate(Bundle) lookup failed")
        return null
    }
    logger.info("Startup entry resolver: launcher validation succeeded: $descriptor->onCreate(Bundle)")
    return StartupEntryPoint(owner, onCreate, isApplication = false)
}

/** Finds a real, non-transient Activity when an explicit target is unavailable. */
internal fun BytecodePatchContext.findFallbackActivity(
    preferredDescriptor: String? = StartupHooks.resolvedLauncherActivityDescriptor,
): MutableClass? {
    val parents = mutableMapOf<String, String>()
    classDefForEach { classDef -> classDef.superclass?.let { parents[classDef.type] = it } }
    fun isPackagedFrameworkActivity(type: String): Boolean {
        val frameworkNamespace = type.startsWith("Landroid/app/") ||
            type.startsWith("Landroid/support/") || type.startsWith("Landroidx/")
        return frameworkNamespace && type.endsWith("Activity;")
    }
    fun isActivity(type: String, seen: MutableSet<String> = mutableSetOf()): Boolean = when {
        type == "Landroid/app/Activity;" || isPackagedFrameworkActivity(type) -> true
        type == "Ljava/lang/Object;" || !seen.add(type) -> false
        else -> parents[type]?.let { isActivity(it, seen) } == true
    }
    val noHistory = StartupHooks.resolvedNoHistoryActivityDescriptors
    val packageName = StartupHooks.resolvedPackageName
    val candidates = mutableListOf<String>()
    classDefForEach { classDef ->
        if (!isActivity(classDef.type) || classDef.type in noHistory) return@classDefForEach
        // A missing launcher resolution must not select a support-library,
        // AndroidX, Google, or other SDK Activity by class-file order. Only
        // an application-owned Activity is a safe generic fallback.
        val binaryName = classDef.type.removePrefix("L").removeSuffix(";").replace('/', '.')
        if (packageName.isNullOrBlank() ||
            !(binaryName == packageName || binaryName.startsWith("$packageName."))
        ) return@classDefForEach
        if (classDef.methods.any {
                it.name == "onCreate" && it.returnType == "V" &&
                    it.parameterTypes == listOf("Landroid/os/Bundle;") && it.implementation != null
            }) candidates += classDef.type
    }
    // The manifest has already identified this component as an Activity. Prefer it even
    // when its final framework superclass is not present in the APK's class pool.
    val preferred = preferredDescriptor?.let { descriptor ->
        classDefByOrNull(descriptor)?.takeIf { candidate ->
            candidate.type == descriptor &&
                candidate.type.removePrefix("L").removeSuffix(";").replace('/', '.')
                    .let { binaryName ->
                        !packageName.isNullOrBlank() &&
                            (binaryName == packageName || binaryName.startsWith("$packageName."))
                    } &&
                candidate.type !in noHistory &&
                candidate.methods.any {
                    it.name == "onCreate" && it.returnType == "V" &&
                        it.parameterTypes == listOf("Landroid/os/Bundle;") && it.implementation != null
                }
        }?.type
    }
    val selected = preferred ?: candidates.firstOrNull { it == preferredDescriptor } ?: candidates.firstOrNull()
    return selected?.let(::mutableClassDefByOrNull)
}

/**
 * One-shot entry-point resolution for simple runtime hooks: the process Application
 * entry point when resolvable, then the validated launcher Activity, then a restricted
 * application-owned Activity fallback. Returns null when no safe target exists.
 */
internal fun BytecodePatchContext.resolveStartupEntryPoint(
    logger: Logger,
): StartupEntryPoint? {
    resolveApplicationEntryPoint(logger)?.let { return it }
    resolveLauncherEntryPoint(StartupHooks.resolvedLauncherActivityDescriptor, logger)?.let { return it }
    val fallback = findFallbackActivity() ?: return null
    val onCreate = fallback.methods.firstOrNull {
        it.name == "onCreate" && it.returnType == "V" && it.parameterTypes == listOf("Landroid/os/Bundle;")
    } ?: return null
    logger.info("Startup entry resolver: using application-owned Activity fallback ${fallback.type}->onCreate(Bundle)")
    return StartupEntryPoint(fallback, onCreate, isApplication = false)
}
