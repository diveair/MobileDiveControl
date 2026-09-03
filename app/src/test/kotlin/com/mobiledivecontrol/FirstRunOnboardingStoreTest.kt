package com.mobiledivecontrol

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FirstRunOnboardingStoreTest {
    @Test
    fun `fresh install requires onboarding and completion survives a new store instance`() {
        val directory = Files.createTempDirectory("dive-onboarding").toFile()
        try {
            val marker = File(directory, "completion-marker")
            val firstLaunch = FirstRunOnboardingStore(marker)
            assertFalse(firstLaunch.isComplete())

            firstLaunch.markComplete()

            assertTrue(FirstRunOnboardingStore(marker).isComplete())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `first launch shows onboarding even with a persisted vacuum`() {
        assertTrue(
            shouldShowOnboarding(
                firstRunComplete = false,
                hasPersistedVacuum = true,
            ),
        )
    }

    @Test
    fun `later launch is vacuum dependent`() {
        assertFalse(
            shouldShowOnboarding(
                firstRunComplete = true,
                hasPersistedVacuum = true,
            ),
        )
        assertTrue(
            shouldShowOnboarding(
                firstRunComplete = true,
                hasPersistedVacuum = false,
            ),
        )
    }
}
