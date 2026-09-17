package unipatches.services

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import java.util.logging.Logger

internal data class ControlEmbeddedOptions(
    val providerMode: String,
    val gmsCorePackage: String,
    val mainActivity: String,
    val storeAvailability: String,
    val bypassLicenseVerification: Boolean,
    val showPlayServicesAvailable: Boolean,
    val bypassPlayServicesCheck: Boolean,
    val bypassPlayLicenseCheck: Boolean,
    val disableFirebasePerformance: Boolean,
    val disableGooglePay: Boolean,
    val disableRemoteConfig: Boolean,
    val forceSignedOut: Boolean,
    val suppressPlayGamesSignIn: Boolean,
    val suppressPlayGamesSignInUi: Boolean,
    val fixMaps: Boolean,
    val nullLocation: Boolean,
    val silenceErrors: Boolean,
    val spoofVersion: Boolean,
)

private fun BytecodePatchContext.matches(fp: Fingerprint) = try {
    with(this) { fp.matchAll() }.map { it.method }
} catch (_: Exception) {
    try { listOfNotNull(fp.methodOrNull) } catch (_: Exception) { emptyList() }
}

private fun BytecodePatchContext.inject(
    fp: Fingerprint,
    label: String,
    returnTypes: Set<String>,
    smali: String,
    logger: Logger,
    minRegisters: Int = 1,
): Int {
    var count = 0
    for (method in matches(fp).distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }) {
        try {
            if (method.implementation == null || method.returnType !in returnTypes) continue
            if (method.implementation!!.registerCount < minRegisters) continue
            method.addInstructions(0, smali)
            count++
        } catch (_: Exception) {}
    }
    if (count > 0) logger.info("Control Embedded Auth / Stores: $label patched $count method(s)")
    return count
}

private fun BytecodePatchContext.bool(fp: Fingerprint, label: String, value: Boolean, logger: Logger): Int =
    inject(fp, label, setOf("Z", "I", "B", "S", "C"), "const/4 v0, ${if (value) "0x1" else "0x0"}\nreturn v0", logger)

private fun BytecodePatchContext.int(fp: Fingerprint, label: String, value: Int, logger: Logger): Int =
    inject(fp, label, setOf("I", "B", "S", "C"), "const/16 v0, 0x${value.toString(16)}\nreturn v0", logger)

private fun BytecodePatchContext.noop(fp: Fingerprint, label: String, logger: Logger): Int =
    inject(fp, label, setOf("V"), "return-void", logger)

private fun BytecodePatchContext.nullObject(fp: Fingerprint, label: String, logger: Logger): Int =
    inject(fp, label, setOf("Lcom/google/android/gms/tasks/Task;", "Lcom/google/android/gms/auth/api/signin/GoogleSignInAccount;", "Landroid/app/Dialog;", "Ljava/lang/String;"), "const/4 v0, 0x0\nreturn-object v0", logger)

private fun BytecodePatchContext.taskBoolean(fp: Fingerprint, label: String, value: Boolean, logger: Logger): Int {
    val bit = if (value) "0x1" else "0x0"
    return inject(fp, label, setOf("Lcom/google/android/gms/tasks/Task;"), "const/4 v0, $bit\ninvoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;\nmove-result-object v0\ninvoke-static {v0}, Lcom/google/android/gms/tasks/Tasks;->forResult(Ljava/lang/Object;)Lcom/google/android/gms/tasks/Task;\nmove-result-object v0\nreturn-object v0", logger)
}

private fun BytecodePatchContext.taskNull(fp: Fingerprint, label: String, logger: Logger): Int =
    inject(fp, label, setOf("Lcom/google/android/gms/tasks/Task;"), "const/4 v0, 0x0\ninvoke-static {v0}, Lcom/google/android/gms/tasks/Tasks;->forResult(Ljava/lang/Object;)Lcom/google/android/gms/tasks/Task;\nmove-result-object v0\nreturn-object v0", logger)

private fun BytecodePatchContext.taskFailure(fp: Fingerprint, label: String, logger: Logger): Int =
    inject(
        fp,
        label,
        setOf("Lcom/google/android/gms/tasks/Task;"),
        "new-instance v0, Ljava/lang/Exception;\nconst-string v1, \"Play Games sign-in suppressed\"\ninvoke-direct {v0, v1}, Ljava/lang/Exception;-><init>(Ljava/lang/String;)V\ninvoke-static {v0}, Lcom/google/android/gms/tasks/Tasks;->forException(Ljava/lang/Exception;)Lcom/google/android/gms/tasks/Task;\nmove-result-object v0\nreturn-object v0",
        logger,
        minRegisters = 2,
    )

private fun BytecodePatchContext.playGamesSignIn(logger: Logger, suppressUiOnly: Boolean): Int {
    var count = 0
    val client = "Lcom/google/android/gms/games/GamesSignInClient;"
    fun concreteClient(name: String): Fingerprint = Fingerprint(
        name = name,
        returnType = "Lcom/google/android/gms/tasks/Task;",
        custom = { _, c ->
            val type = c.type.lowercase()
            type.contains("/games/") && c.type != client
        },
    )
    if (!suppressUiOnly) {
        count += taskFailure(Fingerprint(definingClass = client, name = "isAuthenticated", returnType = "Lcom/google/android/gms/tasks/Task;"), "Play Games authentication check", logger)
        count += taskFailure(concreteClient("isAuthenticated"), "Play Games authentication implementation", logger)
    }
    count += taskFailure(Fingerprint(definingClass = client, name = "signIn", returnType = "Lcom/google/android/gms/tasks/Task;"), "Play Games sign-in UI", logger)
    count += taskFailure(concreteClient("signIn"), "Play Games sign-in implementation", logger)
    return count
}

private fun BytecodePatchContext.maps(logger: Logger): Int {
    var count = int(Fingerprint(definingClass = "Lcom/google/android/gms/maps/MapsInitializer;", name = "initialize", returnType = "I", parameters = listOf("Landroid/content/Context;")), "Maps initialize", 0, logger)
    count += inject(
        Fingerprint(definingClass = "Lcom/google/android/gms/maps/MapsInitializer;", name = "initialize", returnType = "I", parameters = listOf("Landroid/content/Context;", "Lcom/google/android/gms/maps/MapsInitializer\$Renderer;", "Lcom/google/android/gms/maps/OnMapsSdkInitializedCallback;")),
        "Maps initialize callback", setOf("I"),
        "move-object/from16 v2, p2\nsget-object v0, Lcom/google/android/gms/maps/MapsInitializer\$Renderer;->LATEST:Lcom/google/android/gms/maps/MapsInitializer\$Renderer;\ninvoke-interface {v2, v0}, Lcom/google/android/gms/maps/OnMapsSdkInitializedCallback;->onMapsSdkInitialized(Lcom/google/android/gms/maps/MapsInitializer\$Renderer;)V\nconst/4 v0, 0x0\nreturn v0",
        logger, minRegisters = 3,
    )
    return count
}

private fun BytecodePatchContext.store(logger: Logger, mode: String): Int {
    if (mode == "real") return 0
    var count = 0
    val packages = listOf("com.android.vending", "com.amazon.venezia", "com.amazon.device.marketplace")
    for (packageName in packages) {
        val boolFp = Fingerprint(returnType = "Z", parameters = emptyList(), strings = listOf(packageName))
        val stringFp = Fingerprint(returnType = "Ljava/lang/String;", parameters = emptyList(), strings = listOf(packageName))
        val available = when {
            mode == "google" -> packageName == "com.android.vending"
            mode == "amazon" -> packageName != "com.android.vending"
            else -> false
        }
        count += when (mode) {
            "amazon", "google" -> bool(boolFp, "Store availability", available, logger) + inject(stringFp, "Store package", setOf("Ljava/lang/String;"), "const-string v0, \"${if (available) packageName else ""}\"\nreturn-object v0", logger)
            else -> bool(boolFp, "Store availability", false, logger) + inject(stringFp, "Store package", setOf("Ljava/lang/String;"), "const-string v0, \"\"\nreturn-object v0", logger)
        }
    }
    return count
}

internal fun controlEmbeddedAuthStoresManagedPatch(optionsProvider: () -> ControlEmbeddedOptions) = bytecodePatch(
    name = null,
    description = "Internal managed phase for Control Embedded Auth / Stores.",
    default = false,
) {
    execute {
        val o = optionsProvider()
        val logger = Logger.getLogger(this::class.java.name)

        val availability = listOf(
            Fingerprint(definingClass = "Lcom/google/android/gms/common/GoogleApiAvailability;", name = "isGooglePlayServicesAvailable", returnType = "I", parameters = listOf("Landroid/content/Context;")),
            Fingerprint(definingClass = "Lcom/google/android/gms/common/GoogleApiAvailability;", name = "isGooglePlayServicesAvailable", returnType = "I", parameters = listOf("Landroid/content/Context;", "I")),
            Fingerprint(definingClass = "Lcom/google/android/gms/common/GoogleApiAvailability;", name = "isGooglePlayServicesAvailable", returnType = "I", parameters = listOf("Landroid/app/Activity;")),
            Fingerprint(definingClass = "Lcom/google/android/gms/common/GoogleApiAvailability;", name = "isGooglePlayServicesAvailable", returnType = "I", parameters = listOf("Landroid/app/Activity;", "I")),
            Fingerprint(definingClass = "Lcom/google/android/gms/common/GooglePlayServicesUtil;", name = "isGooglePlayServicesAvailable", returnType = "I", parameters = listOf("Landroid/content/Context;")),
        )
        val availabilityEnabled = o.providerMode == "gmscore" || o.providerMode == "zero" || o.showPlayServicesAvailable || o.bypassPlayServicesCheck
        if (availabilityEnabled) availability.forEach { int(it, "Google Play Services availability", if (o.providerMode == "zero") 1 else 0, logger) }

        if (o.bypassLicenseVerification) inject(
            Fingerprint(definingClass = "Lcom/google/android/vending/licensing/LicenseChecker;", name = "verify", returnType = "V", parameters = listOf("Lcom/google/android/vending/licensing/LicenseCheckerCallback;", "I")),
            "License verification", setOf("V"), "const/16 v0, 0x100\nmove-object/from16 v1, p1\ninvoke-interface {v1, v0}, Lcom/google/android/vending/licensing/LicenseCheckerCallback;->allow(I)V\nreturn-void", logger, 2,
        )
        if (o.bypassPlayLicenseCheck) inject(
            Fingerprint(definingClass = "Lcom/google/android/vending/licensing/LicenseChecker;", name = "checkAccess", returnType = "V", parameters = listOf("Lcom/google/android/vending/licensing/ILicenseResultListener;")),
            "Play license check", setOf("V"), "const/4 v0, 0x0\nmove-object/from16 v1, p1\ninvoke-interface {v1, v0}, Lcom/google/android/vending/licensing/ILicenseResultListener;->allow(I)V\nreturn-void", logger, 2,
        )

        if (o.disableFirebasePerformance) for (q in listOf(
            Quad("Lcom/google/firebase/perf/metrics/Trace;", "start", "Firebase Performance Trace.start"),
            Quad("Lcom/google/firebase/perf/metrics/Trace;", "stop", "Firebase Performance Trace.stop"),
            Quad("Lcom/google/firebase/perf/metrics/Trace;", "incrementMetric", "Firebase Performance Trace.incrementMetric"),
            Quad("Lcom/google/firebase/perf/metrics/HttpMetric;", "start", "Firebase Performance HttpMetric.start"),
            Quad("Lcom/google/firebase/perf/metrics/HttpMetric;", "stop", "Firebase Performance HttpMetric.stop"),
        )) noop(Fingerprint(definingClass = q.owner, name = q.name, returnType = "V"), q.label, logger)

        if (o.disableGooglePay) taskBoolean(Fingerprint(definingClass = "Lcom/google/android/gms/wallet/PaymentsClient;", name = "isReadyToPay", returnType = "Lcom/google/android/gms/tasks/Task;"), "Google Pay", false, logger)
        if (o.disableRemoteConfig) {
            taskNull(Fingerprint(definingClass = "Lcom/google/firebase/remoteconfig/FirebaseRemoteConfig;", name = "fetch", returnType = "Lcom/google/android/gms/tasks/Task;"), "Remote Config fetch", logger)
            taskBoolean(Fingerprint(definingClass = "Lcom/google/firebase/remoteconfig/FirebaseRemoteConfig;", name = "fetchAndActivate", returnType = "Lcom/google/android/gms/tasks/Task;"), "Remote Config fetchAndActivate", true, logger)
            bool(Fingerprint(definingClass = "Lcom/google/firebase/remoteconfig/FirebaseRemoteConfig;", name = "activate", returnType = "Z"), "Remote Config activate", true, logger)
        }
        if (o.forceSignedOut) {
            bool(Fingerprint(definingClass = "Lcom/google/android/gms/auth/api/signin/GoogleSignIn;", name = "hasPermissions", returnType = "Z"), "Google sign-out permissions", false, logger)
            nullObject(Fingerprint(definingClass = "Lcom/google/android/gms/auth/api/signin/GoogleSignIn;", name = "getLastSignedInAccount", returnType = "Lcom/google/android/gms/auth/api/signin/GoogleSignInAccount;"), "Google sign-out account", logger)
        }
        if (o.suppressPlayGamesSignIn) playGamesSignIn(logger, suppressUiOnly = false)
        else if (o.suppressPlayGamesSignInUi) playGamesSignIn(logger, suppressUiOnly = true)
        if (o.fixMaps) maps(logger)
        if (o.nullLocation) for (name in listOf("getLastLocation", "requestLocationUpdates", "flushLocations")) taskNull(Fingerprint(definingClass = "Lcom/google/android/gms/location/FusedLocationProviderClient;", name = name, returnType = "Lcom/google/android/gms/tasks/Task;"), "Play Location $name", logger)
        if (o.silenceErrors) {
            nullObject(Fingerprint(definingClass = "Lcom/google/android/gms/common/GoogleApiAvailability;", name = "getErrorDialog", returnType = "Landroid/app/Dialog;"), "Play Services error dialog", logger)
            noop(Fingerprint(definingClass = "Lcom/google/android/gms/common/GoogleApiAvailability;", name = "showErrorNotification", returnType = "V"), "Play Services error notification", logger)
            noop(Fingerprint(definingClass = "Lcom/google/android/gms/common/GoogleApiAvailability;", name = "showErrorDialogFragment", returnType = "V"), "Play Services error fragment", logger)
            bool(Fingerprint(definingClass = "Lcom/google/android/gms/common/GoogleApiAvailability;", name = "isUserResolvableError", returnType = "Z"), "Play Services resolvable error", false, logger)
            inject(Fingerprint(definingClass = "Lcom/google/android/gms/common/GoogleApiAvailability;", name = "getErrorString", returnType = "Ljava/lang/String;"), "Play Services error string", setOf("Ljava/lang/String;"), "const-string v0, \"\"\nreturn-object v0", logger)
        }
        if (o.spoofVersion) for ((owner, name) in listOf(
            "Lcom/google/android/gms/common/GoogleApiAvailability;" to "getApkVersion",
            "Lcom/google/android/gms/common/GoogleApiAvailability;" to "getClientVersion",
            "Lcom/google/android/gms/common/GooglePlayServicesUtilLight;" to "getApkVersion",
            "Lcom/google/android/gms/common/GooglePlayServicesUtilLight;" to "getClientVersion",
        )) inject(Fingerprint(definingClass = owner, name = name, returnType = "I"), "Play Services version $name", setOf("I"), "const v0, 0x17D7840\nreturn v0", logger)

        store(logger, o.storeAvailability)
        if (o.mainActivity.isNotEmpty() && o.providerMode == "gmscore") {
            val activityClass = o.mainActivity.let { value -> if (value.startsWith("L") && value.endsWith(";")) value else "L${value.replace('.', '/')};" }
            val fp = Fingerprint(definingClass = activityClass, name = "onCreate", returnType = "V", parameters = listOf("Landroid/os/Bundle;"))
            inject(fp, "GmsCore startup check", setOf("V"), "invoke-static {}, Lcom/google/android/gms/common/GoogleApiAvailability;->getInstance()Lcom/google/android/gms/common/GoogleApiAvailability;\nmove-result-object v0\nmove-object/from16 v1, p0\ninvoke-virtual {v0, v1}, Lcom/google/android/gms/common/GoogleApiAvailability;->isGooglePlayServicesAvailable(Landroid/content/Context;)I\nmove-result v0", logger, 2)
        }
    }
}

private data class Quad(val owner: String, val name: String, val label: String)
