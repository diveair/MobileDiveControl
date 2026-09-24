package com.mobiledivecontrol.core

import kotlin.test.*

class DiveGasMenuTest {
    private val reducer = ControlReducer()
    private var state = AppState(mode = AppMode.DiveSettings, diveProfile = DiveProfileState(selectedField = DiveSettingsField.Gas))
    private fun act(command: DiveSettingsCommand) { state = reducer.reduce(state, command).state }

    @Test fun `gas cannot change on main settings and OK opens the submenu`() {
        act(DiveSettingsCommand.Adjust(1))
        assertEquals(DiveGas(), state.diveProfile.settings.gas)
        act(DiveSettingsCommand.Confirm)
        assertTrue(state.diveProfile.gasMenuOpen)
        act(DiveSettingsCommand.Adjust(1))
        assertEquals(DiveGas(32), state.diveProfile.settings.gas)
        act(DiveSettingsCommand.Back)
        assertFalse(state.diveProfile.gasMenuOpen)
        assertEquals(AppMode.DiveSettings, state.mode)
    }

    @Test fun `air composition is fixed and navigation skips its component fields`() {
        act(DiveSettingsCommand.Confirm)
        act(DiveSettingsCommand.Navigate(1))
        assertEquals(GasMenuField.Done, state.diveProfile.gasMenuField)
        for (field in listOf(GasMenuField.Oxygen, GasMenuField.Helium)) {
            state = state.copy(diveProfile = state.diveProfile.copy(gasMenuField = field))
            act(DiveSettingsCommand.Adjust(1))
            assertEquals(DiveGas(), state.diveProfile.settings.gas)
        }
    }

    @Test fun `nitrox edits oxygen only and trimix edits both fractions within a valid mixture`() {
        act(DiveSettingsCommand.Confirm)
        act(DiveSettingsCommand.Adjust(1))
        act(DiveSettingsCommand.SelectGasField(GasMenuField.Oxygen))
        act(DiveSettingsCommand.Adjust(1))
        assertEquals(DiveGas(33), state.diveProfile.settings.gas)
        assertFalse(GasMenuField.Helium in gasMenuFields(state.diveProfile.settings.gas))
        act(DiveSettingsCommand.SelectGasField(GasMenuField.Type))
        act(DiveSettingsCommand.Adjust(1))
        assertEquals(DiveGas(21, 35), state.diveProfile.settings.gas)
        act(DiveSettingsCommand.SelectGasField(GasMenuField.Helium))
        repeat(100) { act(DiveSettingsCommand.Adjust(1)) }
        assertEquals(DiveGas(21, 79), state.diveProfile.settings.gas)
        act(DiveSettingsCommand.SelectGasField(GasMenuField.Oxygen))
        act(DiveSettingsCommand.Adjust(1))
        assertEquals(100, state.diveProfile.settings.gas.oxygenPercent + state.diveProfile.settings.gas.heliumPercent)
    }

    @Test fun `active dive locks the gas even if the submenu was already open`() {
        act(DiveSettingsCommand.Confirm)
        state = state.copy(diveProfile = state.diveProfile.copy(active = DiveSession(0, DiveSettings())))
        act(DiveSettingsCommand.Adjust(1))
        assertEquals(DiveGas(), state.diveProfile.settings.gas)
        act(DiveSettingsCommand.CloseGasMenu)
        act(DiveSettingsCommand.Confirm)
        assertFalse(state.diveProfile.gasMenuOpen)
    }

    @Test fun `history confirmation requires fresh surface data and never initializes without confirmation`() {
        act(DiveSettingsCommand.RequestHistoryConfirmation)
        assertNull(state.diveProfile.historyConfirmation)
        state = state.copy(diveProfile = state.diveProfile.copy(sensorAvailable = true, depthMeters = 0.0))
        act(DiveSettingsCommand.RequestHistoryConfirmation)
        assertEquals(DecoHistory.Uninitialized, state.diveProfile.historyConfirmation)
        assertEquals(DecoHistory.Uninitialized, state.diveProfile.decompression.history)
        act(DiveSettingsCommand.DismissHistoryConfirmation)
        assertTrue(state.diveProfile.historyPromptOffered)
        assertNull(state.diveProfile.historyConfirmation)
    }
}
