package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Test

class ReleaseDecoderGateScenarioTest {
    @Test fun savedDebugPreviewCannotDisableReleaseAutocorrectOrUndo() {
        // An existing preference may survive an install/restore. Release must
        // continue to select the shipping decoder even when that value is true.
        val preview = KeyboardSettings.previewDecoderEnabled(savedPreview=true,debugBuild=false)
        assertFalse(preview)
        for (mode in listOf(KeyboardInputMode.AlphaNum,KeyboardInputMode.Quick)) {
            KeyboardScenario(initialMode=mode,englishEngine=ScenarioEnglishEngine(),jointDecoder=preview).use { s ->
                s.start();s.type("teh")
                assertEquals("teh",s.editor.composing)
                assertTrue(s.snapshot().englishCandidates.any { it.text=="the" })
                assertEquals(KeyboardToolbarContent.Candidates,s.snapshot().toolbar())
                s.press(KeyboardKeyKind.Space)
                assertEquals("the ",s.editor.text);assertEquals("",s.editor.composing)
                s.press(KeyboardKeyKind.Delete)
                assertEquals("",s.editor.text);assertEquals("teh",s.editor.composing)
            }
        }
        assertTrue(KeyboardSettings.previewDecoderEnabled(savedPreview=true,debugBuild=true))
        assertFalse(KeyboardSettings.previewDecoderEnabled(savedPreview=false,debugBuild=true))
    }
}
