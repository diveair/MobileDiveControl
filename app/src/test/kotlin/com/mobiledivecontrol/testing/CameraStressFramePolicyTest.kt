package com.mobiledivecontrol.testing

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraStressFramePolicyTest {
    @Test
    fun `manual exposure state is neutralized before the next sweep`() {
        assertEquals(
            "Auto",
            cameraStressNeutralBaseline("pro.iso", listOf("Auto", "50", "3200"), "3200"),
        )
        assertEquals(
            "Auto",
            cameraStressNeutralBaseline("pro.shutter_speed", listOf("Auto", "1/12000", "1/8"), "1/8"),
        )
        assertEquals(
            "0.0",
            cameraStressNeutralBaseline("pro.exposure_value", listOf("-1.0", "0.0", "+1.0"), "+1.0"),
        )
    }

    @Test
    fun `visual routing and diagnostic effects receive a neutral baseline`() {
        assertEquals(
            "Auto",
            cameraStressNeutralBaseline("pro.lens", listOf("Auto", "1x", "3x"), "3x"),
        )
        assertEquals(
            "Off",
            cameraStressNeutralBaseline("pro.focus_peaking", listOf("Off", "On"), "On"),
        )
        assertEquals(
            "None",
            cameraStressNeutralBaseline("photo.filter", listOf("None", "Warm"), "Warm"),
        )
    }

    @Test
    fun `ordinary live setting accepts the first fresh frame`() {
        assertTrue(
            cameraStressFrameIsValid(
                freshFrame = true,
                rebindCapable = false,
                bindObserved = false,
                newBindingPresented = false,
                rebindObservationGraceElapsed = false,
            ),
        )
    }

    @Test
    fun `rebind capable setting waits briefly before accepting a retained graph frame`() {
        assertFalse(
            cameraStressFrameIsValid(
                freshFrame = true,
                rebindCapable = true,
                bindObserved = false,
                newBindingPresented = false,
                rebindObservationGraceElapsed = false,
            ),
        )
        assertTrue(
            cameraStressFrameIsValid(
                freshFrame = true,
                rebindCapable = true,
                bindObserved = false,
                newBindingPresented = false,
                rebindObservationGraceElapsed = true,
            ),
        )
    }

    @Test
    fun `observed bind requires a frame from the replacement binding`() {
        assertFalse(
            cameraStressFrameIsValid(
                freshFrame = true,
                rebindCapable = true,
                bindObserved = true,
                newBindingPresented = false,
                rebindObservationGraceElapsed = true,
            ),
        )
        assertTrue(
            cameraStressFrameIsValid(
                freshFrame = true,
                rebindCapable = true,
                bindObserved = true,
                newBindingPresented = true,
                rebindObservationGraceElapsed = true,
            ),
        )
    }

    @Test
    fun `no policy accepts a stale frame`() {
        assertFalse(
            cameraStressFrameIsValid(
                freshFrame = false,
                rebindCapable = false,
                bindObserved = false,
                newBindingPresented = false,
                rebindObservationGraceElapsed = true,
            ),
        )
    }
}
