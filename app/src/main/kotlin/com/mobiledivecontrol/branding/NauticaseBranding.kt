package com.mobiledivecontrol.branding

import androidx.annotation.DrawableRes
import com.mobiledivecontrol.R

/**
 * Every NAUTICASE brand knob in one place.
 *
 * - Logo: replace `res/drawable/nauticase_logo.xml` (same file name), or point [LOGO] at a new
 *   drawable. The welcome screen never references the artwork any other way.
 * - Timing: [WELCOME_SCREEN_DURATION_MS] is how long the welcome screen stays up each time the
 *   app opens, before anything else (including Android's permission prompts) appears.
 */
object NauticaseBranding {
    /** Welcome screen on-screen time on every app launch, in milliseconds. */
    const val WELCOME_SCREEN_DURATION_MS: Long = 3_000L

    /** The logo drawn on the welcome screen. Placeholder artwork for now. */
    @DrawableRes
    val LOGO: Int = R.drawable.nauticase_logo

    const val WELCOME_LINE: String = "Welcome to"
    const val APP_NAME: String = "NAUTICASE"
    const val WELCOME_PROMPT: String = "INSERT PHONE & SEAL HOUSING"
}

