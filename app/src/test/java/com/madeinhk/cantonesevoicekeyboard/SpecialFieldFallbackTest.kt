package com.madeinhk.cantonesevoicekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardKeyKind
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardToolbarContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialFieldFallbackTest {

    @Test
    fun specialFieldKindMappingTargetsSupportedTypesAndExcludesPasswordsAndPlainText() {
        assertEquals(
            SpecialFieldKind.Email,
            specialFieldKindFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        )
        assertEquals(
            SpecialFieldKind.Email,
            specialFieldKindFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS),
        )
        assertEquals(
            SpecialFieldKind.Phone,
            specialFieldKindFor(InputType.TYPE_CLASS_PHONE),
        )
        assertEquals(
            SpecialFieldKind.PersonName,
            specialFieldKindFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
        )
        assertEquals(
            SpecialFieldKind.PostalAddress,
            specialFieldKindFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS),
        )

        assertNull(specialFieldKindFor(null))
        assertNull(specialFieldKindFor(InputType.TYPE_CLASS_TEXT))
        assertNull(specialFieldKindFor(InputType.TYPE_CLASS_NUMBER))
        assertNull(specialFieldKindFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertNull(specialFieldKindFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
    }

    @Test
    fun storeValidatesDeduplicatesPromotesMruAndCapsAtFive() {
        val store = InMemorySpecialFieldStore()

        // Reject invalid fragments
        store.record(SpecialFieldKind.Email, "")
        store.record(SpecialFieldKind.Email, "not-an-email")
        store.record(SpecialFieldKind.Email, "missing@tld")
        store.record(SpecialFieldKind.Email, "@gmail.com")
        store.record(SpecialFieldKind.Email, "has space@gmail.com")
        assertTrue(store.values(SpecialFieldKind.Email).isEmpty())

        // Record 6 valid emails -> capped at 5 in MRU order
        for (i in 1..6) {
            store.record(SpecialFieldKind.Email, "  user$i@example.com  ")
        }
        assertEquals(
            listOf(
                "user6@example.com",
                "user5@example.com",
                "user4@example.com",
                "user3@example.com",
                "user2@example.com",
            ),
            store.values(SpecialFieldKind.Email),
        )

        // Case-insensitive deduplication promotes latest spelling to front
        store.record(SpecialFieldKind.Email, "User3@Example.com")
        assertEquals("User3@Example.com", store.values(SpecialFieldKind.Email).first())
        assertEquals(5, store.values(SpecialFieldKind.Email).size)

        // Phone validation and digit-based deduplication
        store.record(SpecialFieldKind.Phone, "123")
        store.record(SpecialFieldKind.Phone, "9123abcd")
        assertTrue(store.values(SpecialFieldKind.Phone).isEmpty())

        store.record(SpecialFieldKind.Phone, "+852 9123 4567")
        store.record(SpecialFieldKind.Phone, "+852-9123-4567")
        assertEquals(listOf("+852-9123-4567"), store.values(SpecialFieldKind.Phone))
    }

    @Test
    fun emailFieldForcesAlphaNumEvenWhenEnglishLayoutDisabledAndRestoresPreviousMode() {
        KeyboardScenario(initialMode = KeyboardInputMode.Handwriting).use { s ->
            s.enabledLayouts = setOf(KeyboardSettings.LAYOUT_HANDWRITING)
            s.start()
            assertEquals(KeyboardInputMode.Handwriting, s.snapshot().inputMode)
            assertFalse(s.snapshot().emailBottomRow)

            val emailInfo = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                imeOptions = EditorInfo.IME_ACTION_NEXT
            }
            s.hide()
            s.start(emailInfo)

            val emailState = s.snapshot()
            assertEquals(KeyboardInputMode.AlphaNum, emailState.inputMode)
            assertTrue(emailState.emailBottomRow)
            assertEquals(false, emailState.chinesePunctuation)

            // Typing '.' in an email field must commit ASCII '.' rather than Chinese '。'
            s.type("tony.chan@gmail.com")
            assertEquals("tony.chan@gmail.com", s.editor.text)

            // Leaving the email field records the typed email and restores Handwriting on normal text
            s.hide()
            assertEquals(listOf("tony.chan@gmail.com"), s.specialFields.values(SpecialFieldKind.Email))

            s.editor.text = ""
            s.start()
            assertEquals(KeyboardInputMode.Handwriting, s.snapshot().inputMode)
            assertFalse(s.snapshot().emailBottomRow)
        }
    }

    @Test
    fun emailDomainCompletionsAndSavedValuesReplaceWholeFieldAndYieldToOsAutofill() {
        KeyboardScenario(clipboardText = "stale clipboard").use { s ->
            s.specialFields.record(SpecialFieldKind.Email, "tony.work@company.hk")

            val emailInfo = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            }
            s.start(emailInfo)

            // 1. OS Autofill present -> OS Autofill wins over fallback candidates
            assertEquals(
                KeyboardToolbarContent.Autofill,
                s.snapshot().toolbar(hasAutofill = true),
            )

            // 2. OS Autofill absent -> Fallback candidates win over stale clipboard
            assertEquals(
                KeyboardToolbarContent.Candidates,
                s.snapshot().toolbar(hasAutofill = false),
            )
            assertEquals(
                listOf("tony.work@company.hk"),
                s.snapshot().fieldCandidates.map { it.text },
            )

            // 3. Typing partial local+@ triggers saved prefix matches + common domain completions
            s.type("tony@")
            val candidatesAfterAt = s.snapshot().fieldCandidates.map { it.text }
            assertTrue(candidatesAfterAt.contains("tony@gmail.com"))
            assertTrue(candidatesAfterAt.contains("tony@outlook.com"))
            assertTrue(candidatesAfterAt.contains("tony@yahoo.com.hk"))

            // 4. Selecting domain completion replaces "tony@" cleanly without duplicating prefix
            s.choose("tony@gmail.com")
            assertEquals("tony@gmail.com", s.editor.text)
            assertTrue("exact match should clear redundant candidate", s.snapshot().fieldCandidates.isEmpty())
            assertEquals(
                listOf("tony@gmail.com", "tony.work@company.hk"),
                s.specialFields.values(SpecialFieldKind.Email),
            )
        }
    }

    @Test
    fun incognitoAndUneditedPrefilledFieldsAreNeverRecorded() {
        KeyboardScenario(initialText = "prefilled@example.com").use { s ->
            val emailInfo = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            // Focusing and leaving without user edits does not store prefilled app text
            s.start(emailInfo)
            s.hide()
            assertTrue(s.specialFields.values(SpecialFieldKind.Email).isEmpty())

            // Incognito / no-personalized-learning never stores even on explicit Enter or hide
            s.editor.text = ""
            s.editor.personalizedLearningAllowed = false
            s.editor.actionEnter = true
            s.start(emailInfo)
            s.type("secret@example.com")
            s.press(KeyboardKeyKind.Enter)
            s.hide()
            assertTrue(s.specialFields.values(SpecialFieldKind.Email).isEmpty())
        }
    }

    @Test
    fun phoneAndCantoneseNameFieldsFallbackCleanlyWithoutBlockingCharacterComposition() {
        KeyboardScenario(initialMode = KeyboardInputMode.Quick, clipboardText = null).use { s ->
            s.engine.lookup = { _, code, _, _ ->
                when (code) {
                    "nl" -> listOf(QuickCandidate("陳", 0))
                    "f" -> listOf(QuickCandidate("小", 0), QuickCandidate("少", 1))
                    else -> emptyList()
                }
            }

            // 1. Phone field switches to Number mode, records on Enter, and offers 1-tap fill next time
            val phoneInfo = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_PHONE
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
            s.editor.actionEnter = true
            s.start(phoneInfo)
            assertEquals(KeyboardInputMode.Number, s.snapshot().inputMode)
            s.type("91234567")
            s.press(KeyboardKeyKind.Enter)
            assertEquals(listOf("91234567"), s.specialFields.values(SpecialFieldKind.Phone))

            s.hide()
            s.editor.text = ""
            s.start(phoneInfo)
            assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
            assertEquals(listOf("91234567"), s.snapshot().fieldCandidates.map { it.text })
            s.choose("91234567")
            assertEquals("91234567", s.editor.text)

            // 2. PersonName field in Cantonese Quick mode shows saved name when idle,
            //    hides fieldCandidates while composing Quick radicals, and re-surfaces matching name after commit
            s.specialFields.record(SpecialFieldKind.PersonName, "陳大文")
            val nameInfo = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            }
            s.hide()
            s.editor.text = ""
            s.start(nameInfo)
            assertEquals(KeyboardInputMode.Quick, s.snapshot().inputMode)
            assertEquals(listOf("陳大文"), s.snapshot().fieldCandidates.map { it.text })

            // Typing Quick radical "n" enters composition -> fieldCandidates yields to Quick candidates
            s.type("nl")
            assertTrue(s.snapshot().fieldCandidates.isEmpty())
            assertEquals(listOf("陳"), s.snapshot().quickCandidates.map { it.text })

            // Selecting "陳" finishes composition -> "陳大文" matches committed prefix "陳"
            s.choose("陳")
            assertEquals("陳", s.editor.text)
            assertEquals(listOf("陳大文"), s.snapshot().fieldCandidates.map { it.text })

            // Tapping "陳大文" replaces "陳" with "陳大文" (never "陳陳大文")
            s.choose("陳大文")
            assertEquals("陳大文", s.editor.text)
        }
    }
}
