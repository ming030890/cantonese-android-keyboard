package com.madeinhk.cantonesevoicekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.madeinhk.cantonesevoicekeyboard.english.*
import com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnglishAutocorrectScenarioTest {
    @Test fun boundaryCorrectionAndUndoOnBothLayouts() {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            for (boundary in listOf(" ", ".", "。", "\n")) {
                KeyboardScenario(initialMode = mode, englishEngine = ScenarioEnglishEngine()).use { s ->
                    s.start(); s.type("teh")
                    assertEquals("teh", s.editor.composing)
                    assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                    when (boundary) {
                        " " -> s.press(KeyboardKeyKind.Space)
                        "\n" -> s.press(KeyboardKeyKind.Enter)
                        else -> s.type(boundary)
                    }
                    assertEquals("$mode $boundary", "the$boundary", s.editor.text)
                    assertEquals("", s.editor.composing)
                    s.press(KeyboardKeyKind.Delete)
                    assertEquals("", s.editor.text)
                    assertEquals("teh", s.editor.composing)
                    s.press(KeyboardKeyKind.Space)
                    assertEquals("teh ", s.editor.text)
                }
            }
        }
    }

    @Test fun literalAndManualCandidatesPreserveCase() {
        for (typed in listOf("teh", "Teh", "TEH")) {
            KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, englishEngine = ScenarioEnglishEngine()).use { s ->
                s.start(); s.type(typed)
                val expected = caseWord("the", typed)
                assertEquals(listOf(typed, expected), s.snapshot().englishCandidates.map { it.text })
                s.choose(typed); assertEquals(typed, s.editor.text)
            }
            KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, englishEngine = ScenarioEnglishEngine()).use { s ->
                s.start(); s.type(typed); s.choose(caseWord("the", typed))
                assertEquals(caseWord("the", typed), s.editor.text)
            }
        }
    }

    @Test fun quickChineseOrderAndSelectionRemainIntact() {
        KeyboardScenario(englishEngine = ScenarioEnglishEngine()).use { s ->
            s.engine.lookup = { _, _, _, _ -> listOf(QuickCandidate("你", 0), QuickCandidate("係", 1)) }
            s.start(); s.type("teh")
            assertEquals(listOf("你", "係", "teh", "the"), s.snapshot().quickCandidates.map { it.text })
            s.choose("你"); assertEquals("你", s.editor.text); assertEquals("", s.editor.composing)
            s.press(KeyboardKeyKind.Space); assertEquals("你 ", s.editor.text)
        }
    }

    @Test fun quickKeepsLongEnglishButConvertsNonEnglishPhrases() {
        for ((typed, expectedPrefix) in listOf("keyboard" to "", "zzzzzzz" to "中文中文中文")) {
            KeyboardScenario(englishEngine = ScenarioEnglishEngine()).use { s ->
                s.engine.lookup = { _, code, _, _ -> if (code.length == 6) listOf(QuickCandidate("中文中文中文", 0)) else emptyList() }
                s.start(); s.type(typed)
                assertEquals(expectedPrefix, s.editor.text)
                assertEquals(if (expectedPrefix.isEmpty()) typed else "z", s.editor.composing)
            }
        }
    }

    @Test fun pasteVoiceHideAndLayoutSwitchFinishLiterally() {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            for (action in listOf("paste", "voice", "hide", "layout", "emoji")) {
                KeyboardScenario(initialMode = mode, englishEngine = ScenarioEnglishEngine()).use { s ->
                    s.start(); s.type("teh")
                    when (action) {
                        "paste" -> s.press(KeyboardKeyKind.Clipboard)
                        "voice" -> s.press(KeyboardKeyKind.Record)
                        "hide" -> { s.hide(); s.hide() }
                        "layout" -> s.mode(KeyboardInputMode.Handwriting)
                        else -> s.controller.onVisiblePanelChanged(null)
                    }
                    assertEquals("$mode $action", if (action == "paste") "tehcopied text" else "teh", s.editor.text)
                    assertEquals("", s.editor.composing)
                }
            }
        }
    }

    @Test fun numberPanelPreservesWordForPunctuationButDigitsStayLiteral() {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            KeyboardScenario(initialMode = mode, englishEngine = ScenarioEnglishEngine()).use { s ->
                s.start(); s.type("teh"); s.controller.onVisiblePanelChanged(KeyboardInputMode.Number)
                s.type("."); assertEquals("the.", s.editor.text)
            }
            KeyboardScenario(initialMode = mode, englishEngine = ScenarioEnglishEngine()).use { s ->
                s.start(); s.type("teh"); s.controller.onVisiblePanelChanged(KeyboardInputMode.Number)
                s.controller.onVisiblePanelChanged(mode); s.controller.selectKeyboardInputMode(mode)
                assertEquals("teh", s.editor.composing)
                s.press(KeyboardKeyKind.Space); assertEquals("the ", s.editor.text)
            }
            KeyboardScenario(initialMode = mode, englishEngine = ScenarioEnglishEngine()).use { s ->
                s.start(); s.type("teh"); s.controller.onVisiblePanelChanged(KeyboardInputMode.Number)
                s.type("1"); s.press(KeyboardKeyKind.Space); assertEquals("teh1 ", s.editor.text)
            }
        }
    }

    @Test fun mixedCaseIdentifiersAndApostrophesStayLiteral() {
        for (text in listOf("tEh", "teh_thing", "user@teh", "teh1", "don't")) {
            KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, englishEngine = ScenarioEnglishEngine()).use { s ->
                s.start(); s.type(text); s.press(KeyboardKeyKind.Space); assertEquals("$text ", s.editor.text)
            }
        }
        for (prefix in listOf("www.", "me@example.", "https://")) {
            KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, initialText = prefix,
                englishEngine = ScenarioEnglishEngine()).use { s ->
                s.start(); s.type("teh"); s.press(KeyboardKeyKind.Space)
                assertEquals("${prefix}teh ", s.editor.text)
            }
        }
    }

    @Test fun cantoneseEnglishMixingNeedsNoArtificialSeparator() {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            val engine = ScenarioEnglishEngine()
            KeyboardScenario(initialMode = mode, initialText = "我想", englishEngine = engine).use { s ->
                s.start(); s.type("teh")
                assertEquals("我想", engine.requests.last().left)
                s.press(KeyboardKeyKind.Space); assertEquals("我想the ", s.editor.text)
            }
        }
    }

    @Test fun editorPolicyAndIndependentPreferences() {
        for (type in listOf(InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)) {
            assertFalse(englishAllowed(type))
        }
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            KeyboardScenario(initialMode = mode, englishEngine = ScenarioEnglishEngine()).use { s ->
                s.autocorrectLayouts = if (mode == KeyboardInputMode.AlphaNum) setOf(KeyboardSettings.LAYOUT_QUICK) else setOf(KeyboardSettings.LAYOUT_ENGLISH)
                s.start(); s.type("teh"); s.press(KeyboardKeyKind.Space); assertEquals("teh ", s.editor.text)
            }
        }
    }

    @Test fun actionEnterCorrectsAndSubmitsWithoutNewline() {
        KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, englishEngine = ScenarioEnglishEngine()).use { s ->
            s.editor.actionEnter = true; s.start(); s.type("teh"); s.press(KeyboardKeyKind.Enter)
            assertEquals("the", s.editor.text); assertEquals(1, s.editor.actions)
            s.press(KeyboardKeyKind.Delete); assertEquals("th", s.editor.text)
        }
    }

    @Test fun contextExcludesCompositionAndDeletionAlignsEvidence() {
        val engine = ScenarioEnglishEngine()
        KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, initialText = "我想 ", englishEngine = engine).use { s ->
            s.editor.right = " 好"; s.start()
            val centers = FloatArray(52) { it.toFloat() }
            s.controller.onTouchEvidence(EnglishTouchEvidence(0.1f, 0.2f, centers)); s.type("t")
            s.type("eh"); assertEquals("我想 ", engine.requests.last().left); assertEquals(" 好", engine.requests.last().right)
            assertEquals(0.1f, engine.requests.last().points[0]); assertTrue(engine.requests.last().points[2].isNaN())
            s.press(KeyboardKeyKind.Delete); assertEquals(4, engine.requests.last().points.size)
        }
    }

    @Test fun staleLookupAndNativeFailureNeverRewriteText() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = ScenarioEnglishEngine()
        KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, englishEngine = engine,
            scope = CoroutineScope(SupervisorJob() + dispatcher), lookupDispatcher = dispatcher).use { s ->
            s.start(); s.type("teh"); s.press(KeyboardKeyKind.Space); advanceUntilIdle()
            assertEquals("teh ", s.editor.text); assertTrue(s.snapshot().englishCandidates.isEmpty())
            engine.failure = true; s.type("teh"); advanceUntilIdle(); s.press(KeyboardKeyKind.Space)
            assertEquals("teh teh ", s.editor.text)
        }
    }

    @Test fun selectionChangeClearsCompositionAndUndo() {
        KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, englishEngine = ScenarioEnglishEngine()).use { s ->
            s.start(); s.type("teh"); s.controller.onSelectionChanged(0, 0, 3)
            assertEquals("teh", s.editor.text); assertTrue(s.snapshot().englishCandidates.isEmpty())
            s.press(KeyboardKeyKind.Space); assertEquals("teh ", s.editor.text)
        }
        KeyboardScenario(initialMode = KeyboardInputMode.AlphaNum, englishEngine = ScenarioEnglishEngine()).use { s ->
            s.start(); s.type("teh"); s.press(KeyboardKeyKind.Space)
            s.controller.onSelectionChanged(4, 4, -1)
            s.press(KeyboardKeyKind.Delete); assertEquals("teh", s.editor.composing)
            s.press(KeyboardKeyKind.Space); s.type("teh"); s.press(KeyboardKeyKind.Space)
            s.editor.selectionOverride = 0 to 0
            s.controller.onSelectionChanged(0, 0, -1)
            s.press(KeyboardKeyKind.Delete); assertEquals("", s.editor.composing)
            assertEquals("teh the", s.editor.text)
        }
    }

    @Test fun closingQuoteCanResolveCorrectionAndContractionRemainsLiteral() {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            KeyboardScenario(initialMode = mode, englishEngine = ScenarioEnglishEngine()).use { s ->
                s.start(); s.type("'teh'"); s.press(KeyboardKeyKind.Space)
                assertEquals("'the' ", s.editor.text)
            }
        }
    }

    @Test fun editorRestartFinishesLiteralWordBeforeNewTyping() {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            KeyboardScenario(initialMode = mode, englishEngine = ScenarioEnglishEngine()).use { s ->
                s.start(); s.type("teh")
                s.controller.onStartInput(null, restarting = true)
                assertEquals("teh", s.editor.text); assertEquals("", s.editor.composing)
                s.type("x"); s.press(KeyboardKeyKind.Space)
                assertEquals("tehx ", s.editor.text)
            }
        }
    }
}
