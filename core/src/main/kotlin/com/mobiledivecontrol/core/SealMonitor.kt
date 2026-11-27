package com.mobiledivecontrol.core

import com.mobiledivecontrol.core.HousingSealTransitions.Banner
import com.mobiledivecontrol.core.HousingSealTransitions.Location
import com.mobiledivecontrol.core.HousingSealTransitions.Observation
import com.mobiledivecontrol.core.HousingSealTransitions.Vacuum


enum class SealIncident { IncompleteCheck, SurfaceLeak, UnderwaterLeak, UnsealedDive, SuspectedLoss }
enum class VacuumTrend { Unknown, Steady, Strengthening, Weakening }
enum class DisconnectReason { UnknownDisconnect, ConfirmedUserShutdown }
enum class Verification { Idle, Inspection, Testing, Failed, Interrupted, Passed }

data class SealReading(
    val observation: Observation,
    val magnitudeKpa: Double,
    val depthM: Double,
    val timeMs: Long,
    val ambientKpa: Double,
    val trend: VacuumTrend = VacuumTrend.Unknown,
    val verifiedHold: Boolean = false,
)

data class SealMonitor(
    val last: SealReading? = null,
    val snapshot: SealReading? = null,
    val incidentOrigin: SealReading? = null,
    val lostWhileOfflineMinutes: Long? = null,
    val submergedReference: SealReading? = null,
    val declinePacket: Long? = null,
    val incidents: Set<SealIncident> = emptySet(),
    val verification: Verification = Verification.Idle,
    val inspected: Boolean = false,
    val failures: Int = 0,
    val location: Location? = null,
    val transition: Banner = Banner.B00,
    val banner: Banner = Banner.B01,
    val dismissedVacuumSetup: Boolean = false,
    val acknowledged: Set<Banner> = emptySet(),
    val passAtMs: Long = 0L,
    val wasDisconnected: Boolean = false,
    val disconnectReason: DisconnectReason? = null,
    val surfaceSamples: Int = 0,
    val depthPacket: Long? = null,
    val invalidWater: Boolean = false,
    val invalidBaro: Boolean = false,
    val confirmedRelease: Boolean = false,
    val releaseQuestion: Boolean = false,
    val releaseAnswerYes: Boolean = false,
    val reconnectAmbientPacket: Long? = null,
    val holdSinceMs: Long? = null,
    val submergedSinceSealCheck: Boolean = false,
)

val AppState.vacuumMenuAvailable: Boolean
    get() = safety.sealMonitor.dismissedVacuumSetup && (mode == AppMode.CameraLive || mode == AppMode.CameraAdjust) &&
        !camera.recordingPaused && !camera.panoramaReviewAvailable && !diveProfile.stopExpanded && !airAlertVisible(this)

internal object SealMonitoring {
    val limits = SafetyThresholds()

    fun guard(state: AppState, now: Long): Boolean {
        val water = state.waterPressureTelemetry ?: return false
        val baro = state.barometricPressureTelemetry ?: return false
        return state.bleConnectionState == BleConnectionState.Ready &&
            state.safety.sealMonitor.location == Location.Surface &&
            !state.safety.sealMonitor.invalidWater && !state.safety.sealMonitor.invalidBaro &&
            water.isFresh(now) && baro.isFresh(now) &&
            (water.source == SensorPacketSource.Simulation) == (baro.source == SensorPacketSource.Simulation) &&
            state.safety.waterPressureKpa?.let(::validWaterPressure) == true &&
            state.safety.barometricPressureKpa?.let(::validBarometricPressure) == true
    }

    fun update(state: AppState, now: Long): SealMonitor {
        val s = state.safety
        val old = s.sealMonitor
        if (!state.housing.connected) {
            return old.copy(submergedReference = null, declinePacket = null, snapshot = old.snapshot ?: old.last, last = null,
                location = null, surfaceSamples = 0, depthPacket = null, banner = Banner.B01, dismissedVacuumSetup = false,
                wasDisconnected = old.wasDisconnected || old.last != null,
                inspected = false, reconnectAmbientPacket = null, releaseAnswerYes = false, verification = interrupted(old.verification))
        }
        val water = state.waterPressureTelemetry
        val depth = pressureDepthMeters(s.waterPressureKpa)?.takeIf { water?.isFresh(now) == true && !old.invalidWater }
        val atSurface = depth != null && depth <= limits.surfaceMaxDepthM - limits.surfaceReturnMarginM
        val samples = when {
            atSurface && water?.packetCount == 1L -> 1
            !atSurface -> 0
            old.depthPacket == water?.packetCount -> old.surfaceSamples
            else -> (old.surfaceSamples + 1).coerceAtMost(limits.sealValidationSamples.toInt())
        }
        val loc = when {
            depth == null -> null
            depth > limits.surfaceMaxDepthM -> Location.Underwater
            samples >= limits.sealValidationSamples -> Location.Surface
            else -> null
        }
        var m = old.copy(location = loc, submergedSinceSealCheck = old.submergedSinceSealCheck || loc == Location.Underwater,
            surfaceSamples = samples, depthPacket = if (samples == 1) water?.packetCount else null,
            dismissedVacuumSetup = false)
        val baro = s.barometricPressureKpa?.takeIf(::validBarometricPressure)?.takeIf {
            state.barometricPressureTelemetry?.let { packet -> !old.invalidBaro && packet.isFresh(now) && packet.packetCount >= limits.sealValidationSamples } == true
        }
        if (loc != Location.Surface || baro == null || state.bleConnectionState != BleConnectionState.Ready) {
            m = m.copy(verification = if (m.verification == Verification.Testing) Verification.Interrupted else m.verification,
                inspected = false)
        }
        if (loc == null) return m.copy(banner = Banner.B12, reconnectAmbientPacket = null)
        if (baro == null) {
            if (loc == Location.Underwater && old.last?.observation?.location == Location.Surface)
                m = m.copy(incidents = m.incidents + SealIncident.SuspectedLoss)
            return m.copy(banner = Banner.B11, reconnectAmbientPacket = null)
        }
        val engaged = s.sealState == SealState.LeakMonitoring || s.sealState == SealState.Passed
        val running = s.sealState == SealState.Vacuuming
        val capWait = s.sealState == SealState.WaitingForCoverClosed || s.sealState == SealState.MotorStopping
        if (m.verification == Verification.Testing && engaged && m.holdSinceMs == null)
            m = m.copy(holdSinceMs = now)
        val previous = m.last ?: m.snapshot
        val ambient = s.surfaceAmbientKpa ?: previous?.ambientKpa ?: if (loc == Location.Surface)
            s.waterPressureKpa!! else STANDARD_SURFACE_PRESSURE_KPA
        val magnitude = (ambient - baro).coerceAtLeast(0.0)
        val lost = previous != null && m.snapshot != null && previous.observation.vacuum == Vacuum.Stable &&
            previous.magnitudeKpa - magnitude > limits.rebootMatchToleranceKpa
        // Compare internal pressure at comparable depth. A depth excursion invalidates this
        // reference because hull compression itself can raise the internal reading.
        val reference = old.submergedReference?.takeIf { loc == Location.Underwater &&
            kotlin.math.abs(it.depthM - depth!!) <= limits.surfaceReturnMarginM }
        val declining = reference != null && engaged && s.pendingOutlierKpa == null &&
            reference.magnitudeKpa - magnitude > limits.leakDecaySlowFailKpa
        val packet = state.barometricPressureTelemetry?.packetCount
        val sustainedDecline = declining && old.declinePacket != null &&
            (old.declinePacket != packet || previous?.observation?.vacuum == Vacuum.Leaking)
        m = m.copy(submergedReference = reference, declinePacket = if (declining) old.declinePacket ?: packet else null)
        val vacuum = when {
            s.pendingOutlierKpa != null || running || capWait -> null
            magnitude <= limits.stabilizationToleranceKpa -> Vacuum.None
            s.sealState == SealState.Failed || lost || sustainedDecline -> Vacuum.Leaking
            engaged && magnitude >= limits.establishedVacuumMinKpa -> Vacuum.Stable
            else -> null
        }
        val surfaceLossOnReconnect = old.snapshot?.observation == Observation(Location.Surface, Vacuum.Stable) &&
            loc == Location.Surface && vacuum == Vacuum.None && m.incidents.isEmpty() &&
            !m.confirmedRelease && !m.releaseQuestion && m.verification == Verification.Idle
        if (surfaceLossOnReconnect) {
            if (!guard(state.copy(safety = s.copy(sealMonitor = m)), now))
                return m.copy(banner = Banner.B11, reconnectAmbientPacket = null)
            if (old.reconnectAmbientPacket == null || old.reconnectAmbientPacket == packet)
                return m.copy(reconnectAmbientPacket = packet, banner = Banner.B00)
            m = m.copy(releaseQuestion = true, releaseAnswerYes = false)
        }
        if (m.reconnectAmbientPacket != null) m = m.copy(reconnectAmbientPacket = null)
        if ((loc == Location.Underwater || (vacuum == Vacuum.Stable && !s.vacuumReleasedPrompt)) &&
            (m.releaseQuestion || m.confirmedRelease))
            m = m.copy(releaseQuestion = false, confirmedRelease = false, releaseAnswerYes = false)
        if (vacuum != null) {
            val current = Observation(loc, vacuum)
            val change = previous?.observation != current || m.snapshot != null
            if (change) {
                val candidate = when {
                    previous == null && loc == Location.Underwater -> Banner.B08
                    previous == null -> HousingSealTransitions.connected(current, current)
                    m.snapshot != null -> HousingSealTransitions.reconnect(HousingSealTransitions.disconnected(previous.observation).snapshot, current)
                    else -> HousingSealTransitions.connected(previous.observation, current)
                }
                val lostOffline = if (old.snapshot?.observation?.vacuum == Vacuum.Stable && vacuum != Vacuum.Stable)
                    (state.barometricPressureTelemetry!!.receivedAtEpochMs - old.snapshot.timeMs).coerceAtLeast(0) / 60_000
                    else m.lostWhileOfflineMinutes
                m = m.copy(transition = candidate, snapshot = null, lostWhileOfflineMinutes = lostOffline,
                    acknowledged = when {
                        previous?.observation?.location != loc -> emptySet()
                        vacuum.ordinal > previous.observation.vacuum.ordinal -> m.acknowledged - candidate
                        else -> m.acknowledged
                    })
            }
            var incidents = m.incidents
            if (old.snapshot != null) {
                val prior = old.snapshot.observation
                if (prior.vacuum == Vacuum.Leaking) incidents = incidents +
                    if (prior.location == Location.Surface) SealIncident.SurfaceLeak else SealIncident.UnderwaterLeak
                if (prior.location == Location.Underwater && prior.vacuum == Vacuum.None)
                    incidents = incidents + SealIncident.UnsealedDive
            }
            if (loc == Location.Underwater) {
                if (vacuum == Vacuum.Leaking) incidents = incidents + SealIncident.UnderwaterLeak
                if (vacuum == Vacuum.None || m.transition == Banner.B08) incidents = incidents +
                    if (m.transition == Banner.B07) SealIncident.SuspectedLoss else SealIncident.UnsealedDive
            } else {
                if (m.transition == Banner.B13 || m.transition == Banner.B14) incidents = incidents + SealIncident.SuspectedLoss
                if (vacuum == Vacuum.Leaking || (m.transition == Banner.B05 && !m.confirmedRelease && !m.releaseQuestion))
                    incidents = incidents + SealIncident.SurfaceLeak
            }
            val last = old.last
            val verifiedHold = vacuum == Vacuum.Stable &&
                (s.adoptedHold || s.sealConfidence >= SealConfidence.ManufacturerMinimum)
            val newSurfacePass = verifiedHold && !s.adoptedHold && previous?.verifiedHold != true &&
                loc == Location.Surface && incidents.isEmpty() && m.verification == Verification.Idle
            val reading = if (last?.observation == current && last.magnitudeKpa == magnitude && last.verifiedHold == verifiedHold &&
                last.depthM == depth && last.ambientKpa == ambient) last else SealReading(current, magnitude, depth!!,
                state.barometricPressureTelemetry!!.receivedAtEpochMs, ambient, when {
                    previous == null -> VacuumTrend.Unknown
                    magnitude > previous.magnitudeKpa + limits.stabilizationToleranceKpa -> VacuumTrend.Strengthening
                    magnitude < previous.magnitudeKpa - limits.stabilizationToleranceKpa -> VacuumTrend.Weakening
                    else -> VacuumTrend.Steady
                }, verifiedHold)
            m = m.copy(last = reading, incidents = incidents,
                submergedSinceSealCheck = m.submergedSinceSealCheck && !newSurfacePass,
                submergedReference = if (loc == Location.Underwater && vacuum == Vacuum.Stable) reference ?: reading else reference,
                incidentOrigin = if (incidents.isEmpty()) null else m.incidentOrigin ?: previous ?: reading)
        }
        if (s.warning == VACUUM_NOT_BUILDING_WARNING && loc == Location.Surface)
            m = m.copy(incidents = m.incidents + SealIncident.SurfaceLeak)
        if (m.verification == Verification.Testing) {
            m = when {
                s.sealState == SealState.Failed || s.sealState == SealState.Warning ||
                    s.warning == NO_SUCTION_WARNING || s.warning == VACUUM_NOT_BUILDING_WARNING ->
                    m.copy(verification = Verification.Failed, failures = m.failures + 1, inspected = false)
                !running && !capWait && !engaged -> m.copy(verification = Verification.Interrupted, inspected = false)
                vacuum == Vacuum.Stable && !s.adoptedHold && s.leakMonitoringStartedAtEpochMs != null &&
                    m.holdSinceMs?.let { now - it >= limits.manufacturerMinimumMs } == true &&
                    s.leakMonitoringElapsedMs >= limits.manufacturerMinimumMs && s.sealConfidence >= SealConfidence.ManufacturerMinimum ->
                    m.copy(verification = Verification.Passed, incidents = emptySet(), submergedSinceSealCheck = false,
                        snapshot = null, incidentOrigin = null, lostWhileOfflineMinutes = null,
                        acknowledged = emptySet(), inspected = false, failures = 0, passAtMs = now, transition = Banner.B00)
                else -> m
            }
        }
        if (m.verification == Verification.Passed && now - m.passAtMs >= 10_000L) m = m.copy(verification = Verification.Idle)
        val risk = when {
            loc == Location.Underwater -> when {
                m.transition == Banner.B07 || m.transition == Banner.B10 -> m.transition
                SealIncident.UnsealedDive in m.incidents -> Banner.B08
                SealIncident.UnderwaterLeak in m.incidents || SealIncident.SurfaceLeak in m.incidents -> Banner.B06
                m.incidents.isNotEmpty() -> Banner.B08
                else -> null
            }
            SealIncident.UnsealedDive in m.incidents -> Banner.B09
            SealIncident.UnderwaterLeak in m.incidents || SealIncident.SuspectedLoss in m.incidents ->
                if (vacuum == Vacuum.Leaking) Banner.B13 else Banner.B14
            else -> null
        }
        if ((s.sealState == SealState.Failed || (s.vacuumReleasedPrompt && !m.confirmedRelease && !m.releaseQuestion)) && loc == Location.Surface)
            m = m.copy(incidents = m.incidents + SealIncident.SurfaceLeak)
        val safe = loc == Location.Surface && state.bleConnectionState == BleConnectionState.Ready
        val b = when {
            risk != null && risk !in m.acknowledged && (loc == Location.Underwater ||
                m.verification == Verification.Idle) -> risk
            loc == Location.Underwater -> when {
                vacuum == null -> Banner.B11
                m.verification == Verification.Inspection -> Banner.B15
                else -> Banner.B00
            }
            !safe -> Banner.B11
            m.verification == Verification.Interrupted -> Banner.B19
            m.verification == Verification.Failed -> Banner.B17
            m.verification == Verification.Testing -> Banner.B16
            m.releaseQuestion && m.incidents.isEmpty() -> Banner.ConfirmRelease
            s.vacuumReleasedPrompt && m.incidents.isEmpty() && m.confirmedRelease -> Banner.Release
            s.surfaceVentStartedAtMs != null && engaged && m.incidents.isEmpty() -> Banner.B00
            m.verification == Verification.Passed -> Banner.B18
            running -> Banner.Pumping
            capWait -> if (s.checkDismissed) Banner.B00 else Banner.Reached
            engaged && s.capCloseReminder && !s.checkDismissed && m.incidents.isEmpty() -> Banner.Reached
            m.verification == Verification.Inspection -> when {
                s.pendingOutlierKpa != null -> Banner.B11
                !m.inspected -> Banner.B15
                s.checkDismissed -> return m.copy(banner = Banner.B00, dismissedVacuumSetup = true)
                else -> Banner.B03
            }
            s.warning == NO_SUCTION_WARNING && !s.checkDismissed -> Banner.NoSuction
            s.warning == VACUUM_NOT_BUILDING_WARNING && !s.checkDismissed -> Banner.NotBuilding
            s.sealState == SealState.Failed && loc == Location.Surface -> if (vacuum == Vacuum.None) Banner.B05 else Banner.B04
            vacuum == null -> Banner.B11
            m.incidents.isNotEmpty() -> risk ?: if (vacuum == Vacuum.None) Banner.B05 else Banner.B04
            s.sealState == SealState.Warning -> Banner.Uncertain
            s.verifiedVacuumKpa != null -> Banner.B11
            vacuum == Vacuum.None && s.coverOpen != true -> Banner.Cap
            vacuum == Vacuum.None && s.checkDismissed && !engaged ->
                return m.copy(banner = Banner.B00, dismissedVacuumSetup = true)
            vacuum == Vacuum.None && !s.checkDismissed -> Banner.B03
            engaged -> Banner.Status
            else -> Banner.B00
        }
        return m.copy(banner = if (b in m.acknowledged) Banner.B00 else b)
    }

    private fun interrupted(v: Verification): Verification = when (v) {
        Verification.Testing -> Verification.Interrupted
        Verification.Inspection -> Verification.Idle
        else -> v
    }
}
