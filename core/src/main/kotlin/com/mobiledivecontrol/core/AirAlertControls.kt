package com.mobiledivecontrol.core

import kotlin.math.abs

sealed interface AirAlertCommand {
    data class SelectPage(val page: AirPage) : AirAlertCommand
    data class SelectRow(val row: Int) : AirAlertCommand
    data class SelectSlot(val index: Int) : AirAlertCommand
    data class Navigate(val delta: Int) : AirAlertCommand
    data class Adjust(val delta: Int, val coarse: Boolean = false) : AirAlertCommand
    data object Activate : AirAlertCommand
    data object Back : AirAlertCommand
    data class PromptAction(val choice: Int) : AirAlertCommand
    data class Key(val index: Int) : AirAlertCommand
    data class MoveKey(val dx: Int, val dy: Int) : AirAlertCommand
    data class NudgePressure(val delta: Int, val fine: Boolean = false) : AirAlertCommand
    data class SetPressure(val value: Int) : AirAlertCommand
    data class UseKeypad(val enabled: Boolean) : AirAlertCommand
    data object StopTest : AirAlertCommand
    data class GasAction(val index: Int) : AirAlertCommand
    data class SelectGasAction(val delta: Int) : AirAlertCommand
    data object ConfirmBuddyAir : AirAlertCommand
    data object CancelBuddyAir : AirAlertCommand
    data class EditText(val text: String) : AirAlertCommand
    data class MoveCharacter(val delta: Int) : AirAlertCommand
    data object AddCharacter : AirAlertCommand
    data object DeleteCharacter : AirAlertCommand
    data object SaveText : AirAlertCommand
    data object CancelText : AirAlertCommand
    data object SavePressure : AirAlertCommand
    data object CancelEntry : AirAlertCommand
    data class ReceiverUpdate(val receiver: AirReceiverState) : AirAlertCommand
    data object DiscoverReceivers : AirAlertCommand
    data class ConnectReceiver(val address: String) : AirAlertCommand
    data class Packet(val service: String, val characteristic: String, val bytes: List<Int>, val atMs: Long) : AirAlertCommand
}

enum class AirField(val label: String) {
    Mode("Alert type"), Timing("When"), Confirmation("Ask for pressure"), Tank("Tank & display"), Style("Message & style"), ReceiverSetup("Register receiver"),
    Schedule("Schedule"), Interval("Time"), Times("Edit sequence"), TimeSlot("Selected time"), AddTime("Add time"), RemoveTime("Remove selected time"),
    ConfirmationSlot("Selected alert"), AddConfirmation("Add alert"), RemoveConfirmation("Remove selected alert"),
    Message("Alert text"), SequenceMessage("This alert says"), SequenceAction("This alert asks for"),
    Duration("Visible for"), Opacity("Opacity"), Colour("Colour"), Flash("Flashing"),
    Unit("Pressure unit"), Start("Confirm starting pressure"), SliderMinimum("Slider minimum"), SliderMaximum("Slider maximum"), Reserve("Surface reserve"), Hud("Camera readout"),
    Values("Label other values"), ValueSlot("Selected value"), ValueLabel("Label"), ValueUnit("Unit label"),
    ValueSignal("Signal"), ValueMapping("Map bytes"), AddValue("Add value"), RemoveValue("Remove value"),
    Scan("Scan again"), Device("Device"), Characteristic("Pressure signal"), Disconnect("Disconnect"),
    Mapping("Map pressure bytes"), Calibration("Verify with gauge"), Format("Number format"), ByteOrder("Byte order"),
    ByteOffset("First byte"), Scale("Multiply by"), Offset("Then add"), SourceUnit("Signal unit"),
    Stale("Signal expires after"), Calibrate("Compare gauge reading"), ResetCalibration("Reset verification"), Back("Back")
}

enum class AirMode(val label: String) { Off("Off"), Reminder("Remind only"), Manual("Track air"), Receiver("Live receiver") }
fun airMode(s: AirAlertSettings): AirMode = when {
    !s.enabled -> AirMode.Off
    s.source == AirSource.Receiver -> AirMode.Receiver
    s.confirmation == AirConfirmation.Never -> AirMode.Reminder
    else -> AirMode.Manual
}
fun airTracksPressure(s: AirAlertSettings) = s.enabled && (s.source == AirSource.Receiver || s.confirmation != AirConfirmation.Never ||
    (s.schedule == AirSchedule.AtTimes && AirSequenceAction.Pressure in s.sequenceActions.values))

fun airShowsReceiverPicker(air: AirAlertState) = air.settings.enabled && air.settings.source == AirSource.Receiver &&
    !air.receiver.connected && air.page in setOf(AirPage.Home, AirPage.Receiver)

/** Device rows share a field type but each has its own position in the housing focus order. */
fun airDeviceAtRow(air: AirAlertState, row: Int = air.selectedRow): AirDevice? {
    if (!airShowsReceiverPicker(air)) return null
    return air.receiver.devices.getOrNull(row - if (air.page == AirPage.Home) 1 else 0)
}

/** One source of truth for touch and housing navigation. Inapplicable controls never enter the focus order. */
fun airFields(air: AirAlertState): List<AirField> = buildList {
    val s = air.settings
    if (!s.enabled) { add(AirField.Mode); add(AirField.Back); return@buildList }
    if (airShowsReceiverPicker(air)) {
        if (air.page == AirPage.Home) add(AirField.Mode)
        repeat(air.receiver.devices.size) { add(AirField.Device) }
        add(AirField.Scan); add(AirField.Back)
        return@buildList
    }
    when (air.page) {
        AirPage.Home -> {
            add(AirField.Mode)
            add(AirField.Timing)
            if (airTracksPressure(s)) {
                if ((s.confirmation != AirConfirmation.Never || s.source == AirSource.Receiver) && (s.schedule != AirSchedule.Once || s.source == AirSource.Receiver)) add(AirField.Confirmation)
                add(AirField.Tank)
            }
            add(AirField.Style)
            if (s.enabled && s.source == AirSource.Receiver) add(AirField.ReceiverSetup)
        }
        AirPage.Schedule -> { add(AirField.Schedule); add(if (s.schedule == AirSchedule.AtTimes) AirField.Times else AirField.Interval) }
        AirPage.Times -> { addAll(listOf(AirField.TimeSlot, AirField.SequenceMessage, AirField.SequenceAction)); if (s.timesSeconds.size < 24) add(AirField.AddTime); if (s.timesSeconds.size > 1) add(AirField.RemoveTime) }
        AirPage.Confirmations -> { add(AirField.ConfirmationSlot); if (s.confirmationNumbers.size < 24) add(AirField.AddConfirmation); if (s.confirmationNumbers.size > 1) add(AirField.RemoveConfirmation) }
        AirPage.Appearance -> addAll(listOf(AirField.Message, AirField.Duration, AirField.Colour, AirField.Opacity, AirField.Flash))
        AirPage.Gas -> { add(AirField.Unit); if (s.source == AirSource.Manual) add(AirField.Start); addAll(listOf(AirField.SliderMinimum, AirField.SliderMaximum, AirField.Reserve, AirField.Hud)) }
        AirPage.Receiver -> {
            if (air.receiver.connected) {
                if (air.receiver.characteristics.isNotEmpty()) add(AirField.Characteristic)
                addAll(listOf(AirField.Mapping, AirField.Calibration, AirField.Values, AirField.Disconnect))
            }
        }
        AirPage.Values -> {
            if (s.receiverValues.isNotEmpty()) addAll(listOf(AirField.ValueSlot, AirField.ValueLabel, AirField.ValueSignal,
                AirField.ValueMapping, AirField.ValueUnit, AirField.RemoveValue))
            if (s.receiverValues.size < 8) add(AirField.AddValue)
        }
        AirPage.ValueMapping -> addAll(listOf(AirField.Format, AirField.ByteOrder, AirField.ByteOffset, AirField.Scale, AirField.Offset))
        AirPage.Mapping -> addAll(listOf(AirField.Format, AirField.ByteOrder, AirField.ByteOffset, AirField.Scale, AirField.Offset, AirField.SourceUnit))
        AirPage.Calibration -> { addAll(listOf(AirField.Calibrate, AirField.Stale)); if (s.mapping.verified || air.receiver.firstCalibrationBar != null) add(AirField.ResetCalibration) }
    }
    add(AirField.Back)
}

fun airTime(seconds: Int): String = if (seconds % 60 == 0) "${seconds / 60} min" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} min"
fun airTiming(s: AirAlertSettings): String = when (s.schedule) {
    AirSchedule.Recurring -> "Every ${airTime(s.intervalSeconds)}"
    AirSchedule.Once -> "Once at ${airTime(s.intervalSeconds)}"
    AirSchedule.AtTimes -> s.timesSeconds.distinct().sorted().joinToString(", ") { "${it / 60}" } + " min"
}
fun airFieldValue(air: AirAlertState, field: AirField): String {
    val s = air.settings
    val value = s.receiverValues.getOrNull(air.valueSlot)
    val m = if (air.page == AirPage.ValueMapping) value?.mapping ?: s.mapping else s.mapping
    return when (field) {
        AirField.Mode -> airMode(s).label + if (s.enabled && s.schedule == AirSchedule.AtTimes) " · default" else ""
        AirField.Timing -> airTiming(s)
        AirField.Tank -> if (s.source == AirSource.Receiver) "${s.unit.label} · ${s.unit.display(s.reserveBar)} reserve"
            else "${s.unit.display(s.startBar)} ${s.unit.label} · ${if (air.startArmed) "ready" else "confirm start"}"
        AirField.Style -> "${s.colour.name} · ${if (s.autoDismiss) "${s.displaySeconds}s" else "until dismissed"}"
        AirField.ReceiverSetup -> if (air.receiver.connected) "Connected" else if (air.receiver.phase == AirReceiverPhase.Connecting) "Connecting…" else "Connect"
        AirField.Values -> "${s.receiverValues.size} saved"
        AirField.ValueSlot -> "${air.valueSlot + 1} of ${s.receiverValues.size}"
        AirField.ValueLabel -> value?.label ?: ""
        AirField.ValueUnit -> value?.unit?.ifBlank { "None" } ?: "None"
        AirField.ValueSignal -> value?.mapping?.characteristic ?: "Select signal"
        AirField.Schedule -> when (s.schedule) { AirSchedule.Recurring -> "Repeat"; AirSchedule.Once -> "Once"; AirSchedule.AtTimes -> "Sequence" }
        AirField.Interval -> airTime(s.intervalSeconds)
        AirField.Times -> airTiming(s)
        AirField.TimeSlot -> "${s.timesSeconds.getOrElse(air.timeSlot) { 300 } / 60} min · ${air.timeSlot + 1} of ${s.timesSeconds.size}"
        AirField.Confirmation -> if (s.schedule == AirSchedule.Once) { if (s.confirmation == AirConfirmation.Never) "No · display only" else "Yes" } else when (s.confirmation) {
            AirConfirmation.Never -> "Never · display only"
            AirConfirmation.Every -> if (s.confirmEvery == 1) "Every alert" else "Every ${s.confirmEvery} alerts"
            AirConfirmation.Selected -> s.confirmationNumbers.distinct().sorted().joinToString(", ", prefix = "Alerts ") { "#$it" }
        }
        AirField.ConfirmationSlot -> "#${s.confirmationNumbers.getOrElse(air.confirmationSlot) { 1 }} · ${air.confirmationSlot + 1} of ${s.confirmationNumbers.size}"
        AirField.Message -> s.message
        AirField.SequenceMessage -> s.sequenceMessages[s.timesSeconds[air.timeSlot]] ?: s.message
        AirField.SequenceAction -> when (s.sequenceActions[s.timesSeconds[air.timeSlot]] ?: AirSequenceAction.Default) {
            AirSequenceAction.Default -> "Use default"
            AirSequenceAction.Reminder -> "No input · reminder"
            AirSequenceAction.Pressure -> "Pressure reading"
        }
        AirField.Duration -> if (s.autoDismiss) "${s.displaySeconds} seconds" else "Until dismissed"
        AirField.Opacity -> "${s.opacityPercent}%"
        AirField.Colour -> s.colour.name
        AirField.Flash -> if (s.flash) "On" else "Off"
        AirField.Unit -> s.unit.label
        AirField.Start -> "${s.unit.display(s.startBar)} ${s.unit.label} · ${if (air.startArmed) "confirmed" else "check gauge"}"
        AirField.SliderMinimum -> "${s.unit.display(s.sliderMinBar)} ${s.unit.label}"
        AirField.SliderMaximum -> "${s.unit.display(s.sliderMaxBar)} ${s.unit.label}"
        AirField.Reserve -> "${s.unit.display(s.reserveBar)} ${s.unit.label}"
        AirField.Hud -> s.hudMode.label
        AirField.Device -> airDeviceAtRow(air)?.let { "${it.name} · ${it.address.takeLast(5)}" } ?: ""
        AirField.Scan -> when (air.receiver.phase) { AirReceiverPhase.Scanning -> "Searching…"; AirReceiverPhase.Connecting -> "Connecting…"; else -> "Search nearby" }
        AirField.Characteristic -> if (air.receiver.characteristics.isNotEmpty()) "${air.receiver.selectedCharacteristic + 1} of ${air.receiver.characteristics.size}" else "Connect first"
        AirField.Format -> m.format.name
        AirField.ByteOrder -> if (m.littleEndian) "Little endian" else "Big endian"
        AirField.ByteOffset -> "${m.byteOffset}"
        AirField.Scale -> java.lang.Double.toString(m.scale)
        AirField.Offset -> java.lang.Double.toString(m.offset)
        AirField.SourceUnit -> m.unit.label
        AirField.Stale -> "${s.staleSeconds} seconds"
        AirField.Calibrate -> when { m.verified -> "Verified"; air.receiver.firstCalibrationBar != null -> "Second reading"; else -> "First reading" }
        AirField.Back -> if (air.page == AirPage.Home) "Dive Settings" else air.backStack.lastOrNull()?.page?.label ?: "Dive Alerts"
        else -> "Open"
    }
}

fun airFieldAdjustable(field: AirField) = field in setOf(AirField.ValueSlot, AirField.ValueSignal,AirField.SequenceAction, AirField.Mode, AirField.Timing, AirField.Confirmation,
    AirField.Schedule, AirField.Interval, AirField.TimeSlot, AirField.ConfirmationSlot, AirField.Duration,
    AirField.Opacity, AirField.Colour, AirField.Flash, AirField.Unit, AirField.Hud,
    AirField.Characteristic, AirField.Format, AirField.ByteOrder, AirField.ByteOffset,
    AirField.Scale, AirField.Offset, AirField.SourceUnit, AirField.Stale)

fun airFieldLocked(state: AppState, field: AirField): Boolean =
    (field == AirField.Device && state.airAlerts.receiver.phase == AirReceiverPhase.Connecting) ||
    (field == AirField.Scan && state.airAlerts.receiver.phase in setOf(AirReceiverPhase.Scanning, AirReceiverPhase.Connecting)) ||
    (state.diveProfile.active != null && field in setOf(
    AirField.ReceiverSetup, AirField.Values, AirField.ValueSlot, AirField.ValueLabel, AirField.ValueUnit,
    AirField.ValueSignal, AirField.ValueMapping, AirField.AddValue, AirField.RemoveValue, AirField.Unit, AirField.Start, AirField.SliderMinimum, AirField.SliderMaximum, AirField.Reserve, AirField.Scan, AirField.Device,
    AirField.Characteristic, AirField.Disconnect, AirField.Format, AirField.ByteOrder, AirField.ByteOffset,
    AirField.Scale, AirField.Offset, AirField.SourceUnit, AirField.Stale, AirField.Calibrate, AirField.ResetCalibration))

fun reduceAirAlerts(state: AppState, command: AirAlertCommand, nowMs: Long = pressureMonotonicMs()): Reduction {
    var air = state.airAlerts
    if (command == AirAlertCommand.StopTest) return Reduction(state.copy(airAlerts = air.copy(simulation = null)))
    air.simulation?.let { test ->
        if (command !is AirAlertCommand.ReceiverUpdate && command !is AirAlertCommand.Packet) {
            if (command == AirAlertCommand.Back) return Reduction(state.copy(airAlerts = air.copy(simulation = null)))
            if (command !is AirAlertCommand.PromptAction && command !is AirAlertCommand.Key && command !is AirAlertCommand.MoveKey &&
                command !is AirAlertCommand.NudgePressure && command !is AirAlertCommand.SetPressure && command !is AirAlertCommand.UseKeypad &&
                command != AirAlertCommand.SavePressure && command != AirAlertCommand.CancelEntry &&
                command !is AirAlertCommand.GasAction && command !is AirAlertCommand.SelectGasAction &&
                command != AirAlertCommand.ConfirmBuddyAir && command != AirAlertCommand.CancelBuddyAir) return Reduction(state)
            val next = reduceAirAlerts(state.copy(airAlerts = test.air, diveProfile = test.profile), command, nowMs).state.airAlerts
            return Reduction(state.copy(airAlerts = air.copy(simulation = test.copy(air = next, lastRealMs = nowMs))))
        }
    }
    var s = air.settings
    val now = nowMs
    fun result(): Reduction {
        if (!air.settings.enabled) air = air.copy(page = AirPage.Home, backStack = emptyList())
        else if (!air.receiver.connected && (air.page in setOf(AirPage.Mapping, AirPage.Calibration, AirPage.Values, AirPage.ValueMapping) ||
                (air.settings.source == AirSource.Receiver && air.page !in setOf(AirPage.Home, AirPage.Receiver))))
            air = air.copy(page = AirPage.Receiver, backStack = listOf(AirMenuLocation(AirPage.Home, 0)), textEntry = null)
        air = AirGasWarnings.tick(air, state.diveProfile, nowMs)
        air = air.copy(selectedRow = air.selectedRow.coerceIn(0, airFields(air).lastIndex))
        return Reduction(state.copy(airAlerts = air, diveProfile = if (state.airAlerts.menuOpen && !air.menuOpen)
            state.diveProfile.copy(selectedField = DiveSettingsField.Back) else state.diveProfile))
    }
    fun openPage(page: AirPage) {
        air = air.copy(backStack = air.backStack + AirMenuLocation(air.page, air.selectedRow), page = page, selectedRow = 0)
    }
    fun back() {
        val previous = air.backStack.lastOrNull()
        air = if (previous != null) air.copy(page = previous.page, backStack = air.backStack.dropLast(1))
            else if (air.page != AirPage.Home) air.copy(page = AirPage.Home)
            else air.copy(menuOpen = false)
        air = air.copy(selectedRow = airFields(air).lastIndex)
    }
    fun openNumber(purpose: AirEntryPurpose, number: Int, adding: Boolean = false) {
        air = air.copy(entry = AirPressureEntry(purpose, number.toString(), adding = adding))
    }
    fun openEntry(purpose: AirEntryPurpose, bar: Double) {
        air = air.copy(entry = AirPressureEntry(purpose, s.unit.display(bar).toString()))
    }
    fun resetMapping(mapping: AirPressureMapping) {
        if (air.page == AirPage.ValueMapping) {
            s = s.copy(receiverValues = s.receiverValues.mapIndexed { i, value -> if (i == air.valueSlot) value.copy(mapping = mapping.copy(verified = false)) else value })
            return
        }
        s = s.copy(mapping = mapping.copy(verified = false))
        air = air.copy(receiver = air.receiver.copy(firstCalibrationBar = null, liveBar = null, liveAtMs = null))
    }
    fun request(action: AirReceiverAction) {
        air = air.copy(receiverRequest = AirReceiverRequest((air.receiverRequest?.serial ?: 0) + 1, action))
    }
    fun scanReceivers() {
        // Mark pending immediately, so recomposition or a second OK cannot start another scan.
        air = air.copy(receiver = air.receiver.copy(phase = AirReceiverPhase.Scanning, status = "Looking for receivers…"))
        request(AirReceiverAction.Scan)
    }
    fun connectReceiver(address: String) {
        if (!air.menuOpen || !airShowsReceiverPicker(air) || airFieldLocked(state, AirField.Device)) return
        val device = air.receiver.devices.firstOrNull { it.address.equals(address, true) } ?: return
        if (!device.address.equals(s.mapping.address, true))
            resetMapping(s.mapping.copy(address = device.address, deviceName = device.name, service = "", characteristic = ""))
        else s = s.copy(mapping = s.mapping.copy(deviceName = device.name))
        air = air.copy(settings = s, selectedRow = air.receiver.devices.indexOf(device) + if (air.page == AirPage.Home) 1 else 0,
            receiver = air.receiver.copy(phase = AirReceiverPhase.Connecting,
            status = "Connecting to ${device.name}…"))
        request(AirReceiverAction.Connect)
    }
    when (command) {
        AirAlertCommand.DiscoverReceivers -> {
            if (air.menuOpen && airShowsReceiverPicker(air) && state.diveProfile.active == null && air.receiver.phase == AirReceiverPhase.Idle)
                scanReceivers()
            return result()
        }
        is AirAlertCommand.ConnectReceiver -> { connectReceiver(command.address); return result() }
        is AirAlertCommand.SelectGasAction -> {
            val warning = air.gasWarning ?: return result()
            air = air.copy(gasWarning = warning.copy(selectedAction = Math.floorMod(
                warning.selectedAction + command.delta, if (warning.level.critical) 3 else 4)))
            return result()
        }
        is AirAlertCommand.GasAction -> {
            if (air.gasWarning?.visible != true && !air.sharedAir) return result()
            when (command.index) {
                0 -> air = air.copy(entry = airSuggestedEntry(air, state.diveProfile, now), prompt = null)
                1 -> openEntry(AirEntryPurpose.NextTank, s.startBar)
                2 -> if (!air.sharedAir) air = air.copy(buddyConfirmation = true, entry = null, prompt = null)
                3 -> air = AirGasWarnings.skip(air)
            }
            return result()
        }
        AirAlertCommand.ConfirmBuddyAir -> {
            if (air.buddyConfirmation && air.gasWarning != null && state.diveProfile.active != null)
                air = air.copy(sharedAir = true, buddyConfirmation = false, gasWarning = null,
                    entry = null, prompt = null, tankCheckDueMs = null)
            return result()
        }
        AirAlertCommand.CancelBuddyAir -> { air = air.copy(buddyConfirmation = false); return result() }
        is AirAlertCommand.EditText -> { air = air.copy(textEntry = air.textEntry?.copy(text = cleanAirMessage(command.text), replacing = false, error = null)); return result() }
        is AirAlertCommand.MoveCharacter -> { air = air.copy(textEntry = air.textEntry?.let { it.copy(character = Math.floorMod(it.character + command.delta, AIR_TEXT_ALPHABET.length)) }); return result() }
        AirAlertCommand.AddCharacter -> { air = air.copy(textEntry = air.textEntry?.let {
            it.copy(text = cleanAirMessage((if (it.replacing) "" else it.text) + AIR_TEXT_ALPHABET[it.character]), replacing = false, error = null)
        }); return result() }
        AirAlertCommand.DeleteCharacter -> { air = air.copy(textEntry = air.textEntry?.let { it.copy(text = it.text.dropLast(1), replacing = false, error = null) }); return result() }
        AirAlertCommand.CancelText -> { air = air.copy(textEntry = null); return result() }
        AirAlertCommand.SaveText -> {
            val edit = air.textEntry ?: return result()
            val message = cleanAirMessage(edit.text).trim()
            if (message.isEmpty() && edit.purpose != AirTextPurpose.ValueUnit) { air = air.copy(textEntry = edit.copy(error = "Enter text")); return result() }
            s = if (edit.purpose != AirTextPurpose.Message) s.copy(receiverValues = s.receiverValues.mapIndexed { i, value ->
                if (i != air.valueSlot) value else if (edit.purpose == AirTextPurpose.ValueLabel) value.copy(label = message) else value.copy(unit = message)
            }) else if (edit.sequenceTime == null) s.copy(message = message) else s.copy(sequenceMessages = s.sequenceMessages + (edit.sequenceTime to message))
            air = air.copy(settings = s, textEntry = null); return result()
        }
        is AirAlertCommand.ReceiverUpdate -> {
            val incoming = command.receiver
            val old = air.receiver
            val focusedField = airFields(air).getOrNull(air.selectedRow)
            val focusedAddress = airDeviceAtRow(air)?.address
            air = air.copy(receiver = old.copy(status = incoming.status, connected = incoming.connected, phase = incoming.phase,
                signals = old.signals.takeIf { incoming.connected } ?: emptyMap(),
                devices = incoming.devices, characteristics = incoming.characteristics,
                selectedDevice = old.selectedDevice.coerceIn(0, (incoming.devices.size - 1).coerceAtLeast(0)),
                selectedCharacteristic = if (old.characteristics != incoming.characteristics) incoming.selectedCharacteristic else old.selectedCharacteristic,
                liveBar = old.liveBar.takeIf { incoming.connected }, liveAtMs = old.liveAtMs.takeIf { incoming.connected },
                payload = old.payload.takeIf { incoming.connected } ?: emptyList(), packetAtMs = old.packetAtMs.takeIf { incoming.connected }))
            if (airShowsReceiverPicker(air)) {
                val deviceIndex = incoming.devices.indexOfFirst { it.address.equals(focusedAddress, true) }
                val row = if (focusedField == AirField.Device && deviceIndex >= 0)
                    deviceIndex + if (air.page == AirPage.Home) 1 else 0
                else airFields(air).indexOf(focusedField).takeIf { it >= 0 } ?: airFields(air).indexOf(AirField.Scan)
                air = air.copy(selectedRow = row)
            }
            if (incoming.connected && !old.connected && incoming.characteristics.isNotEmpty()) {
                val signal = incoming.characteristics.getOrElse(incoming.selectedCharacteristic) { incoming.characteristics.first() }
                if (!s.mapping.matches(signal.service, signal.uuid)) resetMapping(s.mapping.copy(service = signal.service, characteristic = signal.uuid))
                air = air.copy(settings = s, receiverRequest = AirReceiverRequest((air.receiverRequest?.serial ?: 0) + 1, AirReceiverAction.Watch))
                if (air.menuOpen && air.settings.source == AirSource.Receiver && air.page == AirPage.Home) openPage(AirPage.Receiver)
                if (air.page == AirPage.Receiver) air = air.copy(selectedRow = 0)
            }
            return result()
        }
        is AirAlertCommand.Packet -> {
            val m = s.mapping
            val pressureSignal = m.matches(command.service, command.characteristic)
            val previousAt = air.receiver.signals[airSignalKey(command.service, command.characteristic)]?.atMs
                ?: air.receiver.packetAtMs.takeIf { pressureSignal } ?: Long.MIN_VALUE
            if (command.bytes.size > 512 || command.atMs < previousAt) return result()
            val customSignal = s.receiverValues.any { it.mapping.address.equals(m.address, true) && it.mapping.matches(command.service, command.characteristic) }
            if (!pressureSignal && !customSignal) return result()
            air = air.copy(receiver = air.receiver.copy(signals = air.receiver.signals +
                (airSignalKey(command.service, command.characteristic) to AirSignalPacket(command.bytes, command.atMs))))
            if (!pressureSignal) return result()
            val value = m.decode(command.bytes)
            air = air.copy(receiver = air.receiver.copy(payload = command.bytes, packetAtMs = command.atMs,
                packetService = command.service, packetCharacteristic = command.characteristic,
                liveBar = if (m.verified) value else null, liveAtMs = if (m.verified && value != null) command.atMs else null))
            return result()
        }
        is AirAlertCommand.SelectPage -> { if (air.page != command.page) openPage(command.page); return result() }
        is AirAlertCommand.SelectSlot -> {
            air = if (air.page == AirPage.Times) air.copy(timeSlot = command.index.coerceIn(0, s.timesSeconds.lastIndex))
                else air.copy(confirmationSlot = command.index.coerceIn(0, s.confirmationNumbers.lastIndex))
            return result()
        }
        is AirAlertCommand.SelectRow -> { air = air.copy(selectedRow = command.row.coerceIn(0, airFields(air).lastIndex)); return result() }
        is AirAlertCommand.Navigate -> { air = air.copy(selectedRow = Math.floorMod(air.selectedRow + command.delta, airFields(air).size)); return result() }
        AirAlertCommand.Back -> { air = air.copy(entry = null, prompt = null); back(); return result() }
        AirAlertCommand.CancelEntry -> { air = air.copy(entry = null, prompt = if (air.entry?.purpose == AirEntryPurpose.Reading) null else air.prompt); return result() }
        is AirAlertCommand.UseKeypad -> {
            val entry = air.entry ?: air.prompt?.takeIf { it.confirmation }?.let {
                airSuggestedEntry(air, state.diveProfile, now)
            } ?: return result()
            air = air.copy(entry = entry.copy(keypad = command.enabled)); return result()
        }
        is AirAlertCommand.SetPressure -> {
            val entry = air.entry ?: air.prompt?.takeIf { it.confirmation }?.let {
                airSuggestedEntry(air, state.diveProfile, now)
            } ?: return result()
            if (!entry.purpose.isPressure()) return result()
            air = air.copy(entry = entry.copy(text = command.value.coerceIn(s.pressureRange(entry.purpose)).toString(), replacing = true, error = null, edited = true)); return result()
        }
        is AirAlertCommand.MoveKey -> {
            val e = air.entry ?: return result()
            val next = if (e.selected == 12) { if (command.dy < 0) 9 else 0 } else if (command.dy > 0 && e.selected >= 9) 12
            else Math.floorMod(e.selected / 3 + command.dy, 4) * 3 + Math.floorMod(e.selected % 3 + command.dx, 3)
            air = air.copy(entry = e.copy(selected = next)); return result()
        }
        is AirAlertCommand.NudgePressure -> {
            val e = air.entry ?: air.prompt?.takeIf { it.confirmation }?.let {
                airSuggestedEntry(air, state.diveProfile, now)
            } ?: return result()
            val numeric = e.purpose in setOf(AirEntryPurpose.Interval, AirEntryPurpose.Time, AirEntryPurpose.ConfirmationNumber)
            val step = if (e.purpose == AirEntryPurpose.Interval) 30 else if (numeric) 1 else if (command.fine) s.unit.fineStep else s.unit.step
            val limit = when (e.purpose) { AirEntryPurpose.Interval -> 21600; AirEntryPurpose.Time -> 360; AirEntryPurpose.ConfirmationNumber -> 999; else -> s.unit.display(350.0) }
            val range = if (e.purpose.isPressure()) s.pressureRange(e.purpose) else 0..limit
            val value = ((e.text.toIntOrNull() ?: 0) + command.delta.coerceIn(-1, 1) * step).coerceIn(range)
            air = air.copy(entry = e.copy(text = value.toString(), replacing = true, error = null, edited = true)); return result()
        }
        is AirAlertCommand.Key -> {
            val e = air.entry ?: return result()
            if (command.index == 11) return reduceAirAlerts(state, AirAlertCommand.SavePressure, nowMs)
            if (command.index == 12) return reduceAirAlerts(state, if (e.purpose.isPressure()) AirAlertCommand.UseKeypad(false) else AirAlertCommand.CancelEntry, nowMs)
            val digit = when (command.index) { in 0..8 -> (command.index + 1).toString(); 9 -> "0"; else -> null }
            val text = if (command.index == 10) e.text.dropLast(1) else if (digit != null) ((if (e.replacing) "" else e.text) + digit).take(if (e.purpose == AirEntryPurpose.Interval) 5 else 4) else e.text
            air = air.copy(entry = e.copy(text = text, selected = command.index, replacing = false, error = null, keypad = true, edited = true)); return result()
        }
        AirAlertCommand.SavePressure -> {
            val e = air.entry ?: return result()
            fun fail(message: String): Reduction { air = air.copy(entry = e.copy(error = message)); return result() }
            val number = e.text.toIntOrNull()
            if (e.purpose in setOf(AirEntryPurpose.Interval, AirEntryPurpose.Time, AirEntryPurpose.ConfirmationNumber)) {
                val range = when (e.purpose) { AirEntryPurpose.Interval -> 30..21600; AirEntryPurpose.Time -> 1..360; else -> 1..999 }
                if (number == null || number !in range) return fail("Enter ${range.first}–${range.last}")
                when (e.purpose) {
                    AirEntryPurpose.Interval -> s = s.copy(intervalSeconds = number)
                    AirEntryPurpose.Time -> {
                        val seconds = number * 60
                        val oldTime = s.timesSeconds[air.timeSlot]
                        if (seconds in s.timesSeconds && (e.adding || seconds != oldTime)) return fail("An alert already uses that time")
                        s = if (e.adding) s.copy(timesSeconds = s.timesSeconds + seconds) else s.moveSequenceTime(oldTime, seconds)
                        s = s.copy(timesSeconds = s.timesSeconds.sorted())
                        air = air.copy(timeSlot = s.timesSeconds.indexOf(number * 60))
                    }
                    else -> {
                        val values = if (e.adding) s.confirmationNumbers + number else s.confirmationNumbers.mapIndexed { i, n -> if (i == air.confirmationSlot) number else n }
                        s = s.copy(confirmation = AirConfirmation.Selected, confirmationNumbers = values.distinct().sorted())
                        air = air.copy(confirmationSlot = s.confirmationNumbers.indexOf(number))
                    }
                }
                air = air.copy(settings = s, entry = null, delivered = s.dueCount(air.elapsedMs)); return result()
            }
            val bar = number?.let { s.unit.toBar(it) }
            if (bar == null || bar !in 0.0..350.0) return fail("Enter 0–${s.unit.display(350.0)} ${s.unit.label}")
            when (e.purpose) {
                AirEntryPurpose.TestStart -> {
                    if (!air.debugControls || state.diveProfile.active != null) return result()
                    if (bar <= s.reserveBar) return fail("Starting pressure must exceed reserve")
                    val changed = bar != s.startBar
                    s = s.copy(startBar = bar)
                    air = air.copy(simulation = AirSimulationEngine.start(s, state.diveProfile.settings, now),
                        startArmed = air.startArmed && !changed, prompt = null)
                }
                AirEntryPurpose.NextTank -> {
                    if ((air.gasWarning == null && !air.sharedAir) || state.diveProfile.active == null) return fail("Tank switch is unavailable")
                    if (bar <= s.reserveBar) return fail("New tank pressure must exceed your surface reserve")
                    air = AirGasWarnings.switchTank(air, bar)
                }
                AirEntryPurpose.Start -> {
                    if (bar <= s.reserveBar) return fail("Starting pressure must exceed reserve")
                    s = s.copy(startBar = bar); air = air.copy(startArmed = true)
                }
                AirEntryPurpose.SliderMinimum -> {
                    if (s.sliderMaxBar - bar < 1.0 - 1e-9) return fail("Leave at least ${s.unit.display(1.0)} ${s.unit.label} between limits")
                    s = s.copy(sliderMinBar = bar)
                }
                AirEntryPurpose.SliderMaximum -> {
                    if (bar - s.sliderMinBar < 1.0 - 1e-9) return fail("Leave at least ${s.unit.display(1.0)} ${s.unit.label} between limits")
                    s = s.copy(sliderMaxBar = bar)
                }
                AirEntryPurpose.Reserve -> { if (bar >= s.startBar || bar < 10.0) return fail("Reserve must be 10 BAR or more and below start"); s = s.copy(reserveBar = bar) }
                AirEntryPurpose.Reading -> {
                    val preview = air.prompt?.preview == true
                    if (!preview) air = AirAlertEngine.observe(air, bar)
                    air = AirGasWarnings.skip(air).copy(prompt = null)
                }
                AirEntryPurpose.Interval, AirEntryPurpose.Time, AirEntryPurpose.ConfirmationNumber -> Unit // handled above
                AirEntryPurpose.Calibration -> {
                    val r = air.receiver
                    val decoded = s.mapping.decode(r.payload)
                    if (!r.connected || r.packetAtMs?.let { now - it in 0..s.staleSeconds * 1000L } != true || decoded == null)
                        return fail("Need a fresh receiver packet")
                    if (abs(decoded - bar) > maxOf(2.0, bar * 0.02)) return fail("Signal does not match gauge; adjust byte mapping")
                    val first = r.firstCalibrationBar
                    if (first != null && abs(first - bar) < 5.0) return fail("Second reading must differ by at least 5 BAR")
                    s = s.copy(mapping = s.mapping.copy(verified = first != null))
                    air = air.copy(receiver = r.copy(firstCalibrationBar = first ?: bar,
                        liveBar = if (first != null) decoded else null, liveAtMs = if (first != null) r.packetAtMs else null))
                }
            }
            air = air.copy(settings = s, entry = null); return result()
        }
        is AirAlertCommand.PromptAction -> {
            val prompt = air.prompt ?: return result()
            if (command.choice == 2 || !prompt.confirmation) { air = air.copy(prompt = null); return result() }
            val entry = airSuggestedEntry(air, state.diveProfile, now)
            air = air.copy(entry = entry)
            if (command.choice == 0) return reduceAirAlerts(state.copy(airAlerts = air), AirAlertCommand.SavePressure, nowMs)
            return result()
        }
        else -> Unit
    }
    if (!air.menuOpen) return result()
    val field = airFields(air).getOrNull(air.selectedRow) ?: return result()
    val delta = (command as? AirAlertCommand.Adjust)?.delta?.coerceIn(-1, 1) ?: 1
    val activate = command == AirAlertCommand.Activate
    fun <T> cycle(values: List<T>, current: T): T = values[Math.floorMod(values.indexOf(current) + delta, values.size)]
    val diving = state.diveProfile.active != null
    val coarse = (command as? AirAlertCommand.Adjust)?.coarse == true
    if (airFieldLocked(state, field)) return result()
    val editedMapping = if (air.page == AirPage.ValueMapping) s.receiverValues.getOrNull(air.valueSlot)?.mapping ?: s.mapping else s.mapping
    when (field) {
        AirField.Mode -> {
            val modes = if (!diving) AirMode.entries.toList() else if (s.source == AirSource.Manual)
                listOf(AirMode.Off, AirMode.Reminder, AirMode.Manual) else listOf(AirMode.Off, AirMode.Receiver)
            s = when (cycle(modes, airMode(s))) {
                AirMode.Off -> s.copy(enabled = false)
                AirMode.Reminder -> s.copy(enabled = true, source = AirSource.Manual, confirmation = AirConfirmation.Never)
                AirMode.Manual -> s.copy(enabled = true, source = AirSource.Manual, confirmation = if (s.confirmation == AirConfirmation.Never) AirConfirmation.Every else s.confirmation)
                AirMode.Receiver -> s.copy(enabled = true, source = AirSource.Receiver, confirmation = AirConfirmation.Never)
            }
        }
        AirField.Timing -> if (activate || s.schedule == AirSchedule.AtTimes) openPage(AirPage.Schedule)
            else s = s.copy(intervalSeconds = (s.intervalSeconds + delta * if (coarse) 60 else 30).coerceIn(30, 21600))
        AirField.Confirmation -> if (s.schedule == AirSchedule.Once) s = s.copy(confirmation = if (s.confirmation == AirConfirmation.Never) AirConfirmation.Every else AirConfirmation.Never)
        else if (activate) {
            s = s.copy(confirmation = AirConfirmation.Selected); openPage(AirPage.Confirmations)
        } else {
            val current = if (s.confirmation == AirConfirmation.Never) 0 else if (s.confirmation == AirConfirmation.Selected) 1 else s.confirmEvery
            val every = (current + delta).coerceIn(if (s.source == AirSource.Receiver) 0 else 1, 100)
            s = s.copy(confirmation = if (every == 0) AirConfirmation.Never else AirConfirmation.Every, confirmEvery = every.coerceAtLeast(1))
        }
        AirField.Tank -> if (activate) openPage(AirPage.Gas)
        AirField.Style -> if (activate) openPage(AirPage.Appearance)
        AirField.ReceiverSetup -> if (activate) {
            openPage(AirPage.Receiver)
        }
        AirField.Schedule -> s = s.copy(schedule = cycle(AirSchedule.entries, s.schedule))
        AirField.Interval -> if (activate) openNumber(AirEntryPurpose.Interval, s.intervalSeconds)
            else s = s.copy(intervalSeconds = (s.intervalSeconds + delta * if (coarse) 60 else 30).coerceIn(30, 21600))
        AirField.Times -> if (activate) openPage(AirPage.Times)
        AirField.TimeSlot -> if (activate) openNumber(AirEntryPurpose.Time, s.timesSeconds[air.timeSlot] / 60)
            else if (coarse) {
                val old = s.timesSeconds[air.timeSlot]; val target = (old + delta * 60).coerceIn(60, 21600)
                if (target !in s.timesSeconds) s = s.moveSequenceTime(old, target)
            }
            else air = air.copy(timeSlot = Math.floorMod(air.timeSlot + delta, s.timesSeconds.size))
        AirField.AddTime -> if (activate && s.timesSeconds.size < 24) openNumber(AirEntryPurpose.Time, ((s.timesSeconds.maxOrNull() ?: 0) / 60 + 5).coerceAtMost(360), adding = true)
        AirField.RemoveTime -> if (activate && s.timesSeconds.size > 1) {
            val time = s.timesSeconds[air.timeSlot]
            s = s.copy(timesSeconds = s.timesSeconds.filterIndexed { i, _ -> i != air.timeSlot }, sequenceMessages = s.sequenceMessages - time, sequenceActions = s.sequenceActions - time)
            air = air.copy(timeSlot = air.timeSlot.coerceAtMost(s.timesSeconds.lastIndex))
        }
        AirField.ConfirmationSlot -> if (activate) openNumber(AirEntryPurpose.ConfirmationNumber, s.confirmationNumbers[air.confirmationSlot])
            else if (coarse) s = s.copy(confirmationNumbers = s.confirmationNumbers.mapIndexed { i, n -> if (i == air.confirmationSlot) (n + delta).coerceIn(1, 999) else n })
            else air = air.copy(confirmationSlot = Math.floorMod(air.confirmationSlot + delta, s.confirmationNumbers.size))
        AirField.AddConfirmation -> if (activate && s.confirmationNumbers.size < 24) openNumber(AirEntryPurpose.ConfirmationNumber, ((s.confirmationNumbers.maxOrNull() ?: 0) + 1).coerceAtMost(999), adding = true)
        AirField.RemoveConfirmation -> if (activate && s.confirmationNumbers.size > 1) { s = s.copy(confirmationNumbers = s.confirmationNumbers.filterIndexed { i, _ -> i != air.confirmationSlot }); air = air.copy(confirmationSlot = air.confirmationSlot.coerceAtMost(s.confirmationNumbers.lastIndex)) }
        AirField.Message -> if (activate) air = air.copy(textEntry = AirTextEntry(s.message))
        AirField.SequenceMessage -> if (activate) {
            val time = s.timesSeconds[air.timeSlot]
            air = air.copy(textEntry = AirTextEntry(s.sequenceMessages[time] ?: s.message, time))
        }
        AirField.SequenceAction -> {
            val time = s.timesSeconds[air.timeSlot]
            val value = cycle(AirSequenceAction.entries, s.sequenceActions[time] ?: AirSequenceAction.Default)
            s = s.copy(sequenceActions = if (value == AirSequenceAction.Default) s.sequenceActions - time else s.sequenceActions + (time to value))
        }
        AirField.Duration -> s = if (activate) s.copy(autoDismiss = !s.autoDismiss)
            else s.copy(autoDismiss = true, displaySeconds = (s.displaySeconds + delta * 5).coerceIn(5, 120))
        AirField.Opacity -> s = s.copy(opacityPercent = (s.opacityPercent + delta * 5).coerceIn(20, 100))
        AirField.Colour -> s = s.copy(colour = cycle(AirColour.entries, s.colour))
        AirField.Flash -> s = s.copy(flash = !s.flash)
        AirField.Unit -> s = s.copy(unit = cycle(AirPressureUnit.entries, s.unit))
        AirField.Start -> if (activate) openEntry(AirEntryPurpose.Start, s.startBar)
        AirField.SliderMinimum -> if (activate) openEntry(AirEntryPurpose.SliderMinimum, s.sliderMinBar)
        AirField.SliderMaximum -> if (activate) openEntry(AirEntryPurpose.SliderMaximum, s.sliderMaxBar)
        AirField.Reserve -> if (activate) openEntry(AirEntryPurpose.Reserve, s.reserveBar)
        AirField.Hud -> s = s.copy(hudMode = cycle(AirHudMode.entries, s.hudMode))
        AirField.Scan -> if (activate) scanReceivers()
        AirField.Device -> if (activate) airDeviceAtRow(air)?.let { connectReceiver(it.address) }
        AirField.Characteristic -> if (air.receiver.characteristics.isNotEmpty()) {
            val index = if (activate) air.receiver.selectedCharacteristic else Math.floorMod(air.receiver.selectedCharacteristic + delta, air.receiver.characteristics.size)
            val characteristic = air.receiver.characteristics[index]
            if (s.mapping.service != characteristic.service || s.mapping.characteristic != characteristic.uuid) {
                resetMapping(s.mapping.copy(service = characteristic.service, characteristic = characteristic.uuid))
                air = air.copy(receiver = air.receiver.copy(selectedCharacteristic = index, payload = emptyList(), packetAtMs = null))
            }
            if (activate) request(AirReceiverAction.Watch)
        }
        AirField.Values -> if (activate && air.receiver.connected) openPage(AirPage.Values)
        AirField.ValueSlot -> if (s.receiverValues.isNotEmpty()) air = air.copy(valueSlot = Math.floorMod(air.valueSlot + delta, s.receiverValues.size))
        AirField.ValueLabel, AirField.ValueUnit -> if (activate) s.receiverValues.getOrNull(air.valueSlot)?.let {
            air = air.copy(textEntry = AirTextEntry(if (field == AirField.ValueLabel) it.label else it.unit,
                purpose = if (field == AirField.ValueLabel) AirTextPurpose.ValueLabel else AirTextPurpose.ValueUnit))
        }
        AirField.ValueMapping -> if (activate) openPage(AirPage.ValueMapping)
        AirField.ValueSignal -> if (air.receiver.characteristics.isNotEmpty()) s.receiverValues.getOrNull(air.valueSlot)?.let { value ->
            val signals = air.receiver.characteristics
            val current = signals.indexOfFirst { value.mapping.matches(it.service, it.uuid) }.coerceAtLeast(0)
            val signal = signals[if (activate) current else Math.floorMod(current + delta, signals.size)]
            s = s.copy(receiverValues = s.receiverValues.mapIndexed { i, v -> if (i == air.valueSlot)
                v.copy(mapping = v.mapping.copy(service = signal.service, characteristic = signal.uuid)) else v })
            request(AirReceiverAction.Watch)
        }
        AirField.AddValue -> if (activate && air.receiver.connected && s.receiverValues.size < 8) {
            val signal = air.receiver.characteristics.getOrNull(air.receiver.selectedCharacteristic)
            if (signal != null) {
                val value = AirReceiverValue("Value ${s.receiverValues.size + 1}", mapping = s.mapping.copy(
                    service = signal.service, characteristic = signal.uuid, format = PressureFormat.UInt8,
                    scale = 1.0, offset = 0.0, byteOffset = 0, verified = false))
                s = s.copy(receiverValues = s.receiverValues + value)
                air = air.copy(valueSlot = s.receiverValues.lastIndex, textEntry = AirTextEntry(value.label, purpose = AirTextPurpose.ValueLabel))
                request(AirReceiverAction.Watch)
            }
        }
        AirField.RemoveValue -> if (activate) {
            s = s.copy(receiverValues = s.receiverValues.filterIndexed { i, _ -> i != air.valueSlot })
            air = air.copy(valueSlot = air.valueSlot.coerceAtMost((s.receiverValues.size - 1).coerceAtLeast(0)))
            request(AirReceiverAction.Watch)
        }
        AirField.Mapping -> if (activate && air.receiver.connected) openPage(AirPage.Mapping)
        AirField.Calibration -> if (activate) openPage(AirPage.Calibration)
        AirField.Disconnect -> if (activate) request(AirReceiverAction.Disconnect)
        AirField.Format -> resetMapping(editedMapping.copy(format = cycle(PressureFormat.entries, editedMapping.format)))
        AirField.ByteOrder -> resetMapping(editedMapping.copy(littleEndian = !editedMapping.littleEndian))
        AirField.ByteOffset -> resetMapping(editedMapping.copy(byteOffset = (editedMapping.byteOffset + delta).coerceIn(0, 508)))
        AirField.Scale -> resetMapping(editedMapping.copy(scale = cycle(listOf(0.0001, 0.001, 0.01, 0.1, 0.5, 1.0, 2.0, 10.0, 100.0), editedMapping.scale)))
        AirField.Offset -> resetMapping(editedMapping.copy(offset = (editedMapping.offset + delta).coerceIn(-10000.0, 10000.0)))
        AirField.SourceUnit -> resetMapping(editedMapping.copy(unit = cycle(AirPressureUnit.entries, editedMapping.unit)))
        AirField.Stale -> s = s.copy(staleSeconds = (s.staleSeconds + delta * 5).coerceIn(5, 60))
        AirField.Calibrate -> if (activate) openEntry(AirEntryPurpose.Calibration, s.mapping.decode(air.receiver.payload) ?: s.startBar)
        AirField.ResetCalibration -> if (activate) resetMapping(s.mapping)
        AirField.Back -> if (activate) back()
    }
    // Changing a schedule starts at the next future occurrence, never replays old deadlines.
    if (s.schedule != air.settings.schedule || s.intervalSeconds != air.settings.intervalSeconds || s.timesSeconds != air.settings.timesSeconds || s.enabled != air.settings.enabled)
        air = air.copy(delivered = s.dueCount(air.elapsedMs), prompt = null)
    air = air.copy(settings = s)
    return result()
}

fun airAlertPresentationBlocked(state: AppState): Boolean {
    val air = state.airAlerts.simulation?.air ?: state.airAlerts
    if (air.gasWarning?.level?.critical == true || air.buddyConfirmation || air.sharedAir ||
        air.entry?.purpose == AirEntryPurpose.NextTank) return false
    return state.mode == AppMode.Safety || state.safety.sealState == SealState.Failed || state.safety.vacuumReleasedPrompt ||
    (state.diveProfile.active != null && !state.housing.inputEnabled)
}

/** Shared by layout and housing routing so OK always belongs to the visible foreground card. */
fun airAlertVisible(state: AppState): Boolean {
    if (airAlertPresentationBlocked(state)) return false
    val air = state.airAlerts.simulation?.air ?: state.airAlerts
    return air.prompt != null || air.entry != null || air.gasWarning?.visible == true ||
        air.buddyConfirmation || air.sharedAir || air.textEntry != null
}

/** Own only visible alert controls; housing safety and the stop banner get first refusal. */
fun routeAirAlerts(state: AppState, event: HousingButtonEvent): RouteDecision? {
    val air = state.airAlerts
    if (airAlertPresentationBlocked(state)) return null
    fun route(command: AirAlertCommand) = RouteDecision(commands = listOf(DiveSettingsCommand.Alerts(command)))
    air.simulation?.let { test ->
        if (test.air.prompt == null && test.air.entry == null && test.air.gasWarning?.visible != true && !test.air.buddyConfirmation && !test.air.sharedAir) return when (event) {
            HousingButtonEvent.Down, HousingButtonEvent.BackOrSafety -> route(AirAlertCommand.StopTest)
            else -> RouteDecision()
        }
        return routeAirAlerts(state.copy(airAlerts = test.air, diveProfile = test.profile,
            housing = state.housing.copy(inputEnabled = true)), event) ?: RouteDecision()
    }
    if (air.textEntry != null && air.gasWarning?.visible != true) return when (event) {
        HousingButtonEvent.Left -> route(AirAlertCommand.MoveCharacter(-1))
        HousingButtonEvent.Right -> route(AirAlertCommand.MoveCharacter(1))
        HousingButtonEvent.ZoomIn -> route(AirAlertCommand.MoveCharacter(5))
        HousingButtonEvent.ZoomOut -> route(AirAlertCommand.MoveCharacter(-5))
        HousingButtonEvent.Ok -> route(AirAlertCommand.AddCharacter)
        HousingButtonEvent.Down -> route(AirAlertCommand.DeleteCharacter)
        HousingButtonEvent.Shutter -> route(AirAlertCommand.SaveText)
        HousingButtonEvent.Up, HousingButtonEvent.BackOrSafety -> route(AirAlertCommand.CancelText)
        else -> RouteDecision()
    }
    if (air.buddyConfirmation) return when (event) {
        HousingButtonEvent.Ok -> route(AirAlertCommand.ConfirmBuddyAir)
        HousingButtonEvent.Down, HousingButtonEvent.BackOrSafety -> route(AirAlertCommand.CancelBuddyAir)
        else -> RouteDecision()
    }
    if (air.sharedAir && air.entry == null && event == HousingButtonEvent.Ok) return route(AirAlertCommand.GasAction(1))
    air.entry?.let { entry ->
        if (!entry.keypad && entry.purpose.isPressure()) return when (event) {
            HousingButtonEvent.Left -> route(AirAlertCommand.NudgePressure(-1, fine = true))
            HousingButtonEvent.Right -> route(AirAlertCommand.NudgePressure(1, fine = true))
            HousingButtonEvent.ZoomIn -> route(AirAlertCommand.NudgePressure(-1))
            HousingButtonEvent.ZoomOut -> route(AirAlertCommand.NudgePressure(1))
            HousingButtonEvent.Ok, HousingButtonEvent.Shutter -> route(AirAlertCommand.SavePressure)
            HousingButtonEvent.Up -> route(AirAlertCommand.UseKeypad(true))
            HousingButtonEvent.Down, HousingButtonEvent.BackOrSafety -> route(AirAlertCommand.CancelEntry)
            else -> RouteDecision()
        }
        return when (event) {
            HousingButtonEvent.Up -> route(AirAlertCommand.MoveKey(0, -1))
            HousingButtonEvent.Down -> route(AirAlertCommand.MoveKey(0, 1))
            HousingButtonEvent.Left -> route(AirAlertCommand.MoveKey(-1, 0))
            HousingButtonEvent.Right -> route(AirAlertCommand.MoveKey(1, 0))
            HousingButtonEvent.ZoomIn -> route(AirAlertCommand.NudgePressure(if (entry.purpose.isPressure()) -1 else 1))
            HousingButtonEvent.ZoomOut -> route(AirAlertCommand.NudgePressure(if (entry.purpose.isPressure()) 1 else -1))
            HousingButtonEvent.Ok -> route(AirAlertCommand.Key(entry.selected))
            HousingButtonEvent.Shutter -> route(AirAlertCommand.SavePressure)
            HousingButtonEvent.BackOrSafety -> route(if (entry.purpose.isPressure()) AirAlertCommand.UseKeypad(false) else AirAlertCommand.CancelEntry)
            else -> RouteDecision()
        }
    }
    air.gasWarning?.takeIf { it.visible }?.let { warning ->
        return when (event) {
            HousingButtonEvent.Left, HousingButtonEvent.Up -> route(AirAlertCommand.SelectGasAction(-1))
            HousingButtonEvent.Right, HousingButtonEvent.Down -> route(AirAlertCommand.SelectGasAction(1))
            HousingButtonEvent.Ok -> route(AirAlertCommand.GasAction(warning.selectedAction))
            HousingButtonEvent.BackOrSafety -> route(AirAlertCommand.GasAction(3))
            else -> RouteDecision()
        }
    }
    air.prompt?.let { prompt ->
        return when (event) {
            HousingButtonEvent.Ok -> route(AirAlertCommand.PromptAction(0))
            HousingButtonEvent.Up -> if (prompt.confirmation) route(AirAlertCommand.UseKeypad(true)) else null
            HousingButtonEvent.Left, HousingButtonEvent.Right -> if (prompt.confirmation)
                route(AirAlertCommand.NudgePressure(if (event == HousingButtonEvent.Right) 1 else -1, fine = true)) else null
            HousingButtonEvent.Down, HousingButtonEvent.BackOrSafety -> route(AirAlertCommand.PromptAction(2))
            HousingButtonEvent.ZoomIn, HousingButtonEvent.ZoomOut -> if (prompt.confirmation)
                route(AirAlertCommand.NudgePressure(if (event == HousingButtonEvent.ZoomIn) -1 else 1)) else null
            else -> null // Shutter keeps shooting until the pressure input is adjusted.
        }
    }
    if (!air.menuOpen || state.mode != AppMode.DiveSettings) return null
    return when (event) {
        HousingButtonEvent.Up -> route(AirAlertCommand.Navigate(-1))
        HousingButtonEvent.Down -> route(AirAlertCommand.Navigate(1))
        HousingButtonEvent.Left -> route(AirAlertCommand.Adjust(-1))
        HousingButtonEvent.Right -> route(AirAlertCommand.Adjust(1))
        HousingButtonEvent.ZoomOut -> route(AirAlertCommand.Adjust(-1, coarse = true))
        HousingButtonEvent.ZoomIn -> route(AirAlertCommand.Adjust(1, coarse = true))
        HousingButtonEvent.Ok, HousingButtonEvent.Shutter -> route(AirAlertCommand.Activate)
        HousingButtonEvent.BackOrSafety -> route(AirAlertCommand.Back)
        else -> RouteDecision()
    }
}
