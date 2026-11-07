package com.madeinhk.cantonesevoicekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class QuickLearningEditorPolicyTest {
    @Test fun excludesEveryPasswordVariationAndNoLearningFlag() {
        val inputs = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        inputs.forEach { type ->
            val info = EditorInfo().apply { inputType = type }
            assertFalse(ImeEditor({ null }, { info }).isPersonalizedLearningAllowed())
        }
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        assertFalse(ImeEditor({ null }, { info }).isPersonalizedLearningAllowed())
        info.imeOptions = 0
        assertTrue(ImeEditor({ null }, { info }).isPersonalizedLearningAllowed())
    }
}
