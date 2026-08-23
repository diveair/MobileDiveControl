package com.mobiledivecontrol.viewmodel

import com.mobiledivecontrol.core.CameraCatalog
import com.mobiledivecontrol.core.CameraModeId
import com.mobiledivecontrol.core.GalaxyDeviceVariant
import java.util.Locale
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ISO, shutter, white balance and exposure all moved onto the native camera's own scales. A value
 * saved by the previous build must still name a real rung afterwards — if it does not, the reducer
 * reads the miss as index 0, which is "Auto" on ISO/shutter/WB and the far negative end on the EV
 * dials, so the diver's first wheel click would jump somewhere they never chose while the HUD
 * still showed the stored value.
 */
class ScaleLadderMigrationTest {

    private fun migrate(key: String, value: String): String? =
        snapScaleValuesToLadders(mapOf(key to value))[key]

    @Test
    fun `a legacy ISO 6400 lands on the nearest real rung, never on Auto`() {
        assertEquals("3200", migrate("pro.iso", "6400"))
    }

    @Test
    fun `every legacy ISO rung that still exists is left exactly alone`() {
        listOf("Auto", "50", "100", "200", "400", "800", "1600", "3200").forEach { legacy ->
            assertEquals(legacy, migrate("pro.iso", legacy))
        }
    }

    @Test
    fun `the legacy half-second shutter is renamed, not lost`() {
        assertEquals("0.5\"", migrate("pro.shutter_speed", "1/2"))
    }

    @Test
    fun `every legacy shutter rung survives onto a real rung`() {
        listOf(
            "Auto", "1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125",
            "1/60", "1/30", "1/15", "1/8", "1/4", "1/2",
            "1\"", "2\"", "4\"", "8\"", "15\"", "30\"",
        ).forEach { value ->
            val migrated = migrate("pro.shutter_speed", value)
            assertTrue(migrated in CameraCatalog.shutterLadder, "$value -> $migrated is not a rung")
            assertTrue(
                value == "Auto" || CameraCatalog.shutterLadder.indexOf(migrated) > 0,
                "$value collapsed to Auto",
            )
        }
    }

    /** Legacy single-Auto state maps explicitly; Samsung's native 100 K values stay exact. */
    @Test
    fun `legacy white balance values land on the native table and explicit auto mode`() {
        assertEquals(CameraCatalog.WB_AUTO_CONTINUOUS, migrate("pro.white_balance", "Auto"))
        // Every current rung is untouched exactly — the ladder itself is a fixed point.
        CameraCatalog.whiteBalanceLadder.forEach { rung ->
            assertEquals(rung, migrate("pro.white_balance", rung))
        }
        // Every value the old flat-100K builds could have saved remains byte-identical.
        for (legacy in 2300..10000 step 100) {
            assertEquals("${legacy}K", migrate("pro.white_balance", "${legacy}K"))
        }
        assertEquals("2800K", migrate("pro.white_balance", "2830K"))
    }

    @Test
    fun `legacy exposure spellings snap to the nearest native tenth`() {
        assertEquals("0.0", migrate("pro.exposure_value", "+0.03"))
        assertEquals("0.0", migrate("pro.exposure_value", "0"))
        assertEquals("+0.1", migrate("pro.exposure_value", "+0.10"))
        assertEquals("-1.4", migrate("pro.exposure_value", "-1.37"))
        assertEquals("+2.0", migrate("pro.exposure_value", "+2.00"))
        // The old ladder's "Auto" names nothing on the native EV dial — it is dropped, so the
        // catalog default "0.0" applies, which is exactly the native reset behaviour.
        assertEquals(null, migrate("photo.exposure_compensation", "Auto"))
        assertEquals(null, migrate("pro.exposure_value", "Auto"))
        assertEquals("0.0", migrate("night.exposure", "0"))
        // The quick-bar keys snap onto THEIR ladder: a stored +3.0 cannot survive on a +/-2.0 bar.
        assertEquals("+2.0", migrate("photo.exposure_compensation", "+3.0"))
        // The Pro dial reaches +/-4.0, so the same value survives there.
        assertEquals("+3.0", migrate("pro.exposure_value", "+3.0"))
    }

    @Test
    fun `every legacy 0_025 exposure rung lands within half a tenth of where it was`() {
        for (step in -80..80) {
            val legacy = if (step == 0) "0" else String.format(Locale.US, "%+.2f", step / 40.0)
            val migrated = migrate("pro.exposure_value", legacy)
            assertTrue(migrated in CameraCatalog.exposureProLadder, "$legacy -> $migrated")
            val moved = abs(migrated!!.replace("+", "").toDouble() - step / 40.0)
            assertTrue(moved <= 0.05 + 1e-9, "$legacy moved $moved EV")
        }
    }

    /**
     * The cap arithmetic must ROUND like the runtime's: truncating 1e9/60 gives 16,666,666 and
     * evicts the legal "1/60" cap rung itself (16,666,667), demoting a legitimately stored 1/60
     * to 1/90 on every restore.
     */
    @Test
    fun `a stored 1-60 at 60fps survives the restore demotion`() {
        val out = snapScaleValuesToLadders(
            mapOf("pro_video.shutter_speed" to "1/60", "pro_video.frame_rate" to "60fps"),
        )
        assertEquals("1/60", out["pro_video.shutter_speed"])
        // And a genuinely over-cap value still demotes to the cap rung, not past it.
        val over = snapScaleValuesToLadders(
            mapOf("pro_video.shutter_speed" to "1/30", "pro_video.frame_rate" to "60fps"),
        )
        assertEquals("1/60", over["pro_video.shutter_speed"])
    }

    @Test
    fun `migration is idempotent for all four scales`() {
        val once = snapScaleValuesToLadders(
            mapOf(
                "pro.iso" to "6400",
                "pro.shutter_speed" to "1/2",
                "pro.white_balance" to "5600K",
                "pro.exposure_value" to "-1.37",
            ),
        )
        assertEquals(once, snapScaleValuesToLadders(once))
    }

    @Test
    fun `unreadable values are dropped so the catalog default applies`() {
        assertFalse("pro.iso" in snapScaleValuesToLadders(mapOf("pro.iso" to "banana")))
        assertFalse(
            "pro.shutter_speed" in snapScaleValuesToLadders(mapOf("pro.shutter_speed" to "1/0")),
        )
    }

    @Test
    fun `unrelated settings are untouched`() {
        val out = snapScaleValuesToLadders(mapOf("pro.lens" to "5x", "pro.iso" to "6400"))
        assertEquals("5x", out["pro.lens"])
        assertEquals("3200", out["pro.iso"])
    }

    @Test
    fun `every migrated value names a rung on its own ladder, for all three manual modes`() {
        val legacy = mapOf(
            "expert.iso" to "6400",
            "pro.iso" to "6400",
            "pro_video.iso" to "6400",
            "expert.shutter_speed" to "1/2",
            "pro.shutter_speed" to "1/2",
            "pro_video.shutter_speed" to "1/2",
            "expert.white_balance" to "2800K",
            "pro.white_balance" to "2800K",
            "pro_video.white_balance" to "2800K",
            "expert.exposure_value" to "+1.25",
            "pro.exposure_value" to "+1.25",
            "pro_video.exposure_value" to "+1.25",
            "photo.exposure_compensation" to "-0.55",
            "night.exposure" to "-0.55",
        )
        val migrated = snapScaleValuesToLadders(legacy)

        assertEquals(legacy.size, migrated.size, "no key may be dropped")
        migrated.forEach { (key, value) ->
            val ladder = when {
                key.endsWith(".iso") -> CameraCatalog.isoLadder
                key.endsWith(".shutter_speed") -> CameraCatalog.shutterLadder
                key.endsWith(".white_balance") -> CameraCatalog.whiteBalanceLadder
                key == "photo.exposure_compensation" || key == "night.exposure" ->
                    CameraCatalog.exposureQuickLadder
                else -> CameraCatalog.exposureProLadder
            }
            assertTrue(value in ladder, "$key = $value is not on its ladder")
            if (key.endsWith(".iso") || key.endsWith(".shutter_speed")) {
                assertTrue(value != "Auto", "$key collapsed to Auto")
            }
            if (key.endsWith(".white_balance")) assertTrue(value.endsWith("K"), "$key collapsed to auto")
        }
    }

    /**
     * The migration addresses settings by NAME, from four hand-written key lists, while the
     * catalog grows by mode. A mode added later that carries one of these four ladders would be
     * silently uncovered — and uncovered is not "left alone", it is the original bug: the stored
     * value misses the ladder, the reducer reads the miss as index 0, and index 0 is "Auto".
     *
     * So this walks the real catalog, finds every setting actually built on one of the four
     * ladders, and requires the migration to move an off-ladder value for each one. It fails the
     * build the day a key list falls behind rather than the dive after.
     */
    @Test
    fun `every catalog setting built on one of the four ladders is covered by the migration`() {
        // Off-ladder on purpose, and chosen to avoid an exact midpoint so the nearest rung is
        // unambiguous: 6400 is a stop above the ISO top, 1/2 is a legacy spelling of 0.5",
        // 2830K sits between native 100 K rungs but not halfway, and +1.23 sits nearer +1.2 on both
        // EV dials. The WB expectation is computed, not spelled, so it tracks the ladder.
        val wbProbeExpected =
            CameraCatalog.nearestWhiteBalanceOption(2_830, CameraCatalog.whiteBalanceLadder)!!
        val probes = listOf(
            Triple(CameraCatalog.isoLadder, "6400", "3200"),
            Triple(CameraCatalog.shutterLadder, "1/2", "0.5\""),
            Triple(CameraCatalog.whiteBalanceLadder, "2830K", wbProbeExpected),
            Triple(CameraCatalog.exposureProLadder, "+1.23", "+1.2"),
            Triple(CameraCatalog.exposureQuickLadder, "+1.23", "+1.2"),
        )
        var checked = 0

        for (variant in GalaxyDeviceVariant.entries) {
            for (mode in CameraModeId.entries) {
                for (spec in CameraCatalog.settingsFor(mode, variant)) {
                    val probe = probes.firstOrNull { (ladder, _, _) -> spec.options == ladder }
                        ?: continue
                    val (_, stored, expected) = probe
                    // Pro Video's restore path additionally demotes a shutter slower than the
                    // default 30 fps frame period — the native rule — so its expected landing
                    // for a 0.5" probe is the 1/30 cap, not the rung itself.
                    val expectedHere =
                        if (spec.id == "pro_video.shutter_speed" && stored == "1/2") "1/30" else expected
                    assertEquals(
                        expectedHere,
                        migrate(spec.id, stored),
                        "${spec.id} ($mode/$variant) is not covered by the migration key lists",
                    )
                    checked++
                }
            }
        }

        // Guard against the loop silently finding nothing, which would make the test vacuous.
        assertTrue(checked >= 12, "expected the catalog to carry these ladders; found $checked")
    }
}
