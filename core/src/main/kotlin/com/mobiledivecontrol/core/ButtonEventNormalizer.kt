package com.mobiledivecontrol.core

import java.time.Duration
import java.time.Instant

data class AcceptedButtonEvent(
    val event: HousingButtonEvent,
    val repeatCount: Int,
)

class ButtonEventNormalizer(
    private val duplicateWindow: Duration = Duration.ofMillis(30),
    private val repeatContinuationWindow: Duration = Duration.ofMillis(90),
) {
    private var lastEvent: HousingButtonEvent? = null
    private var lastAcceptedAt: Instant? = null
    private var repeatCount: Int = 0

    fun accept(event: HousingButtonEvent, at: Instant): AcceptedButtonEvent? {
        val previousEvent = lastEvent
        val previousAcceptedAt = lastAcceptedAt
        val elapsedSincePrevious = previousAcceptedAt?.takeIf { !at.isBefore(it) }?.let { previous ->
            Duration.between(previous, at)
        }

        if (
            previousEvent != null &&
            previousEvent == event &&
            elapsedSincePrevious != null &&
            elapsedSincePrevious < duplicateWindow
        ) {
            return null
        }

        val continuesRepeatSequence =
            previousEvent == event &&
                elapsedSincePrevious != null &&
                elapsedSincePrevious <= repeatContinuationWindow

        repeatCount = if (continuesRepeatSequence) {
            repeatCount + 1
        } else {
            0
        }

        lastEvent = event
        lastAcceptedAt = at
        return AcceptedButtonEvent(event = event, repeatCount = repeatCount)
    }

    fun reset() {
        lastEvent = null
        lastAcceptedAt = null
        repeatCount = 0
    }
}
