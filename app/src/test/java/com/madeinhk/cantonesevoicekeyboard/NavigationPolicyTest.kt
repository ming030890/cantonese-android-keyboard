package com.madeinhk.cantonesevoicekeyboard

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
}
