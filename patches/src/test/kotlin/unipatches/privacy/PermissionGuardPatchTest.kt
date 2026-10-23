package unipatches.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGuardPatchTest {
    @Test
    fun selectedGroupsUseStablePermissionOrder() {
        assertEquals(
            listOf("camera", "location", "bluetooth"),
            permissionGuardGroups(
                mapOf("bluetooth" to true, "location" to true, "camera" to true),
            ),
        )
    }

    @Test
    fun noSelectedGroupsMeansRuntimeAllowsAllByDefault() {
        assertTrue(permissionGuardGroups(emptyMap()).isEmpty())
    }

    @Test
    fun rangeInvocationsUseRangeStaticOpcode() {
        assertEquals("invoke-static/range", permissionGuardInvokeOpcode("invoke-virtual/range"))
        assertEquals("invoke-static", permissionGuardInvokeOpcode("invoke-virtual"))
    }

    @Test
    fun declarationsAreNotPartOfRuntimeGroupSelection() {
        assertEquals(
            setOf("android.permission.CAMERA"),
            permissionGroups.getValue("camera"),
        )
    }

    @Test
    fun internetGroupMapsInternetPermission() {
        assertEquals(
            setOf("android.permission.INTERNET"),
            permissionGroups.getValue("internet"),
        )
        assertEquals(listOf("internet"), permissionGuardGroups(mapOf("internet" to true)))
    }
}
