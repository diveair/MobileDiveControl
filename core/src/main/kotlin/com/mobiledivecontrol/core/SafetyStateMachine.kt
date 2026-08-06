package com.mobiledivecontrol.core

/**
 * Tuning for the vacuum seal check.
 *
 * The barometric characteristic (`0x1627`) reports in 1 Pa units but resolves to roughly
 * 5 kPa steps, which drives two of these numbers:
 *
 * - [vacuumTargetDeltaKpa] is 20 kPa, matching the only published hard number in the
 *   industry (the Vivid Leak Sentinel alarms at ~5.9 inHg ≈ 20 kPa). The previous 5 kPa
 *   target sat exactly on one quantisation step, so pass/fail turned on a single LSB.
 * - [stabilizationToleranceKpa] must exceed half the sensor resolution or quantisation
 *   noise alone reads as a leak.
 *
 * [motorTimeoutMs] is the hardware backstop: the pump may never run longer than this
 * without reaching target, whatever else goes wrong.
 */
data class SafetyThresholds(
    val vacuumTargetDeltaKpa: Double = 20.0,
    val stabilizationToleranceKpa: Double = 1.0,
    val requiredStabilizationSamples: Int = 3,
    val motorTimeoutMs: Long = 120_000L,
    val leakMonitoringDurationMs: Long = 300_000L,
    /** 3 min — enough evidence to call the seal provisionally good. */
    val provisionalMs: Long = 180_000L,
    /** 5 min — the housing manufacturer's stated minimum. */
    val manufacturerMinimumMs: Long = 300_000L,
    /** 10 min — common practice across vacuum systems. */
    val recommendedMs: Long = 600_000L,
    /** 30 min — Weefine's requirement for their own smartphone housing. */
    val conservativeMs: Long = 1_800_000L,
    /** Drop below a captured surface baseline that counts as an already-established vacuum. */
    val establishedVacuumMinKpa: Double = 4.0,
    /**
     * Same, referenced against the dry water-pressure sensor instead: correct at any altitude,
     * but a different physical sensor, so it earns a kPa of cross-sensor slack.
     */
    val establishedVacuumCrossSensorMinKpa: Double = 5.0,
    /**
     * Same, when no reference exists at all and sea level is assumed. Doubled, because at
     * altitude the assumption itself is ~10 kPa wrong and must not read as a phantom vacuum.
     */
    val establishedVacuumFallbackMinKpa: Double = 8.0,
    /** Progress smaller than this within the stall window counts as no progress at all. */
    val stallMinProgressKpa: Double = 0.3,
    /**
     * How long the pump may run with real suction achieved but no further progress before it is
     * stopped. Longer than [stallCapOnQuickWindowMs] because mid-pump sensor quantisation
     * plateaus are real; four seconds of truly flat readings is already conclusive.
     */
    val stallWindowMs: Long = 4_000L,
    /**
     * The fast path for the capped-port signature: a working pump moves the needle almost
     * immediately, so ZERO total suction for a full second of samples can only be the blue cap.
     * Any real progress (>= [stallMinProgressKpa]) resets this clock, which is what makes a
     * window this short safe. Kept apart from [stallWindowMs] because the mid-pump case (real
     * suction that stopped climbing) genuinely needs the longer window to rule out sensor
     * quantisation plateaus.
     */
    val stallCapOnQuickWindowMs: Long = 1_000L,
    /**
     * Total suction below this when the quick window expires means the pump never moved the
     * needle AT ALL — the signature of a blue cap still screwed on (no port to pull through).
     * Judged on TOTAL achieved suction, never on per-sample step sizes, so a slow-but-steady
     * pull can never be mistaken for a capped port whatever the sensor's sample rate.
     */
    val capOnNoSuctionMaxKpa: Double = 0.5,
    /**
     * Achieved suction at which the pump has proven itself and the rate watch arms. Between
     * [capOnNoSuctionMaxKpa] and this, a stall falls to the flat window and reads as a leak.
     */
    val stallCapOnMaxProgressKpa: Double = 2.0,
    /**
     * The rate watch, which is what actually catches a leak fast: once the pump has proven
     * itself (>= [stallCapOnMaxProgressKpa] pulled), it must gain at least
     * [leakMinGainPerWindowKpa] every [leakRateWindowMs] or the pull is being balanced by a
     * leak. The floor is deliberately far below any healthy pump's rate (0.2 kPa/s) so a slow
     * pump never trips it, while a balanced leak — which earns ~zero — always does.
     */
    val leakRateWindowMs: Long = 2_000L,
    val leakMinGainPerWindowKpa: Double = 0.4,
    /**
     * Rate watch stands down this close to target: pumps naturally slow at depth, and killing a
     * pull at 19 of 20 kPa over a rate technicality would be absurd. The 4 s flat window still
     * covers this last band.
     */
    val leakRateGuardBandKpa: Double = 2.0,
    /**
     * The decay watch, which catches what the spread check is structurally blind to: a SLOW
     * leak. The spread check sees ~a second of samples, so a hair in the O-ring bleeding
     * 0.2 kPa/min never trips it — field-found at -21.2 falling to -15.4 with no alarm.
     *
     * Decay is measured against the hold's FIRST accepted reading, deliberately not a ratcheted
     * minimum: diving cools the shell and deepens the vacuum, and a ratchet that chased the
     * cold-depth reading down would then read the normal post-dive warm-up as a leak. And the
     * check stands down entirely while the housing is submerged — hull compression at depth
     * legitimately raises internal pressure, and an actual ingress event underwater is fast and
     * belongs to the spread and vent checks.
     *
     * Two limits: tight while the seal is still inside its 5-minute certification window, when
     * the shell has had no time to warm; looser after, because a phone running inside a sealed
     * shell genuinely warms the air a few kPa and a proven seal must not fail red over sunshine.
     */
    val leakDecayFailKpa: Double = 2.0,
    val leakDecaySlowFailKpa: Double = 5.0,
    /**
     * A between-samples jump bigger than this is quarantined until a second reading agrees.
     * Physically nothing but a vent moves the shell this fast, and a vent sustains — so the
     * only thing this delays is a real event, by one sample.
     */
    val glitchJumpKpa: Double = 6.0,
    /**
     * How closely a boot-time reading must match the persisted hard-verified one to count as the
     * same unbroken hold. Loose enough for an overnight temperature swing (ideal-gas: ~0.3%/K on
     * an evacuated shell), tight enough that a real partial loss cannot pass as a match.
     */
    val rebootMatchToleranceKpa: Double = 2.5,
    /**
     * Deepest the phone may sit and still count as "at the surface" for the released-vacuum
     * banner. Generous enough for swell and sensor noise on a boat deck; far too shallow for
     * anyone actually diving.
     */
    val surfaceMaxDepthM: Double = 0.5,
) {
    /**
     * Confidence is a function of hold time alone. Leak detection is independent of it and
     * can drop the seal to [SealState.Failed] at any tier — time never gates *detection*,
     * only how much credit a still-holding seal has earned.
     */
    fun confidenceFor(elapsedMs: Long): SealConfidence = when {
        elapsedMs >= conservativeMs -> SealConfidence.Conservative
        elapsedMs >= recommendedMs -> SealConfidence.Recommended
        elapsedMs >= manufacturerMinimumMs -> SealConfidence.ManufacturerMinimum
        elapsedMs >= provisionalMs -> SealConfidence.Provisional
        else -> SealConfidence.Monitoring
    }
}

/**
 * The exact warning the machine writes when it stops a pump that is pulling nothing. The UI keys
 * its dedicated NO VACUUM SUCTION banner on this value, so it is a contract, not just a string.
 */
const val NO_SUCTION_WARNING = "Pump stopped — no suction detected."

/**
 * Same contract for the other stall shape: real suction achieved, then flat — air re-entering
 * as fast as the pump removes it. Keys the VACUUM NOT BUILDING banner.
 */
const val VACUUM_NOT_BUILDING_WARNING = "Pump stopped — vacuum not building; air is re-entering the housing."

/**
 * Prefix of the decay watch's failure message. The UI keys the slow-leak banner on it — the one
 * failure where the housing's own green light is probably still on (its firmware alarm is far
 * less sensitive), so the banner must say so before the diver has to choose whom to believe.
 */
const val SLOW_LEAK_WARNING_PREFIX = "Vacuum decayed"

sealed interface SafetySignal {
    data object StartVacuumCheckRequested : SafetySignal
    data object CancelVacuumCheckRequested : SafetySignal

    /** Silences the pre-dive prompt without disabling the feature. */
    data object DismissSealCheckRequested : SafetySignal

    /** Ends leak monitoring early, keeping whatever confidence tier was reached. */
    data object SkipToResultRequested : SafetySignal
    data object ResetSealStateRequested : SafetySignal
    data class CoverStateChanged(val open: Boolean) : SafetySignal
    data class BarometricPressureSample(val kpa: Double, val timestampMs: Long) : SafetySignal
}

data class SafetyMachineResult(
    val state: SafetyState,
    val effects: List<PlatformEffect> = emptyList(),
    val note: String? = null,
)

/**
 * Implements the manufacturer's 7-step vacuum workflow:
 *
 * 1. Confirm cover is open
 * 2. Open solenoid valve
 * 3. Turn on motor, start pumping
 * 4. When target pressure reached, stop motor
 * 5. Confirm cover is closed (gate: leak detection is inaccurate with open cover)
 * 6. Close solenoid valve (saves power, allows easy cover opening later)
 * 7. Monitor for leakage via pressure, reporting escalating confidence over time
 *
 * This class is the **sole producer of motor-on effects** in the codebase. Nothing else may
 * emit [HousingCommand.SetVacuumMotor] with `enabled = true`; a pump running outside this
 * workflow has no timeout, no cover confirmation, and no stop condition.
 *
 * Motor timeout: auto-stop after [SafetyThresholds.motorTimeoutMs] if target not reached.
 */
class SafetyStateMachine(
    private val thresholds: SafetyThresholds = SafetyThresholds(),
) {
    fun apply(state: SafetyState, signal: SafetySignal): SafetyMachineResult = when (signal) {
        SafetySignal.StartVacuumCheckRequested -> startVacuumCheck(state)
        SafetySignal.CancelVacuumCheckRequested -> cancelVacuumCheck(state)
        SafetySignal.DismissSealCheckRequested -> dismissSealCheck(state)
        SafetySignal.SkipToResultRequested -> skipToResult(state)
        SafetySignal.ResetSealStateRequested -> resetSealState(state)
        is SafetySignal.CoverStateChanged -> handleCoverChange(state, signal.open)
        is SafetySignal.BarometricPressureSample -> handlePressureSample(state, signal.kpa, signal.timestampMs)
    }

    // --- Step 1: Confirm cover is open, then open solenoid + start motor ---

    private fun startVacuumCheck(state: SafetyState): SafetyMachineResult {
        // A live released-prompt is its own cover-open proof: the shell just equalised to
        // ambient, and air can only have come in through the open port. The cover byte may
        // well be stale at this moment — it usually is — and must not block the re-pump the
        // banner explicitly offered.
        if (state.coverOpen != true && !state.vacuumReleasedPrompt) {
            return SafetyMachineResult(
                state = state.copy(
                    sealState = SealState.Warning,
                    warning = "Cover must be open before vacuum check.",
                ),
                note = "Cover must be open before vacuum check.",
            )
        }

        // The cover is open and the pump has not started, so the shell is vented and the
        // internal sensor is reading true atmosphere. Last chance to capture the depth
        // reference before the vacuum makes the live reading useless for that purpose.
        val surfaceAmbient = state.surfaceAmbientKpa ?: state.barometricPressureKpa

        // Steps 2+3: open solenoid, start motor
        return SafetyMachineResult(
            state = state.copy(
                sealState = SealState.Vacuuming,
                vacuumBestKpa = null,
                vacuumLastProgressAtMs = null,
                vacuumRateWindowStartMs = null,
                vacuumRateWindowStartKpa = null,
                capCloseReminder = false,
                adoptedHold = false,
                vacuumReleasedPrompt = false,
                restartFailAgoMinutes = null,
                sealConfidence = SealConfidence.Monitoring,
                baselinePressureKpa = state.barometricPressureKpa,
                surfaceAmbientKpa = surfaceAmbient,
                stabilizationSamples = emptyList(),
                motorStartedAtEpochMs = System.currentTimeMillis(),
                leakMonitoringStartedAtEpochMs = null,
                leakMonitoringElapsedMs = 0L,
                warning = null,
            ),
            effects = listOf(
                PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = true)),
                PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = true)),
            ),
        )
    }

    // --- Pressure sample handling: drives the state machine forward ---

    private fun handlePressureSample(state: SafetyState, kpa: Double, timestampMs: Long): SafetyMachineResult {
        val updated = state.copy(
            barometricPressureKpa = kpa,
            surfaceAmbientKpa = if (capturesSurfaceAmbient(state, kpa)) kpa else state.surfaceAmbientKpa,
        )

        return when {
            // Step 4: Motor is running — check if target pressure reached or motor timed out
            updated.sealState == SealState.Vacuuming -> handleVacuumingPressure(updated, kpa, timestampMs)

            // Step 5 fallback: the hold must be able to start on pressure evidence alone,
            // because the cover byte this transition officially waits for is the least
            // reliable signal the housing produces — it can arrive stale or never.
            updated.sealState == SealState.MotorStopping ||
                updated.sealState == SealState.WaitingForCoverClosed ->
                handleCapWaitPressure(updated, kpa, timestampMs)

            // Step 7: leak monitoring. It continues after the seal reaches Passed so that a
            // late leak still drops the state — confidence must never freeze the verdict.
            updated.leakMonitoringStartedAtEpochMs != null &&
                (updated.sealState == SealState.LeakMonitoring || updated.sealState == SealState.Passed) ->
                handleLeakMonitoringPressure(updated, kpa, timestampMs)

            // Other states: an unexplained deep vacuum means the housing was evacuated before the
            // app was looking — adopt it rather than asking the diver to pull it again. Either
            // way this sample DECIDES any primed boot record: adoption consumes it, and a reading
            // with no vacuum refutes it — leaving it would suppress the pump prompt forever on
            // the strength of a record the pressure just disproved.
            else -> detectEstablishedVacuum(updated, kpa, timestampMs)
                ?: run {
                    // No vacuum in this reading. Against a primed boot record that is a seal
                    // failure — but only on two agreeing readings, because one glitched packet
                    // must never turn last session's earned trust into a red banner.
                    if (updated.verifiedVacuumKpa != null &&
                        updated.sealState in ESTABLISHED_VACUUM_ADOPTABLE_STATES
                    ) {
                        val pending = updated.pendingAdoptionKpa
                        if (pending == null ||
                            kotlin.math.abs(kpa - pending) > thresholds.stabilizationToleranceKpa
                        ) {
                            SafetyMachineResult(state = updated.copy(pendingAdoptionKpa = kpa))
                        } else {
                            restartSealFailed(updated, timestampMs)
                        }
                    } else {
                        SafetyMachineResult(state = updated.copy(pendingAdoptionKpa = null))
                    }
                }
        }
    }

    /**
     * Recognises a vacuum the machine did not pull itself.
     *
     * The housing holds its vacuum across app restarts and phone reboots — its own green LED keeps
     * saying so — but this machine used to know only about pump-downs it had personally driven, so
     * a relaunched app would sit at -20 kPa showing "press OK to start the vacuum pump". Prompting
     * a diver to re-evacuate an already-sealed housing is worse than wrong: pumping against a
     * sealed shell burns the ~100-cycle pump budget for nothing.
     *
     * Detection is pressure-based: the shell reading far below surface ambient can only mean an
     * established vacuum, so the machine adopts it and goes straight to leak monitoring — the hold
     * timer restarts from this moment, which is honest, because this app has no evidence for how
     * long the seal has already held.
     *
     * The threshold doubles when no surface baseline has been captured this session (fresh launch,
     * cap already closed): the fallback reference is standard sea-level atmosphere, and a diver at
     * altitude would otherwise read low ambient as a phantom vacuum. States actively mid-workflow
     * never reach here, and an open cover vents the shell to ambient, so neither can false-trigger.
     */
    private fun detectEstablishedVacuum(
        state: SafetyState,
        kpa: Double,
        timestampMs: Long,
    ): SafetyMachineResult? {
        if (state.sealState !in ESTABLISHED_VACUUM_ADOPTABLE_STATES) return null

        // Reference preference: a captured baseline, then the dry water-pressure sensor (true
        // local atmosphere, right even at altitude, but a different physical sensor so it gets a
        // little slack), then assumed sea level with the strictest threshold of the three.
        val ambient = state.surfaceAmbientKpa
        val water = state.waterPressureKpa
        val (reference, threshold) = when {
            ambient != null -> ambient to thresholds.establishedVacuumMinKpa
            water != null -> water to thresholds.establishedVacuumCrossSensorMinKpa
            else -> STANDARD_ATMOSPHERE_KPA to thresholds.establishedVacuumFallbackMinKpa
        }
        val drop = reference - kpa
        if (drop < threshold) return null

        // Stability gate: adopt only a vacuum that is standing still. A genuinely held vacuum
        // reads flat sample to sample; a shell part-way through a manual vent reads exactly like
        // a vacuum for a moment while MOVING several kPa per second — and adopting that transient
        // flashed "VACUUM REACHED" in the middle of the diver releasing it. One agreeing
        // follow-up reading is all the flatness proof needed, at the sensor's rate ~half a second.
        val pending = state.pendingAdoptionKpa
        if (pending == null || kotlin.math.abs(kpa - pending) > thresholds.stabilizationToleranceKpa) {
            return SafetyMachineResult(state = state.copy(pendingAdoptionKpa = kpa))
        }

        // A persisted hard-verified reading that still matches means this is the SAME hold the
        // last session already proved for five clean minutes — and it survived a power cycle on
        // top. Backdating the clock restores the trust it earned instead of demanding the diver
        // re-earn it; the cap reminder is skipped because a shell that held across a reboot
        // self-evidently has its cap on. A mismatch gets no benefit of the doubt.
        val verified = state.verifiedVacuumKpa != null &&
            kotlin.math.abs(kpa - state.verifiedVacuumKpa) <= thresholds.rebootMatchToleranceKpa
        if (verified) {
            // The floor: the tier the hold had provably earned when it was persisted. Nothing
            // below Provisional is ever written.
            val persistedTier = maxOf(
                state.verifiedVacuumConfidence ?: SealConfidence.ManufacturerMinimum,
                SealConfidence.Provisional,
            )
            val tierFloorMs = when (persistedTier) {
                SealConfidence.Conservative -> thresholds.conservativeMs
                SealConfidence.Recommended -> thresholds.recommendedMs
                SealConfidence.ManufacturerMinimum -> thresholds.manufacturerMinimumMs
                else -> thresholds.provisionalMs
            }
            // The truth, when available: the hold's ORIGINAL start survived with the record, so
            // the clock resumes where it really is — a 25-minute hold restarts as a 25-minute
            // hold, not as its last tier's floor. The persisted tier stays as a lower bound in
            // case the phone's clock moved backwards between sessions.
            val persistedStart = state.verifiedVacuumStartedAtEpochMs
            val trueElapsedMs = if (persistedStart != null && persistedStart in 1 until timestampMs) {
                timestampMs - persistedStart
            } else {
                null
            }
            val elapsedMs = maxOf(trueElapsedMs ?: 0L, tierFloorMs)
            val tier = thresholds.confidenceFor(elapsedMs)
            return SafetyMachineResult(
                state = state.copy(
                    sealState = SealState.Passed,
                    sealConfidence = tier,
                    baselinePressureKpa = kpa,
                    stabilizationSamples = listOf(kpa),
                    leakMonitoringStartedAtEpochMs = timestampMs - elapsedMs,
                    leakMonitoringElapsedMs = elapsedMs,
                    capCloseReminder = false,
                    adoptedHold = true,
                    vacuumReleasedPrompt = false,
                    pendingAdoptionKpa = null,
                    verifiedVacuumKpa = null,
                    verifiedVacuumConfidence = null,
                    verifiedVacuumStartedAtEpochMs = null,
                    warning = null,
                ),
                note = "Verified vacuum held across restart (-%.1f kPa). Trust restored at %s, %d min held.".format(drop, tier.name, elapsedMs / 60_000),
            )
        }

        // A record was primed but this stable reading does not match it: the seal failed while
        // the app was away. Silent re-adoption here would hide a real degradation event — the
        // diver left a proven seal and came back to a weaker one, and that is a verdict, not a
        // fresh start.
        if (state.verifiedVacuumKpa != null) {
            return restartSealFailed(state, timestampMs)
        }

        val message = "Existing vacuum detected (-%.1f kPa). Monitoring seal.".format(drop)
        return SafetyMachineResult(
            state = state.copy(
                sealState = SealState.LeakMonitoring,
                sealConfidence = SealConfidence.Monitoring,
                baselinePressureKpa = kpa,
                stabilizationSamples = listOf(kpa),
                leakMonitoringStartedAtEpochMs = timestampMs,
                leakMonitoringElapsedMs = 0L,
                capCloseReminder = true,
                adoptedHold = true,
                vacuumReleasedPrompt = false,
                pendingAdoptionKpa = null,
                verifiedVacuumKpa = null,
                verifiedVacuumConfidence = null,
                verifiedVacuumStartedAtEpochMs = null,
                warning = null,
            ),
            note = message,
        )
    }

    /**
     * True when the live barometric reading can be trusted as surface atmosphere.
     *
     * The suction cover being open vents the shell — but only while no part of the vacuum
     * workflow is in flight. During pumping the motor is actively evacuating the shell
     * through that same open port, and between pump-stop and cover-close the shell is
     * already ~20 kPa down. Capturing then would poison the depth reference with the very
     * vacuum this baseline exists to cancel out.
     *
     * The cover byte alone is not proof, though: a fresh app start against a housing holding
     * -20 kPa can arrive with a stale or wrong "open" reading, and capturing that sample would
     * poison the baseline in a way that also blinds established-vacuum detection — the vacuum
     * would be compared against itself and measure zero. The water-pressure sensor is the
     * cross-check: dry at the surface it reads true local atmosphere (correct at altitude,
     * unlike any constant), so a barometric reading far below it means the shell is NOT vented,
     * whatever the cover byte claims.
     */
    private fun capturesSurfaceAmbient(state: SafetyState, kpa: Double): Boolean {
        if (state.coverOpen != true || state.sealState in VACUUM_IN_FLIGHT_STATES) return false
        val water = state.waterPressureKpa ?: return true
        return water - kpa <= AMBIENT_CAPTURE_DISAGREEMENT_KPA
    }

    private fun handleVacuumingPressure(state: SafetyState, kpa: Double, timestampMs: Long): SafetyMachineResult {
        val baseline = state.baselinePressureKpa ?: kpa
        val pressureDrop = baseline - kpa

        // Motor timeout check
        val motorStarted = state.motorStartedAtEpochMs
        if (motorStarted != null && (timestampMs - motorStarted) >= thresholds.motorTimeoutMs) {
            val message = "Motor timeout: target pressure not reached within ${thresholds.motorTimeoutMs / 1000}s."
            return SafetyMachineResult(
                state = state.copy(
                    sealState = SealState.Failed,
                    sealConfidence = SealConfidence.Monitoring,
                    motorStartedAtEpochMs = null,
                    warning = message,
                ),
                effects = safeHardwareState() + PlatformEffect.EmitAlert(
                    priority = AlertPriority.Critical,
                    message = "Motor timeout: vacuum target not reached.",
                ),
                note = "Motor timeout: vacuum target not reached.",
            )
        }

        // Step 4: Target pressure reached — stop motor, move to MotorStopping
        if (pressureDrop >= thresholds.vacuumTargetDeltaKpa) {
            return SafetyMachineResult(
                state = state.copy(
                    sealState = SealState.MotorStopping,
                    baselinePressureKpa = kpa,
                    motorStartedAtEpochMs = null,
                    warning = null,
                ),
                effects = listOf(
                    PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = false)),
                ),
                note = "Target pressure reached. Motor stopped. Close the cover to continue.",
            )
        }

        // Stall watch: the pump is running but the pressure is not moving. The diagnosis comes
        // from the SHAPE of the stall, ranked by likelihood.
        val best = state.vacuumBestKpa?.let { minOf(it, kpa) } ?: kpa
        val improved = state.vacuumBestKpa == null ||
            kpa <= state.vacuumBestKpa - thresholds.stallMinProgressKpa
        val lastProgressAt = if (improved) timestampMs else state.vacuumLastProgressAtMs ?: timestampMs
        val stalledForMs = timestampMs - lastProgressAt
        val achievedKpa = baseline - best

        // Capped port, the fast path: a working pump moves the needle within a couple of
        // seconds, so ZERO suction this far in can only mean it has no port to pull through.
        // The motor is STOPPED, not left trying — this pump has a finite service life and
        // grinding it against a sealed shell buys nothing. Back to the pre-pump state with the
        // diagnosis attached; Menu/OK retries once the cap is actually off.
        if (achievedKpa < thresholds.capOnNoSuctionMaxKpa &&
            stalledForMs >= thresholds.stallCapOnQuickWindowMs
        ) {
            return SafetyMachineResult(
                state = state.copy(
                    sealState = SealState.CoverOpen,
                    motorStartedAtEpochMs = null,
                    vacuumBestKpa = null,
                    vacuumLastProgressAtMs = null,
                    baselinePressureKpa = null,
                    warning = NO_SUCTION_WARNING,
                ),
                effects = safeHardwareState(),
                note = "Pump stopped: no suction after ${thresholds.stallCapOnQuickWindowMs / 1000}s — blue cap suspected.",
            )
        }

        // Mid-pump stall: real suction that stopped climbing means the pump works and the cap
        // is off, but air is re-entering as fast as it leaves — an open housing or an unseated
        // O-ring. Stopped as decisively as the capped-port case: a pump balancing a leak is
        // spending the same finite service life for nothing, and pumping harder cannot fix a
        // seal. Menu/OK retries once the housing is actually closed.
        //
        // Two detectors, fastest first. The RATE WATCH runs while the pump is proven but not
        // yet near target: it demands a minimum gain every couple of seconds, so a balanced
        // leak — earning ~zero — is caught in one window (~2 s), including the slow-creep case
        // that pure flatness can never see. The FLAT WINDOW is the backstop for the near-target
        // band where the rate watch stands down.
        val rateWatchActive = achievedKpa >= thresholds.stallCapOnMaxProgressKpa &&
            achievedKpa < thresholds.vacuumTargetDeltaKpa - thresholds.leakRateGuardBandKpa
        var rateWindowStartMs = state.vacuumRateWindowStartMs
        var rateWindowStartKpa = state.vacuumRateWindowStartKpa
        if (!rateWatchActive) {
            rateWindowStartMs = null
            rateWindowStartKpa = null
        } else if (rateWindowStartMs == null || rateWindowStartKpa == null) {
            rateWindowStartMs = timestampMs
            rateWindowStartKpa = kpa
        } else if (timestampMs - rateWindowStartMs >= thresholds.leakRateWindowMs) {
            val gain = rateWindowStartKpa - kpa
            if (gain < thresholds.leakMinGainPerWindowKpa) {
                return SafetyMachineResult(
                    state = state.copy(
                        sealState = SealState.CoverOpen,
                        motorStartedAtEpochMs = null,
                        vacuumBestKpa = null,
                        vacuumLastProgressAtMs = null,
                        vacuumRateWindowStartMs = null,
                        vacuumRateWindowStartKpa = null,
                        baselinePressureKpa = null,
                        warning = VACUUM_NOT_BUILDING_WARNING,
                    ),
                    effects = safeHardwareState(),
                    note = "Pump stopped: reached -%.1f kPa but gained only %.1f kPa in %d ms — housing seal suspected.".format(achievedKpa, gain, timestampMs - rateWindowStartMs),
                )
            }
            rateWindowStartMs = timestampMs
            rateWindowStartKpa = kpa
        }

        if (stalledForMs >= thresholds.stallWindowMs) {
            return SafetyMachineResult(
                state = state.copy(
                    sealState = SealState.CoverOpen,
                    motorStartedAtEpochMs = null,
                    vacuumBestKpa = null,
                    vacuumLastProgressAtMs = null,
                    vacuumRateWindowStartMs = null,
                    vacuumRateWindowStartKpa = null,
                    baselinePressureKpa = null,
                    warning = VACUUM_NOT_BUILDING_WARNING,
                ),
                effects = safeHardwareState(),
                note = "Pump stopped: reached -%.1f kPa then stalled — housing seal suspected.".format(achievedKpa),
            )
        }

        // Still vacuuming — update baseline
        return SafetyMachineResult(
            state = state.copy(
                baselinePressureKpa = baseline,
                vacuumBestKpa = best,
                vacuumLastProgressAtMs = lastProgressAt,
                vacuumRateWindowStartMs = rateWindowStartMs,
                vacuumRateWindowStartKpa = rateWindowStartKpa,
                warning = null,
            ),
        )
    }

    // --- Step 5: Wait for cover to close ---

    /**
     * Pressure-based escape from the cap wait. The workflow's official exit is a cover-closed
     * notification, but that byte is unreliable on real hardware — it can report stale state or
     * simply never fire — and a hold that cannot start without it leaves the diver staring at a
     * pumped-down shell with no timer forever. The shell itself is the better witness: the pump
     * just reached target, the motor is off, and if the pressure then sits still for a few
     * samples the vacuum is real and holding, cap byte or no cap byte. Monitoring starts with
     * [SafetyState.capCloseReminder] raised, so the diver is still told to close the cap; the
     * timer simply refuses to be hostage to the housing's flakiest sensor.
     *
     * The mirror case is also handled: the shell returning to ambient while the diver is
     * standing at the open port is someone changing their mind, not a leak — nothing has been
     * promised yet, so nothing has failed. Two consecutive ambient readings step quietly back
     * to Unknown, same confirmation discipline as the vent quarantine.
     */
    private fun handleCapWaitPressure(state: SafetyState, kpa: Double, timestampMs: Long): SafetyMachineResult {
        val reference = state.surfaceAmbientKpa
            ?: state.waterPressureKpa
            ?: STANDARD_ATMOSPHERE_KPA
        val deficit = reference - kpa
        val samples = (state.stabilizationSamples + kpa).takeLast(thresholds.requiredStabilizationSamples)

        // Deliberate abandon: this reading and the previous one both at ambient.
        val nearAmbientKpa = thresholds.stabilizationToleranceKpa * 2
        if (deficit <= nearAmbientKpa) {
            val previousAlsoAmbient = state.stabilizationSamples.lastOrNull()
                ?.let { reference - it <= nearAmbientKpa } == true
            if (previousAlsoAmbient) {
                return SafetyMachineResult(
                    state = state.copy(
                        sealState = SealState.Unknown,
                        sealConfidence = SealConfidence.Monitoring,
                        baselinePressureKpa = null,
                        stabilizationSamples = emptyList(),
                        checkDismissed = false,
                        warning = null,
                    ),
                    note = "Shell vented during cap wait. Seal state reset.",
                )
            }
            return SafetyMachineResult(state = state.copy(stabilizationSamples = samples))
        }

        // The hold starts only on a vacuum that is unmistakably the one just pumped: most of the
        // target still present (post-stop rebound allowed for) and flat across the stabilization
        // window. Anything in between keeps waiting — the cover byte may yet arrive.
        val spread = (samples.max() - samples.min())
        val stable = samples.size >= thresholds.requiredStabilizationSamples &&
            spread <= thresholds.stabilizationToleranceKpa &&
            deficit >= thresholds.vacuumTargetDeltaKpa * CAP_WAIT_TARGET_FRACTION
        if (!stable) {
            return SafetyMachineResult(state = state.copy(stabilizationSamples = samples))
        }

        return SafetyMachineResult(
            state = state.copy(
                sealState = SealState.LeakMonitoring,
                sealConfidence = SealConfidence.Monitoring,
                stabilizationSamples = emptyList(),
                leakMonitoringStartedAtEpochMs = timestampMs,
                leakMonitoringElapsedMs = 0L,
                capCloseReminder = true,
                adoptedHold = false,
                vacuumReleasedPrompt = false,
                warning = null,
            ),
            effects = listOf(
                // Step 6 still happens — the solenoid closes exactly as it would have on the
                // cover confirmation, and closing it is the safe direction.
                PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = false)),
            ),
            note = "Vacuum stable after pump-down. Leak monitoring started on pressure evidence; cover byte not required.",
        )
    }

    private fun handleCoverChange(state: SafetyState, open: Boolean): SafetyMachineResult {
        return when {
            // Cover opened. Only a real closed -> open transition means anything; the cover
            // is legitimately open for the whole pumping phase, and a repeat notification
            // after a resubscribe must not tear down an in-flight workflow.
            open && state.coverOpen == true -> SafetyMachineResult(state = state)

            // A vented shell voids every prior verdict, including Passed: the seal that was
            // measured no longer exists. Reporting Passed with the cap off would be exactly
            // the false certainty the product must never show. Failed is dropped too, so
            // that popping the cap to fix an o-ring re-arms the start prompt.
            open -> SafetyMachineResult(
                state = state.copy(
                    coverOpen = true,
                    sealState = SealState.CoverOpen,
                    sealConfidence = SealConfidence.Monitoring,
                    stabilizationSamples = emptyList(),
                    leakMonitoringStartedAtEpochMs = null,
                    leakMonitoringElapsedMs = 0L,
                    checkDismissed = false,
                    warning = null,
                ),
            )

            // Cover closed during MotorStopping or WaitingForCoverClosed:
            // Step 5 confirmed → Step 6: close solenoid → Step 7: start leak monitoring
            state.sealState == SealState.MotorStopping ||
                state.sealState == SealState.WaitingForCoverClosed -> {
                SafetyMachineResult(
                    state = state.copy(
                        coverOpen = false,
                        sealState = SealState.LeakMonitoring,
                        sealConfidence = SealConfidence.Monitoring,
                        stabilizationSamples = emptyList(),
                        leakMonitoringStartedAtEpochMs = System.currentTimeMillis(),
                        leakMonitoringElapsedMs = 0L,
                        warning = null,
                    ),
                    effects = listOf(
                        // Step 6: close solenoid after cover is confirmed closed
                        PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = false)),
                    ),
                    note = "Cover closed. Solenoid closed. Leak monitoring started.",
                )
            }

            // Cover closed in other states
            else -> {
                val nextSealState = when (state.sealState) {
                    SealState.Passed -> SealState.Passed
                    SealState.Failed -> SealState.Failed
                    SealState.CoverOpen -> SealState.ReadyToVacuum
                    else -> state.sealState
                }
                SafetyMachineResult(
                    state = state.copy(
                        coverOpen = false,
                        sealState = nextSealState,
                        warning = null,
                    ),
                )
            }
        }
    }

    // --- Step 7: Monitor for leaks, continuously ---

    /**
     * Evaluates the sample spread on **every** sample once enough samples exist, rather than
     * waiting for the hold duration to elapse. The old behaviour hid a gross leak for five
     * minutes; now a leak shows up as fast as the sensor can produce
     * [SafetyThresholds.requiredStabilizationSamples] readings — a couple of seconds at the
     * 2–5 Hz barometric rate.
     *
     * Elapsed time gates only how high [SealConfidence] may climb.
     */
    private fun handleLeakMonitoringPressure(
        state: SafetyState,
        kpa: Double,
        timestampMs: Long,
    ): SafetyMachineResult {
        val monitoringStarted = state.leakMonitoringStartedAtEpochMs ?: timestampMs
        val elapsedMs = (timestampMs - monitoringStarted).coerceAtLeast(0L)

        // Quarantine gate. A reading that jumps implausibly from the last accepted one does not
        // enter the evidence window until a second reading agrees with it. One glitched packet
        // nine minutes into a perfect hold — field-found — must be able to erase NOTHING: not
        // through the vent check, and not by detonating the spread check from inside the window.
        // A real event loses only ~half a second to confirmation, because real pressure stays
        // where it moved.
        val lastAccepted = state.stabilizationSamples.lastOrNull()
        val jumped = lastAccepted != null &&
            kotlin.math.abs(kpa - lastAccepted) > thresholds.glitchJumpKpa
        if (jumped) {
            val pending = state.pendingOutlierKpa
            if (pending == null || kotlin.math.abs(kpa - pending) > thresholds.glitchJumpKpa) {
                return SafetyMachineResult(
                    state = state.copy(
                        pendingOutlierKpa = kpa,
                        leakMonitoringStartedAtEpochMs = monitoringStarted,
                        leakMonitoringElapsedMs = elapsedMs,
                    ),
                )
            }
            // Two agreeing readings in a new pressure regime: this is real. Ambient means the
            // cap came off; anything else is the seal letting go mid-range.
            val cleared = state.copy(
                pendingOutlierKpa = null,
                stabilizationSamples = listOf(kpa),
                leakMonitoringStartedAtEpochMs = monitoringStarted,
                leakMonitoringElapsedMs = elapsedMs,
            )
            val ambientRef = state.surfaceAmbientKpa ?: state.waterPressureKpa
            val vented = ambientRef != null &&
                kpa >= ambientRef - thresholds.stabilizationToleranceKpa &&
                pending >= ambientRef - thresholds.stabilizationToleranceKpa
            return sealLost(
                cleared,
                elapsedMs,
                vent = vented,
                if (vented) {
                    "Pressure back to ambient after ${elapsedMs / 1000}s: cover open or seal lost."
                } else {
                    "Pressure unstable after ${elapsedMs / 1000}s: leak detected."
                },
            )
        }

        val samples = (state.stabilizationSamples + kpa).takeLast(thresholds.requiredStabilizationSamples)
        // The decay reference: the hold's first accepted reading, fixed for the whole hold.
        // Anchored here rather than at pump-end because the shell gives a little pressure back
        // between motor-off and the hold starting, and that rebound is not decay.
        val holdReferenceKpa = if (state.stabilizationSamples.isEmpty()) {
            kpa
        } else {
            state.baselinePressureKpa ?: kpa
        }
        val monitoring = state.copy(
            pendingOutlierKpa = null,
            stabilizationSamples = samples,
            baselinePressureKpa = holdReferenceKpa,
            leakMonitoringStartedAtEpochMs = monitoringStarted,
            leakMonitoringElapsedMs = elapsedMs,
        )

        // Creeping approach to ambient — too slow to trip the jump gate, but two consecutive
        // readings effectively at atmosphere mean the vacuum is gone however it happened.
        val ambient = state.surfaceAmbientKpa ?: state.waterPressureKpa
        val atAmbient = ambient != null &&
            samples.size >= 2 &&
            samples.takeLast(2).all { it >= ambient - thresholds.stabilizationToleranceKpa }
        if (atAmbient) {
            return sealLost(
                monitoring,
                elapsedMs,
                vent = true,
                "Pressure back to ambient after ${elapsedMs / 1000}s: cover open or seal lost.",
            )
        }

        // Slow leak: the spread across the accepted window exceeds tolerance. Every sample in
        // the window survived the quarantine gate, so this can no longer be one bad packet.
        if (samples.size >= thresholds.requiredStabilizationSamples) {
            val spread = (samples.maxOrNull() ?: kpa) - (samples.minOrNull() ?: kpa)
            if (spread > thresholds.stabilizationToleranceKpa) {
                return sealLost(
                    monitoring,
                    elapsedMs,
                    vent = false,
                    "Pressure unstable after ${elapsedMs / 1000}s: leak detected.",
                )
            }
        }

        // Decay watch: total loss since the hold began. This is the slow-leak detector — the
        // only one of the three that accumulates across the whole hold instead of looking at a
        // one-second window. Surface only (hull flex at depth is not a leak), and it reports
        // through sealFailed directly, NEVER the deliberate path: a hand on the cap reads as a
        // fast vent or drift and is caught above; pressure that took minutes to bleed away is
        // physics, not a diver, whatever the clock says.
        val decayKpa = kpa - holdReferenceKpa
        val decayLimit = if (elapsedMs < thresholds.manufacturerMinimumMs) {
            thresholds.leakDecayFailKpa
        } else {
            thresholds.leakDecaySlowFailKpa
        }
        val depthM = state.waterPressureKpa?.let { water ->
            (water - (state.surfaceAmbientKpa ?: STANDARD_ATMOSPHERE_KPA)).coerceAtLeast(0.0) / 9.81
        }
        val submerged = depthM != null && depthM > thresholds.surfaceMaxDepthM
        if (!submerged && decayKpa >= decayLimit) {
            return sealFailed(
                monitoring,
                "$SLOW_LEAK_WARNING_PREFIX %.1f kPa since the hold began: slow leak detected.".format(decayKpa),
            )
        }

        // Holding. Passed is reached at the Provisional tier and confidence keeps climbing
        // from there without another state transition, so the UI improves on its own.
        val confidence = thresholds.confidenceFor(elapsedMs)
        val nextSealState = if (confidence == SealConfidence.Monitoring) {
            SealState.LeakMonitoring
        } else {
            SealState.Passed
        }
        val note = if (confidence != state.sealConfidence && nextSealState == SealState.Passed) {
            "Seal holding after ${elapsedMs / 1000}s (${confidence.name})."
        } else {
            null
        }

        return SafetyMachineResult(
            state = monitoring.copy(
                sealState = nextSealState,
                sealConfidence = confidence,
                warning = null,
            ),
            note = note,
        )
    }

    // --- Dismiss, skip, cancel and reset ---

    private fun dismissSealCheck(state: SafetyState): SafetyMachineResult {
        return SafetyMachineResult(
            state = state.copy(checkDismissed = true),
        )
    }

    private fun skipToResult(state: SafetyState): SafetyMachineResult {
        val monitoring = state.leakMonitoringStartedAtEpochMs != null &&
            (state.sealState == SealState.LeakMonitoring || state.sealState == SealState.Passed)
        if (!monitoring) {
            return SafetyMachineResult(state = state)
        }

        val elapsedSeconds = state.leakMonitoringElapsedMs / 1000
        return if (state.sealConfidence == SealConfidence.Monitoring) {
            // Below the Provisional tier there is no evidence to pass on. Say so instead of
            // promoting an unfinished check.
            val message = "Seal check ended after ${elapsedSeconds}s: not enough evidence to call it sealed."
            SafetyMachineResult(
                state = state.copy(
                    sealState = SealState.Warning,
                    leakMonitoringStartedAtEpochMs = null,
                    warning = message,
                ),
                effects = safeHardwareState(),
                note = message,
            )
        } else {
            val message = "Seal check ended early at ${state.sealConfidence.name} (${elapsedSeconds}s)."
            SafetyMachineResult(
                state = state.copy(
                    sealState = SealState.Passed,
                    leakMonitoringStartedAtEpochMs = null,
                    warning = null,
                ),
                effects = safeHardwareState(),
                note = message,
            )
        }
    }

    private fun cancelVacuumCheck(state: SafetyState): SafetyMachineResult {
        return SafetyMachineResult(
            state = state.copy(
                sealState = SealState.Unknown,
                sealConfidence = SealConfidence.Monitoring,
                baselinePressureKpa = null,
                stabilizationSamples = emptyList(),
                motorStartedAtEpochMs = null,
                leakMonitoringStartedAtEpochMs = null,
                leakMonitoringElapsedMs = 0L,
                warning = "Vacuum check cancelled.",
            ),
            effects = safeHardwareState(),
            note = "Vacuum check cancelled.",
        )
    }

    private fun resetSealState(state: SafetyState): SafetyMachineResult {
        return SafetyMachineResult(
            state = state.copy(
                sealState = SealState.Unknown,
                sealConfidence = SealConfidence.Monitoring,
                baselinePressureKpa = null,
                stabilizationSamples = emptyList(),
                motorStartedAtEpochMs = null,
                leakMonitoringStartedAtEpochMs = null,
                leakMonitoringElapsedMs = 0L,
                checkDismissed = false,
                adoptedHold = false,
                vacuumReleasedPrompt = false,
                restartFailAgoMinutes = null,
                warning = null,
            ),
            // Reset means "forget what we knew", and the only safe thing to assume about
            // hardware we no longer have state for is that it must be driven to rest.
            effects = safeHardwareState(),
        )
    }

    /**
     * Routes a lost vacuum by *when* it was lost, because the same pressure event means two
     * different things on two sides of the five-minute hard verify:
     *
     *  - Inside the hard verify the seal was still on trial, so depressurisation goes to Failed
     *    — the UI words it as a definite leak or as "vacuum inactive", depending on whether the
     *    diver ever claimed the cap was back on.
     *  - Past the hard verify the seal had already proven itself. A shell that loses pressure
     *    after that was opened by its owner — the cap unscrewed, the housing cracked for a
     *    battery swap — and greeting a deliberate act with a leak alarm teaches the diver that
     *    the alarm cries wolf. The machine steps quietly back to the start: seal Unknown,
     *    prompts re-armed, no alert, and at the surface the VACUUM RELEASED banner.
     */
    private fun sealLost(
        state: SafetyState,
        elapsedMs: Long,
        vent: Boolean,
        message: String,
    ): SafetyMachineResult {
        // The quiet released path opens only past the 5-minute hard verify: by then the seal has
        // proven itself, a leak is unlikely, and a diver adjusting their phone between dives is
        // the story that fits the evidence. Any depressurisation before that — clean vent or
        // gradual fall — goes to Failed, where the UI splits the message on whether the diver
        // ever claimed the cap was on. The one exception is a full vent of an ADOPTED hold
        // ([SafetyState.adoptedHold]): the housing proved that seal before this app's clock
        // existed, so the clock has no standing to call its release early. A hold this app
        // pumped itself never gets that pass, whatever cap banner happened to be raised.
        val deliberate = elapsedMs >= thresholds.manufacturerMinimumMs ||
            (vent && state.adoptedHold)
        if (!deliberate) {
            return sealFailed(state, message)
        }
        // At the surface a deliberate release means the diver just pulled the cap — the next
        // screen must not ask them to remove it. Underwater (or at unknown depth from a live
        // water reading) the deliberate classification stands but the released banner does not:
        // "you may open the housing" is not a sentence this app will ever say below the surface.
        val depthM = state.waterPressureKpa?.let { water ->
            val surface = state.surfaceAmbientKpa ?: STANDARD_ATMOSPHERE_KPA
            (water - surface).coerceAtLeast(0.0) / 9.81
        }
        val atSurface = depthM == null || depthM <= thresholds.surfaceMaxDepthM
        return SafetyMachineResult(
            state = state.copy(
                sealState = SealState.Unknown,
                sealConfidence = SealConfidence.Monitoring,
                baselinePressureKpa = null,
                stabilizationSamples = emptyList(),
                leakMonitoringStartedAtEpochMs = null,
                leakMonitoringElapsedMs = 0L,
                checkDismissed = false,
                vacuumReleasedPrompt = atSurface,
                warning = null,
            ),
            effects = safeHardwareState(),
            note = "Vacuum released after ${elapsedMs / 1000}s — past hard verify, treating as deliberate.",
        )
    }

    /**
     * The verdict for a boot record disproved by today's readings: the vacuum that was verified
     * good when the app last saw it is now lost or degraded. The banner leads with how long ago
     * that trust was earned, because "failed" without "since when" is not actionable.
     */
    private fun restartSealFailed(state: SafetyState, timestampMs: Long): SafetyMachineResult {
        val lastGoodMs = state.verifiedVacuumRecordedAtEpochMs
            ?: state.verifiedVacuumStartedAtEpochMs
        val agoMinutes = lastGoodMs
            ?.takeIf { it in 1 until timestampMs }
            ?.let { (timestampMs - it) / 60_000L }
        val ago = agoMinutes?.let { formatAgo(it) } ?: "an unknown time"
        val cleared = state.copy(
            verifiedVacuumKpa = null,
            verifiedVacuumConfidence = null,
            verifiedVacuumStartedAtEpochMs = null,
            verifiedVacuumRecordedAtEpochMs = null,
            pendingAdoptionKpa = null,
            restartFailAgoMinutes = agoMinutes ?: -1L,
        )
        return sealFailed(
            cleared,
            "Vacuum lost while the app was closed. Last verified $ago ago.",
        )
    }

    private fun formatAgo(minutes: Long): String = if (minutes < 60) {
        "$minutes min"
    } else {
        "${minutes / 60}h ${minutes % 60}m"
    }

    private fun sealFailed(state: SafetyState, message: String): SafetyMachineResult {
        return SafetyMachineResult(
            state = state.copy(
                sealState = SealState.Failed,
                // A failed check has earned no confidence; never let the UI pair a tier
                // label with a failure.
                sealConfidence = SealConfidence.Monitoring,
                leakMonitoringStartedAtEpochMs = null,
                warning = message,
            ),
            effects = safeHardwareState() + PlatformEffect.EmitAlert(
                priority = AlertPriority.Critical,
                message = "Seal failure: $message",
            ),
            note = message,
        )
    }

    /**
     * The two writes that put the housing back in its rest position. Every failure, cancel
     * and abort path emits both — a motor left running or a solenoid left open is the only
     * failure mode here with real hardware consequences.
     */
    private fun safeHardwareState(): List<PlatformEffect> = listOf(
        PlatformEffect.ExecuteHousing(HousingCommand.SetVacuumMotor(enabled = false)),
        PlatformEffect.ExecuteHousing(HousingCommand.SetSolenoidValve(open = false)),
    )

    private companion object {
        /** States in which the shell is either being evacuated or already holding a vacuum. */
        val VACUUM_IN_FLIGHT_STATES = setOf(
            SealState.Vacuuming,
            SealState.MotorStopping,
            SealState.WaitingForCoverClosed,
            SealState.LeakMonitoring,
        )

        /**
         * States allowed to adopt a pre-existing vacuum. Everything mid-workflow is excluded —
         * those branches own their own pressure logic — as are the terminal verdicts: a Failed
         * seal stays failed until the diver opens the cap, whatever the pressure says.
         */
        val ESTABLISHED_VACUUM_ADOPTABLE_STATES = setOf(
            SealState.Unknown,
            SealState.CoverOpen,
            SealState.ReadyToVacuum,
        )

        /** Same sea-level fallback the depth gauge uses when no baseline was ever captured. */
        const val STANDARD_ATMOSPHERE_KPA = 101.325

        /**
         * How much of the pump target must survive the motor stopping for the cap-wait fallback
         * to start the hold. Post-stop rebound of a few kPa is normal; a shell that gives back
         * more than a quarter of a 20 kPa pull within seconds is not one to certify quietly.
         */
        const val CAP_WAIT_TARGET_FRACTION = 0.75

        /**
         * How far the barometric reading may sit below the dry water-pressure reading and still
         * be believed as "vented shell". Beyond this, the shell holds vacuum regardless of what
         * the cover byte says, and the sample must not become the surface baseline.
         */
        const val AMBIENT_CAPTURE_DISAGREEMENT_KPA = 5.0
    }
}
