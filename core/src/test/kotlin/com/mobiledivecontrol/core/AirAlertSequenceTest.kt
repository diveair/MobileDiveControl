package com.mobiledivecontrol.core

import kotlin.test.*

class AirAlertSequenceTest {
    private val settings = AirAlertSettings(enabled = true, schedule = AirSchedule.AtTimes,
        timesSeconds = listOf(900, 300, 600), message = "CHECK AIR", confirmation = AirConfirmation.Never,
        sequenceMessages = mapOf(300 to "CHECK BUDDY", 900 to "REPORT TANK PRESSURE"),
        sequenceActions = mapOf(300 to AirSequenceAction.Reminder, 900 to AirSequenceAction.Pressure))
    private fun field(air: AirAlertState, f: AirField, command: AirAlertCommand = AirAlertCommand.Activate): AirAlertState {
        val index = airFields(air).indexOf(f)
        assertTrue(index >= 0)
        return reduceAirAlerts(AppState(airAlerts = air.copy(selectedRow = index)), command, 0).state.airAlerts
    }
    private fun command(air: AirAlertState, c: AirAlertCommand) = reduceAirAlerts(AppState(airAlerts = air), c, 0).state.airAlerts

    @Test fun `sequence applies unique messages and pressure choices in chronological order`() {
        assertEquals("CHECK BUDDY", settings.messageFor(1))
        assertEquals("CHECK AIR", settings.messageFor(2))
        assertEquals("REPORT TANK PRESSURE", settings.messageFor(3))
        assertFalse(settings.needsConfirmation(1))
        assertFalse(settings.needsConfirmation(2))
        assertTrue(settings.needsConfirmation(3))
        assertTrue(airTracksPressure(settings))
    }
    @Test fun `changing or removing a sequence time keeps or deletes only its own message and action`() {
        var air = AirAlertState(settings = settings, page = AirPage.Times, menuOpen = true, timeSlot = 1,
            entry = AirPressureEntry(AirEntryPurpose.Time, "7"))
        air = command(air, AirAlertCommand.SavePressure)
        assertEquals("CHECK BUDDY", air.settings.sequenceMessages[420])
        assertNull(air.settings.sequenceMessages[300])
        assertEquals(AirSequenceAction.Reminder, air.settings.sequenceActions[420])
        air = field(air, AirField.RemoveTime)
        assertNull(air.settings.sequenceMessages[420])
        assertEquals("REPORT TANK PRESSURE", air.settings.sequenceMessages[900])
    }
    @Test fun `default and per-alert messages save independently and blank or cancelled edits do not replace them`() {
        var air = AirAlertState(settings = settings, page = AirPage.Appearance, menuOpen = true)
        air = field(air, AirField.Message)
        air = command(air, AirAlertCommand.EditText("TURN THE DIVE"))
        air = command(air, AirAlertCommand.CancelText)
        assertEquals("CHECK AIR", air.settings.message)
        air = field(air, AirField.Message)
        air = command(air, AirAlertCommand.EditText("  "))
        air = command(air, AirAlertCommand.SaveText)
        assertNotNull(air.textEntry!!.error)
        air = command(air, AirAlertCommand.EditText("CHECK YOUR AIR"))
        air = command(air, AirAlertCommand.SaveText)
        assertEquals("CHECK YOUR AIR", air.settings.message)
        assertEquals("CHECK BUDDY", air.settings.sequenceMessages[300])
    }
    @Test fun `hardware text input replaces suggestion then appends selected characters`() {
        var air = AirAlertState(textEntry = AirTextEntry("CHECK AIR"))
        air = command(air, AirAlertCommand.AddCharacter)
        air = command(air, AirAlertCommand.MoveCharacter(1))
        air = command(air, AirAlertCommand.AddCharacter)
        assertEquals("AB", air.textEntry!!.text)
        air = command(air, AirAlertCommand.SaveText)
        assertEquals("AB", air.settings.message)
    }
    @Test fun `custom sequence roundtrip and v1 migration preserve existing preferences`() {
        assertEquals(settings, AirAlertSettingsCodec.decode(AirAlertSettingsCodec.encode(settings)))
        val old = AirAlertSettingsCodec.encode(settings).replace("version=6", "version=1")
        val migrated = AirAlertSettingsCodec.decode(old)
        assertEquals(settings.timesSeconds, migrated.timesSeconds)
        assertEquals("CHECK AIR REMINDER", migrated.message)
        assertTrue(migrated.sequenceMessages.isEmpty())
        assertTrue(migrated.sequenceActions.isEmpty())
    }
    @Test fun `message length and control characters are bounded`() {
        assertEquals("HELLOWORLD", cleanAirMessage("HELLO\nWORLD"))
        assertEquals(AIR_MESSAGE_LIMIT, cleanAirMessage("A".repeat(100)).length)
    }
}
