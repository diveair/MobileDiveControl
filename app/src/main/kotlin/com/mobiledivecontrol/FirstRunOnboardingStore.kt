package com.mobiledivecontrol

import android.content.Context
import java.io.File

/**
 * Records completion of the one-time permissions/housing walkthrough.
 *
 * The marker deliberately lives in [Context.getNoBackupFilesDir]. Camera preferences may be
 * restored after reinstall, but an Android backup must never make a genuinely fresh install skip
 * its safety-critical housing introduction.
 */
class FirstRunOnboardingStore internal constructor(
    private val marker: File,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, COMPLETION_MARKER),
    )

    fun isComplete(): Boolean = marker.isFile

    fun markComplete() {
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText("complete", Charsets.UTF_8)
        }
    }

    private companion object {
        const val COMPLETION_MARKER = "first-run-onboarding-v1-complete"
    }
}

/** First install always wins; after it, a verified vacuum is what permits the fast camera path. */
internal fun shouldShowOnboarding(
    firstRunComplete: Boolean,
    hasPersistedVacuum: Boolean,
): Boolean = !firstRunComplete || !hasPersistedVacuum
