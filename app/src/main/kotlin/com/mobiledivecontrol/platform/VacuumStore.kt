package com.mobiledivecontrol.platform

import android.content.Context

/**
 * The one fact worth remembering between sessions: the pressure of a vacuum that completed its
 * full hold.
 *
 * Written only after the hard verify — a reading from an unproven hold is a rumour, and restoring
 * trust at boot on the strength of a rumour is exactly the false certainty the product forbids.
 * Cleared the moment the hold ends for any reason, because a stale record that outlives its
 * vacuum would greet the NEXT vacuum with unearned confidence.
 *
 * Records expire after [MAX_AGE_MS]: a week-old reading matching today's by coincidence of
 * weather is more likely than a housing sitting sealed and untouched for a week.
 */
class VacuumStore(context: Context) {

    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The persisted hold, or null if none was recorded, it expired, or it was cleared. */
    fun read(): PersistedVacuum? {
        if (!preferences.contains(KEY_KPA)) return null
        val recordedAt = preferences.getLong(KEY_RECORDED_AT, 0L)
        if (System.currentTimeMillis() - recordedAt > MAX_AGE_MS) {
            clear()
            return null
        }
        return PersistedVacuum(
            kpa = Double.fromBits(preferences.getLong(KEY_KPA, 0L)),
            confidenceOrdinal = preferences.getInt(KEY_CONFIDENCE, DEFAULT_CONFIDENCE_ORDINAL),
            startedAtEpochMs = preferences.getLong(KEY_STARTED_AT, 0L).takeIf { it > 0L },
            recordedAtEpochMs = recordedAt.takeIf { it > 0L },
        )
    }

    fun record(kpa: Double, confidenceOrdinal: Int, startedAtEpochMs: Long?) {
        preferences.edit()
            .putLong(KEY_KPA, kpa.toRawBits())
            .putInt(KEY_CONFIDENCE, confidenceOrdinal)
            .putLong(KEY_STARTED_AT, startedAtEpochMs ?: 0L)
            .putLong(KEY_RECORDED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear() {
        preferences.edit()
            .remove(KEY_KPA)
            .remove(KEY_CONFIDENCE)
            .remove(KEY_STARTED_AT)
            .remove(KEY_RECORDED_AT)
            .apply()
    }

    /** [startedAtEpochMs] null on records written before the start time rode along. */
    data class PersistedVacuum(
        val kpa: Double,
        val confidenceOrdinal: Int,
        val startedAtEpochMs: Long? = null,
        val recordedAtEpochMs: Long? = null,
    )

    private companion object {
        const val PREFS_NAME = "vacuum_store"
        const val KEY_KPA = "verified_kpa_bits"
        const val KEY_CONFIDENCE = "verified_confidence_ordinal"
        const val KEY_RECORDED_AT = "recorded_at_ms"
        const val KEY_STARTED_AT = "hold_started_at_ms"
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** Records written before the tier rode along were only ever written at the hard verify. */
        const val DEFAULT_CONFIDENCE_ORDINAL = 2
    }
}
