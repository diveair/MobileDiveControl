package com.mobiledivecontrol.accessibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionDialogPolicyTest {
    @Test
    fun `permission controller allow and deny ids are classified without UI language`() {
        assertEquals(
            PermissionDialogChoice.Accept,
            permissionDialogChoice(
                "com.google.android.permissioncontroller:id/permission_allow_foreground_only_button",
                null,
            ),
        )
        assertEquals(
            PermissionDialogChoice.Reject,
            permissionDialogChoice(
                "com.android.permissioncontroller:id/permission_deny_button",
                null,
            ),
        )
    }

    @Test
    fun `common Samsung system prompt labels are classified`() {
        assertEquals(PermissionDialogChoice.Accept, permissionDialogChoice(null, "Only this time"))
        assertEquals(PermissionDialogChoice.Accept, permissionDialogChoice(null, "Turn on"))
        assertEquals(PermissionDialogChoice.Reject, permissionDialogChoice(null, "Don’t allow"))
        assertEquals(PermissionDialogChoice.Reject, permissionDialogChoice(null, "Not now"))
        assertNull(permissionDialogChoice(null, "Delete every photo"))
    }

    @Test
    fun `service package allowlist excludes ordinary apps`() {
        assertTrue(isSystemPermissionControllerPackage("com.google.android.permissioncontroller"))
        assertTrue(isSystemPermissionControllerPackage("com.samsung.android.permissioncontroller"))
        assertFalse(isSystemPermissionControllerPackage("com.example.bank"))
        assertFalse(isSystemPermissionControllerPackage("com.mobiledivecontrol"))
    }
}
