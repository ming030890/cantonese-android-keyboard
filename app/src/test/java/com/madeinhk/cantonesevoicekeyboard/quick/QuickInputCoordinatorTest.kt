package com.madeinhk.cantonesevoicekeyboard.quick

import android.view.inputmethod.InputConnection
import com.madeinhk.cantonesevoicekeyboard.ImeEditor
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuickInputCoordinatorTest {

    private lateinit var coordinator: QuickInputCoordinator
    private var lastComposingText: String? = null
    private var lastCommittedText: String? = null
    private val committedTexts = mutableListOf<String>()

    @Before
    fun setUp() {
        committedTexts.clear()
        val engine = object : QuickCandidateEngine {
            override fun candidates(version: Int, code: String, left: String, right: String) =
                listOf("係", "你").mapIndexed { i, text -> QuickCandidate(text, i) }
        }

        val handler = object : InvocationHandler {
            override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
                when (method.name) {
                    "setComposingText" -> {
                        lastComposingText = args?.get(0)?.toString()
                        return true
                    }
                    "commitText" -> {
                        val text = args?.get(0)?.toString().orEmpty()
                        lastCommittedText = text
                        committedTexts += text
                        return true
                    }
                    "finishComposingText" -> {
                        lastComposingText = ""
                        return true
                    }
                }
                return null
            }
        }

        val proxyConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
            handler
        ) as InputConnection

        val editor = ImeEditor(
            connection = { proxyConnection },
            editorInfo = { null }
        )

        coordinator = QuickInputCoordinator(
            engineLoader = { engine },
            editor = editor,
            initialVersion = 3
        )
    }

    @Test
    fun forwardsCommittedContextWithoutQuickCodeAndChangesVersion() {
        var request: List<String>? = null
        val backend = object : QuickCandidateEngine {
            override fun candidates(version: Int, code: String, left: String, right: String): List<QuickCandidate> {
                request = listOf(version.toString(), code, left, right)
                return listOf(QuickCandidate("你", 0))
            }
        }
        val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, _ ->
            when (method.name) {
                "getTextBeforeCursor" -> "我係of"
                "getTextAfterCursor" -> "呀"
                else -> true
            }
        } as InputConnection
        val c = QuickInputCoordinator({ backend }, ImeEditor({ connection }, { null }))
        c.onKey("o"); c.onKey("f")
        assertEquals(listOf("3", "of", "我係", "呀"), request)
        c.setVersion(5)
        assertEquals(listOf("5", "of", "我係", "呀"), request)
        c.close()
        assertTrue(c.candidates.value.isEmpty())
    }

    @Test
    fun predictsWithoutCodeAndRefreshesAfterSelectionAndDiscard() {
        var before = "我想"
        var requests = 0
        val backend = object : QuickCandidateEngine {
            override fun candidates(version: Int, code: String, left: String, right: String) = listOf(QuickCandidate("你", 0))
            override fun predictions(left: String, right: String, limit: Int): List<QuickCandidate> {
                assertEquals(before, left)
                assertEquals("呀", right)
                assertEquals(100, limit)
                requests++
                return (0 until limit).map { QuickCandidate(if (it == 0) "食" else "候選$it", it) }
            }
        }
        val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, args ->
            when (method.name) {
                "getTextBeforeCursor" -> before
                "getTextAfterCursor" -> "呀"
                "commitText" -> { before += args!![0].toString(); true }
                else -> true
            }
        } as InputConnection
        val c = QuickInputCoordinator({ backend }, ImeEditor({ connection }, { null }))
        c.refreshPredictions()
        assertEquals(100, c.candidates.value.size)
        assertEquals("食", c.candidates.value.first().text)
        assertEquals("候選99", c.candidates.value.last().text)
        assertFalse(c.hasActiveInput())
        c.selectCandidate("食")
        assertEquals("我想食", before)
        assertEquals(2, requests)
        assertEquals(100, c.candidates.value.size)
        assertEquals("食", c.candidates.value.first().text)
        c.discard()
        assertTrue(c.candidates.value.isEmpty())
        c.close()
        c.refreshPredictions()
        assertTrue(c.candidates.value.isEmpty())
    }

    @Test
    fun predictionAddsWordBoundaryButQuickSelectionDoesNot() {
        var committed = ""
        val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, args ->
            when (method.name) {
                "getTextBeforeCursor" -> "like"
                "getTextAfterCursor" -> "today"
                "commitText" -> { committed = args!![0].toString(); true }
                else -> true
            }
        } as InputConnection
        val c = QuickInputCoordinator({ null }, ImeEditor({ connection }, { null }))
        c.selectCandidate("it")
        assertEquals(" it ", committed)
        c.onKey("o")
        c.selectCandidate("你")
        assertEquals("你", committed)
        c.close()
    }

    @Test
    fun cancelledLookupCannotRestoreCandidates() = kotlinx.coroutines.test.runTest {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val backend = object : QuickCandidateEngine {
            override fun candidates(version: Int, code: String, left: String, right: String): List<QuickCandidate> {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                finished.countDown()
                return listOf(QuickCandidate("你", 0))
            }
        }
        val c = QuickInputCoordinator({ backend }, ImeEditor({ null }, { null }), scope = this)
        c.onKey("o")
        testScheduler.runCurrent()
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        c.discard()
        release.countDown()
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        testScheduler.advanceUntilIdle()
        assertTrue(c.candidates.value.isEmpty())
        assertFalse(c.hasActiveInput())
    }

    @Test
    fun testTypingQuickCodeProducesCandidates() {
        coordinator.onKey("o")
        assertEquals("o", coordinator.getComposingText())
        assertEquals("o", lastComposingText)
        assertFalse(coordinator.candidates.value.isEmpty())

        coordinator.onKey("f")
        assertEquals("of", coordinator.getComposingText())
        assertEquals("of", lastComposingText)
        val candidates = coordinator.candidates.value
        assertTrue(candidates.size >= 2)
        assertEquals("係", candidates[0].text)
        assertEquals("你", candidates[1].text)
    }

    @Test
    fun testSelectingCandidateCommitsChineseCharacter() {
        coordinator.onKey("o")
        coordinator.onKey("f")
        coordinator.selectCandidate("你")

        assertEquals("你", lastCommittedText)
        assertFalse(coordinator.hasActiveInput())
        assertTrue(coordinator.candidates.value.isEmpty())
    }

    @Test
    fun testTypingSpaceCommitsEnglishWord() {
        // User types "hello"
        "hello".forEach { coordinator.onKey(it.toString()) }
        assertEquals("hello", coordinator.getComposingText())
        // Phrase lookup continues beyond a single two-letter code.
        assertFalse(coordinator.candidates.value.isEmpty())

        // User taps space -> "Tap space, it was typing english."
        coordinator.onSpace()
        assertEquals("hello ", lastCommittedText)
        assertFalse(coordinator.hasActiveInput())
    }

    @Test
    fun testTypingSpaceOnTwoLetterCodeCommitsEnglish() {
        coordinator.onKey("o")
        coordinator.onKey("f")
        // Instead of candidate, user taps space
        coordinator.onSpace()
        assertEquals("of ", lastCommittedText)
        assertFalse(coordinator.hasActiveInput())
    }

    @Test
    fun testBackspaceDeletesFromComposingBuffer() {
        coordinator.onKey("o")
        coordinator.onKey("f")
        assertEquals("of", coordinator.getComposingText())

        val handled1 = coordinator.onDelete()
        assertTrue(handled1)
        assertEquals("o", coordinator.getComposingText())
        assertEquals("o", lastComposingText)

        val handled2 = coordinator.onDelete()
        assertTrue(handled2)
        assertEquals("", coordinator.getComposingText())
        assertFalse(coordinator.hasActiveInput())

        // Next delete is not handled by coordinator
        val handled3 = coordinator.onDelete()
        assertFalse(handled3)
    }

    @Test
    fun testAcceptCommitsComposingText() {
        "you".forEach { coordinator.onKey(it.toString()) }
        assertEquals("you", coordinator.getComposingText())
        assertTrue(coordinator.hasActiveInput())

        coordinator.accept()
        assertEquals("you", lastCommittedText)
        assertEquals(listOf("you"), committedTexts)
        assertFalse(coordinator.hasActiveInput())
        assertEquals("", coordinator.getComposingText())
    }

    @Test
    fun testTypingNonLetterKeyCommitsComposingTextAndPunctuation() {
        "you".forEach { coordinator.onKey(it.toString()) }
        assertEquals("you", coordinator.getComposingText())
        assertTrue(coordinator.hasActiveInput())

        coordinator.onKey("?")
        assertEquals(listOf("you", "?"), committedTexts)
        assertFalse(coordinator.hasActiveInput())
        assertEquals("", coordinator.getComposingText())
    }

    @Test
    fun testTypingSentenceWithQuestionMarkDoesNotDeleteYou() {
        // "how" + space
        "how".forEach { coordinator.onKey(it.toString()) }
        coordinator.onSpace()
        // "are" + space
        "are".forEach { coordinator.onKey(it.toString()) }
        coordinator.onSpace()
        // "you"
        "you".forEach { coordinator.onKey(it.toString()) }
        assertEquals("you", coordinator.getComposingText())
        assertTrue(coordinator.hasActiveInput())

        // type '?'
        coordinator.onKey("?")
        assertEquals(listOf("how ", "are ", "you", "?"), committedTexts)
        assertFalse(coordinator.hasActiveInput())
    }

    @Test
    fun continuousCodesSelectAndDeleteWholePhrase() {
        val codes = mutableListOf<String>()
        var committed = ""
        val backend = object : QuickCandidateEngine {
            override fun candidates(version: Int, code: String, left: String, right: String): List<QuickCandidate> {
                codes += code
                return if (code == "hion") listOf(QuickCandidate("我們", 0), QuickCandidate("我今", 1))
                    else listOf(QuickCandidate("我", 0))
            }
        }
        val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, args ->
            when (method.name) {
                "commitText" -> { committed = args!![0].toString(); true }
                else -> if (method.returnType == java.lang.Boolean.TYPE) true else null
            }
        } as InputConnection
        val c = QuickInputCoordinator({ backend }, ImeEditor({ connection }, { null }))
        "hion".forEach { c.onKey(it.toString()) }
        assertEquals("hion", c.getComposingText())
        assertEquals(listOf("我們", "我今"), c.candidates.value.map { it.text })
        c.onDelete()
        assertEquals("hio", codes.last())
        c.onKey("n")
        c.selectCandidate("我們")
        assertEquals("我們", committed)
        assertFalse(c.hasActiveInput())
        c.close()
    }

    @Test
    fun sixCharactersRemainSelectableUntilNextKey() {
        for (targetCode in listOf("hionhionhion", "qqqqqq", "qhionqhion")) {
            val committed = mutableListOf<String>()
            val targetBackend = object : QuickCandidateEngine {
                override fun candidates(version: Int, code: String, left: String, right: String) =
                    listOf(QuickCandidate(if (code == targetCode) "𠮷們手我們手" else "手", 0))
            }
            val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java)) { _, method, args ->
                if (method.name == "commitText") { committed += args!![0].toString(); true } else if (method.returnType == java.lang.Boolean.TYPE) true else null
            } as InputConnection
            val c = QuickInputCoordinator({ targetBackend }, ImeEditor({ connection }, { null }))
            targetCode.forEach { c.onKey(it.toString()) }
            assertTrue(committed.isEmpty())
            assertEquals(targetCode, c.getComposingText())
            c.onKey("q")
            assertEquals(listOf("𠮷們手我們手"), committed)
            assertEquals("q", c.getComposingText())
            c.close()
        }
    }

    @Test
    fun codeLengthAloneDoesNotCommitMissingOrShortCandidates() {
        for (text in listOf(null, "我們")) {
            val committed = mutableListOf<String>()
            val backend = object : QuickCandidateEngine {
                override fun candidates(version: Int, code: String, left: String, right: String) =
                    text?.let { listOf(QuickCandidate(it, 0)) }.orEmpty()
            }
            val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
                arrayOf(InputConnection::class.java)) { _, method, args ->
                if (method.name == "commitText") {
                    committed += args!![0].toString()
                    true
                } else if (method.returnType == java.lang.Boolean.TYPE) true else null
            } as InputConnection
            val c = QuickInputCoordinator({ backend }, ImeEditor({ connection }, { null }))
            val code = "hionhionhionhion"
            code.forEach { c.onKey(it.toString()) }
            assertTrue(committed.isEmpty())
            assertEquals(code, c.getComposingText())
            c.close()
        }
    }

    @Test
    fun capResolvesCurrentCodeWhenAsyncCandidatesAreNotReady() = kotlinx.coroutines.test.runTest {
        val committed = mutableListOf<String>()
        val backend = object : QuickCandidateEngine {
            override fun candidates(version: Int, code: String, left: String, right: String) =
                listOf(QuickCandidate(if (code == "qqqqqq") "手手手手手手" else "手", 0))
        }
        val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java)) { _, method, args ->
            if (method.name == "commitText") { committed += args!![0].toString(); true } else if (method.returnType == java.lang.Boolean.TYPE) true else null
        } as InputConnection
        val c = QuickInputCoordinator({ backend }, ImeEditor({ connection }, { null }),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)))
        "qqqqqqq".forEach { c.onKey(it.toString()) }
        assertEquals(listOf("手手手手手手"), committed)
        assertEquals("q", c.getComposingText())
        testScheduler.runCurrent()
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { c.candidates.first { it.isNotEmpty() } }
        }
        assertEquals("手", result.first().text)
        c.close()
    }

    @Test
    fun getPredictionsDelegatesToBackend() {
        val backend = object : QuickCandidateEngine {
            override fun candidates(version: Int, code: String, left: String, right: String) = emptyList<QuickCandidate>()
            override fun predictions(left: String, right: String, limit: Int): List<QuickCandidate> {
                return (1..limit).map { QuickCandidate("$left$it", it - 1) }
            }
        }
        val c = QuickInputCoordinator({ backend }, ImeEditor({ null }, { null }))
        val preds = c.getPredictions("你", "", 5)
        assertEquals(5, preds.size)
        assertEquals("你1", preds[0].text)
        assertEquals(100, c.getPredictions("你", "").size)
        c.close()
    }
}
