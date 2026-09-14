package unipatches.ads

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import helpers.bytecode.numberOfParameterRegisters

/** The return type expected by a generated runtime guard. */
internal enum class AdsGuardReturnType {
    VOID,
    BOOLEAN,
    OBJECT,
    INT,
}

private val parameterWriteInstruction = Regex(
    "(?:const(?:/[0-9]+)?|move(?:-object|-wide)?(?:/(?:from16|16))?|move-result(?:-object|-wide)?|new-instance|iget(?:-object|-boolean|-byte|-char|-short|-wide)?|sget(?:-object|-boolean|-byte|-char|-short|-wide)?)\\s+p\\d+\\b",
)

private val labelDeclaration = Regex("(?m)^(\\s*):([A-Za-z0-9_.$-]+)(\\s*)$")
private val branchLabel = Regex(
    "(?m)(\\b(?:goto(?:/[0-9]+)?|if-[a-z-]+)\\s+(?:[^\\n,]+,\\s*)?):([A-Za-z0-9_.$-]+)\\b",
)

internal fun hasResolvedLabels(body: String): Boolean {
    val declarations = labelDeclaration.findAll(body)
        .map { it.groupValues[2] }
        .toSet()
    return branchLabel.findAll(body)
        .map { it.groupValues[2] }
        .all { it in declarations }
}

/** Prevent injected labels from colliding with labels already present in a target method. */
internal fun uniquifyInjectedLabels(body: String, namespace: String): String {
    val safeNamespace = namespace
        .replace(Regex("[^A-Za-z0-9_]"), "_")
        .takeLast(72)
    val prefix = "unipatch_ads_${safeNamespace}_"
    val withDeclarations = body.replace(labelDeclaration) { match ->
        "${match.groupValues[1]}:$prefix${match.groupValues[2]}${match.groupValues[3]}"
    }
    return withDeclarations.replace(branchLabel) { match ->
        "${match.groupValues[1]}:$prefix${match.groupValues[2]}"
    }
}

internal fun hasGuardParameterWrite(body: String): Boolean =
    parameterWriteInstruction.containsMatchIn(body)

private fun AdsGuardReturnType.matches(returnType: String): Boolean = when (this) {
    AdsGuardReturnType.VOID -> returnType == "V"
    AdsGuardReturnType.BOOLEAN -> returnType == "Z"
    AdsGuardReturnType.OBJECT -> returnType.startsWith("L") || returnType.startsWith("[")
    AdsGuardReturnType.INT -> returnType == "I"
}

internal fun isTypedGuardSafe(
    actualReturnType: String,
    expectedReturnType: AdsGuardReturnType,
    localRegisters: Int,
    body: String,
): Boolean = expectedReturnType.matches(actualReturnType) &&
    localRegisters >= 1 &&
    !hasGuardParameterWrite(body)

private fun safeLabel(method: MutableMethod, suffix: String): String {
    val identity = "${method.definingClass}_${method.name}_${method.parameterTypes}_${method.returnType}"
        .replace(Regex("[^A-Za-z0-9_]"), "_")
    return "unipatch_ads_${identity.takeLast(80)}_$suffix"
}

private fun validateGuard(
    method: MutableMethod,
    returnType: AdsGuardReturnType,
    body: String,
): Boolean {
    val implementation = method.implementation ?: return false
    // Runtime guards must not mutate p-registers. They may read them when invoking the original
    // body, but synthetic callback code that writes a parameter is rejected here.
    return isTypedGuardSafe(
        actualReturnType = method.returnType,
        expectedReturnType = returnType,
        localRegisters = implementation.registerCount - method.numberOfParameterRegisters,
        body = body,
    )
}

private fun buildGuard(
    method: MutableMethod,
    policyMethod: String,
    returnType: AdsGuardReturnType,
    blockedBody: String,
): String? {
    if (!validateGuard(method, returnType, blockedBody)) return null
    val original = safeLabel(method, "original")
    val safeBlockedBody = uniquifyInjectedLabels(blockedBody, original)
    return """
        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->$policyMethod()Z
        move-result v0
        if-eqz v0, :$original
        $safeBlockedBody
        :$original
    """.trimIndent()
}

internal fun guardVoid(method: MutableMethod, policyMethod: String, blockedBody: String): String? =
    buildGuard(method, policyMethod, AdsGuardReturnType.VOID, blockedBody)

internal fun guardBoolean(method: MutableMethod, policyMethod: String, blockedBody: String): String? =
    buildGuard(method, policyMethod, AdsGuardReturnType.BOOLEAN, blockedBody)

internal fun guardObject(method: MutableMethod, policyMethod: String, blockedBody: String): String? =
    buildGuard(method, policyMethod, AdsGuardReturnType.OBJECT, blockedBody)

internal fun guardInt(method: MutableMethod, policyMethod: String, blockedBody: String): String? =
    buildGuard(method, policyMethod, AdsGuardReturnType.INT, blockedBody)

internal fun guardSharedVoid(method: MutableMethod, blockedBody: String): String? {
    if (!validateGuard(method, AdsGuardReturnType.VOID, blockedBody)) return null
    val block = safeLabel(method, "block")
    val original = safeLabel(method, "original")
    val safeBlockedBody = uniquifyInjectedLabels(blockedBody, original)
    return """
        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldBlockInterstitials()Z
        move-result v0
        if-nez v0, :$block
        invoke-static {}, Lunipatch/overlaycore/AdsRuntimePolicy;->shouldBlockRewarded()Z
        move-result v0
        if-eqz v0, :$original
        :$block
        $safeBlockedBody
        :$original
    """.trimIndent()
}
