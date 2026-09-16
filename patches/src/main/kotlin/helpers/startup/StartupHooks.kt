package helpers.startup

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import helpers.manifest.NS_ANDROID
import helpers.manifest.applicationOrNull

/**
 * Shared plumbing for the startup toast/dialog patches.
 *
 * Injections are written into cloned methods that received extra registers
 * ([helpers.bytecode.cloneMutable]), so every temporary register is
 * guaranteed valid regardless of the original register layout. The context
 * register (`p0`) keeps its original absolute number and stays untouched.
 */
internal object StartupHooks {
    /** Name of the static boolean guard field added to the Application class. */
    const val GUARD_FIELD = "unipatchStartupShown"

    /**
     * Real Application class descriptor resolved from the manifest's
     * `android:name` (e.g. `Lcom/peacock/flashlight/App;`), or `null` when the
     * manifest could not be read. `BytecodePatchContext` has no manifest access
     * of its own, so the [resolveRealApplicationPatch] resource patch discovers
     * it and publishes it here for the bytecode patches to consume.
     */
    var resolvedApplicationDescriptor: String? = null

    /** Package name read from the manifest for narrow app-specific patch targeting. */
    var resolvedPackageName: String? = null

    /**
     * Launcher activity descriptor resolved from the manifest's MAIN/LAUNCHER
     * intent filter (e.g. `Lcom/peacock/flashlight/pages/splash/SplashActivity;`).
     * When set and the app has more than one activity, the dialog patch skips
     * injecting into the launcher so the dialog does not flash on a transient
     * splash screen that immediately transitions to the real main activity.
     */
    var resolvedLauncherActivityDescriptor: String? = null

    /** Activity descriptors declared with android:noHistory="true". */
    var resolvedNoHistoryActivityDescriptors: Set<String> = emptySet()

    /** Application class whose onCreate already owns the Universal Overlay bridge in this run. */
    var overlayApplicationBridgeOwner: String? = null

    /** Reads the Application and launcher classes declared in the manifest. */
    val resolveRealApplicationPatch = resourcePatch(
        name = "Resolve Real Application (internal)",
        default = false,
    ) {
        execute {
            try {
                document("AndroidManifest.xml").use { doc ->
                    val application = doc.documentElement.applicationOrNull()
                    resolvedPackageName = doc.documentElement.getAttribute("package").ifEmpty { null }
                    val appName = application?.getAttributeNS(NS_ANDROID, "name")
                        ?.ifEmpty { application.getAttribute("android:name") }
                        .orEmpty()
                    resolvedApplicationDescriptor = if (!appName.isNullOrEmpty()) {
                        componentDescriptor(appName, doc.documentElement.getAttribute("package"))
                    } else {
                        null
                    }

                    resolvedLauncherActivityDescriptor = findLauncherActivity(doc.documentElement)
                    resolvedNoHistoryActivityDescriptors = findNoHistoryActivities(doc.documentElement)
                    overlayApplicationBridgeOwner = null
                    println(
                        "Universal Overlay startup resolution: package=$resolvedPackageName " +
                            "application=$resolvedApplicationDescriptor " +
                            "launcher=$resolvedLauncherActivityDescriptor " +
                            "noHistory=${resolvedNoHistoryActivityDescriptors.size}",
                    )
                }
            } catch (error: Exception) {
                resolvedApplicationDescriptor = null
                resolvedPackageName = null
                resolvedLauncherActivityDescriptor = null
                resolvedNoHistoryActivityDescriptors = emptySet()
                overlayApplicationBridgeOwner = null
                println("Universal Overlay startup resolution failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    private fun findNoHistoryActivities(root: Element): Set<String> {
        val packageName = root.getAttribute("package")
        val result = mutableSetOf<String>()
        for (activity in descendants(root, "activity")) {
            val noHistory = activity.getAttributeNS(NS_ANDROID, "noHistory")
                .ifEmpty { activity.getAttribute("android:noHistory") }
            if (noHistory == "true") {
                val name = activity.getAttributeNS(NS_ANDROID, "name")
                    .ifEmpty { activity.getAttribute("android:name") }
                if (name.isNotEmpty()) result += componentDescriptor(name, packageName)
            }
        }
        return result
    }

    private fun descendants(root: Element, localName: String): List<Element> {
        val plain = root.getElementsByTagName(localName)
        if (plain.length > 0) return (0 until plain.length).mapNotNull { plain.item(it) as? Element }
        val namespaced = root.getElementsByTagNameNS("*", localName)
        return (0 until namespaced.length).mapNotNull { namespaced.item(it) as? Element }
    }

    /** Returns the descriptor of the activity with a MAIN/LAUNCHER filter, or null. */
    private fun findLauncherActivity(root: Element): String? {
        val packageName = root.getAttribute("package")
        fun attr(element: Element, name: String): String =
            element.getAttributeNS(NS_ANDROID, name).ifEmpty { element.getAttribute("android:$name") }
        for (activity in descendants(root, "activity")) {
            if (attr(activity, "enabled") == "false") continue
            var hasMain = false
            var hasLauncher = false
            for (filter in descendants(activity, "intent-filter")) {
                for (action in descendants(filter, "action")) {
                    if (attr(action, "name") == "android.intent.action.MAIN") {
                        hasMain = true
                    }
                }
                for (category in descendants(filter, "category")) {
                    if (attr(category, "name") == "android.intent.category.LAUNCHER") {
                        hasLauncher = true
                    }
                }
                if (hasMain && hasLauncher) break
            }
            if (hasMain && hasLauncher) {
                val name = attr(activity, "name")
                if (!name.isNullOrEmpty()) return componentDescriptor(name, packageName)
            }
        }
        for (alias in descendants(root, "activity-alias")) {
            if (attr(alias, "enabled") == "false") continue
            var hasMain = false
            var hasLauncher = false
            for (filter in descendants(alias, "intent-filter")) {
                for (action in descendants(filter, "action")) {
                    if (attr(action, "name") == "android.intent.action.MAIN") hasMain = true
                }
                for (category in descendants(filter, "category")) {
                    if (attr(category, "name") == "android.intent.category.LAUNCHER") hasLauncher = true
                }
            }
            if (hasMain && hasLauncher) {
                val target = attr(alias, "targetActivity")
                if (!target.isNullOrEmpty()) return componentDescriptor(target, packageName)
            }
        }
        return null
    }

    private fun componentDescriptor(name: String, packageName: String): String {
        val qualifiedName = when {
            name.startsWith(".") -> packageName + name
            '.' !in name -> "$packageName.$name"
            else -> name
        }
        return "L" + qualifiedName.replace('.', '/') + ";"
    }

    fun escapeSmali(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\n")
            .replace("\t", "\\t")
}
