package com.madeinhk.cantonesevoicekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    @Test fun publicBuildRejectsAllTrainingDestinationsIncludingRestoredKeys() {
        for (key in listOf(DebugAudio, DebugHandwriting, PromptedHandwriting)) {
            assertFalse(destinationAvailable(key, internalTools = false))
            assertTrue(destinationAvailable(key, internalTools = true))
        }
    }

    @Test fun setupAndSettingsRemainAvailableInPublicBuilds() {
        assertTrue(destinationAvailable(Main, internalTools = false))
        assertTrue(destinationAvailable(Settings, internalTools = false))
    }

    @Test fun freshInstallAndMidOnboardingOpenWizard() {
        assertEquals(
            Main,
            resolveInitialDestination(
                onboardingCompleted = false,
                keyboardEnabled = false,
                keyboardSelected = false,
                isSettingsRequested = false,
            )
        )
        assertEquals(
            Main,
            resolveInitialDestination(
                onboardingCompleted = false,
                keyboardEnabled = true,
                keyboardSelected = false,
                isSettingsRequested = false,
            )
        )
    }

    @Test fun completedOnboardingStaysOnHomeSettingsEvenWhenSwitchingKeyboards() {
        assertEquals(
            Settings,
            resolveInitialDestination(
                onboardingCompleted = true,
                keyboardEnabled = true,
                keyboardSelected = false,
                isSettingsRequested = false,
            )
        )
        assertEquals(
            Settings,
            resolveInitialDestination(
                onboardingCompleted = true,
                keyboardEnabled = false,
                keyboardSelected = false,
                isSettingsRequested = false,
            )
        )
    }

    @Test fun activeImeOrExplicitSettingsIntentOpensHomeSettings() {
        assertEquals(
            Settings,
            resolveInitialDestination(
                onboardingCompleted = false,
                keyboardEnabled = true,
                keyboardSelected = true,
                isSettingsRequested = false,
            )
        )
        assertEquals(
            Settings,
            resolveInitialDestination(
                onboardingCompleted = false,
                keyboardEnabled = false,
                keyboardSelected = false,
                isSettingsRequested = true,
            )
        )
    }
}
