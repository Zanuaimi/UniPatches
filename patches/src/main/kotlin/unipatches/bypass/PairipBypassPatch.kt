package unipatches.bypass

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.logging.Logger
import org.w3c.dom.Element
import helpers.ads.*
import helpers.manifest.NS_ANDROID
import helpers.manifest.applicationOrNull

private fun ResourcePatchContext.discoverPairipAppClass(logger: Logger): String? {
    val dir = try {
        get("AndroidManifest.xml", false).parentFile
    } catch (_: Exception) {
        null
    }
        ?: return null.also { logger.warning("Cannot determine APK directory") }
    for (i in 0..99) {
        val f = java.io.File(dir, if (i == 0) "classes.dex" else "classes${i + 1}.dex")
        if (!f.exists()) break
        try {
            for (cls in DexFileFactory.loadDexFile(f, Opcodes.getDefault()).classes) {
                if (cls.type != "Lcom/pairip/application/Application;") continue
                val sup = cls.superclass ?: continue
                if (sup == "Ljava/lang/Object;" || sup == "Landroid/app/Application;") continue
                return sup.substringAfter("L").substringBefore(";").replace('/', '.').also {
                    logger.info("Discovered real app class from ${f.name}: $it")
                }
            }
        } catch (e: Exception) {
            logger.warning("Failed to parse ${f.name}: ${e.message}")
        }
    }
    return null
}

@Suppress("unused")
val pairipBypassPatch = bytecodePatch(
    name = "PairIP Bypass Enhanced (Experimental)",
    description = """
        A merged experimental PairIP bypass for common legacy, V2, and V3 protection layouts.

        Automatic mode applies compatible strategies up to the selected risk level. It defaults to
        Low Risk Strategies; Low and Med Risk Strategies enable medium-risk strategies, while Low, Med, and
        High Risk Strategies also enables the invasive high-risk strategies.

        Turn off automatic mode to test the individual manual strategies. Manual selections are
        independent of the automatic risk-level setting, and every manual strategy is disabled by default.

        This patch is experimental and app-dependent. It does not bypass server-side Play Integrity,
        server-side licensing, or other server-side enforcement.

        This enhanced patch is a merged product of the PairIP bypass patches from the credited
        developers, with improvements for broader functionality, safer strategy selection, and usability.

        Credits: Nai64Patches from Nai64, Entree, kondratjev, and rushiranpise (Doom).
    """.trimIndent(),
    default = false,
) {
    val automaticStrategySelection by booleanOption(
        key = "automaticStrategySelection",
        default = true,
        title = "PairIP > Automatic > Strategy selection",
        description = "Automatically apply compatible PairIP strategies according to the risk policy below. Turn this off to select individual strategies for testing.",
    )
    val automaticRiskLevel by stringOption(
        key = "automaticRiskLevel",
        default = "low",
        title = "PairIP > Automatic > Risk level",
        description = "Choose the highest risk level that automatic mode may apply. Manual strategy selections are unaffected.",
        values = linkedMapOf(
            "Low Risk Strategies" to "low",
            "Low and Med Risk Strategies" to "lowMedium",
            "Low, Med, and High Risk Strategies" to "all",
        ),
    )
    val applicationRedirectStrategy by booleanOption(
        key = "applicationRedirectStrategy",
        default = false,
        title = "PairIP > Manifest > Redirect Application (Low Risk)",
        description = "Replace the PairIP Application wrapper with the discovered real application class.",
    )
    val manifestCleanupStrategy by booleanOption(
        key = "manifestCleanupStrategy",
        default = false,
        title = "PairIP > Manifest > Remove PairIP entries (Low Risk)",
        description = "Remove PairIP license activities, provider, and CHECK_LICENSE permission from AndroidManifest.xml.",
    )
    // Nai64 Firebase cleanup strategy, integrated with the enhanced patch.
    val disableFirebase by booleanOption(
        key = "disableFirebase",
        default = false,
        title = "PairIP > Manifest > Disable Firebase auto-init (Opt-in)",
        description = "Optional Firebase startup cleanup. Enable only for apps that crash during Firebase measurement initialization; it may affect Firebase Auth or Play Games integrations.",
    )
    var applicationRedirectApplied = false
    var manifestCleanupApplied = false
    var firebaseCleanupApplied = false
    var installerSpoofApplied: String? = null
    var vmCallSitesApplied = 0

    // -- Resource Strategy 1: PairIP Application redirect --
    // Replace the PairIP wrapper with a discovered real Application superclass.
    val applicationRedirectPatch = resourcePatch(
        name = "Pairip Application Redirect (internal)",
        default = false,
    ) {
        execute {
            val logger = Logger.getLogger(this::class.java.name)
            val applyManifestChanges = applicationRedirectStrategy == true ||
                    (automaticStrategySelection == true && automaticRiskLevel != "high")
            if (!applyManifestChanges) {
                logger.info("Pairip Application redirect disabled by strategy selection")
                return@execute
            }

            val real = discoverPairipAppClass(logger) ?: run {
                logger.warning("Could not discover real app class. Skipping manifest redirect.")
                return@execute
            }

            document("AndroidManifest.xml").use { doc ->
                val app = doc.getElementsByTagName("application").item(0) as? Element ?: run {
                    logger.warning("No <application> element found")
                    return@execute
                }
                val ns = "http://schemas.android.com/apk/res/android"
                val cur = app.getAttributeNS(ns, "name")
                    .let { if (!it.isNullOrEmpty()) it else app.getAttribute("android:name") }
                if (cur != "com.pairip.application.Application") {
                    logger.info("Application class is '$cur' - not PairIP, skipping")
                    return@execute
                }
                app.setAttributeNS(ns, "android:name", real)
                applicationRedirectApplied = true
                logger.info("Redirected PairIP -> $real - PairIP Application Redirect (internal) patch succeeded")
            }
        }
    }

    // -- Resource Strategy 2: PairIP license manifest cleanup --
    // Remove only the exact PairIP license components and CHECK_LICENSE permission.
    val pairipLicenseManifestCleanupPatch = resourcePatch(
        name = "Pairip License Manifest Cleanup (internal)",
        default = false,
    ) {
        dependsOn(applicationRedirectPatch)

        execute {
            val logger = Logger.getLogger(this::class.java.name)
            val applyManifestCleanup = manifestCleanupStrategy == true ||
                    (automaticStrategySelection == true && automaticRiskLevel != "high")
            if (!applyManifestCleanup) {
                logger.info("Pairip XML manifest cleanup disabled by strategy selection")
                return@execute
            }

            val androidNamespace = "http://schemas.android.com/apk/res/android"
            val pairipComponents = setOf(
                "com.pairip.licensecheck.LicenseActivity",
                "com.pairip.licensecheck.LicenseContentProvider",
            )
            var removed = 0

            document("AndroidManifest.xml").use { manifest ->
                for (tag in listOf("activity", "provider")) {
                    val nodes = manifest.getElementsByTagName(tag)
                    for (index in nodes.length - 1 downTo 0) {
                        val component = nodes.item(index) as? Element ?: continue
                        val name = component.getAttributeNS(androidNamespace, "name")
                        if (name in pairipComponents) {
                            component.parentNode?.removeChild(component)
                            removed++
                        }
                    }
                }

                val permissions = manifest.getElementsByTagName("uses-permission")
                for (index in permissions.length - 1 downTo 0) {
                    val permission = permissions.item(index) as? Element ?: continue
                    if (permission.getAttributeNS(androidNamespace, "name") == "com.android.vending.CHECK_LICENSE") {
                        permission.parentNode?.removeChild(permission)
                        removed++
                    }
                }
            }

            if (removed > 0) {
                manifestCleanupApplied = true
                logger.info("Removed $removed Pairip license manifest entr${if (removed == 1) "y" else "ies"}")
            }
        }
    }

    // -- Resource Strategy 3: Firebase cleanup --
    // Credit: Nai64Patches / Nai64. This preserves Nai64's metadata switches
    // and measurement-component removal while keeping it independently toggleable.
    val firebaseCleanupPatch = resourcePatch(
        name = "Pairip Firebase Cleanup (internal)",
        default = false,
    ) {
        dependsOn(pairipLicenseManifestCleanupPatch)

        execute {
            val logger = Logger.getLogger(this::class.java.name)
            // Credit: Nai64Patches / Nai64. Keep this opt-in because removing
            // Firebase initialization can affect Firebase Auth and Play Games.
            if (disableFirebase != true) {
                logger.info("Firebase cleanup disabled by strategy selection")
                return@execute
            }

            val switches = mapOf(
                "firebase_analytics_collection_enabled" to "false",
                "firebase_messaging_auto_init_enabled" to "false",
                "firebase_crashlytics_collection_enabled" to "false",
                "firebase_performance_collection_enabled" to "false",
            )
            var added = 0
            var updated = 0
            document("AndroidManifest.xml").use { manifest ->
                val application = manifest.documentElement.applicationOrNull()
                    ?: return@use
                val metadata = application.getElementsByTagName("meta-data")
                for ((name, value) in switches) {
                    var target: Element? = null
                    for (index in 0 until metadata.length) {
                        val meta = metadata.item(index) as? Element ?: continue
                        if (meta.getAttributeNS(NS_ANDROID, "name") == name) {
                            target = meta
                            break
                        }
                    }
                    if (target != null) {
                        if (target.getAttributeNS(NS_ANDROID, "value") != value) {
                            target.setAttributeNS(NS_ANDROID, "android:value", value)
                            updated++
                        }
                    } else {
                        val meta = manifest.createElement("meta-data")
                        meta.setAttributeNS(NS_ANDROID, "android:name", name)
                        meta.setAttributeNS(NS_ANDROID, "android:value", value)
                        application.appendChild(meta)
                        added++
                    }
                }
            }
            if (added > 0 || updated > 0) {
                logger.info("Firebase auto-init: $added switch(es) added, $updated overridden")
            }

            val measurementComponents = setOf(
                "io.invertase.firebase.app.ReactNativeFirebaseAppInitProvider",
                "com.google.firebase.provider.FirebaseInitProvider",
                "com.google.android.gms.measurement.AppMeasurementReceiver",
                "com.google.android.gms.measurement.AppMeasurementService",
                "com.google.android.gms.measurement.AppMeasurementJobService",
                "com.google.android.gms.measurement.AppMeasurementContentProvider",
            )
            var removed = 0
            document("AndroidManifest.xml").use { manifest ->
                for (tag in listOf("provider", "receiver", "service")) {
                    val nodes = manifest.getElementsByTagName(tag)
                    for (index in nodes.length - 1 downTo 0) {
                        val component = nodes.item(index) as? Element ?: continue
                        val name = component.getAttributeNS(NS_ANDROID, "name")
                            .ifEmpty { component.getAttribute("android:name") }
                        if (name in measurementComponents) {
                            component.parentNode?.removeChild(component)
                            removed++
                        }
                    }
                }
            }
            firebaseCleanupApplied = added > 0 || updated > 0 || removed > 0
            if (removed > 0) {
                logger.info("Removed $removed Firebase measurement component(s)")
            } else {
                logger.info("No Firebase measurement components found")
            }
        }
    }

    dependsOn(firebaseCleanupPatch)

    // Every concrete strategy has its own option. The title prefixes organize
    // the manual settings in the same way as the Universal Overlay settings.
    // Automatic mode selects strategies up to the configured risk level; manual
    // mode isolates one transformation.
    // Low-risk strategies: narrow UI, metadata, and repeated-check changes.
    val pairipLicenseClientStartErrorDialog by booleanOption(
        key = "pairipLicenseClientStartErrorDialog",
        default = false,
        title = "PairIP > UI > Suppress LicenseClient error dialog (Low Risk)",
        description = "Disable LicenseClient.startErrorDialogActivity()."
    )
    val pairipLicenseActivityShowErrorDialog by booleanOption(
        key = "pairipLicenseActivityShowErrorDialog",
        default = false,
        title = "PairIP > UI > Suppress LicenseActivity error dialog (Low Risk)",
        description = "Disable LicenseActivity.showErrorDialog()."
    )
    val pairipLicenseActivityLogAndShowErrorDialog by booleanOption(
        key = "pairipLicenseActivityLogAndShowErrorDialog",
        default = false,
        title = "PairIP > UI > Suppress logged error dialog (Low Risk)",
        description = "Disable LicenseActivity.logAndShowErrorDialog()."
    )
    val pairipLicenseResponseHelperGetRepeatedCheckMetadata by booleanOption(
        key = "pairipLicenseResponseHelperGetRepeatedCheckMetadata",
        default = false,
        title = "PairIP > Response > Remove repeated-check metadata (Low Risk)",
        description = "Return null from LicenseResponseHelper.getRepeatedCheckMetadata()."
    )
    val pairipV2ScheduleRepeatedLicenseCheck by booleanOption(
        key = "pairipV2ScheduleRepeatedLicenseCheck",
        default = false,
        title = "PairIP > V2 > Disable repeated checks (Low Risk)",
        description = "Disable V2 repeated license-check scheduling."
    )
    val pairipRepeatedCheckEnabledRead by booleanOption(
        key = "pairipRepeatedCheckEnabledRead",
        default = false,
        title = "PairIP > V2 > Disable repeated-check flag (Low Risk)",
        description = "Force LicenseClient.repeatedCheckEnabled reads to false."
    )

    // Medium-risk strategies: licensing, installer, and response-flow changes.
    val pairipLicenseClientStartPaywall by booleanOption(
        key = "pairipLicenseClientStartPaywall",
        default = false,
        title = "PairIP > UI > Suppress LicenseClient paywall (Medium Risk)",
        description = "Disable LicenseClient.startPaywallActivity()."
    )
    val pairipLicenseActivityShowPaywall by booleanOption(
        key = "pairipLicenseActivityShowPaywall",
        default = false,
        title = "PairIP > UI > Suppress LicenseActivity paywall (Medium Risk)",
        description = "Disable LicenseActivity.showPaywallAndCloseApp()."
    )
    val pairipLicenseActivityNnStart by booleanOption(
        key = "pairipLicenseActivityNnStart",
        default = false,
        title = "PairIP > UI > Suppress LicenseActivity nnStart (Medium Risk)",
        description = "Disable the obfuscated LicenseActivity.nnStart() startup path."
    )
    val pairipLicenseActivityOnStart by booleanOption(
        key = "pairipLicenseActivityOnStart",
        default = false,
        title = "PairIP > UI > Suppress LicenseActivity onStart (Medium Risk)",
        description = "Disable LicenseActivity.onStart()."
    )
    val pairipLicenseActivityCloseApp by booleanOption(
        key = "pairipLicenseActivityCloseApp",
        default = false,
        title = "PairIP > UI > Suppress LicenseActivity closeApp (Medium Risk)",
        description = "Disable LicenseActivity.closeApp()."
    )
    val pairipLicenseActivityExitApp by booleanOption(
        key = "pairipLicenseActivityExitApp",
        default = false,
        title = "PairIP > UI > Suppress LicenseActivity exitApp (Medium Risk)",
        description = "Disable LicenseActivity.exitApp()."
    )
    val pairipLicenseActivityCloseapp by booleanOption(
        key = "pairipLicenseActivityCloseapp",
        default = false,
        title = "PairIP > UI > Suppress LicenseActivity closeapp (Medium Risk)",
        description = "Disable the lowercase LicenseActivity.closeapp() variant."
    )
    val pairipLicenseActivityExitapp by booleanOption(
        key = "pairipLicenseActivityExitapp",
        default = false,
        title = "PairIP > UI > Suppress LicenseActivity exitapp (Medium Risk)",
        description = "Disable the lowercase LicenseActivity.exitapp() variant."
    )
    val pairipLicenseActivityCloseAllTasks by booleanOption(
        key = "pairipLicenseActivityCloseAllTasks",
        default = false,
        title = "PairIP > UI > Suppress LicenseActivity closeAllTasks (Medium Risk)",
        description = "Disable LicenseActivity.closeAllTasks()."
    )
    val pairipPerformLocalInstallerCheck by booleanOption(
        key = "pairipPerformLocalInstallerCheck",
        default = false,
        title = "PairIP > Installer > Spoof local installer check (Medium Risk)",
        description = "Make PairIP performLocalInstallerCheck() report success."
    )
    val pairipLicenseClientCheckLicense by booleanOption(
        key = "pairipLicenseClientCheckLicense",
        default = false,
        title = "PairIP > License Client > Bypass checkLicense (Medium Risk)",
        description = "Disable LicenseClient.checkLicense()."
    )
    val pairipLicenseClientInitializeLicenseCheck by booleanOption(
        key = "pairipLicenseClientInitializeLicenseCheck",
        default = false,
        title = "PairIP > License Client > Bypass initializeLicenseCheck (Medium Risk)",
        description = "Disable LicenseClient.initializeLicenseCheck()."
    )
    val pairipLicenseClientConnectToLicensingService by booleanOption(
        key = "pairipLicenseClientConnectToLicensingService",
        default = false,
        title = "PairIP > License Client > Bypass service connection (Medium Risk)",
        description = "Disable LicenseClient.connectToLicensingService()."
    )
    val pairipLicenseClientProcessResponse by booleanOption(
        key = "pairipLicenseClientProcessResponse",
        default = false,
        title = "PairIP > License Client > Bypass processResponse (Medium Risk)",
        description = "Disable the older LicenseClient.processResponse() path."
    )
    val pairipLicenseResponseHelperValidateResponse by booleanOption(
        key = "pairipLicenseResponseHelperValidateResponse",
        default = false,
        title = "PairIP > Response > Bypass helper validation (Medium Risk)",
        description = "Disable LicenseResponseHelper.validateResponse()."
    )
    val pairipLicenseResponseHelperVerifySignature by booleanOption(
        key = "pairipLicenseResponseHelperVerifySignature",
        default = false,
        title = "PairIP > Response > Bypass helper signature (Medium Risk)",
        description = "Make the legacy response helper signature check succeed."
    )
    val pairipResponseValidatorValidateResponse by booleanOption(
        key = "pairipResponseValidatorValidateResponse",
        default = false,
        title = "PairIP > Response > Bypass validator validation (Medium Risk)",
        description = "Disable the legacy ResponseValidator.validateResponse() path."
    )
    val pairipResponseValidatorVerifySignature by booleanOption(
        key = "pairipResponseValidatorVerifySignature",
        default = false,
        title = "PairIP > Response > Bypass validator signature (Medium Risk)",
        description = "Make the legacy ResponseValidator signature check succeed."
    )
    val pairipResponseValidatorV3ValidateResponse by booleanOption(
        key = "pairipResponseValidatorV3ValidateResponse",
        default = false,
        title = "PairIP > V3 > Bypass response validation (Medium Risk)",
        description = "Disable licensecheck3.ResponseValidator.validateResponse()."
    )

    // High-risk strategies: startup, provider, signature, VM, and broad control-flow changes.
    val pairipApplicationAttachBaseContext by booleanOption(
        key = "pairipApplicationAttachBaseContext",
        default = false,
        title = "PairIP > Application > Bypass attachBaseContext (High Risk)",
        description = "Skip PairIP startup code in Application.attachBaseContext()."
    )
    val pairipApplicationOnCreate by booleanOption(
        key = "pairipApplicationOnCreate",
        default = false,
        title = "PairIP > Application > Bypass onCreate (High Risk)",
        description = "Skip PairIP startup code in Application.onCreate()."
    )
    val pairipApplicationClinit by booleanOption(
        key = "pairipApplicationClinit",
        default = false,
        title = "PairIP > Runtime > Bypass Application static initializer (High Risk)",
        description = "Prevent PairIP Application.<clinit>() from starting its runtime."
    )
    val pairipVmRunnerInvoke by booleanOption(
        key = "pairipVmRunnerInvoke",
        default = false,
        title = "PairIP > Runtime > Bypass VMRunner.invoke (High Risk)",
        description = "Return null from PairIP VMRunner.invoke()."
    )
    val pairipStartupLauncherLaunch by booleanOption(
        key = "pairipStartupLauncherLaunch",
        default = false,
        title = "PairIP > Runtime > Bypass StartupLauncher.launch (High Risk)",
        description = "Disable PairIP StartupLauncher.launch()."
    )
    val pairipStartupLauncherPairip by booleanOption(
        key = "pairipStartupLauncherPairip",
        default = false,
        title = "PairIP > Runtime > Bypass StartupLauncher.pairip (High Risk)",
        description = "Disable the PairIP StartupLauncher.pairip() entry point."
    )
    val pairipLicenseClientV3OnActivityCreate by booleanOption(
        key = "pairipLicenseClientV3OnActivityCreate",
        default = false,
        title = "PairIP > V3 > Bypass LicenseClient activity (High Risk)",
        description = "Disable LicenseClientV3.onActivityCreate()."
    )
    val pairipGenericInstallerSource by booleanOption(
        key = "pairipGenericInstallerSource",
        default = false,
        title = "PairIP > Installer > Spoof installer source (High Risk)",
        description = "Return the Play Store package name from a generic installer-source check."
    )
    val pairipSignatureVerifyIntegrity by booleanOption(
        key = "pairipSignatureVerifyIntegrity",
        default = false,
        title = "PairIP > Integrity > Bypass signature integrity (High Risk)",
        description = "Disable SignatureCheck.verifyIntegrity()."
    )
    val pairipSignatureVerifySignatureMatches by booleanOption(
        key = "pairipSignatureVerifySignatureMatches",
        default = false,
        title = "PairIP > Integrity > Bypass signature match (High Risk)",
        description = "Make SignatureCheck.verifySignatureMatches() report success."
    )
    val pairipLicenseContentProviderOnCreate by booleanOption(
        key = "pairipLicenseContentProviderOnCreate",
        default = false,
        title = "PairIP > Provider > Bypass initialization (High Risk)",
        description = "Make LicenseContentProvider.onCreate() report success."
    )
    val pairipLicenseContentProviderQuery by booleanOption(
        key = "pairipLicenseContentProviderQuery",
        default = false,
        title = "PairIP > Provider > Bypass query (High Risk)",
        description = "Return no result from LicenseContentProvider.query()."
    )
    val pairipInitContextProviderGetContext by booleanOption(
        key = "pairipInitContextProviderGetContext",
        default = false,
        title = "PairIP > Provider > Bypass context provider (High Risk)",
        description = "Return null from InitContextProvider.getContext()."
    )
    val pairipV2CheckLicenseInternal by booleanOption(
        key = "pairipV2CheckLicenseInternal",
        default = false,
        title = "PairIP > V2 > Bypass checkLicenseInternal (High Risk)",
        description = "Route the V2 check directly to its success callback."
    )
    val pairipV2VerifySignature by booleanOption(
        key = "pairipV2VerifySignature",
        default = false,
        title = "PairIP > V2 > Bypass response signature (High Risk)",
        description = "Disable the V2 response signature check."
    )
    val vmCallSiteChecks by booleanOption(
        key = "vmCallSiteChecks",
        default = false,
        title = "PairIP > Advanced > External VMRunner call sites (High Risk)",
        description = "Neutralize void callers of VMRunner.invoke() outside com.pairip. This is invasive and may affect app features."
    )

    execute {
        val logger = Logger.getLogger(this::class.java.name)

        // Automatic mode is intentionally conservative. Strategies that rewrite
        // application startup, provider state, installer identity, signatures, or
        // VM control flow must be explicitly enabled by the user. Manual mode still
        // honors every individual option exactly as before.
        fun isSelected(option: Boolean?, risk: String = "medium") =
            option == true || (automaticStrategySelection == true && when (automaticRiskLevel) {
                "low" -> risk == "low"
                "lowMedium" -> risk == "low" || risk == "medium"
                "all" -> true
                else -> risk == "low" || risk == "medium"
            })

        // Internal group gates keep the execution readable, but each concrete
        // operation below is guarded by its own manual option.
        val applyLocalInstallerChecks =
            isSelected(pairipPerformLocalInstallerCheck) ||
                    isSelected(pairipGenericInstallerSource, risk = "high")
        val applySignatureChecks =
            isSelected(pairipSignatureVerifyIntegrity, risk = "high") ||
                    isSelected(pairipSignatureVerifySignatureMatches, risk = "high")
        val applyLicenseUiSuppression = isSelected(pairipLicenseClientStartErrorDialog, risk = "low") ||
                isSelected(pairipLicenseClientStartPaywall) ||
                isSelected(pairipLicenseActivityShowPaywall) ||
                isSelected(pairipLicenseActivityNnStart) ||
                isSelected(pairipLicenseActivityOnStart) ||
                isSelected(pairipLicenseActivityCloseApp) ||
                isSelected(pairipLicenseActivityExitApp) ||
                isSelected(pairipLicenseActivityCloseapp) ||
                isSelected(pairipLicenseActivityExitapp) ||
                isSelected(pairipLicenseActivityCloseAllTasks) ||
                isSelected(pairipLicenseActivityShowErrorDialog, risk = "low") ||
                isSelected(pairipLicenseActivityLogAndShowErrorDialog, risk = "low")
        val applyApplicationStartupHooks =
            isSelected(pairipApplicationAttachBaseContext, risk = "high") ||
                    isSelected(pairipApplicationOnCreate, risk = "high")
        val applyLicenseClientChecks = isSelected(pairipLicenseClientCheckLicense) ||
                isSelected(pairipLicenseClientInitializeLicenseCheck) ||
                isSelected(pairipLicenseClientConnectToLicensingService) ||
                isSelected(pairipLicenseClientProcessResponse)
        val applyContentProviderChecks = isSelected(pairipLicenseContentProviderOnCreate, risk = "high") ||
                isSelected(pairipLicenseContentProviderQuery, risk = "high") ||
                isSelected(pairipInitContextProviderGetContext, risk = "high")
        val applyResponseValidationChecks = isSelected(pairipLicenseResponseHelperValidateResponse) ||
                isSelected(pairipLicenseResponseHelperGetRepeatedCheckMetadata, risk = "low") ||
                isSelected(pairipLicenseResponseHelperVerifySignature) ||
                isSelected(pairipResponseValidatorValidateResponse) ||
                isSelected(pairipResponseValidatorVerifySignature) ||
                isSelected(pairipResponseValidatorV3ValidateResponse)
        val applyPairipV2Checks = isSelected(pairipV2CheckLicenseInternal, risk = "high") ||
                isSelected(pairipV2VerifySignature, risk = "high") ||
                isSelected(pairipV2ScheduleRepeatedLicenseCheck, risk = "low") ||
                isSelected(pairipRepeatedCheckEnabledRead, risk = "low")
        val applyPairipVmRunnerChecks = isSelected(pairipApplicationClinit, risk = "high") ||
                isSelected(pairipVmRunnerInvoke, risk = "high")
        val applyStartupLauncherChecks =
            isSelected(pairipStartupLauncherLaunch, risk = "high") ||
                    isSelected(pairipStartupLauncherPairip, risk = "high")
        val applyLicenseClientV3Activity = isSelected(pairipLicenseClientV3OnActivityCreate, risk = "high")
        val applyVmCallSiteChecks = isSelected(vmCallSiteChecks, risk = "high")
        var repeatedCheckReadApplied = false

        if (applyLocalInstallerChecks) {
            // -- Strategy 1: Local installer check --
            // Report a successful local installer check.
            if (isSelected(pairipPerformLocalInstallerCheck)) PerformLocalInstallerCheckFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, listOf(
                        BuilderInstruction11n(Opcode.CONST_4, 0, 1),
                        BuilderInstruction11x(Opcode.RETURN, 0),
                    )
                )
                logger.info("Applied Pairip performLocalInstallerCheck spoof")
            }

            // -- Strategy 2: Generic Play Store installer spoof --
            // Credit: Nai64Patches / Nai64. Try the boolean and string forms,
            // then the fallback fingerprints, stopping after the first match.
            if (isSelected(pairipGenericInstallerSource, risk = "high")) {
                if (installerSpoofApplied == null) GenericBooleanInstallerCheckFingerprint.methodOrNull?.let {
                    it.addInstructions(0, listOf(
                        BuilderInstruction11n(Opcode.CONST_4, 0, 1),
                        BuilderInstruction11x(Opcode.RETURN, 0),
                    ))
                    installerSpoofApplied = "generic boolean installer spoof"
                    logger.info("Applied generic boolean Play Store spoof")
                }
                if (installerSpoofApplied == null) GenericStringInstallerCheckFingerprint.methodOrNull?.let {
                    it.addInstructions(0, """
                        const-string v0, "com.android.vending"
                        return-object v0
                    """.trimIndent())
                    installerSpoofApplied = "generic string installer spoof"
                    logger.info("Applied Play Store installer source spoof")
                }
                if (installerSpoofApplied == null) FallbackBooleanInstallerCheckFingerprint.methodOrNull?.let {
                    it.addInstructions(0, listOf(
                        BuilderInstruction11n(Opcode.CONST_4, 0, 1),
                        BuilderInstruction11x(Opcode.RETURN, 0),
                    ))
                    installerSpoofApplied = "fallback boolean installer spoof"
                    logger.info("Applied fallback boolean Play Store spoof")
                }
                if (installerSpoofApplied == null) FallbackStringInstallerCheckFingerprint.methodOrNull?.let {
                    it.addInstructions(0, """
                        const-string v0, "com.android.vending"
                        return-object v0
                    """.trimIndent())
                    installerSpoofApplied = "fallback string installer spoof"
                    logger.info("Applied fallback installer source spoof")
                }
            }

        }

        if (applySignatureChecks) {
            // -- Strategy 3: APK signature integrity check --
            // Skip PairIP's APK integrity verification routine.
            if (isSelected(pairipSignatureVerifyIntegrity)) PairipSignatureCheckVerifyIntegrityFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip SignatureCheck.verifyIntegrity bypass")
            }

            // -- Strategy 4: Signature match check --
            // Report a successful signature match to PairIP.
            if (isSelected(pairipSignatureVerifySignatureMatches)) PairipSignatureCheckVerifySignatureMatchesFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, listOf(
                        BuilderInstruction11n(Opcode.CONST_4, 0, 1),
                        BuilderInstruction11x(Opcode.RETURN, 0),
                    )
                )
                logger.info("Applied Pairip SignatureCheck.verifySignatureMatches bypass")
            }

        }

        if (applyLicenseUiSuppression) {
            // -- Strategy 5: LicenseClient error dialog --
            // Prevent the PairIP license failure dialog from opening.
            if (isSelected(pairipLicenseClientStartErrorDialog, risk = "low")) PairipLicenseClientStartErrorDialogFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip LicenseClient error dialog suppress")
            }

            // -- Strategy 6: LicenseClient paywall --
            // Prevent the PairIP Play Store paywall activity from opening.
            if (isSelected(pairipLicenseClientStartPaywall)) PairipLicenseClientStartPaywallFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip LicenseClient paywall suppress")
            }

            // -- Strategy 7: LicenseActivity.showPaywallAndCloseApp --
            // Suppress the PairIP activity's paywall-and-exit flow.
            if (isSelected(pairipLicenseActivityShowPaywall)) PairipLicenseActivityShowPaywallFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip LicenseActivity paywall suppress")
            }

            // -- Strategy 8: LicenseActivity.nnStart / onStart --
            // Suppress PairIP license activity startup variants used across
            // obfuscated and non-obfuscated PairIP builds.
            if (isSelected(pairipLicenseActivityNnStart)) PairipLicenseActivityNnStartFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseActivity.nnStart suppress")
            }
            if (isSelected(pairipLicenseActivityOnStart)) PairipLicenseActivityOnStartFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseActivity.onStart suppress")
            }

            // -- Strategy 9: LicenseActivity.closeApp --
            // Prevent the PairIP license activity from closing the app.
            if (isSelected(pairipLicenseActivityCloseApp)) PairipLicenseActivityCloseMethodFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseActivity.closeApp suppress")
            }

            // -- Strategy 10: LicenseActivity.exitApp --
            // Prevent the PairIP license activity from exiting the app.
            if (isSelected(pairipLicenseActivityExitApp)) PairipLicenseActivityExitMethodFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseActivity.exitApp suppress")
            }

            // -- Strategy 11: LicenseActivity.closeapp --
            // Suppress the case-sensitive lowercase close-app variant.
            if (isSelected(pairipLicenseActivityCloseapp)) PairipCloseappFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseActivity.closeapp suppress")
            }

            // -- Strategy 12: LicenseActivity.exitapp --
            // Suppress the case-sensitive lowercase exit-app variant.
            if (isSelected(pairipLicenseActivityExitapp)) PairipExitappFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseActivity.exitapp suppress")
            }

            // Entree also covers these error-path names used by older and lightly
            // obfuscated PairIP builds. Each fingerprint is return-type constrained
            // so the injected instruction remains valid bytecode.
            if (isSelected(pairipLicenseActivityCloseAllTasks)) PairipLicenseActivityCloseAllTasksFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseActivity.closeAllTasks suppress")
            }
            if (isSelected(pairipLicenseActivityShowErrorDialog, risk = "low")) PairipLicenseActivityShowErrorDialogFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseActivity.showErrorDialog suppress")
            }
            if (isSelected(pairipLicenseActivityLogAndShowErrorDialog, risk = "low")) PairipLicenseActivityLogAndShowErrorDialogFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseActivity.logAndShowErrorDialog suppress")
            }

        }

        if (applyApplicationStartupHooks) {
            // -- Strategy 13: Application.attachBaseContext --
            // Skip PairIP's early Application startup code while preserving framework initialization.
            if (isSelected(pairipApplicationAttachBaseContext)) PairipApplicationAttachBaseContextFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                invoke-super {p0, p1}, Landroid/app/Application;->attachBaseContext(Landroid/content/Context;)V
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip Application.attachBaseContext bypass")
            }

            // -- Strategy 14: Application.onCreate --
            // Skip PairIP's Application onCreate startup hook.
            if (isSelected(pairipApplicationOnCreate)) PairipApplicationOnCreateFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                invoke-super {p0}, Landroid/app/Application;->onCreate()V
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip Application.onCreate bypass")
            }

        }

        if (applyPairipVmRunnerChecks) {
            // -- Strategy 15: Application.<clinit> --
            // Prevent static PairIP initialization from starting the VM or native core.
            if (isSelected(pairipApplicationClinit)) PairipApplicationClinitFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip Application.<clinit> bypass")
            }

            // -- Strategy 16: VMRunner.invoke --
            // Return null instead of executing PairIP VM instructions.
            if (isSelected(pairipVmRunnerInvoke)) PairipVMRunnerInvokeFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                const/4 v0, 0x0
                return-object v0
            """.trimIndent()
                )
                logger.info("Applied PairIP VMRunner.invoke bypass")
            }
        }

        if (applyStartupLauncherChecks) {
            // -- Strategy 17: StartupLauncher.launch --
            // Disable the PairIP startup dispatcher.
            if (isSelected(pairipStartupLauncherLaunch)) PairipStartupLauncherLaunchFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied PairIP StartupLauncher.launch bypass")
            }

            // -- Strategy 18: StartupLauncher.pairip --
            // Disable the PairIP dispatcher entry point.
            if (isSelected(pairipStartupLauncherPairip)) PairipStartupLauncherPairipFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied PairIP StartupLauncher.pairip bypass")
            }
        }

        if (applyLicenseClientV3Activity) {
            // -- Strategy 19: LicenseClientV3.onActivityCreate --
            // Disable the newer PairIP V3 license activity path.
            if (isSelected(pairipLicenseClientV3OnActivityCreate)) PairipLicenseClientV3OnActivityCreateFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseClientV3.onActivityCreate bypass")
            }
        }

        if (applyLicenseClientChecks) {
            // -- Strategy 20: LicenseClient.checkLicense --
            // Prevent the legacy license check from taking the root-termination path.
            if (isSelected(pairipLicenseClientCheckLicense)) PairipLicenseClientCheckLicenseFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip LicenseClient.checkLicense root kill")
            }

            // -- Strategy 21: LicenseClient.initializeLicenseCheck --
            // Prevent the legacy client from scheduling or starting its license check.
            if (isSelected(pairipLicenseClientInitializeLicenseCheck)) PairipLicenseClientInitializeLicenseCheckFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseClient.initializeLicenseCheck suppress")
            }

            // Entree's older PairIP coverage includes these response-path methods,
            // which may exist before the newer ResponseValidator classes appear.
            if (isSelected(pairipLicenseClientConnectToLicensingService)) PairipLicenseClientConnectToLicensingServiceFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseClient.connectToLicensingService bypass")
            }
            if (isSelected(pairipLicenseClientProcessResponse)) PairipLicenseClientProcessResponseFingerprint.methodOrNull?.let {
                it.addInstructions(0, "return-void")
                logger.info("Applied Pairip LicenseClient.processResponse bypass")
            }

        }

        if (applyContentProviderChecks) {
            // -- Strategy 22: LicenseContentProvider.onCreate --
            // Report successful provider initialization to PairIP.
            if (isSelected(pairipLicenseContentProviderOnCreate)) PairipLicenseContentProviderOnCreateFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, listOf(
                        BuilderInstruction11n(Opcode.CONST_4, 0, 1),
                        BuilderInstruction11x(Opcode.RETURN, 0),
                    )
                )
                logger.info("Applied Pairip LicenseContentProvider.onCreate bypass")
            }

            // -- Strategy 23: LicenseContentProvider.query --
            // Return no result from the PairIP license provider query.
            if (isSelected(pairipLicenseContentProviderQuery)) PairipLicenseContentProviderQueryFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                const/4 v0, 0x0
                return-object v0
            """.trimIndent()
                )
                logger.info("Applied Pairip LicenseContentProvider.query bypass")
            }

            // -- Strategy 24: InitContextProvider.getContext --
            // Prevent PairIP from retrieving its initialization context.
            if (isSelected(pairipInitContextProviderGetContext)) PairipInitContextProviderGetContextFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                const/4 v0, 0x0
                return-object v0
            """.trimIndent()
                )
                logger.info("Applied Pairip InitContextProvider.getContext bypass")
            }

        }

        if (applyResponseValidationChecks) {
            // -- Strategy 25: LicenseResponseHelper.validateResponse --
            // Skip legacy license-response validation.
            if (isSelected(pairipLicenseResponseHelperValidateResponse)) PairipLicenseResponseHelperValidateResponseFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip LicenseResponseHelper.validateResponse bypass")
            }

            // -- Strategy 26: LicenseResponseHelper.getRepeatedCheckMetadata --
            // Remove metadata used to schedule repeated license checks.
            if (isSelected(pairipLicenseResponseHelperGetRepeatedCheckMetadata, risk = "low")) PairipLicenseResponseHelperGetRepeatedCheckMetadataFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                const/4 v0, 0x0
                return-object v0
            """.trimIndent()
                )
                logger.info("Applied Pairip LicenseResponseHelper.getRepeatedCheckMetadata bypass")
            }

            // -- Strategy 27: LicenseResponseHelper.verifySignature --
            // Report a successful response signature check.
            if (isSelected(pairipLicenseResponseHelperVerifySignature)) PairipLicenseResponseHelperVerifySignatureFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, listOf(
                        BuilderInstruction11n(Opcode.CONST_4, 0, 1),
                        BuilderInstruction11x(Opcode.RETURN, 0),
                    )
                )
                logger.info("Applied Pairip LicenseResponseHelper.verifySignature bypass")
            }

            // -- Strategy 28: ResponseValidator.validateResponse --
            // Skip the legacy ResponseValidator response check.
            if (isSelected(pairipResponseValidatorValidateResponse)) PairipResponseValidatorValidateResponseFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip ResponseValidator.validateResponse bypass")
            }

            // -- Strategy 29: ResponseValidator.verifySignature --
            // Report a successful ResponseValidator signature check.
            if (isSelected(pairipResponseValidatorVerifySignature)) PairipResponseValidatorVerifySignatureFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, listOf(
                        BuilderInstruction11n(Opcode.CONST_4, 0, 1),
                        BuilderInstruction11x(Opcode.RETURN, 0),
                    )
                )
                logger.info("Applied Pairip ResponseValidator.verifySignature bypass")
            }

            // -- Strategy 30: licensecheck3 ResponseValidator.validateResponse --
            // Skip the observed V3 response validation path.
            if (isSelected(pairipResponseValidatorV3ValidateResponse)) PairipResponseValidatorV3ValidateResponseFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip licensecheck3 ResponseValidator.validateResponse bypass")
            }

        }

        if (applyPairipV2Checks) {
            // -- Strategy 31: PairIP V2 checkLicenseInternal --
            // Report a successful V2 license result through the supplied binder.
            // V2 routes the verification result back to the app through the IBinder
            // listener supplied to checkLicenseInternal. Short-circuit it to call the
            // success path directly so the app unlocks regardless of the (now
            // neutralized) signature / response checks.
            if (isSelected(pairipV2CheckLicenseInternal)) PairipV2CheckLicenseInternalFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                invoke-virtual {p0, p1}, Lcom/pairip/licensecheck/LicenseClient;->reportSuccessfulLicenseCheck(Landroid/os/IBinder;)V
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip V2 checkLicenseInternal force-success")
            }

            // -- Strategy 32: PairIP V2 LicenseResponseHelper.verifySignature --
            // Skip the V2 response signature verification routine.
            // V2's verifySignature returns void (V1 returned Z); neutralize it so the
            // JWS signature of the license response is never rejected.
            if (isSelected(pairipV2VerifySignature)) PairipV2LicenseResponseHelperVerifySignatureFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip V2 LicenseResponseHelper.verifySignature bypass")
            }

            // -- Strategy 33: PairIP V2 scheduleRepeatedLicenseCheck --
            // Stop PairIP from scheduling a later background recheck.
            // Stops PairIP from re-verifying (and potentially re-locking) the app in
            // the background after the initial unlock.
            if (isSelected(pairipV2ScheduleRepeatedLicenseCheck, risk = "low")) PairipV2ScheduleRepeatedLicenseCheckFingerprint.methodOrNull?.let {
                it.addInstructions(
                    0, """
                return-void
            """.trimIndent()
                )
                logger.info("Applied Pairip V2 scheduleRepeatedLicenseCheck suppress")
            }

            // kondratjev's field-level fallback survives PairIP moving the read
            // into a differently named method. The fingerprint is restricted to
            // LicenseClient.repeatedCheckEnabled, never arbitrary app booleans.
            if (isSelected(pairipRepeatedCheckEnabledRead, risk = "low")) with(this@execute) { PairipRepeatedCheckEnabledReadFingerprint.matchOrNull() }?.let { match ->
                val read = match.instructionMatches.firstOrNull()
                val register = read?.getInstruction<OneRegisterInstruction>()?.registerA
                if (register != null) {
                    match.method.addInstructions(read.index + 1, "const/4 v$register, 0x0")
                    repeatedCheckReadApplied = true
                    logger.info("Applied Pairip repeatedCheckEnabled field-read suppress")
                }
            }

        }

        if (applyVmCallSiteChecks) {
            // Doom also neutralizes callers outside com.pairip.*. This is kept
            // as an explicit VM option because a caller may legitimately use
            // the VM result for application functionality.
            classDefForEach { classDef ->
                if (classDef.type.startsWith("Lcom/pairip/")) return@classDefForEach

                val callers = classDef.methods.filter { method ->
                    method.implementation?.instructions?.any { instruction ->
                        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        reference?.definingClass == "Lcom/pairip/VMRunner;" &&
                                reference.name == "invoke"
                    } == true
                }
                if (callers.isEmpty()) return@classDefForEach

                val mutableClass = mutableClassDefByOrNull(classDef.type) ?: return@classDefForEach
                callers.forEach { caller ->
                    mutableClass.methods.firstOrNull {
                        it.name == caller.name && it.returnType == caller.returnType
                    }?.let {
                        // A caller's return value may be used by the app. Only
                        // short-circuit void callers here; emitting return-void
                        // for a non-void caller would create invalid bytecode.
                        if (it.returnType == "V") {
                            it.addInstructions(0, "return-void")
                            vmCallSitesApplied++
                        } else {
                            logger.warning("Skipped non-void external VMRunner caller ${it.definingClass}->${it.name}")
                        }
                    }
                }
            }
            logger.info("Applied Pairip external VMRunner call-site suppress to $vmCallSitesApplied method(s)")
        }

        val applied = buildList {
            fun addIfMatched(enabled: Boolean, name: String, matched: Boolean) {
                if (enabled && matched) add(name)
            }

            if (applicationRedirectApplied) add("manifest Application redirect")
            if (manifestCleanupApplied) add("manifest license cleanup")
            if (firebaseCleanupApplied) add("Firebase cleanup")
            if (isSelected(vmCallSiteChecks, risk = "high") && vmCallSitesApplied > 0) {
                add("external VMRunner call sites")
            }

            addIfMatched(
                isSelected(pairipPerformLocalInstallerCheck),
                "performLocalInstallerCheck",
                PerformLocalInstallerCheckFingerprint.methodOrNull != null
            )
            installerSpoofApplied?.let { add(it) }
            addIfMatched(
                isSelected(pairipSignatureVerifyIntegrity, risk = "high"),
                "verifyIntegrity",
                PairipSignatureCheckVerifyIntegrityFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipSignatureVerifySignatureMatches, risk = "high"),
                "verifySignatureMatches",
                PairipSignatureCheckVerifySignatureMatchesFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseClientStartErrorDialog, risk = "low"),
                "errorDialog",
                PairipLicenseClientStartErrorDialogFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseClientStartPaywall),
                "paywall",
                PairipLicenseClientStartPaywallFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseActivityShowPaywall),
                "showPaywallAndCloseApp",
                PairipLicenseActivityShowPaywallFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseActivityNnStart),
                "nnStart",
                PairipLicenseActivityNnStartFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseActivityOnStart),
                "onStart",
                PairipLicenseActivityOnStartFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseActivityCloseApp),
                "closeApp",
                PairipLicenseActivityCloseMethodFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseActivityExitApp),
                "exitApp",
                PairipLicenseActivityExitMethodFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseActivityCloseapp),
                "closeapp",
                PairipCloseappFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseActivityExitapp),
                "exitapp",
                PairipExitappFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseActivityCloseAllTasks),
                "closeAllTasks",
                PairipLicenseActivityCloseAllTasksFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseActivityShowErrorDialog, risk = "low"),
                "showErrorDialog",
                PairipLicenseActivityShowErrorDialogFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseActivityLogAndShowErrorDialog, risk = "low"),
                "logAndShowErrorDialog",
                PairipLicenseActivityLogAndShowErrorDialogFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipApplicationAttachBaseContext, risk = "high"),
                "attachBaseContext",
                PairipApplicationAttachBaseContextFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipApplicationOnCreate, risk = "high"),
                "onCreate",
                PairipApplicationOnCreateFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipApplicationClinit, risk = "high"),
                "Application.<clinit>",
                PairipApplicationClinitFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipVmRunnerInvoke, risk = "high"),
                "VMRunner.invoke",
                PairipVMRunnerInvokeFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipStartupLauncherLaunch, risk = "high"),
                "StartupLauncher.launch",
                PairipStartupLauncherLaunchFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipStartupLauncherPairip, risk = "high"),
                "StartupLauncher.pairip",
                PairipStartupLauncherPairipFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseClientV3OnActivityCreate, risk = "high"),
                "LicenseClientV3.onActivityCreate",
                PairipLicenseClientV3OnActivityCreateFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseClientCheckLicense),
                "checkLicense",
                PairipLicenseClientCheckLicenseFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseClientInitializeLicenseCheck),
                "initializeLicenseCheck",
                PairipLicenseClientInitializeLicenseCheckFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseClientConnectToLicensingService),
                "connectToLicensingService",
                PairipLicenseClientConnectToLicensingServiceFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseClientProcessResponse),
                "processResponse",
                PairipLicenseClientProcessResponseFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseContentProviderOnCreate, risk = "high"),
                "onCreate (ContentProvider)",
                PairipLicenseContentProviderOnCreateFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseContentProviderQuery, risk = "high"),
                "query",
                PairipLicenseContentProviderQueryFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipInitContextProviderGetContext, risk = "high"),
                "getContext",
                PairipInitContextProviderGetContextFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseResponseHelperValidateResponse),
                "validateResponse (ResponseHelper)",
                PairipLicenseResponseHelperValidateResponseFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseResponseHelperGetRepeatedCheckMetadata, risk = "low"),
                "getRepeatedCheckMetadata",
                PairipLicenseResponseHelperGetRepeatedCheckMetadataFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipLicenseResponseHelperVerifySignature),
                "verifySignature (ResponseHelper)",
                PairipLicenseResponseHelperVerifySignatureFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipResponseValidatorValidateResponse),
                "validateResponse (ResponseValidator)",
                PairipResponseValidatorValidateResponseFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipResponseValidatorVerifySignature),
                "verifySignature (ResponseValidator)",
                PairipResponseValidatorVerifySignatureFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipResponseValidatorV3ValidateResponse),
                "validateResponse (V3)",
                PairipResponseValidatorV3ValidateResponseFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipV2CheckLicenseInternal, risk = "high"),
                "checkLicenseInternal (V2)",
                PairipV2CheckLicenseInternalFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipV2VerifySignature, risk = "high"),
                "verifySignature (V2)",
                PairipV2LicenseResponseHelperVerifySignatureFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipV2ScheduleRepeatedLicenseCheck, risk = "low"),
                "scheduleRepeatedLicenseCheck (V2)",
                PairipV2ScheduleRepeatedLicenseCheckFingerprint.methodOrNull != null
            )
            addIfMatched(
                isSelected(pairipRepeatedCheckEnabledRead, risk = "low"),
                "repeatedCheckEnabled field read",
                repeatedCheckReadApplied
            )
        }
        if (applied.isEmpty()) {
            val reason = if (automaticStrategySelection == true) {
                "No Pairip license methods found. No changes applied."
            } else {
                "No selected Pairip strategies matched. No changes applied."
            }
            logger.warning(reason)
        } else {
            logger.info("PairIP Bypass Enhanced (Experimental) patch succeeded (${applied.size} strategy(s) applied)")
            logger.warning("IF THE PATCHED APP STILL DOES NOT WORK AFTER ALL AVAILABLE STRATEGIES HAVE BEEN TRIED, STOP TRYING.")
            logger.warning("DO NOT ASK FOR A FIX; THIS PATCH CANNOT MAKE THAT APP WORK.")
            logger.warning("THE APP IS NOT SUPPORTED BY THIS PATCH AND WILL NOT WORK.")
        }
    }
}
