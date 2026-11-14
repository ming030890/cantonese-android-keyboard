package com.madeinhk.cantonesevoicekeyboard.english

import com.madeinhk.cantonesevoicekeyboard.KeyboardScenario
import com.madeinhk.cantonesevoicekeyboard.ScenarioEnglishEngine
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardInputMode
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.KeyboardKeyKind
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class AutocorrectEditTrackerTest {

    @Test
    fun undoKeepLiteralRecordsNegativeCorrectionSignal() {
        val episodes = mutableListOf<AutocorrectEpisode>()
        KeyboardScenario(
            initialMode = KeyboardInputMode.AlphaNum,
            englishEngine = ScenarioEnglishEngine(),
        ).use { s ->
            s.english?.onAutocorrectEpisode = { episodes += it }
            s.start()
            s.type("teh")
            s.press(KeyboardKeyKind.Space)
            assertEquals("the ", s.editor.text)

            // User presses Backspace to undo "the " -> "teh", then commits "teh "
            s.press(KeyboardKeyKind.Delete)
            assertEquals("teh", s.editor.composing)
            s.press(KeyboardKeyKind.Space)
            assertEquals("teh ", s.editor.text)

            assertEquals(1, episodes.size)
            val ep = episodes.single()
            assertEquals("undo_keep_literal", ep.signal)
            assertEquals("teh", ep.typed)
            assertEquals("the", ep.autoCommitted)
            assertEquals("teh", ep.target)
        }
    }

    @Test
    fun undoThenEditRecordsTargetWordSignal() {
        val episodes = mutableListOf<AutocorrectEpisode>()
        KeyboardScenario(
            initialMode = KeyboardInputMode.AlphaNum,
            englishEngine = ScenarioEnglishEngine(),
        ).use { s ->
            s.english?.onAutocorrectEpisode = { episodes += it }
            s.start()
            s.type("teh")
            s.press(KeyboardKeyKind.Space)
            assertEquals("the ", s.editor.text)

            // Undo -> "teh", then backspace 'h' and type "a " -> "tea "
            s.press(KeyboardKeyKind.Delete)
            assertEquals("teh", s.editor.composing)
            s.press(KeyboardKeyKind.Delete)
            assertEquals("te", s.editor.composing)
            s.type("a")
            s.press(KeyboardKeyKind.Space)
            assertEquals("tea ", s.editor.text)

            assertEquals(1, episodes.size)
            val ep = episodes.single()
            assertEquals("undo_then_edit", ep.signal)
            assertEquals("teh", ep.typed)
            assertEquals("the", ep.autoCommitted)
            assertEquals("tea", ep.target)
        }
    }

    @Test
    fun backspaceIntoCommittedWordAndRetypeRecordsRepairSignal() {
        val episodes = mutableListOf<AutocorrectEpisode>()
        val engine = object : EnglishCandidateEngine {
            override fun predict(
                word: String,
                centers: FloatArray,
                points: FloatArray,
                left: String,
                right: String,
            ) = EnglishPrediction(
                known = word in setOf("form", "from"),
                candidates = listOf(EnglishCandidate("from", 1.2, 1.0, 1, 0)),
                correction = null,
            )
        }
        KeyboardScenario(
            initialMode = KeyboardInputMode.AlphaNum,
            initialText = "sent ",
            englishEngine = engine,
        ).use { s ->
            s.english?.onAutocorrectEpisode = { episodes += it }
            s.start()
            s.type("form")
            s.press(KeyboardKeyKind.Space)
            assertEquals("sent form ", s.editor.text)

            // Backspace space + "rm" ("sent fo"), then type "rom " -> "sent from "
            //Wait: "form" -> backspace space, 'm', 'r', 'o' -> "sent f", then type "rom "
            s.press(KeyboardKeyKind.Delete) // deletes space
            s.press(KeyboardKeyKind.Delete) // deletes 'm'
            s.press(KeyboardKeyKind.Delete) // deletes 'r'
            s.press(KeyboardKeyKind.Delete) // deletes 'o'
            assertEquals("sent f", s.editor.text)
            s.type("rom")
            s.press(KeyboardKeyKind.Space)
            assertEquals("sent from ", s.editor.text)

            assertEquals(1, episodes.size)
            val ep = episodes.single()
            assertEquals("backspace_retype_repair", ep.signal)
            assertEquals("sent ", ep.left)
            assertEquals("form", ep.typed)
            assertEquals("form", ep.autoCommitted)
            assertEquals("from", ep.target)
        }
    }

    @Test
    fun candidateSelectionAndKeepLiteralRecordSignals() {
        val episodes = mutableListOf<AutocorrectEpisode>()
        KeyboardScenario(
            initialMode = KeyboardInputMode.AlphaNum,
            englishEngine = ScenarioEnglishEngine(),
        ).use { s ->
            s.english?.onAutocorrectEpisode = { episodes += it }
            s.start()
            // 1. Tapping literal candidate when autocorrect would have replaced "teh" -> "the"
            s.type("teh")
            s.choose("teh")
            assertEquals("teh", s.editor.text)
            assertEquals(1, episodes.size)
            assertEquals("candidate_keep_literal", episodes[0].signal)
            assertEquals("teh", episodes[0].typed)
            assertEquals("the", episodes[0].autoCommitted)
            assertEquals("teh", episodes[0].target)

            s.type(" ")
            // 2. Tapping non-literal candidate "the"
            s.type("teh")
            s.choose("the")
            assertEquals("teh the", s.editor.text)
            assertEquals(2, episodes.size)
            assertEquals("candidate_selected", episodes[1].signal)
            assertEquals("teh", episodes[1].typed)
            assertEquals("the", episodes[1].target)
        }
    }

    @Test
    fun unchallengedCommitFlushesOnNextWordOrSessionEnd() {
        val episodes = mutableListOf<AutocorrectEpisode>()
        KeyboardScenario(
            initialMode = KeyboardInputMode.AlphaNum,
            englishEngine = ScenarioEnglishEngine(),
        ).use { s ->
            s.english?.onAutocorrectEpisode = { episodes += it }
            s.start()
            s.type("teh")
            s.press(KeyboardKeyKind.Space)
            assertTrue(episodes.isEmpty())

            // Typing and committing the next word confirms "teh" -> "the" was accepted
            s.type("cat")
            s.press(KeyboardKeyKind.Space)
            assertEquals(1, episodes.size)
            assertEquals("accepted_correction", episodes[0].signal)
            assertEquals("teh", episodes[0].typed)
            assertEquals("the", episodes[0].target)

            s.controller.onFinishInputView()
        }
    }

    @Test
    fun lanHttpSyncUploadsBatchAndClearsPendingFile() {
        val tempFile = File.createTempFile("autocorrect_pending", ".jsonl")
        tempFile.deleteOnExit()

        val ep1 = AutocorrectEpisode(
            timestampMillis = 1700000001000L,
            layout = "english",
            left = "hello ",
            typed = "teh",
            autoCommitted = "the",
            target = "tea",
            signal = "undo_then_edit",
            known = false,
            topCandidate = "the",
            topScore = 2.5,
            topEdits = 1,
        )
        val ep2 = AutocorrectEpisode(
            timestampMillis = 1700000002000L,
            layout = "quick",
            left = "send ",
            typed = "form",
            autoCommitted = "form",
            target = "from",
            signal = "backspace_retype_repair",
            known = true,
            topCandidate = "from",
            topScore = 1.1,
            topEdits = 1,
        )
        AutocorrectTelemetryStore.appendLineBounded(tempFile, ep1.toJson().toString())
        AutocorrectTelemetryStore.appendLineBounded(tempFile, ep2.toJson().toString())
        assertTrue(tempFile.exists())

        var receivedBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/autocorrect-episodes") { exchange ->
            receivedBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val response = """{"status":"ok","received":2}""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val port = server.address.port
            val ok = AutocorrectTelemetryStore.syncFileToEndpoints(
                file = tempFile,
                endpoints = listOf(
                    "http://127.0.0.1:1/unreachable",
                    "http://127.0.0.1:$port/api/v1/autocorrect-episodes",
                ),
                connectTimeoutMs = 500,
                readTimeoutMs = 1000,
            )
            assertTrue(ok)
            assertFalse(tempFile.exists())

            val parsed = JSONObject(receivedBody)
            assertEquals(1, parsed.getInt("schema"))
            val arr = parsed.getJSONArray("episodes")
            assertEquals(2, arr.length())
            assertEquals("undo_then_edit", arr.getJSONObject(0).getString("signal"))
            assertEquals("tea", arr.getJSONObject(0).getString("target"))
            assertEquals("backspace_retype_repair", arr.getJSONObject(1).getString("signal"))
            assertEquals("from", arr.getJSONObject(1).getString("target"))
        } finally {
            server.stop(0)
        }
    }
}
