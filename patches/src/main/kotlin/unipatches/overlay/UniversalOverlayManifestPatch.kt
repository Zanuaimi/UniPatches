package unipatches.overlay

import app.morphe.patcher.patch.resourcePatch
import helpers.manifest.NS_ANDROID
import org.w3c.dom.Element

/** Adds only the manifest permission needed by the optional Do Not Disturb module. */
internal fun universalOverlayManifestPatch(enabledProvider: () -> Boolean) = resourcePatch(
    name = null,
    default = false,
) {
    execute {
        if (!enabledProvider()) return@execute
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val permissionName = "android.permission.ACCESS_NOTIFICATION_POLICY"
            val alreadyDeclared = (0 until root.getElementsByTagName("uses-permission").length).any { index ->
                val permission = root.getElementsByTagName("uses-permission").item(index) as? Element
                permission?.getAttributeNS(NS_ANDROID, "name") == permissionName
            }
            if (!alreadyDeclared) {
                val permission = manifest.createElement("uses-permission")
                permission.setAttributeNS(NS_ANDROID, "android:name", permissionName)
                val application = root.getElementsByTagName("application").item(0)
                if (application != null) root.insertBefore(permission, application) else root.appendChild(permission)
            }
        }
    }
}
