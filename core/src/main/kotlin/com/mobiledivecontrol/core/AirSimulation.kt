package com.mobiledivecontrol.core

/** Runtime-only debug dive. It owns a separate observation history and never enters dive logs or GATT. */
data class AirSimulation(
    val air: AirAlertState,
    val diveSettings: DiveSettings,
    val lastRealMs: Long,
    val countdownMs: Long = 10_000,
    val checks: Int = 0,
    val segmentStartMs: Long = 0,
    val intervalMs: Long,
    val complete: Boolean = false,
) {
    val profile: DiveProfileState get() = DiveProfileState(settings = diveSettings, sensorAvailable = true, depthMeters = 20.0,
        active = DiveSession(-1, diveSettings, elapsedMs = air.elapsedMs, maxDepthMeters = 20.0))
}

object AirSimulationEngine {
    const val COUNTDOWN_MS = 10_000L
    fun start(settings: AirAlertSettings, diveSettings: DiveSettings, nowMs: Long): AirSimulation {
        val baseline = AirObservation(settings.startBar, 0, 0.0)
        // Enable only this isolated copy; retain the configured source, cadence and sequence.
        val testSettings = settings.copy(enabled = true)
        val interval = deadline(testSettings, 1)
        return AirSimulation(AirAlertState(settings = testSettings, sessionId = -1, observation = baseline, lastManualObservation = baseline, rateAnchor = baseline,
            lastAmbient = 3.0), diveSettings, nowMs, intervalMs = interval ?: 0, complete = interval == null)
    }

    private fun deadline(settings: AirAlertSettings, number: Int): Long? = settings.scheduledAtMs(number)

    fun tick(test: AirSimulation, nowMs: Long, blocked: Boolean = false): AirSimulation {
        var air = AirGasWarnings.tick(test.air, test.profile, nowMs)
        if (blocked) return test.copy(air = air.copy(prompt = air.prompt?.copy(shownAtMs = nowMs)), lastRealMs = nowMs)
        val prompt = air.prompt
        if (prompt != null || air.entry != null || air.buddyConfirmation || air.gasWarning?.visible == true) {
            if (prompt != null && air.entry == null && air.settings.autoDismiss && nowMs - prompt.shownAtMs >= air.settings.displaySeconds * 1000L)
                air = air.copy(prompt = null)
            return test.copy(air = air, lastRealMs = nowMs)
        }
        val delta = (nowMs - test.lastRealMs).coerceIn(0, COUNTDOWN_MS)
        if (test.complete) {
            // Finite reminders end, but learned gas monitoring and a new-tank check keep running.
            if (airTracksPressure(air.settings) && (air.surfaceBarPerMinute != null || air.manualTankOverride)) {
                val elapsed = air.elapsedMs + delta * air.settings.intervalSeconds.coerceAtLeast(30) / 10
                air = air.copy(elapsedMs = elapsed, ambientMinutes = elapsed / 60_000.0 * 3.0, lastTickMs = nowMs)
                air = AirGasWarnings.tick(air, test.profile, nowMs)
            }
            return test.copy(air = air, lastRealMs = nowMs)
        }
        val remaining = (test.countdownMs - delta).coerceAtLeast(0)
        val elapsed = test.segmentStartMs + test.intervalMs * (COUNTDOWN_MS - remaining) / COUNTDOWN_MS
        air = air.copy(elapsedMs = elapsed, ambientMinutes = elapsed / 60_000.0 * 3.0, lastTickMs = nowMs)
        air = AirGasWarnings.tick(air, test.profile, nowMs)
        if (air.gasWarning?.visible == true || remaining > 0)
            return test.copy(air = air, lastRealMs = nowMs, countdownMs = remaining)
        val number = test.checks + 1
        val next = deadline(air.settings, number + 1)
        return test.copy(air = air.copy(delivered = number, prompt = AirPrompt(number, air.settings.needsConfirmation(number), nowMs).takeUnless { air.sharedAir }), checks = number,
            lastRealMs = nowMs, countdownMs = COUNTDOWN_MS, segmentStartMs = elapsed,
            intervalMs = next?.minus(elapsed) ?: 0, complete = next == null)
    }
}

/** One visible selection for the slider, its live estimate, and hardware OK. */
fun airSuggestedEntry(air: AirAlertState, dive: DiveProfileState, nowMs: Long): AirPressureEntry {
    val expected = air.settings.unit.display(AirAlertEngine.estimate(air, dive, nowMs)?.pressureBar ?: air.settings.startBar)
    val selected = expected.coerceIn(air.settings.pressureRange(AirEntryPurpose.Reading))
    // A bounded suggestion differs from EXPECTED; show the selected tank value explicitly before OK.
    return AirPressureEntry(AirEntryPurpose.Reading, selected.toString(), edited = selected != expected)
}

fun airSuggestedPressure(air: AirAlertState, dive: DiveProfileState, nowMs: Long): Int =
    airSuggestedEntry(air, dive, nowMs).text.toInt()
