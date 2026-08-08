package com.mobiledivecontrol.viewmodel

import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.GalaxyDeviceVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The focus ladder gained a decimal place. A value saved by the previous build must still name a
 * real rung afterwards — if it does not, the reducer reads the miss as index 0, which is "AF",
 * and the diver's first wheel click throws focus to autofocus with no warning.
 */
class FocusLadderMigrationTest {

    private val ladder = CameraCatalog
        .settingsFor(CameraModeId.Photo, GalaxyDeviceVariant.S26Ultra)
        .first { it.id == "photo.manual_focus" }
        .options

    private fun migrate(value: String): String? =
        snapFocusValuesToLadder(mapOf("photo.manual_focus" to value))["photo.manual_focus"]

    @Test
    fun `a legacy two-decimal focus lands on a real rung, never on AF`() {
        val migrated = migrate("0.42")

        assertEquals("0.420", migrated)
        assertTrue(migrated in ladder, "migrated value must exist on the ladder")
        assertTrue(ladder.indexOf(migrated) > 0, "index 0 is AF — a miss would silently mean AF")
    }

    @Test
    fun `every legacy hundredth survives the move`() {
        for (step in 0..100) {
            val legacy = String.format(java.util.Locale.US, "%.2f", step / 100.0)
            val migrated = migrate(legacy)

            assertTrue(migrated in ladder, "$legacy migrated to $migrated, which is not a rung")
            assertTrue(ladder.indexOf(migrated) > 0, "$legacy collapsed to AF")
            assertEquals(step / 100.0, migrated!!.toDouble(), 1e-9, "$legacy moved the focus plane")
        }
    }

    @Test
    fun `an off-ladder value snaps to the nearest rung rather than to AF`() {
        assertEquals("0.425", migrate("0.4245"))
        assertEquals("0.420", migrate("0.4210"))
    }

    @Test
    fun `migration is idempotent`() {
        val once = migrate("0.42")!!
        assertEquals(once, migrate(once))
    }

    @Test
    fun `AF is left alone`() {
        assertEquals("AF", migrate("AF"))
    }

    @Test
    fun `unreadable or out-of-range values are dropped so the catalog default applies`() {
        assertFalse("photo.manual_focus" in snapFocusValuesToLadder(mapOf("photo.manual_focus" to "abc")))
        assertFalse("photo.manual_focus" in snapFocusValuesToLadder(mapOf("photo.manual_focus" to "1.7")))
        assertFalse("photo.manual_focus" in snapFocusValuesToLadder(mapOf("photo.manual_focus" to "-0.2")))
    }

    @Test
    fun `other settings are untouched`() {
        val out = snapFocusValuesToLadder(mapOf("photo.iso" to "400", "photo.manual_focus" to "0.42"))

        assertEquals("400", out["photo.iso"])
        assertEquals("0.420", out["photo.manual_focus"])
    }
}
