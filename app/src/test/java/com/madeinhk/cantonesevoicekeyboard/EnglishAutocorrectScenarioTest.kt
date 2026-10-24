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
    @Test fun contextualProfileCanCorrectShortEnglishDespiteAnOverlappingChineseQuickCode() {
        for(confident in listOf(false,true)) for(selectChinese in listOf(false,true)) {
            val engine=object:EnglishCandidateEngine {
                override val allowsQuickCodeCorrections=true
                override fun predict(word:String,centers:FloatArray,points:FloatArray,left:String,right:String)=EnglishPrediction(
                    candidates=listOf(EnglishCandidate("it",1.0,1.0,1,0)),correction=if(word=="jt" && confident) "it" else null,
                    confidence=if(confident) .99 else .5,replaceBefore=word.length)
            }
            KeyboardScenario(initialText="please test ",englishEngine=engine).use {s->
                s.engine.lookup={_,_,_,_->listOf(com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate("乙",0))}
                s.start();s.type("jt")
                assertTrue(s.snapshot().quickCandidates.any {it.text=="乙"})
                if(selectChinese) s.choose("乙") else s.press(KeyboardKeyKind.Space)
                assertEquals("please test "+if(selectChinese) "乙" else if(confident) "it " else "jt ",s.editor.text)
            }
        }
    }
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
                    assertEquals("$mode $boundary", "the${SmartPunctuation.toAscii(boundary)}", s.editor.text)
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

    @Test fun quickKeepsLongEnglishAndChineseCompositionsUntilSelection() {
        for (typed in listOf("keyboard", "zzzzzzz")) {
            KeyboardScenario(englishEngine = ScenarioEnglishEngine()).use { s ->
                s.engine.lookup = { _, code, _, _ -> if (code.length == 6) listOf(QuickCandidate("中文中文中文", 0)) else emptyList() }
                s.engine.classify = { code, _, _, _ -> if (code == "zzzzzz")
                    com.madeinhk.cantonesevoicekeyboard.quick.QuickLanguageEvidence(0.001f, 0.999f, 0.0001f, 0.1f) else null }
                s.start(); s.type(typed)
                assertEquals("", s.editor.text)
                assertEquals(typed, s.editor.composing)
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
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)) {
            assertFalse(englishAllowed(type))
        }
        assertTrue(englishAllowed(InputType.TYPE_NULL))
        assertTrue(englishAllowed(InputType.TYPE_CLASS_TEXT))
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
            assertEquals("the ", s.editor.text); assertTrue(s.snapshot().englishCandidates.isEmpty())
            engine.failure = true; s.type("teh"); advanceUntilIdle(); s.press(KeyboardKeyKind.Space)
            assertEquals("the teh ", s.editor.text)
        }
    }

    @Test fun pendingBoundaryQueuesNewInputAndSupportsUndo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            KeyboardScenario(initialMode = mode, englishEngine = ScenarioEnglishEngine(),
                scope = CoroutineScope(SupervisorJob() + dispatcher), lookupDispatcher = dispatcher).use { s ->
                s.start(); s.type("teh"); s.press(KeyboardKeyKind.Space)
                assertEquals("", s.editor.text)
                assertEquals("teh", s.editor.composing)
                advanceUntilIdle(); assertEquals("the ", s.editor.text)
                s.press(KeyboardKeyKind.Delete); assertEquals("teh", s.editor.composing)
                s.press(KeyboardKeyKind.Space); advanceUntilIdle(); assertEquals("teh ", s.editor.text)
            }
            for (action in listOf("type", "hide", "selection", "selectionReturn", "layout")) {
                KeyboardScenario(initialMode = mode, englishEngine = ScenarioEnglishEngine(),
                    scope = CoroutineScope(SupervisorJob() + dispatcher), lookupDispatcher = dispatcher).use { s ->
                    s.start(); s.type("teh"); s.press(KeyboardKeyKind.Space)
                    when (action) {
                        "type" -> s.type("x")
                        "hide" -> s.hide()
                        "layout" -> s.mode(KeyboardInputMode.Handwriting)
                        else -> {
                            s.editor.selectionOverride = 0 to 0; s.controller.onSelectionChanged(0, 0, -1)
                            if (action == "selectionReturn") {
                                s.editor.selectionOverride = 4 to 4; s.controller.onSelectionChanged(4, 4, -1)
                            }
                        }
                    }
                    advanceUntilIdle()
                    assertEquals("$mode $action", if (action == "type") "the " else "teh ", s.editor.text)
                    if (action == "type") assertEquals("x", s.editor.composing)
                }
            }
        }
    }

    @Test fun mixedCandidateBarUsesContextRankingAndBothLanguagesRemainSelectable() {
        for (selected in listOf("the", "你", "teh")) {
            val engine = ScenarioEnglishEngine().apply {
                ranker = { left, _, pool ->
                    assertEquals("I use ", left)
                    listOf("the", "你", "係", "teh").filter { it in pool }
                }
            }
            KeyboardScenario(initialText = "I use ", englishEngine = engine).use { s ->
                s.engine.lookup = { _, _, _, _ -> listOf(QuickCandidate("你", 0), QuickCandidate("係", 1)) }
                s.start(); s.type("teh")
                assertEquals(listOf("the", "你", "係", "teh"), s.snapshot().quickCandidates.map { it.text })
                s.choose(selected); assertEquals("I use $selected", s.editor.text)
                assertEquals("", s.editor.composing)
            }
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
