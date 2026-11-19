package com.mobiledivecontrol.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

enum class AirPressureUnit(val label: String, val perBar: Double) {
    Bar("BAR", 1.0), Psig("PSIG", 14.503773773);
    fun display(bar: Double): Int = (bar * perBar).roundToInt()
    fun toBar(value: Int): Double = value / perBar
    val step: Int get() = 5
    val fineStep: Int get() = if (this == Bar) 1 else 10
}
enum class AirSchedule { Recurring, Once, AtTimes }
enum class AirConfirmation { Never, Every, Selected }
enum class AirSource { Manual, Receiver }
enum class AirSequenceAction { Default, Reminder, Pressure }
const val AIR_MESSAGE_LIMIT = 64
fun cleanAirMessage(text: String) = text.filter { !it.isISOControl() }.take(AIR_MESSAGE_LIMIT)
enum class AirColour(val argb: Long) {
    Amber(0xFFFFC857), Cyan(0xFF48E5E0), Green(0xFF74E39A), Purple(0xFFC7A0FF), Red(0xFFFF7676), White(0xFFFFFFFF)
}
enum class AirPage(val label: String) {
    Home("Dive Alerts"), Schedule("Timing"), Times("Alert sequence"), Confirmations("Specific alerts"),
    Appearance("Message & style"), Gas("Tank & display"), Receiver("Connect receiver"), Mapping("Byte mapping"), Calibration("Verify pressure"), Values("Labelled values"), ValueMapping("Value mapping")
}
enum class PressureFormat(val bytes: Int) { UInt8(1), UInt16(2), UInt24(3), UInt32(4), Float32(4) }

data class AirPressureMapping(
    val address: String = "", val deviceName: String = "", val service: String = "", val characteristic: String = "",
    val format: PressureFormat = PressureFormat.UInt16, val littleEndian: Boolean = true,
    val byteOffset: Int = 0, val scale: Double = 0.1, val offset: Double = 0.0,
    val unit: AirPressureUnit = AirPressureUnit.Bar, val verified: Boolean = false,
) {
    fun decodeNumber(payload: List<Int>): Double? {
        if (byteOffset !in 0..508 || payload.size < byteOffset + format.bytes ||
            !scale.isFinite() || scale <= 0 || !offset.isFinite()) return null
        val data = payload.subList(byteOffset, byteOffset + format.bytes)
        if (data.any { it !in 0..255 }) return null
        val raw = if (format == PressureFormat.Float32) ByteBuffer.wrap(data.map { it.toByte() }.toByteArray())
            .order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).float.toDouble()
        else (if (littleEndian) data.reversed() else data).fold(0L) { n, b -> (n shl 8) or b.toLong() }.toDouble()
        return (raw * scale + offset).takeIf { it.isFinite() }
    }
    fun decode(payload: List<Int>): Double? = decodeNumber(payload)?.div(unit.perBar)?.takeIf { it in 0.0..350.0 }
    fun matches(service: String, characteristic: String) = this.service.equals(service, true) && this.characteristic.equals(characteristic, true)
}

data class AirAlertSettings(
    val enabled: Boolean = false,
    val schedule: AirSchedule = AirSchedule.Recurring,
    val intervalSeconds: Int = 300,
    val timesSeconds: List<Int> = listOf(300, 600, 900),
    val confirmation: AirConfirmation = AirConfirmation.Every,
    val confirmEvery: Int = 1,
    val confirmationNumbers: List<Int> = listOf(1),
    val displaySeconds: Int = 15,
    val autoDismiss: Boolean = true,
    val opacityPercent: Int = 90,
    val colour: AirColour = AirColour.Amber,
    val flash: Boolean = true,
    val unit: AirPressureUnit = AirPressureUnit.Bar,
    val source: AirSource = AirSource.Manual,
    val startBar: Double = 200.0,
    /** Pressure to retain AFTER ascent and the safety stop; this is a user gas-plan input. */
    val reserveBar: Double = 50.0,
    val hudMode: AirHudMode = AirHudMode.TimeAndLast,
    val staleSeconds: Int = 15,
    val mapping: AirPressureMapping = AirPressureMapping(),
    val message: String = "CHECK AIR REMINDER",
    val sequenceMessages: Map<Int, String> = emptyMap(),
    val sequenceActions: Map<Int, AirSequenceAction> = emptyMap(),
    val sliderMinBar: Double = 0.0,
    val sliderMaxBar: Double = 350.0,
    val receiverValues: List<AirReceiverValue> = emptyList(),
) {
    fun pressureRange(purpose: AirEntryPurpose): IntRange = if (purpose == AirEntryPurpose.Reading)
        unit.display(sliderMinBar)..unit.display(sliderMaxBar) else 0..unit.display(350.0)

    fun dueCount(elapsedMs: Long): Int {
        val seconds = elapsedMs.coerceAtLeast(0) / 1000
        return when (schedule) {
            AirSchedule.Recurring -> (seconds / intervalSeconds.coerceAtLeast(30)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            AirSchedule.Once -> if (seconds >= intervalSeconds) 1 else 0
            AirSchedule.AtTimes -> timesSeconds.distinct().count { it <= seconds }
        }
    }
    fun sequenceTime(number: Int): Int? = if (schedule == AirSchedule.AtTimes) timesSeconds.distinct().sorted().getOrNull(number - 1) else null
    fun scheduledAtMs(number: Int): Long? = if (number < 1) null else when (schedule) {
        AirSchedule.Recurring -> number.toLong() * intervalSeconds.coerceAtLeast(30) * 1000L
        AirSchedule.Once -> (intervalSeconds * 1000L).takeIf { number == 1 }
        AirSchedule.AtTimes -> sequenceTime(number)?.times(1000L)
    }
    fun messageFor(number: Int): String = sequenceTime(number)?.let { sequenceMessages[it] } ?: message
    fun actionFor(number: Int): AirSequenceAction = sequenceTime(number)?.let { sequenceActions[it] } ?: AirSequenceAction.Default
    fun needsConfirmation(number: Int): Boolean = when (actionFor(number)) {
        AirSequenceAction.Reminder -> false
        AirSequenceAction.Pressure -> true
        AirSequenceAction.Default -> if (schedule == AirSchedule.Once) confirmation != AirConfirmation.Never else when (confirmation) {
            AirConfirmation.Never -> false
            AirConfirmation.Every -> number % confirmEvery.coerceAtLeast(1) == 0
            AirConfirmation.Selected -> number in confirmationNumbers
        }
    }
    fun moveSequenceTime(from: Int, to: Int): AirAlertSettings = copy(
        timesSeconds = timesSeconds.map { if (it == from) to else it },
        sequenceMessages = (sequenceMessages - from).let { m -> sequenceMessages[from]?.let { m + (to to it) } ?: m },
        sequenceActions = (sequenceActions - from).let { m -> sequenceActions[from]?.let { m + (to to it) } ?: m })

}

/** New installations start with a simple reminder; stored choices, including Off, are preserved. */
fun defaultAirAlertSettings() = AirAlertSettings(enabled = true, confirmation = AirConfirmation.Never)

fun airNextAlertAtMs(air: AirAlertState): Long? = if (!air.settings.enabled) null else
    air.settings.scheduledAtMs(maxOf(air.delivered, air.prompt?.number ?: 0, air.settings.dueCount(air.elapsedMs)) + 1)

fun airDiveClock(elapsedMs: Long): String {
    val seconds = elapsedMs.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

data class AirDevice(val address: String, val name: String)
data class AirCharacteristic(val service: String, val uuid: String, val readable: Boolean, val notifiable: Boolean)
enum class AirReceiverPhase { Idle, Scanning, Connecting, Ready, Failed }
data class AirReceiverValue(val label: String, val unit: String = "", val mapping: AirPressureMapping)
data class AirSignalPacket(val bytes: List<Int>, val atMs: Long)
fun airSignalKey(service: String, characteristic: String) = "${service.lowercase()}/${characteristic.lowercase()}"
fun airReceiverValueNumber(air: AirAlertState, value: AirReceiverValue, nowMs: Long): Double? {
    if (!air.receiver.connected || !value.mapping.address.equals(air.settings.mapping.address, true)) return null
    val packet = air.receiver.signals[airSignalKey(value.mapping.service, value.mapping.characteristic)] ?: return null
    if (nowMs - packet.atMs !in 0..air.settings.staleSeconds * 1000L) return null
    return value.mapping.decodeNumber(packet.bytes)
}

data class AirReceiverState(
    val status: String = "Not connected", val connected: Boolean = false,
    val devices: List<AirDevice> = emptyList(), val selectedDevice: Int = 0,
    val characteristics: List<AirCharacteristic> = emptyList(), val selectedCharacteristic: Int = 0,
    val payload: List<Int> = emptyList(), val packetAtMs: Long? = null,
    val packetService: String = "", val packetCharacteristic: String = "",
    val liveBar: Double? = null, val liveAtMs: Long? = null,
    val firstCalibrationBar: Double? = null,
    val phase: AirReceiverPhase = AirReceiverPhase.Idle,
    val signals: Map<String, AirSignalPacket> = emptyMap(),
)
data class AirObservation(val bar: Double, val elapsedMs: Long, val ambientMinutes: Double, val receiver: Boolean = false)
data class AirPrompt(val number: Int, val confirmation: Boolean, val shownAtMs: Long, val preview: Boolean = false)
enum class AirEntryPurpose { Reading, Start, TestStart, Reserve, Calibration, Interval, Time, ConfirmationNumber, SliderMinimum, SliderMaximum, NextTank }
fun AirEntryPurpose.isPressure() = this !in setOf(AirEntryPurpose.Interval, AirEntryPurpose.Time, AirEntryPurpose.ConfirmationNumber)
data class AirPressureEntry(
    val purpose: AirEntryPurpose, val text: String, val selected: Int = 4,
    val replacing: Boolean = true, val error: String? = null, val adding: Boolean = false, val keypad: Boolean = !purpose.isPressure(),
    val edited: Boolean = false,
)
enum class AirReceiverAction { Scan, Connect, Watch, Disconnect }
data class AirReceiverRequest(val serial: Long, val action: AirReceiverAction)

const val AIR_TEXT_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -!?.,"
enum class AirTextPurpose { Message, ValueLabel, ValueUnit }
data class AirTextEntry(val text: String, val sequenceTime: Int? = null, val character: Int = 0,
    val replacing: Boolean = true, val error: String? = null, val purpose: AirTextPurpose = AirTextPurpose.Message)

data class AirMenuLocation(val page: AirPage, val row: Int)
data class AirAlertState(
    val settings: AirAlertSettings = defaultAirAlertSettings(),
    val menuOpen: Boolean = false, val page: AirPage = AirPage.Home, val selectedRow: Int = 0,
    val backStack: List<AirMenuLocation> = emptyList(),
    val debugControls: Boolean = false, val simulation: AirSimulation? = null,
    val timeSlot: Int = 0, val confirmationSlot: Int = 0, val valueSlot: Int = 0,
    val startArmed: Boolean = false,
    val sessionId: Long? = null, val elapsedMs: Long = 0, val lastTickMs: Long? = null,
    val ambientMinutes: Double = 0.0, val lastAmbient: Double? = null,
    val observation: AirObservation? = null, val rateAnchor: AirObservation? = null,
    val surfaceBarPerMinute: Double? = null,
    val depthGap: Boolean = false,
    val delivered: Int = 0, val prompt: AirPrompt? = null, val promptChoice: Int = 0,
    val entry: AirPressureEntry? = null, val textEntry: AirTextEntry? = null,
    val lastManualObservation: AirObservation? = null,
    val comparison: String? = null,
    val receiver: AirReceiverState = AirReceiverState(),
    val receiverRequest: AirReceiverRequest? = null,
    val gasWarning: AirGasWarning? = null,
    val gasWarningSequence: Long = 0,
    val tankNumber: Int = 1,
    val previousTanks: List<AirTankRecord> = emptyList(),
    val manualTankOverride: Boolean = false,
    val buddyConfirmation: Boolean = false,
    val sharedAir: Boolean = false,
    val tankCheckDueMs: Long? = null,
)

/** Gas-only advisory at the present depth; not an NDL or a decompression/ascent clearance. */
data class AirEstimate(val pressureBar: Double, val bottomMinutes: Double?, val ascentAndStopBar: Double?, val live: Boolean, val projected: Boolean = false)

object AirAlertEngine {
    fun liveBar(air: AirAlertState, nowMs: Long): Double? = air.receiver.liveBar?.takeIf {
        !air.sharedAir && !air.manualTankOverride && air.settings.mapping.verified && air.settings.source == AirSource.Receiver && air.receiver.connected &&
            air.receiver.liveAtMs?.let { at -> nowMs - at in 0..air.settings.staleSeconds * 1000L } == true
    }

    fun estimate(air: AirAlertState, dive: DiveProfileState, nowMs: Long): AirEstimate? {
        if (air.sharedAir) return null
        val live = liveBar(air, nowMs)
        val observation = air.observation
        val rate = air.surfaceBarPerMinute
        val projected = rate != null && !air.depthGap && dive.sensorAvailable &&
            (air.settings.source == AirSource.Manual || live != null || observation?.receiver == false)
        val pressure = live ?: observation?.let {
            (it.bar - (if (projected) rate!! else 0.0) * (air.ambientMinutes - it.ambientMinutes).coerceAtLeast(0.0)).coerceAtLeast(0.0)
        } ?: return null
        if (air.sessionId == null || !dive.sensorAvailable || air.depthGap || rate == null ||
            (air.settings.source == AirSource.Receiver && live == null && observation?.receiver != false) || dive.decompressionHold ||
            dive.active?.limitReached == true) return AirEstimate(pressure, null, null, live != null, projected)
        val depth = dive.depthMeters ?: return AirEstimate(pressure, null, null, live != null, projected)
        val settings = dive.active?.settings ?: dive.settings
        val stopDepth = minOf(depth, settings.stopDepthMeters.toDouble())
        val ascentRate = airAscentBudgetMetersPerMinute(dive, nowMs)
        val ascent = (depth - stopDepth).coerceAtLeast(0.0) / ascentRate
        val stopMinutes = if (dive.active?.stopRecommended == true) settings.stopDurationSeconds / 60.0 else 0.0
        val ascentGas = rate * (ascent * (1 + (depth + stopDepth) / 20.0) +
            stopMinutes * (1 + stopDepth / 10.0) + stopDepth / ascentRate * (1 + stopDepth / 20.0))
        val bottom = ((pressure - air.settings.reserveBar - ascentGas) / (rate * (1 + depth / 10.0))).coerceAtLeast(0.0)
        return AirEstimate(pressure, bottom, ascentGas, live != null, projected)
    }

    /** Preview the gauge reading without committing it or changing the independently predicted pressure. */
    fun readingEstimate(air: AirAlertState, dive: DiveProfileState, nowMs: Long): AirEstimate? {
        val entry = air.entry?.takeIf { it.purpose == AirEntryPurpose.Reading && it.edited }
            ?: return estimate(air, dive, nowMs)
        val bar = entry.text.toIntOrNull()?.let { air.settings.unit.toBar(it) }
            ?.takeIf { it in 0.0..350.0 } ?: return null
        if (air.prompt?.preview == true) return null
        val draft = observe(air, bar)
        // The slider represents a manual gauge reading, even when a receiver is configured.
        return estimate(draft.copy(receiver = draft.receiver.copy(liveBar = null, liveAtMs = null)), dive, nowMs)
    }

    fun tick(input: AirAlertState, dive: DiveProfileState, nowMs: Long, presentationBlocked: Boolean = false): AirAlertState {
        var current = input
        if (current.simulation != null) {
            if (dive.active != null || !current.debugControls || !current.menuOpen) current = current.copy(simulation = null)
            else return current.copy(simulation = AirSimulationEngine.tick(current.simulation!!, nowMs, presentationBlocked), lastTickMs = nowMs)
        }
        return AirGasWarnings.tick(tickLive(current, dive, nowMs, presentationBlocked), dive, nowMs)
    }

    private fun tickLive(input: AirAlertState, dive: DiveProfileState, nowMs: Long, presentationBlocked: Boolean): AirAlertState {
        val session = dive.active
        val surfaceActive = input.menuOpen || input.prompt != null || input.entry != null || input.receiver.connected
        if (session == null) return input.copy(sessionId = null, elapsedMs = 0, lastTickMs = if (surfaceActive) nowMs else null,
            prompt = input.prompt?.takeIf { it.preview && (input.entry != null || !input.settings.autoDismiss || nowMs - it.shownAtMs < input.settings.displaySeconds * 1000L) },
            entry = input.entry?.takeUnless { it.purpose == AirEntryPurpose.Reading && input.prompt?.preview != true },
            observation = null, lastManualObservation = null, rateAnchor = null, surfaceBarPerMinute = null, ambientMinutes = 0.0, lastAmbient = null, depthGap = false)
        var air = input
        if (air.sessionId != session.startedAtEpochMs) {
            val baseline = if (air.startArmed && session.elapsedMs < 5_000) AirObservation(air.settings.startBar, 0, 0.0) else null
            air = air.copy(sessionId = session.startedAtEpochMs, elapsedMs = session.elapsedMs, lastTickMs = nowMs,
                ambientMinutes = 0.0, observation = baseline, lastManualObservation = baseline, rateAnchor = baseline, surfaceBarPerMinute = null,
                tankNumber = 1, previousTanks = emptyList(), manualTankOverride = false, tankCheckDueMs = null,
                sharedAir = false, buddyConfirmation = false,
                startArmed = false, delivered = 0, prompt = null, entry = null, comparison = null, depthGap = false, lastAmbient = dive.depthMeters?.takeIf { dive.sensorAvailable }?.let { 1 + it / 10.0 })
        }
        val delta = air.lastTickMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0
        val ambient = dive.depthMeters?.takeIf { dive.sensorAvailable }?.let { 1 + it.coerceAtLeast(0.0) / 10.0 }
        val continuous = delta <= 5_000 && ambient != null && air.lastAmbient != null
        // Gaps invalidate the consumption interval. Never interpolate missing depth as if measured.
        if ((!continuous && delta > 0) && air.lastTickMs != nowMs) {
            air = air.copy(depthGap = true, surfaceBarPerMinute = null, rateAnchor = null)
        }
        air = air.copy(elapsedMs = maxOf(session.elapsedMs, air.elapsedMs + delta), lastTickMs = nowMs,
            ambientMinutes = air.ambientMinutes + if (continuous) delta / 60_000.0 * ((ambient!! + air.lastAmbient!!) / 2) else 0.0,
            lastAmbient = ambient)
        if (!air.settings.enabled) return air.copy(prompt = null,
            entry = air.entry?.takeUnless { it.purpose == AirEntryPurpose.Reading })
        if (dive.atSurface) return air.copy(prompt = null, delivered = air.settings.dueCount(air.elapsedMs),
            entry = air.entry?.takeUnless { it.purpose == AirEntryPurpose.Reading })
        if (air.sharedAir) return air.copy(prompt = null)
        val live = liveBar(air, nowMs)
        if (live != null && (air.observation?.receiver != true ||
                air.elapsedMs - (air.observation?.elapsedMs ?: 0) >= 10_000)) {
            air = observe(air, live, receiver = true)
        }
        if (presentationBlocked) return air.copy(prompt = air.prompt?.copy(shownAtMs = nowMs))
        val old = air.prompt
        if (old != null && air.entry == null && air.settings.autoDismiss && nowMs - old.shownAtMs >= air.settings.displaySeconds * 1000L) {
            air = air.copy(prompt = null)
        }
        val count = air.settings.dueCount(air.elapsedMs)
        if (count > air.delivered && air.prompt == null && air.entry == null && !air.menuOpen) {
            // Coalesce missed deadlines to one current reminder; no notification backlog underwater.
            air = air.copy(delivered = count, prompt = AirPrompt(count, air.settings.needsConfirmation(count), nowMs), promptChoice = 0)
        }
        return air
    }

    fun observe(air: AirAlertState, bar: Double, receiver: Boolean = false): AirAlertState {
        if (air.sharedAir || !bar.isFinite() || bar !in 0.0..350.0 || air.sessionId == null) return air
        val current = AirObservation(bar, air.elapsedMs, air.ambientMinutes, receiver)
        val sourceChanged = air.observation?.receiver?.let { it != receiver } == true
        val previous = air.rateAnchor?.takeIf { it.receiver == receiver }
        val elapsed = previous?.let { current.elapsedMs - it.elapsedMs } ?: 0
        val exposure = previous?.let { current.ambientMinutes - it.ambientMinutes } ?: 0.0
        val drop = previous?.let { it.bar - bar } ?: 0.0
        val refill = previous != null && bar > previous.bar + 2.0
        val rate = if (previous != null && elapsed >= 30_000 && exposure > 0.0 && drop > 0.5 && !air.depthGap)
            drop / exposure else null
        val predicted = air.observation?.let { it.bar - (air.surfaceBarPerMinute ?: 0.0) * (air.ambientMinutes - it.ambientMinutes) }
        val comparison = when {
            refill -> "Pressure increased: consumption estimate reset"
            predicted == null || air.surfaceBarPerMinute == null -> "Reading saved"
            bar < predicted - maxOf(2.0, predicted * 0.02) -> "More air used than estimated"
            else -> "Reading agrees with estimate"
        }
        return air.copy(observation = current, lastManualObservation = if (receiver) air.lastManualObservation else current, rateAnchor = when {
            previous == null || refill || air.depthGap || rate != null -> current
            else -> previous
        }, surfaceBarPerMinute = when { refill || air.depthGap || sourceChanged -> null; rate != null -> rate; else -> air.surfaceBarPerMinute },
            depthGap = false, comparison = comparison)
    }
}
