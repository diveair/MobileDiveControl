package com.mobiledivecontrol.branding

import androidx.annotation.DrawableRes
import com.mobiledivecontrol.R

/**
 * Every NAUTICASE brand knob in one place.
 *
 * - [LOGO] contains the supplied emblem and wordmark together.
 * - Timing: [WELCOME_SCREEN_DURATION_MS] is how long the welcome screen stays up each time the
 *   app opens, before anything else (including Android's permission prompts) appears.
 */
object NauticaseBranding {
    /** Welcome screen on-screen time on every app launch, in milliseconds. */
    const val WELCOME_SCREEN_DURATION_MS: Long = 4_500L

    /** Static density-independent welcome artwork. */
    @DrawableRes
    val LOGO: Int = R.drawable.nauticase_logo

    const val WELCOME_LINE: String = "Welcome to"
    const val APP_NAME: String = "NAUTICASE"
    const val WELCOME_PROMPT: String = "INSERT PHONE AND SEAL HOUSING"
}

