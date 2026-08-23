package com.mobiledivecontrol.platform

import android.content.Context
import com.mobiledivecontrol.core.HeadingMath

/** The target survives camera-mode changes, activity recreation and an interrupted dive. */
class HeadingStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): Double? = preferences.getFloat(KEY_TARGET_DEGREES, Float.NaN)
        .takeUnless { it.isNaN() }
        ?.toDouble()
        ?.let(HeadingMath::normalize)

    fun write(degrees: Double) {
        preferences.edit()
            .putFloat(KEY_TARGET_DEGREES, HeadingMath.normalize(degrees).toFloat())
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "tracked_heading"
        const val KEY_TARGET_DEGREES = "target_degrees"
    }
}
