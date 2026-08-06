package com.mobiledivecontrol.platform.ble

import com.mobiledivecontrol.core.HousingCommand
import com.mobiledivecontrol.core.SafetyCommand

/**
 * Build-time kill switches for housing commands that move physical hardware.
 *
 * The vacuum motor and the solenoid valve change the seal state of a housing the diver is about to
 * submerge. They are enabled now that `SafetyStateMachine` owns them end to end — it confirms the
 * suction cover is open before starting, arms a motor timeout, and emits an explicit stop and a
 * valve close on every exit path including cancellation and failure.
 *
 * That guarantee only holds for effects the state machine produced. The raw `SafetyCommand`
 * passthroughs in `ControlReducer.reduceSafety` reach the same characteristics without any of those
 * preconditions, so they stay blocked separately — see [RAW_SAFETY_PASSTHROUGH_ENABLED].
 *
 * The check lives here rather than in the caller so the view model and the transport enforce the
 * same rule; there is no path that reaches the radio without passing through it.
 */
object HousingFeatureFlags {

    /**
     * Gate for vacuum motor and solenoid valve writes.
     *
     * Enabled because the seal-check workflow now owns preconditions, a timeout and an explicit
     * stop. `HousingLink.send` still refuses a motor start that has no evidence the cover was ever
     * opened, so enabling this flag cannot on its own run the pump against a sealed shell.
     */
    const val HIGH_RISK_COMMANDS_ENABLED: Boolean = true

    /**
     * Gate for hardware commands issued as bare [SafetyCommand]s rather than by the state machine.
     *
     * `SafetyCommand.OpenSolenoid` and `CloseSolenoid` are direct writes to the valve with no
     * workflow behind them: nothing has checked the cover, nothing will close the valve if the
     * caller goes away, and the state machine's idea of the valve position silently stops matching
     * the hardware. Diagnostics might one day want them; the dive path never does.
     */
    const val RAW_SAFETY_PASSTHROUGH_ENABLED: Boolean = false

    /**
     * Returns why [command] must not be sent, or `null` when it is permitted.
     *
     * The message is user-facing: it is surfaced as a warning rather than swallowed, because a
     * command that silently does nothing is exactly the failure mode the product forbids.
     */
    fun rejectionReason(command: HousingCommand): String? = when (command) {
        is HousingCommand.SetVacuumMotor ->
            VACUUM_DISABLED.takeUnless { HIGH_RISK_COMMANDS_ENABLED }
        is HousingCommand.SetSolenoidValve ->
            SOLENOID_DISABLED.takeUnless { HIGH_RISK_COMMANDS_ENABLED }
        else -> null
    }

    /**
     * Returns why [command] must not be dispatched, or `null` when it is permitted.
     *
     * Applied where safety commands enter the core, which is the only place the origin of a
     * hardware write is still visible. Once the reducer has turned one into a
     * `PlatformEffect.ExecuteHousing` it is indistinguishable from one the state machine produced.
     */
    fun rejectionReason(command: SafetyCommand): String? = when (command) {
        SafetyCommand.OpenSolenoid, SafetyCommand.CloseSolenoid ->
            RAW_SOLENOID_BLOCKED.takeUnless { RAW_SAFETY_PASSTHROUGH_ENABLED }
        // Belt and braces: the reducer already refuses to emit an effect for this one.
        SafetyCommand.StartVacuumMotor ->
            RAW_MOTOR_BLOCKED.takeUnless { RAW_SAFETY_PASSTHROUGH_ENABLED }
        else -> null
    }

    /** Convenience inverse of [rejectionReason] for call sites that only need the decision. */
    fun isPermitted(command: HousingCommand): Boolean = rejectionReason(command) == null

    private const val VACUUM_DISABLED =
        "Vacuum motor control is disabled in this build. Command ignored."

    private const val SOLENOID_DISABLED =
        "Solenoid valve control is disabled in this build. Command ignored."

    private const val RAW_SOLENOID_BLOCKED =
        "Solenoid valve moves only from the seal-check workflow. Command ignored."

    private const val RAW_MOTOR_BLOCKED =
        "Vacuum motor starts only from the seal-check workflow. Command ignored."
}
