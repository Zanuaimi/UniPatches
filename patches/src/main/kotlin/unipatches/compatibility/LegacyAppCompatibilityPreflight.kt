package unipatches.compatibility

import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.logging.Logger

internal data class LegacyCompatibilityPreflightFinding(
    val pattern: String,
    val detected: Boolean,
    val proposal: String,
    val apiRange: String,
    val confidence: String,
    val uncertainty: String,
)

private fun preflightValue(value: String): String =
    value.filterNot { Character.isISOControl(it) }.take(300)

internal fun formatLegacyCompatibilityPreflightFinding(finding: LegacyCompatibilityPreflightFinding): String =
    "Legacy compatibility preflight: pattern=${preflightValue(finding.pattern)}; " +
        "detected=${finding.detected}; proposal=${preflightValue(finding.proposal)}; " +
        "api=${preflightValue(finding.apiRange)}; confidence=${preflightValue(finding.confidence)}; " +
        "uncertainty=${preflightValue(finding.uncertainty)}"

internal fun shouldApplyLegacyCompatibilityMutations(diagnosticsOnly: Boolean): Boolean = !diagnosticsOnly

internal fun legacyCompatibilityBytecodePreflightPatch() = bytecodePatch(
    name = null,
    description = "Internal diagnostics-only scan for Legacy App Compatibility fingerprints.",
    default = false,
) {
    execute {
        val logger = Logger.getLogger(this::class.java.name)
        val classes = mutableListOf<ClassDef>()
        classDefForEach { classes += it }

        val licenseClassType = "Lcom/google/android/vending/licensing/LicenseChecker;"
        val licenseCallbackType = "Lcom/google/android/vending/licensing/LicenseCheckerCallback;"
        val licenseClass = classes.firstOrNull { it.type == licenseClassType }
        val licenseMethod = licenseClass?.methods?.firstOrNull {
            it.name == "checkAccess" && it.parameterTypes == listOf(licenseCallbackType) && it.returnType == "V"
        }
        val licenseInstructions = licenseMethod?.implementation?.instructions?.toList()
        val licenseConstructors = licenseInstructions.orEmpty().mapIndexedNotNull { index, instruction ->
            val reference = instruction as? ReferenceInstruction ?: return@mapIndexedNotNull null
            if (isIntentStringConstructor(reference)) index to reference else null
        }
        val licenseRegisters = licenseConstructors.singleOrNull()?.second?.let(::intentStringConstructorRegisters)
        val licenseExact = hasApkPureLicenseAction(licenseInstructions.orEmpty()) &&
            licenseConstructors.size == 1 &&
            licenseRegisters?.let {
                licenseServiceIntentPackageBlock(it[0], it[1], licenseMethod?.implementation?.registerCount ?: 0) != null
            } == true
        logger.info(
            formatLegacyCompatibilityPreflightFinding(
                LegacyCompatibilityPreflightFinding(
                    pattern = "Google Play LVL LicenseChecker.checkAccess implicit-service intent",
                    detected = licenseClass != null,
                    proposal = "set the known LVL Intent package to com.android.vending; retain license verification",
                    apiRange = "Android API 21+",
                    confidence = if (licenseExact) "exact APKPure action, method, constructor, and register fingerprint" else "uncertain",
                    uncertainty = if (licenseExact) "Play Store service availability and license result remain runtime-dependent" else "class, action, constructor count, or register encoding does not match; no auto-fix",
                ),
            ),
        )

        for (target in downloaderPendingIntentTargets) {
            val targetClass = classes.firstOrNull { it.type == target.classType }
            val method = targetClass?.methods?.firstOrNull {
                it.name == target.methodName && it.parameterTypes == target.parameterTypes && it.returnType == "V"
            }
            val calls = method?.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
                val reference = instruction as? ReferenceInstruction ?: return@mapNotNull null
                if (isFixedGetActivity(reference)) reference else null
            }
            val exact = method != null && calls.size == 1 && pendingIntentCallRegisters(calls.single()) != null
            logger.info(
                formatLegacyCompatibilityPreflightFinding(
                    LegacyCompatibilityPreflightFinding(
                        pattern = "fixed downloader PendingIntent ${target.classType}->${target.methodName}",
                        detected = method != null,
                        proposal = "add FLAG_IMMUTABLE only to a recognized FLAG_UPDATE_CURRENT call with supported registers",
                        apiRange = "Android API 31+",
                        confidence = if (exact) "exact method and call-shape fingerprint" else "uncertain",
                        uncertainty = if (exact) "flag value is checked again by the mutating patch" else "target method or call register shape is absent or changed; no auto-fix",
                    ),
                ),
            )
        }

        val openIabMethod = classes.firstOrNull { it.type == "Lorg/onepf/openiab/UnityPlugin;" }?.methods?.firstOrNull { it.name == "createBroadcasts" }
        logger.info(
            formatLegacyCompatibilityPreflightFinding(
                LegacyCompatibilityPreflightFinding(
                    pattern = "OpenIAB UnityPlugin.createBroadcasts receiver setup",
                    detected = openIabMethod != null,
                    proposal = "use OpenIAB Automatic mode for recognized internal/external-store actions; ambiguous filters stay unchanged",
                    apiRange = "Android API 33+",
                    confidence = if (openIabMethod != null) "exact class and method fingerprint" else "not found",
                    uncertainty = "automatic mode checks receiver ownership and action allowlists; Force mode can change broadcast delivery",
                ),
            ),
        )

        val parents = mutableMapOf<String, String>()
        classes.forEach { classDef -> classDef.superclass?.let { parents[classDef.type] = it } }
        var receiverCalls = 0
        var resolvedFilters = 0
        classes.forEach { classDef ->
            classDef.methods.forEach { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                instructions.forEachIndexed { index, instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
                    if (!isVerifiedAppContextOwner(reference.definingClass, parents) ||
                        reference.name != "registerReceiver" ||
                        reference.returnType != "Landroid/content/Intent;" ||
                        reference.parameterTypes != listOf("Landroid/content/BroadcastReceiver;", "Landroid/content/IntentFilter;")
                    ) return@forEachIndexed
                    receiverCalls++
                    val registers = appWideReceiverCallRegisters(instruction)
                    if (registers != null && resolvedReceiverFilterActions(instructions, index, registers[2]) != null) {
                        resolvedFilters++
                    }
                }
            }
        }
        logger.info(
            formatLegacyCompatibilityPreflightFinding(
                LegacyCompatibilityPreflightFinding(
                    pattern = "legacy dynamic registerReceiver calls",
                    detected = receiverCalls > 0,
                    proposal = "on API 33+, keep app-only filters private, export known system/store filters, and skip ambiguous filters",
                    apiRange = "Android API 33+",
                    confidence = if (receiverCalls > 0 && resolvedFilters == receiverCalls) "all filter action sets statically resolved" else "partial",
                    uncertainty = "${resolvedFilters} of ${receiverCalls} filters have static actions; app ID/sender identity and runtime-built filters may remain unknown",
                ),
            ),
        )
    }
}
