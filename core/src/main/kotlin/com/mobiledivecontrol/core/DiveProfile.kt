package com.mobiledivecontrol.core

import kotlin.math.abs

/** Declared open-circuit breathing mix. */
data class DiveGas(val oxygenPercent: Int = 21, val heliumPercent: Int = 0) {
    init {
        require(oxygenPercent in 10..100 && heliumPercent in 0..90)
        require(oxygenPercent + heliumPercent <= 100)
    }
    val label: String get() = when {
        heliumPercent > 0 -> "Trimix $oxygenPercent/$heliumPercent"
        oxygenPercent == 21 -> "Air"
        oxygenPercent == 100 -> "Oxygen"
        else -> "Nitrox $oxygenPercent"
    }
}

data class DiveSettings(
    val gas: DiveGas = DiveGas(),
    val stopDepthMeters: Int = 5,
    val stopDurationSeconds: Int = 180,
) {
    init {
        require(stopDepthMeters == 0 || stopDepthMeters in 3..6)
        require(stopDurationSeconds in 180..300)
    }
    val bandMin: Double get() = if (stopDepthMeters == 0) 0.0 else maxOf(3.0, stopDepthMeters - 1.0)
    val bandMax: Double get() = if (stopDepthMeters == 0) 1.0 else minOf(6.0, stopDepthMeters + 1.0)
}

enum class DiveStopPhase { Watching, Armed, Holding, TooShallow, TooDeep, Complete }
enum class DiveStopAlert { Started, Completed }
enum class DiveStopPresentation { Expanded, Minimized, Dismissed }
enum class DiveLogOutcome { CompletedStop, IncompleteStop, NoStopRecorded }

data class DiveSample(val elapsedMs: Long, val depthMeters: Double, val gapBefore: Boolean = false)

/** Observed maxima for this dive; absent on logs written before exposure summaries were recorded. */
data class DiveExposureSummary(
    val maxCeilingMeters: Double,
    val maxCnsPercent: Double,
    val cnsOutsideTable: Boolean,
    val maxOtu24Hours: Double,
    val maxPpO2Ata: Double?,
)

data class DiveSession(
    val startedAtEpochMs: Long,
    val settings: DiveSettings,
    val elapsedMs: Long = 0,
    val maxDepthMeters: Double = 0.0,
    val samples: List<DiveSample> = emptyList(),
    val phase: DiveStopPhase = DiveStopPhase.Watching,
    val stopElapsedMs: Long = 0,
    val stopStarted: Boolean = false,
    val profileIncomplete: Boolean = false,
    val outcome: DiveLogOutcome? = null,
    /** Surface confirmation must be made entirely from fresh samples. */
    val surfaceElapsedMs: Long = 0,
    /** Latched when a valid computed NDL reaches zero; persists after off-gassing on ascent. */
    val limitReached: Boolean = false,
    /** Shallowest observed depth since completion, to distinguish renewed descent from drift. */
    val shallowestAfterStopMeters: Double? = null,
    /** Lowest valid NDL observed during this dive; absent on legacy or uninitialized profiles. */
    val minimumNdlSeconds: Int? = null,
    val exposure: DiveExposureSummary? = null,
) {
    /** Recommend a stop on ascent from every dive that went deeper than its target. */
    val stopRecommended: Boolean get() = maxDepthMeters > settings.stopDepthMeters + 1e-9
    val requiredByKnownProfile: Boolean get() = maxDepthMeters >= 30.0 || limitReached
    val remainingSeconds: Int get() = ((settings.stopDurationSeconds * 1000L - stopElapsedMs + 999) / 1000)
        .coerceAtLeast(0).toInt()
}

data class DiveProfileState(
    val settings: DiveSettings = DiveSettings(),
    val active: DiveSession? = null,
    val logs: List<DiveSession> = emptyList(),
    val depthMeters: Double? = null,
    /** Positive = sinking, negative = rising. */
    val verticalMetersPerMinute: Double? = null,
    val lastSampleMs: Long? = null,
    val sensorAvailable: Boolean = false,
    val rateWindow: List<DiveSample> = emptyList(),
    val alertSequence: Long = 0,
    val lastAlert: DiveStopAlert? = null,
    val selectedField: DiveSettingsField = DiveSettingsField.Log,
    /** 0 = current/latest, 1 = newest saved dive when a current dive exists, etc. */
    val logIndex: Int = 0,
    val stopPresentation: DiveStopPresentation = DiveStopPresentation.Expanded,
    val decompression: DecompressionState = DecompressionState(),
    val historyConfirmation: DecoHistory? = null,
    val historyPromptOffered: Boolean = false,
    val logMenuSession: DiveSession? = null,
    val logMenuDeleteSelected: Boolean = true,
    /** The exact saved session being confirmed, independent of changing list indices. */
    val pendingLogDeletion: DiveSession? = null,
    val deleteLogSelected: Boolean = false,
    val gasMenuOpen: Boolean = false,
    val gasMenuField: GasMenuField = GasMenuField.Type,
    /** Previous sample's ceiling state, so clearance alone cannot credit the preceding interval. */
    val stopDecompressionPaused: Boolean = false,
) {
    val requiredDecompressionCeilingMeters: Double? get() = decompression.readings?.ceilingMeters?.takeIf {
        decompression.history == DecoHistory.Tracking && it > 0.0
    }
    val decompressionHold: Boolean get() = requiredDecompressionCeilingMeters != null ||
        (stopDecompressionPaused && decompression.history != DecoHistory.Tracking)
    val selectedSavedLog: DiveSession? get() = logs.getOrNull(logIndex - if (active != null) 1 else 0)
    val stopActive: Boolean get() = active?.let { it.stopStarted && it.phase != DiveStopPhase.Complete } == true
    val stopExpanded: Boolean get() = active != null && (decompressionHold ||
        (active.stopStarted && stopPresentation == DiveStopPresentation.Expanded))
}

/**
 * Advisory recreational safety-stop timer. Trigger rules and implementation choices are in
 * docs/DIVE_PROFILE.md. Tissue loading and oxygen exposure are handled by DecompressionTracker.
 * Count only intervals bracketed by two fresh, ordered readings inside the configured band.
 */
object DiveProfileTracker {
    const val MAX_SAMPLES = 3_600
    const val MAX_LOGS = 1_000
    const val SURFACE_CONFIRM_MS = 60_000L
    const val SURFACE_END_METERS = 0.05

    /** Retire the previously requested 0 m bench checkpoint before enabling normal diving. */
    fun forLiveDiving(state: DiveProfileState): DiveProfileState {
        val surfaceRun = state.active?.takeIf { it.settings.stopDepthMeters == 0 }
        return state.copy(settings = if (state.settings.stopDepthMeters == 0) state.settings.copy(stopDepthMeters = 5) else state.settings,
            active = if (surfaceRun != null) null else state.active,
            logs = if (surfaceRun == null) state.logs else (listOf(surfaceRun.copy(
                outcome = if (surfaceRun.phase == DiveStopPhase.Complete) DiveLogOutcome.CompletedStop else DiveLogOutcome.IncompleteStop,
                surfaceElapsedMs = 0)) + state.logs).take(MAX_LOGS),
            stopPresentation = if (surfaceRun != null) DiveStopPresentation.Dismissed else state.stopPresentation)
    }

    fun unavailable(state: DiveProfileState): DiveProfileState = state.copy(
        sensorAvailable = false, depthMeters = null, verticalMetersPerMinute = null,
        rateWindow = emptyList(),
        decompression = DecompressionTracker.unavailable(state.decompression),
        active = state.active?.copy(profileIncomplete = true, surfaceElapsedMs = 0),
    )

    fun tick(state: DiveProfileState, nowMs: Long, connected: Boolean): DiveProfileState {
        if (!state.sensorAvailable) return state
        val age = state.lastSampleMs?.let { nowMs - it }
        return if (!connected || age == null || age !in 0 until PRESSURE_STALE_MS) unavailable(state) else state
    }

    fun sample(state: DiveProfileState, depthMeters: Double, nowMs: Long, epochMs: Long,
        triggerAtSurface: Boolean = false, surfaceMotionExercise: Boolean = false): DiveProfileState {
        val minimumDepth = if (triggerAtSurface && surfaceMotionExercise) -1.5 else 0.0
        if (!depthMeters.isFinite() || depthMeters !in minimumDepth..100.0) return unavailable(state)
        // Replayed, duplicate and out-of-order packets never earn time or move a profile backwards.
        if (state.lastSampleMs != null && nowMs <= state.lastSampleMs) return state
        val delta = state.lastSampleMs?.let { nowMs - it }
        val continuous = state.sensorAvailable && delta != null && delta < PRESSURE_STALE_MS
        val dt = if (continuous) delta!! else 0L
        val rateWindow = ((if (continuous) state.rateWindow else emptyList()) + DiveSample(nowMs, depthMeters))
            .filter { nowMs - it.elapsedMs <= 5_000L }.takeLast(100)
        val first = rateWindow.first()
        val rate = if (nowMs - first.elapsedMs >= 1_000) {
            (depthMeters - first.depthMeters) * 60_000.0 / (nowMs - first.elapsedMs)
        } else null
        val decompressionRequired = state.decompressionHold
        var next = state.copy(depthMeters = depthMeters, sensorAvailable = true, lastSampleMs = nowMs,
            verticalMetersPerMinute = rate, rateWindow = rateWindow, stopDecompressionPaused = decompressionRequired)
        var dive = state.active ?: if (depthMeters >= 1.5 || triggerAtSurface) {
            DiveSession(epochMs, if (triggerAtSurface) state.settings.copy(stopDepthMeters = 0) else state.settings,
                profileIncomplete = depthMeters > 3.0)
        } else return next
        // Temporary live bench trigger, explicitly requested by the user. Same samples, timer,
        // alerts, UI and persistent log; only the entry condition and target depth differ.
        if (triggerAtSurface && dive.settings.stopDepthMeters != 0) {
            dive = dive.copy(settings = dive.settings.copy(stopDepthMeters = 0), phase = DiveStopPhase.Armed,
                stopElapsedMs = 0, stopStarted = false)
        }
        val elapsed = dive.elapsedMs + if (state.active != null) (delta ?: 0L).coerceAtLeast(0L) else 0L
        val maxDepth = maxOf(dive.maxDepthMeters, depthMeters)
        val limitReached = dive.limitReached || (state.decompression.history == DecoHistory.Tracking &&
            state.decompression.readings?.ndlSeconds == 0)
        val readings = state.decompression.readings.takeIf { state.decompression.history == DecoHistory.Tracking }
        val ndl = readings?.ndlSeconds
        val exposure = readings?.let { reading ->
            val previous = dive.exposure
            DiveExposureSummary(
                maxOf(previous?.maxCeilingMeters ?: reading.ceilingMeters, reading.ceilingMeters),
                maxOf(previous?.maxCnsPercent ?: reading.cnsPercent, reading.cnsPercent),
                previous?.cnsOutsideTable == true || reading.cnsOutsideTable,
                maxOf(previous?.maxOtu24Hours ?: reading.otu24Hours, reading.otu24Hours),
                listOfNotNull(previous?.maxPpO2Ata, state.decompression.ppO2Ata).maxOrNull(),
            )
        } ?: dive.exposure
        dive = dive.copy(maxDepthMeters = maxDepth, limitReached = limitReached,
            minimumNdlSeconds = ndl?.let { minOf(dive.minimumNdlSeconds ?: it, it) } ?: dive.minimumNdlSeconds,
            exposure = exposure)
        val config = dive.settings
        val inBand = depthMeters in config.bandMin..config.bandMax
        var phase = dive.phase
        var progress = dive.stopElapsedMs
        var started = dive.stopStarted
        var shallowestAfterStop = if (phase == DiveStopPhase.Complete)
            minOf(dive.shallowestAfterStopMeters ?: state.depthMeters ?: depthMeters, depthMeters) else null
        var alert: DiveStopAlert? = null
        // A completed stop can rearm only on a renewed descent beyond the target tolerance.
        // An unfinished stop merely pauses on a deeper excursion; it never starts a second timer.
        val descending = continuous && state.depthMeters?.let { depthMeters > it + 1e-9 } == true
        if (phase == DiveStopPhase.Complete && descending &&
            depthMeters > maxOf(config.stopDepthMeters + 0.25, (shallowestAfterStop ?: depthMeters) + 0.25) + 1e-9 &&
            (triggerAtSurface || dive.stopRecommended)) {
            phase = DiveStopPhase.Armed
            progress = 0
            started = false
            shallowestAfterStop = null
        }
        if (phase == DiveStopPhase.Watching && (triggerAtSurface || dive.stopRecommended)) phase = DiveStopPhase.Armed
        val ascending = continuous && state.depthMeters?.let { it > depthMeters + 1e-9 } == true
        val surfaced = !triggerAtSurface && depthMeters <= SURFACE_END_METERS
        val ascendingIntoStop = !surfaced && (triggerAtSurface ||
            (dive.stopRecommended && ascending)) &&
            depthMeters <= config.stopDepthMeters + 1e-9
        if (phase == DiveStopPhase.Armed && ascendingIntoStop) {
            started = true
            alert = DiveStopAlert.Started
        }
        if (started && phase != DiveStopPhase.Complete) {
            if (inBand && dive.phase == DiveStopPhase.Holding && continuous &&
                !decompressionRequired && !state.stopDecompressionPaused &&
                state.depthMeters?.let { it in config.bandMin..config.bandMax } == true) {
                progress = (progress + dt).coerceAtMost(config.stopDurationSeconds * 1000L)
            }
            phase = when {
                progress >= config.stopDurationSeconds * 1000L && !decompressionRequired -> DiveStopPhase.Complete
                depthMeters < config.bandMin -> DiveStopPhase.TooShallow
                depthMeters > config.bandMax -> DiveStopPhase.TooDeep
                else -> DiveStopPhase.Holding
            }
            if (phase == DiveStopPhase.Complete) {
                alert = DiveStopAlert.Completed
                shallowestAfterStop = depthMeters
            }
        }
        val surfaceMs = if (depthMeters <= 0.8) {
            dive.surfaceElapsedMs + if (state.depthMeters?.let { it <= 0.8 } == true) dt else 0L
        } else 0L
        var samples = dive.samples
        // At most one regular log point each 5 s; always retain gaps, stop transitions and the final surface reading.
        if (samples.isEmpty() || elapsed - samples.last().elapsedMs >= 5_000 || !continuous || phase != dive.phase || surfaced) {
            if (samples.size >= MAX_SAMPLES) samples = listOf(samples.first()) + samples.drop(1).chunked(2).map { pair ->
                pair.last().copy(gapBefore = pair.any { it.gapBefore })
            }
            samples = samples + DiveSample(elapsed, depthMeters, state.active != null && !continuous)
        }
        dive = dive.copy(elapsedMs = elapsed, maxDepthMeters = maxDepth, samples = samples, phase = phase,
            stopElapsedMs = progress, stopStarted = started, surfaceElapsedMs = surfaceMs,
            shallowestAfterStopMeters = shallowestAfterStop,
            profileIncomplete = dive.profileIncomplete || (state.active != null && !continuous))
        if (alert != null) next = next.copy(alertSequence = next.alertSequence + 1, lastAlert = alert,
            stopPresentation = DiveStopPresentation.Expanded)
        // A fresh 0.0 m reading ends this dive. Surface noise cannot start another dive until 1.5 m.
        return if (surfaced) {
            val outcome = when {
                phase == DiveStopPhase.Complete -> DiveLogOutcome.CompletedStop
                dive.stopRecommended || dive.requiredByKnownProfile -> DiveLogOutcome.IncompleteStop
                else -> DiveLogOutcome.NoStopRecorded
            }
            next.copy(active = null, logs = (listOf(dive.copy(outcome = outcome, surfaceElapsedMs = 0)) + state.logs).take(MAX_LOGS),
                logIndex = 0, stopPresentation = DiveStopPresentation.Dismissed, lastAlert = null)
        } else next.copy(active = dive)
    }

    /** Never credit unobserved time. A previously completed stop stays completed across restarts. */
    fun restored(state: DiveProfileState): DiveProfileState = state.copy(
        sensorAvailable = false, depthMeters = null, verticalMetersPerMinute = null, lastSampleMs = null,
        rateWindow = emptyList(), lastAlert = null,
        decompression = DecompressionTracker.restored(state.decompression),
        stopDecompressionPaused = state.stopDecompressionPaused || (state.active != null &&
            state.decompression.tissues.size == 16 &&
            Buhlmann.ceilingMeters(state.decompression.tissues, state.decompression.anchorMeters) > 0.0),
        active = state.active?.copy(profileIncomplete = true, surfaceElapsedMs = 0,
            stopElapsedMs = if (state.active.phase == DiveStopPhase.Complete) state.active.stopElapsedMs else 0),
    )

    fun movementLabel(state: DiveProfileState): String = when {
        !state.sensorAvailable -> "DEPTH UNAVAILABLE"
        state.verticalMetersPerMinute == null -> "MEASURING DRIFT"
        abs(state.verticalMetersPerMinute) < 0.6 -> "HOLDING DEPTH"
        state.verticalMetersPerMinute < 0 -> "↑ RISING"
        else -> "↓ SINKING"
    }
}

enum class DiveSettingsField { Log, Gas, StopDepth, StopDuration, Back }
enum class GasMenuField { Type, Oxygen, Helium, Done }

fun gasMenuFields(gas: DiveGas): List<GasMenuField> = when {
    gas == DiveGas() -> listOf(GasMenuField.Type, GasMenuField.Done)
    gas.heliumPercent == 0 -> listOf(GasMenuField.Type, GasMenuField.Oxygen, GasMenuField.Done)
    else -> GasMenuField.entries
}

enum class StopDepthZone { Unknown, OnTarget, TooShallow, TooDeep }

fun stopDepthZone(depthMeters: Double?, targetMeters: Double): StopDepthZone = when {
    depthMeters == null || !depthMeters.isFinite() -> StopDepthZone.Unknown
    depthMeters < targetMeters - 0.25 - 1e-9 -> StopDepthZone.TooShallow
    depthMeters > targetMeters + 0.25 + 1e-9 -> StopDepthZone.TooDeep
    else -> StopDepthZone.OnTarget
}

sealed interface DiveSettingsCommand : ControlCommand {
    data object AcknowledgeStop : DiveSettingsCommand
    data object ExpandStop : DiveSettingsCommand
    /** Explicit declarations, never inferred from elapsed time or a missing checkpoint. */
    data object ConfirmNoRecentDives : DiveSettingsCommand
    data object ConfirmSurfaceInterval : DiveSettingsCommand
    data object DismissHistoryConfirmation : DiveSettingsCommand
    data object RequestHistoryConfirmation : DiveSettingsCommand
    data object CancelLogDeletion : DiveSettingsCommand
    data object ConfirmLogDeletion : DiveSettingsCommand
    data class SelectLogMenuAction(val delete: Boolean) : DiveSettingsCommand
    data object CloseLogMenu : DiveSettingsCommand
    data class SelectGasField(val field: GasMenuField) : DiveSettingsCommand
    data object CloseGasMenu : DiveSettingsCommand
    data class Select(val field: DiveSettingsField) : DiveSettingsCommand
    data class Navigate(val delta: Int) : DiveSettingsCommand
    data class Adjust(val delta: Int) : DiveSettingsCommand
    data object Confirm : DiveSettingsCommand
    data object Back : DiveSettingsCommand
}

fun reduceDiveSettings(state: AppState, command: DiveSettingsCommand): Reduction {
    val dive = state.diveProfile
    val gas = dive.active?.settings?.gas ?: dive.settings.gas
    if (command == DiveSettingsCommand.RequestHistoryConfirmation) return Reduction(
        if (dive.sensorAvailable && dive.active == null && (dive.depthMeters ?: 100.0) <= 0.5 &&
            dive.decompression.history != DecoHistory.Tracking && dive.pendingLogDeletion == null &&
            dive.logMenuSession == null && !dive.gasMenuOpen)
            state.copy(diveProfile = dive.copy(historyConfirmation = dive.decompression.history, historyPromptOffered = true)) else state)
    if (command == DiveSettingsCommand.DismissHistoryConfirmation ||
        (command == DiveSettingsCommand.Back && dive.historyConfirmation != null)) {
        return Reduction(state.copy(diveProfile = dive.copy(historyConfirmation = null)))
    }
    if (command == DiveSettingsCommand.Confirm && dive.historyConfirmation != null) {
        return reduceDiveSettings(state, if (dive.historyConfirmation == DecoHistory.SurfaceIntervalUnconfirmed)
            DiveSettingsCommand.ConfirmSurfaceInterval else DiveSettingsCommand.ConfirmNoRecentDives)
    }
    if (command == DiveSettingsCommand.ConfirmNoRecentDives || command == DiveSettingsCommand.ConfirmSurfaceInterval) {
        if (!dive.sensorAvailable) return Reduction(state)
        val deco = if (command == DiveSettingsCommand.ConfirmNoRecentDives)
            DecompressionTracker.initialize(dive.decompression, gas)
        else DecompressionTracker.confirmSurfaceInterval(dive.decompression, gas)
        return Reduction(state.copy(diveProfile = dive.copy(decompression = deco, historyConfirmation = null)))
    }
    if (dive.historyConfirmation != null) return Reduction(state)
    if (command == DiveSettingsCommand.AcknowledgeStop) {
        if (!dive.stopExpanded || dive.decompressionHold) return Reduction(state)
        return Reduction(state.copy(diveProfile = dive.copy(stopPresentation =
            if (dive.active?.phase == DiveStopPhase.Complete) DiveStopPresentation.Dismissed else DiveStopPresentation.Minimized)))
    }
    if (command == DiveSettingsCommand.ExpandStop) return Reduction(if (dive.stopActive)
        state.copy(diveProfile = dive.copy(stopPresentation = DiveStopPresentation.Expanded)) else state)
    if (dive.gasMenuOpen) return reduceGasMenu(state, command)
    if (command is DiveSettingsCommand.SelectGasField || command == DiveSettingsCommand.CloseGasMenu) return Reduction(state)
    if (dive.pendingLogDeletion != null) {
        val target = dive.pendingLogDeletion
        val cancel = command == DiveSettingsCommand.CancelLogDeletion || command == DiveSettingsCommand.Back ||
            (command == DiveSettingsCommand.Confirm && !dive.deleteLogSelected)
        if (cancel) return Reduction(state.copy(diveProfile = dive.copy(pendingLogDeletion = null, deleteLogSelected = false)))
        if (command is DiveSettingsCommand.Adjust || command is DiveSettingsCommand.Navigate) {
            val delta = if (command is DiveSettingsCommand.Adjust) command.delta else (command as DiveSettingsCommand.Navigate).delta
            return Reduction(state.copy(diveProfile = dive.copy(deleteLogSelected = delta > 0)))
        }
        if (command == DiveSettingsCommand.ConfirmLogDeletion || command == DiveSettingsCommand.Confirm) {
            val index = dive.logs.indexOf(target)
            val logs = if (index < 0 || target == dive.active) dive.logs else dive.logs.filterIndexed { i, _ -> i != index }
            val offset = if (dive.active != null) 1 else 0
            return Reduction(state.copy(diveProfile = dive.copy(logs = logs, pendingLogDeletion = null, logMenuSession = null,
                deleteLogSelected = false, selectedField = DiveSettingsField.Log,
                logIndex = (index + offset).coerceIn(0, (logs.size + offset - 1).coerceAtLeast(0)))))
        }
        return Reduction(state)
    }
    if (command == DiveSettingsCommand.ConfirmLogDeletion || command == DiveSettingsCommand.CancelLogDeletion) return Reduction(state)
    if (dive.logMenuSession != null) {
        if (command == DiveSettingsCommand.CloseLogMenu || command == DiveSettingsCommand.Back ||
            (command == DiveSettingsCommand.Confirm && !dive.logMenuDeleteSelected))
            return Reduction(state.copy(diveProfile = dive.copy(logMenuSession = null)))
        if (command is DiveSettingsCommand.SelectLogMenuAction)
            return Reduction(state.copy(diveProfile = dive.copy(logMenuDeleteSelected = command.delete)))
        if (command is DiveSettingsCommand.Navigate || command is DiveSettingsCommand.Adjust)
            return Reduction(state.copy(diveProfile = dive.copy(logMenuDeleteSelected = !dive.logMenuDeleteSelected)))
        if (command == DiveSettingsCommand.Confirm)
            return Reduction(state.copy(diveProfile = dive.copy(pendingLogDeletion = dive.logMenuSession, deleteLogSelected = false)))
        return Reduction(state)
    }
    if (command is DiveSettingsCommand.SelectLogMenuAction || command == DiveSettingsCommand.CloseLogMenu) return Reduction(state)
    val fields = DiveSettingsField.entries
    if (command == DiveSettingsCommand.Back ||
        (command == DiveSettingsCommand.Confirm && dive.selectedField == DiveSettingsField.Back)) {
        return Reduction(state.copy(mode = AppMode.CameraLive))
    }
    if (command is DiveSettingsCommand.Select) return Reduction(state.copy(diveProfile = dive.copy(selectedField = command.field)))
    if (command is DiveSettingsCommand.Navigate) return Reduction(state.copy(diveProfile = dive.copy(
        selectedField = fields[Math.floorMod(dive.selectedField.ordinal + command.delta, fields.size)])))
    if (dive.selectedField == DiveSettingsField.Gas) return Reduction(
        if (command == DiveSettingsCommand.Confirm && dive.active == null)
            state.copy(diveProfile = dive.copy(gasMenuOpen = true, gasMenuField = GasMenuField.Type)) else state)
    val delta = if (command is DiveSettingsCommand.Adjust) command.delta.coerceIn(-1, 1) else 1
    if (dive.selectedField == DiveSettingsField.Log) {
        if (command == DiveSettingsCommand.Confirm) return Reduction(state.copy(diveProfile = dive.copy(
            logMenuSession = dive.selectedSavedLog, logMenuDeleteSelected = true)))
        val count = dive.logs.size + if (dive.active != null) 1 else 0
        return Reduction(state.copy(diveProfile = dive.copy(logIndex = if (count == 0) 0 else Math.floorMod(dive.logIndex + delta, count))))
    }
    // Lock the configuration captured by an active dive, even through stale pressure or reconnects.
    if (dive.active != null || dive.selectedField == DiveSettingsField.Back) return Reduction(state)
    val settings = dive.settings
    val updated = when (dive.selectedField) {
        DiveSettingsField.StopDepth -> settings.copy(stopDepthMeters = (settings.stopDepthMeters + delta).coerceIn(3, 6))
        DiveSettingsField.StopDuration -> settings.copy(stopDurationSeconds = if (settings.stopDurationSeconds == 180) 300 else 180)
        else -> settings
    }
    return Reduction(state.copy(diveProfile = dive.copy(settings = updated)))
}

private fun reduceGasMenu(state: AppState, command: DiveSettingsCommand): Reduction {
    val dive = state.diveProfile
    val field = dive.gasMenuField
    val gas = dive.settings.gas
    if (command == DiveSettingsCommand.Back || command == DiveSettingsCommand.CloseGasMenu ||
        (command == DiveSettingsCommand.Confirm && field == GasMenuField.Done))
        return Reduction(state.copy(diveProfile = dive.copy(gasMenuOpen = false)))
    val fields = gasMenuFields(gas)
    if (command is DiveSettingsCommand.SelectGasField) return Reduction(
        if (command.field in fields) state.copy(diveProfile = dive.copy(gasMenuField = command.field)) else state)
    if (command is DiveSettingsCommand.Navigate || command == DiveSettingsCommand.Confirm) {
        val delta = if (command is DiveSettingsCommand.Navigate) command.delta else 1
        return Reduction(state.copy(diveProfile = dive.copy(gasMenuField = fields[Math.floorMod(fields.indexOf(field) + delta, fields.size)])))
    }
    if (command !is DiveSettingsCommand.Adjust || dive.active != null) return Reduction(state)
    val delta = command.delta.coerceIn(-1, 1)
    val updated = when (field) {
        GasMenuField.Type -> {
            val type = when { gas == DiveGas() -> 0; gas.heliumPercent == 0 -> 1; else -> 2 }
            listOf(DiveGas(), DiveGas(32), DiveGas(21, 35))[Math.floorMod(type + delta, 3)]
        }
        GasMenuField.Oxygen -> if (gas == DiveGas()) gas else gas.copy(
            oxygenPercent = (gas.oxygenPercent + delta).coerceIn(if (gas.heliumPercent == 0) 22 else 10, 100 - gas.heliumPercent))
        GasMenuField.Helium -> if (gas.heliumPercent == 0) gas else gas.copy(
            heliumPercent = (gas.heliumPercent + delta).coerceIn(1, 100 - gas.oxygenPercent))
        GasMenuField.Done -> gas
    }
    return Reduction(state.copy(diveProfile = dive.copy(settings = dive.settings.copy(gas = updated))))
}
