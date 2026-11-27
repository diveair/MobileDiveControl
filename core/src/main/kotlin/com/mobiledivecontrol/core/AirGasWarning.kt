package com.mobiledivecontrol.core

/** Pressure-based advisories, never a claim that the actual breathing supply is known. */
enum class AirGasLevel(val critical: Boolean, val repeatMs: Long) {
    Reserve(false, 30_000), Low(false, 15_000), Critical(true, 5_000), Empty(true, 5_000)
}

data class AirGasWarning(
    val level: AirGasLevel,
    val visible: Boolean = true,
    val nextAtMs: Long,
    val selectedAction: Int = 0,
    val projectedSurfaceBar: Double? = null,
)

data class AirTankRecord(val number: Int, val lastReading: AirObservation?, val surfaceBarPerMinute: Double?)

object AirGasWarnings {
    // Bench-design margins, not a prescribed reserve or a guarantee of emergency ascent gas.
    const val REACTION_SECONDS = 60.0
    val minimumCriticalMarginBar = AirPressureUnit.Psig.toBar(10)

    fun criticalSurfaceMarginBar(air: AirAlertState, dive: DiveProfileState): Double {
        val ambient = 1 + (dive.depthMeters ?: 0.0).coerceAtLeast(0.0) / 10.0
        val reaction = (air.surfaceBarPerMinute ?: 0.0) * ambient * REACTION_SECONDS / 60.0
        return maxOf(minimumCriticalMarginBar, air.settings.reserveBar * 0.25) + reaction
    }

    /** The normal timer can reserve the full stop; warnings credit only verified completed stop time. */
    fun projectedSurfaceBar(air: AirAlertState, dive: DiveProfileState, nowMs: Long): Double? {
        val estimate = AirAlertEngine.estimate(air, dive, nowMs) ?: return null
        val budget = estimate.ascentAndStopBar ?: return null
        val rate = air.surfaceBarPerMinute ?: return null
        val session = dive.active ?: return null
        val stopDepth = minOf(dive.depthMeters ?: return null, session.settings.stopDepthMeters.toDouble())
        val creditedStopMs = if (session.stopRecommended)
            session.stopElapsedMs.coerceIn(0, session.settings.stopDurationSeconds * 1000L) else 0
        val creditedGas = rate * (creditedStopMs / 60_000.0) * (1 + stopDepth / 10.0)
        return estimate.pressureBar - (budget - creditedGas).coerceAtLeast(0.0)
    }

    fun tick(input: AirAlertState, dive: DiveProfileState, nowMs: Long): AirAlertState {
        if (!input.settings.enabled) return input.copy(gasWarning = null, prompt = null,
            tankCheckDueMs = null, buddyConfirmation = false, sharedAir = false,
            entry = input.entry?.takeUnless { it.purpose == AirEntryPurpose.Reading || it.purpose == AirEntryPurpose.NextTank })
        val freshSurface = dive.sensorAvailable && dive.depthMeters?.let {
            it.isFinite() && (it <= 0.05 || (it <= DiveProfileTracker.SURFACE_END_METERS &&
                (dive.active?.surfaceElapsedMs ?: 0) >= DiveProfileTracker.SURFACE_SHUTDOWN_CONFIRM_MS))
        } == true
        if (freshSurface) return input.copy(gasWarning = null, tankCheckDueMs = null, sharedAir = false, buddyConfirmation = false)
        if (input.sharedAir) return input.copy(gasWarning = null, prompt = null, tankCheckDueMs = null)
        val old = input.gasWarning
        if (old?.level?.critical == true) {
            // Missing pressure, loss of the receiver, dismissals and menus are not surfacing.
            val projected = projectedSurfaceBar(input, dive, nowMs)
            val reported = AirAlertEngine.liveBar(input, nowMs) ?: input.observation?.takeUnless { it.receiver }?.bar
            val level = if (reported != null && reported <= 0.0) AirGasLevel.Empty else old.level
            return input.copy(gasWarning = old.copy(level = level, visible = true,
                selectedAction = old.selectedAction.coerceIn(0, 2),
                projectedSurfaceBar = projected ?: old.projectedSurfaceBar),
                entry = input.entry?.takeIf { it.purpose == AirEntryPurpose.Reading || it.purpose == AirEntryPurpose.NextTank },
                textEntry = null, prompt = null,
                gasWarningSequence = input.gasWarningSequence + if (level != old.level) 1 else 0)
        }
        val confirmed = AirAlertEngine.liveBar(input, nowMs) ?: input.observation?.takeUnless { it.receiver }?.bar
        val knownNearEmpty = confirmed != null && confirmed <= minimumCriticalMarginBar
        if (dive.active == null || !airTracksPressure(input.settings) ||
            (!knownNearEmpty && (!dive.sensorAvailable || dive.depthMeters?.let { it > 1.0 } != true)))
            return input.copy(gasWarning = null)
        var air = if (input.surfaceBarPerMinute != null) input.copy(tankCheckDueMs = null) else input
        // A confirmed new tank needs a prompt reading, even with a once-only alert schedule.
        if (air.tankCheckDueMs?.let { air.elapsedMs >= it } == true && air.entry == null && air.prompt == null) {
            air = air.copy(prompt = AirPrompt(air.delivered + 1, true, nowMs),
                tankCheckDueMs = if (air.surfaceBarPerMinute == null) air.elapsedMs + 30_000 else null)
        }
        val live = AirAlertEngine.liveBar(air, nowMs)
        val reported = live ?: air.observation?.takeUnless { it.receiver }?.bar
        val surface = projectedSurfaceBar(air, dive, nowMs)
        val level = when {
            reported != null && reported <= 0.0 -> AirGasLevel.Empty
            reported != null && reported <= minimumCriticalMarginBar -> AirGasLevel.Critical
            surface == null || !surface.isFinite() -> return air.copy(gasWarning = old)
            surface <= criticalSurfaceMarginBar(air, dive) -> AirGasLevel.Critical
            surface <= air.settings.reserveBar * 0.5 -> AirGasLevel.Low
            surface <= air.settings.reserveBar -> AirGasLevel.Reserve
            else -> null
        }
        if (level == null) return air.copy(gasWarning = null)
        val changed = old == null || level != old.level
        val repeat = if (level == AirGasLevel.Low && surface != null && surface <= air.settings.reserveBar * 0.25)
            5_000L else level.repeatMs
        val show = changed || old!!.visible || air.elapsedMs >= old.nextAtMs
        val announce = changed || (show && old?.visible == false)
        val next = if (announce) air.elapsedMs + repeat else minOf(old!!.nextAtMs, air.elapsedMs + repeat)
        return air.copy(
            gasWarning = AirGasWarning(level, show, next, if (changed) 0 else old!!.selectedAction,
                surface ?: old?.projectedSurfaceBar),
            gasWarningSequence = air.gasWarningSequence + if (announce) 1 else 0,
            prompt = if (show && air.entry == null) null else air.prompt,
            entry = if (level.critical) air.entry?.takeIf {
                it.purpose == AirEntryPurpose.Reading || it.purpose == AirEntryPurpose.NextTank
            } else air.entry,
            textEntry = if (level.critical) null else air.textEntry,
        )
    }

    fun skip(air: AirAlertState): AirAlertState {
        val warning = air.gasWarning ?: return air
        if (warning.level.critical) return air
        val repeat = if (warning.level == AirGasLevel.Low &&
            warning.projectedSurfaceBar?.let { it <= air.settings.reserveBar * 0.25 } == true) 5_000L
            else warning.level.repeatMs
        return air.copy(gasWarning = warning.copy(visible = false, nextAtMs = air.elapsedMs + repeat))
    }

    fun switchTank(air: AirAlertState, pressureBar: Double): AirAlertState {
        val baseline = AirObservation(pressureBar, air.elapsedMs, air.ambientMinutes)
        return air.copy(
            tankNumber = air.tankNumber + 1,
            previousTanks = (air.previousTanks + AirTankRecord(air.tankNumber, air.observation, air.surfaceBarPerMinute)).takeLast(8),
            manualTankOverride = true, sharedAir = false, buddyConfirmation = false,
            observation = baseline, lastManualObservation = baseline, rateAnchor = baseline,
            surfaceBarPerMinute = null, depthGap = false, comparison = null,
            gasWarning = null, prompt = null, entry = null,
            tankCheckDueMs = air.elapsedMs + 30_000,
            receiver = air.receiver.copy(liveBar = null, liveAtMs = null),
        )
    }
}

fun airGasTitle(warning: AirGasWarning, dive: DiveProfileState): String = when (warning.level) {
    AirGasLevel.Empty -> "TANK REPORTED EMPTY"
    AirGasLevel.Critical -> "OUT OF AIR RISK"
    AirGasLevel.Low -> "LOW AIR RESERVE"
    AirGasLevel.Reserve -> when {
        dive.active?.stopStarted == true -> "CHECK AIR RESERVE"
        warning.promptsPlannedStopAscent(dive) -> "BEGIN PLANNED ASCENT TO SAFETY STOP"
        else -> "BEGIN YOUR PLANNED ASCENT"
    }
}

fun AirGasWarning.promptsPlannedStopAscent(dive: DiveProfileState): Boolean =
    level == AirGasLevel.Reserve && dive.active?.let { it.stopRecommended && !it.stopStarted } == true

fun airGasSpeech(level: AirGasLevel): String = when (level) {
    AirGasLevel.Reserve -> "Low air reserve. Check your gauge and begin your planned ascent."
    AirGasLevel.Low -> "Low air. Reserve is falling. Check your gauge and signal your buddy."
    AirGasLevel.Critical -> "Out of air risk. Out of air risk. Gas margin critically low. Check your gauge and signal your buddy."
    AirGasLevel.Empty -> "Out of air. Out of air. Tank reported empty. Signal your buddy and follow your emergency training."
}

/** Only the optional panel is minimized. Decompression obligations and recorded stop time stay intact. */
fun airWarningStopProfile(state: AppState): DiveProfileState {
    val air = state.airAlerts.simulation?.air ?: state.airAlerts
    val dive = state.diveProfile
    return if (!air.sharedAir && air.gasWarning?.level?.critical == true &&
        dive.stopPresentation == DiveStopPresentation.Expanded && !dive.decompressionHold && dive.active?.limitReached != true)
        dive.copy(stopPresentation = DiveStopPresentation.Minimized) else dive
}
