package com.madeinhk.cantonesevoicekeyboard

import com.madeinhk.cantonesevoicekeyboard.english.SharedLmEnglishEngine
import com.madeinhk.cantonesevoicekeyboard.english.EnglishCandidate
import com.madeinhk.cantonesevoicekeyboard.english.EnglishPrediction
import com.madeinhk.cantonesevoicekeyboard.quick.NativeQuickEngine
import com.madeinhk.cantonesevoicekeyboard.ui.keyboard.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

class SharedLmEnglishScenarioTest {
    @Test fun refittedAcceptanceCorrectsAtSpaceAndUndoAcrossLayoutsThroughJni() {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        for ((mode, version) in listOf(KeyboardInputMode.AlphaNum to 3,
            KeyboardInputMode.Quick to 3, KeyboardInputMode.Quick to 5)) {
            val quick = NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() })
            val english = SharedLmEnglishEngine(quick::englishContextHandle)
            assertEquals("game", english.predict("gwme", floatArrayOf(), floatArrayOf(), "", "").correction)
            assertNull("Competing burger/butter repairs remain uncertain",
                english.predict("butger", floatArrayOf(), floatArrayOf(), "", "").correction)
            KeyboardScenario(initialMode=mode, clipboardText=null, quickEngine=quick,
                englishEngine=english).use { s ->
                s.quickVersion=version
                s.start(); s.type("gwme")
                val candidates = if (mode==KeyboardInputMode.Quick) s.snapshot().quickCandidates
                    else s.snapshot().englishCandidates
                assertTrue(candidates.any { it.text=="game" })
                assertEquals(KeyboardToolbarContent.Candidates,s.snapshot().toolbar())
                s.press(KeyboardKeyKind.Space)
                assertEquals("game ",s.editor.text)
                assertEquals("",s.editor.composing)
                s.press(KeyboardKeyKind.Delete)
                assertEquals("",s.editor.text)
                assertEquals("gwme",s.editor.composing)
                s.press(KeyboardKeyKind.Space)
                assertEquals("gwme ",s.editor.text)
            }
        }
    }

    @Test fun mixedRankingReceivesObservedKeysAndCandidatesBeyondTheOldEightItemCutoff() {
        var observedKeys: String? = null
        val phrases=(1..12).map { "候選$it" }
        val engine=SharedLmEnglishEngine({7L},{_,_,_->emptyList()}, {_,left,_,pool,observed ->
            observedKeys=observed
            assertEquals("我想",left)
            if (observed.length>2) assertTrue("Every generated phrase reaches the ranker",pool.containsAll(phrases))
            listOf(phrases.last())+pool.filter { it!=phrases.last() }
        }, {_,_,_->EnglishPrediction()})
        KeyboardScenario(initialText="我想",clipboardText=null,englishEngine=engine).use { s ->
            s.engine.lookup={_,_,_,_->phrases.mapIndexed { i,t -> com.madeinhk.cantonesevoicekeyboard.quick.QuickCandidate(t,i) }}
            s.start();s.type("abcd")
            assertEquals("abcd",observedKeys)
            assertEquals(phrases.last(),s.snapshot().quickCandidates.first().text)
            assertEquals((phrases+"abcd").toSet(),s.snapshot().quickCandidates.map { it.text }.toSet())
            assertEquals("abcd",s.editor.composing)
            assertEquals(KeyboardToolbarContent.Candidates,s.snapshot().toolbar())
            s.choose(phrases.last())
            assertEquals("我想${phrases.last()}",s.editor.text)
            assertEquals("",s.editor.composing)
        }
    }

    @Test fun actualShortCodePreviewRetainsEveryMatchThroughJni() {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() }).use { quick ->
            for (version in listOf(3, 5)) for (left in listOf("", "I use ", "我想")) {
                for (code in listOf("df", "DF", "d", "of")) {
                    val preview = quick.previewCandidates(version, code, left, "")
                    val full = quick.candidates(version, code, left, "")
                    assertFalse(preview.isEmpty())
                    assertEquals(full.map { it.text }.sorted(), preview.map { it.text }.sorted())
                    assertEquals(preview.indices.toList(), preview.map { it.rank })
                }
            }
            assertTrue(quick.previewCandidates(3, "dfg", "", "").isEmpty())
        }
    }

    @Test fun englishSuggestionDoesNotWaitForQuickMonitor() {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() }).use { quick ->
            val english = SharedLmEnglishEngine(quick::englishContextHandle)
            val occupied = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val chinese = workers.submit {
                    synchronized(quick) {
                        occupied.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
                assertTrue(occupied.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val result = workers.submit<EnglishPrediction> {
                    english.predict("whrn", floatArrayOf(), floatArrayOf(), "", "")
                }
                // A generous test timeout proves independence, not phone latency.
                assertEquals("when", result.get(1, java.util.concurrent.TimeUnit.SECONDS).correction)
                release.countDown()
                chinese.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                release.countDown()
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun quickControllerCorrectsWhileChineseWorkerIsOccupied() = kotlinx.coroutines.test.runTest {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        val quick = NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() })
        val english = SharedLmEnglishEngine(quick::englishContextHandle)
        val occupied = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val ui = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + ui)
        try {
            worker.submit {
                synchronized(quick) {
                    occupied.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            assertTrue(occupied.await(5, java.util.concurrent.TimeUnit.SECONDS))
            KeyboardScenario(initialMode=KeyboardInputMode.Quick, quickEngine=quick,
                englishEngine=english, scope=scope,
                englishDispatcher=kotlinx.coroutines.Dispatchers.Unconfined).use { s ->
                try {
                    s.start(); s.type("whrn"); testScheduler.runCurrent()
                    assertEquals("whrn", s.editor.composing)
                    assertTrue(s.snapshot().quickCandidates.any { it.text == "when" })
                    assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                    s.press(KeyboardKeyKind.Space); testScheduler.runCurrent()
                    assertEquals("when ", s.editor.text)
                    assertEquals("", s.editor.composing)
                    s.press(KeyboardKeyKind.Delete); testScheduler.runCurrent()
                    assertEquals("", s.editor.text)
                    assertEquals("whrn", s.editor.composing)
                } finally { release.countDown() }
            }
        } finally {
            release.countDown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            quick.close()
        }
    }

    @Test fun actualConcurrentQuickEnglishLatency() {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() }).use { quick ->
            val english = SharedLmEnglishEngine(quick::englishContextHandle)
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
            val timings = mutableListOf<Double>()
            try {
                english.predict("whrn", floatArrayOf(), floatArrayOf(), "", "")
                repeat(32) { index ->
                    val entered = java.util.concurrent.CountDownLatch(1)
                    val chinese = worker.submit {
                        synchronized(quick) {
                            entered.countDown()
                            quick.candidates(3, "df", "I use item $index ", "")
                        }
                    }
                    assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    // Let the native call enter before measuring the other worker.
                    // This is a host contention panel, not a phone latency assertion.
                    java.util.concurrent.CountDownLatch(1).await(5, java.util.concurrent.TimeUnit.MILLISECONDS)
                    val start = System.nanoTime()
                    val prediction = english.predict("whrn", floatArrayOf(), floatArrayOf(), "", "")
                    timings += (System.nanoTime() - start) / 1e6
                    assertEquals("when", prediction.correction)
                    chinese.get(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                println("QUICK_ENGLISH_CONTENTION_MS=" + timings.joinToString(","))
            } finally {
                worker.shutdownNow()
                assertTrue(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun reportedSentenceCorrectsAtSpaceInBothQuickVersions() = kotlinx.coroutines.test.runTest {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        // These are current model decisions, including known limitations, not gold labels
        // for fitting the policy. Keep the user's example outside the frozen eval set.
        val words = listOf(
            "whrn" to "when", "i" to "i", "tyoe" to "type", "i" to "i",
            "dont" to "don't", "rrally" to "really", "know" to "know",
            "whay" to "what", "it" to "it", "meant" to "meant",
        )
        for (version in listOf(3, 5)) for (settleBeforeSpace in listOf(false, true)) {
            val ui = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + ui)
            try {
                val quick = NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() })
                val english = SharedLmEnglishEngine(quick::englishContextHandle)
                KeyboardScenario(initialMode=KeyboardInputMode.Quick, clipboardText=null,
                    quickEngine=quick, englishEngine=english, scope=scope,
                    lookupDispatcher=ui, englishDispatcher=ui).use { s ->
                    s.quickVersion = version
                    s.start()
                    var committed = ""
                    for ((typed, expected) in words) {
                        s.type(typed)
                        if (settleBeforeSpace) testScheduler.runCurrent()
                        val label = "Quick$version settled=$settleBeforeSpace after $committed$typed"
                        assertEquals(label, committed, s.editor.text)
                        assertEquals(label, typed, s.editor.composing)
                        if (settleBeforeSpace) {
                            val candidates = s.snapshot().quickCandidates.map { it.text }
                            assertTrue("$label offers literal", typed in candidates)
                            if (typed != expected) assertTrue("$label offers correction", expected in candidates)
                            assertEquals(label, KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                        }
                        s.press(KeyboardKeyKind.Space)
                        testScheduler.runCurrent()
                        committed += "$expected "
                        assertEquals(label, committed, s.editor.text)
                        assertEquals(label, "", s.editor.composing)
                    }
                    assertEquals("when i type i don't really know what it meant ", s.editor.text)
                }
            } finally {
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                testScheduler.runCurrent()
            }
        }
    }

    @Test fun dontRestoresApostropheOnSpaceAndUndoInBothLayouts() {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            val quick = NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() })
            val english = SharedLmEnglishEngine(quick::englishContextHandle)
            KeyboardScenario(initialText="I ", initialMode=mode, quickEngine=quick,
                englishEngine=english).use { s ->
                s.start(); s.type("dont")
                val candidates = if (mode==KeyboardInputMode.Quick) s.snapshot().quickCandidates
                    else s.snapshot().englishCandidates
                assertTrue(candidates.any { it.text=="don't" })
                assertEquals(KeyboardToolbarContent.Candidates, s.snapshot().toolbar())
                s.press(KeyboardKeyKind.Space)
                assertEquals("I don't ", s.editor.text)
                assertEquals("", s.editor.composing)
                s.press(KeyboardKeyKind.Delete)
                assertEquals("I ", s.editor.text)
                assertEquals("dont", s.editor.composing)
                s.press(KeyboardKeyKind.Space)
                assertEquals("I dont ", s.editor.text)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun mixedModelRanksEnglishAndChinesePhrasesFromObservedKeys() = kotlinx.coroutines.test.runTest {
        val library = System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary", library)
        System.load(requireNotNull(library))
        for (version in listOf(3,5)) for (word in listOf("humo", "continue")) {
            val ui = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + ui)
            try {
                val quick = NativeQuickEngine.fromAssets(read = { File("src/main/assets/$it").readBytes() })
                val english = SharedLmEnglishEngine(quick::englishContextHandle)
                KeyboardScenario(initialMode=KeyboardInputMode.Quick, quickEngine=quick,
                    englishEngine=english, scope=scope, lookupDispatcher=ui).use { s ->
                    s.quickVersion=version; s.start(); s.type(word); testScheduler.runCurrent()
                    val candidates=s.snapshot().quickCandidates.map { it.text }
                    assertEquals(word,s.editor.composing)
                    assertEquals("",s.editor.text)
                    assertEquals(KeyboardToolbarContent.Candidates,s.snapshot().toolbar())
                    if (word=="continue") {
                        assertEquals("Quick$version English exact match", "continue",candidates.first())
                        s.press(KeyboardKeyKind.Space);testScheduler.runCurrent()
                        assertEquals("continue ",s.editor.text)
                    } else {
                        assertTrue("Quick$version offers 兒歌", "兒歌" in candidates)
                        assertTrue("Quick$version offers 自歌", "自歌" in candidates)
                        assertTrue("Quick$version coherent phrase outranks 自歌", candidates.indexOf("兒歌") < candidates.indexOf("自歌"))
                        assertTrue("Quick$version 兒歌 in top eight", candidates.indexOf("兒歌") < 8)
                        assertTrue("Quick$version chooses Chinese interpretation",candidates.first().any { it.code>127 })
                        s.choose("兒歌"); testScheduler.runCurrent()
                        assertEquals("兒歌",s.editor.text)
                    }
                    assertEquals("",s.editor.composing)
                }
            } finally {scope.coroutineContext[kotlinx.coroutines.Job]?.cancel();testScheduler.runCurrent()}
        }
    }

    private fun fake() = SharedLmEnglishEngine({ 7L }, { handle, _, _ ->
        assertEquals(7L, handle)
        listOf("hello", "help", "the")
    }, { _, _, _, words, _ -> words }, { _,_,typed ->
        EnglishPrediction(candidates=listOf("hello","help","the").filter {it.startsWith(typed,true)}
            .map {EnglishCandidate(it,0.0,0.0,0,(it.length-typed.length).coerceAtLeast(0))})
    })

    @Test fun uncertainSpellingsSurviveEveryBoundaryInBothLayouts() {
        for (mode in listOf(KeyboardInputMode.AlphaNum, KeyboardInputMode.Quick)) {
            for (boundary in listOf(" ", ".", "\n")) {
                KeyboardScenario(initialMode=mode, englishEngine=fake()).use { s ->
                    s.engine.lookup={_,_,_,_->emptyList()}
                    s.start(); s.type("teh")
                    assertEquals("teh",s.editor.composing)
                    when(boundary) {
                        " " -> s.press(KeyboardKeyKind.Space)
                        "\n" -> s.press(KeyboardKeyKind.Enter)
                        else -> s.type(boundary)
                    }
                    assertEquals("teh$boundary",s.editor.text)
                    assertEquals("",s.editor.composing)
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun pendingUncertainLookupCommitsLiterallyWithoutLateRewrite() = kotlinx.coroutines.test.runTest {
        val dispatcher=kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=fake(),scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+dispatcher),
            lookupDispatcher=dispatcher).use { s ->
            s.start();s.type("teh");s.press(KeyboardKeyKind.Space)
            testScheduler.advanceUntilIdle()
            assertEquals("teh ",s.editor.text)
        }
    }

    @Test fun prefixSuggestionsRequireExplicitSelection() {
        KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=fake()).use { s ->
            s.start();s.type("hel")
            assertEquals("",s.editor.text)
            assertEquals("hel",s.editor.composing)
            assertEquals(listOf("hel","hello","help"),s.snapshot().englishCandidates.map {it.text})
            assertEquals(KeyboardToolbarContent.Candidates,s.snapshot().toolbar())
            s.choose("hello")
            assertEquals("hello",s.editor.text)
            assertEquals("",s.editor.composing)
        }
    }

    @Test fun nextWordSelectionUsesTheSharedEngineWithoutComposition() {
        KeyboardScenario(initialText="I said ",initialMode=KeyboardInputMode.AlphaNum,englishEngine=fake()).use { s ->
            s.start()
            assertEquals(listOf("hello","help","the"),s.snapshot().englishCandidates.map {it.text})
            s.choose("hello")
            assertEquals("I said hello",s.editor.text)
            assertEquals("",s.editor.composing)
        }
    }

    private fun correcting() = SharedLmEnglishEngine({1L},{_,_,_->emptyList()},
        {_,_,_,words,_->words}, {_,_,typed->
            EnglishPrediction(candidates=listOf(EnglishCandidate("you",-1.0,2.0,1,0)),
                correction=if(typed=="yiu") "you" else null,reason="shared_lm_spelling")
        })

    @Test fun sharedSpellingSuggestionsCorrectOnSpaceAndUndoInBothLayouts() {
        for(mode in listOf(KeyboardInputMode.AlphaNum,KeyboardInputMode.Quick)) {
            KeyboardScenario(initialText="how are ",initialMode=mode,englishEngine=correcting()).use {s->
                s.engine.lookup={_,_,_,_->emptyList()}
                s.start();s.type("yiu")
                val words=if(mode==KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                assertTrue(words.any {it.text=="you"})
                assertEquals(KeyboardToolbarContent.Candidates,s.snapshot().toolbar())
                s.press(KeyboardKeyKind.Space)
                assertEquals("how are you ",s.editor.text)
                s.press(KeyboardKeyKind.Delete)
                assertEquals("how are ",s.editor.text)
                assertEquals("yiu",s.editor.composing)
                s.press(KeyboardKeyKind.Space)
                assertEquals("how are yiu ",s.editor.text)
            }
        }
    }

    @Test fun spellingAlternativeCanAlsoBeSelectedExplicitly() {
        KeyboardScenario(initialText="how are ",initialMode=KeyboardInputMode.AlphaNum,englishEngine=correcting()).use {s->
            s.start();s.type("yiu");s.choose("you")
            assertEquals("how are you",s.editor.text)
            assertEquals("",s.editor.composing)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun pendingSharedCorrectionFinishesBeforeFollowingKeys() = kotlinx.coroutines.test.runTest {
        val dispatcher=kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        KeyboardScenario(initialText="how are ",initialMode=KeyboardInputMode.AlphaNum,englishEngine=correcting(),
            scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+dispatcher),
            lookupDispatcher=dispatcher).use {s->
            s.start();s.type("yiu");s.press(KeyboardKeyKind.Space);s.type("today")
            testScheduler.advanceUntilIdle()
            assertEquals("how are you ",s.editor.text)
            assertEquals("today",s.editor.composing)
        }
    }

    @Test fun actualHandwritingRecognizerAcceptsTheSharedCnnAndFusion() {
        val library=System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary",library)
        System.load(requireNotNull(library))
        val type=com.madeinhk.cantonesevoicekeyboard.hwr.HandwritingRecognizer::class.java
        val create=type.getDeclaredMethod("nativeCreate", *Array(5) {ByteArray::class.java}).apply {isAccessible=true}
        fun read(name:String)=File("src/main/assets/$name").readBytes()
        val handle=create.invoke(null,read("handwriting/model.safetensors"),read("handwriting/labels.txt"),
            byteArrayOf(),read("language_model/model.cng"),read("language_model/handwriting_fusion.json")) as Long
        assertNotEquals(0L,handle)
        val recognizer=type.getDeclaredConstructor(Long::class.javaPrimitiveType).apply {isAccessible=true}
            .newInstance(handle)
        recognizer.use {
            it.setLanguageContext("可以","")
            // Invoke the production JNI directly: Android Log is unavailable in this host JVM.
            val predict=type.getDeclaredMethod("nativePredictStrokes",Long::class.javaPrimitiveType,
                FloatArray::class.java,IntArray::class.java,Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,Int::class.javaPrimitiveType,String::class.java).apply {isAccessible=true}
            val result=predict.invoke(null,handle,floatArrayOf(10f,50f,0f,90f,50f,100f),
                intArrayOf(2),100f,100f,3,null) as Array<*>
            assertEquals(3,result.size)
            assertTrue(result.all {c -> (c as String).isNotBlank()})
        }
    }

    @Test fun actualSharedNativeModelCompletesEnglishAndRanksQuick() {
        val library=System.getProperty("english.native.library")
        assumeNotNull("Actual JNI requires -PenglishNativeLibrary",library)
        System.load(requireNotNull(library))
        NativeQuickEngine.fromAssets(read={File("src/main/assets/$it").readBytes()}).use { quick ->
            val english=SharedLmEnglishEngine(quick::englishContextHandle)
            for(word in listOf("wastg","bokeh","Alstom","Xentara")) {
                assertNull("Unexpected automatic correction: $word",english.predict(word,floatArrayOf(),floatArrayOf(),"","").correction)
            }
            // The learned rule deliberately abstains on uncertain proposals.
            // Suggested alternatives remain available for explicit selection.
            for ((typed, target, automatic) in listOf(
                Triple("hwllo", "hello", true), Triple("helo", "hello", true),
                Triple("helllo", "hello", true), Triple("teh", "the", true),
                Triple("lokk", "look", true), Triple("thses", "these", false),
                Triple("exmaole", "example", false), Triple("tyoes", "types", true),
                Triple("sjre", "sure", true), Triple("varely", "barely", true),
                Triple("engloaj", "english", true), Triple("troggee", "trigger", true),
                Triple("mwrge", "merge", false), Triple("wrror", "error", true),
                Triple("inthe", "in the", false))) {
                val prediction = english.predict(typed, floatArrayOf(), floatArrayOf(), "", "")
                assertTrue("$typed still offers $target", prediction.candidates.any { it.word.equals(target, true) })
                assertEquals("$typed automatic decision", if (automatic) target else null, prediction.correction?.lowercase())
            }
            assertEquals("you", english.predict("yiu", floatArrayOf(), floatArrayOf(), "how are ", "").correction?.lowercase())
            assertEquals("the", english.predict("thr", floatArrayOf(), floatArrayOf(), "see all ", "").correction?.lowercase())
            assertEquals("you support", english.predict("youbsupport", floatArrayOf(), floatArrayOf(), "did ", "").correction?.lowercase())
            assertEquals("you support", english.predict("yousupport", floatArrayOf(), floatArrayOf(), "did ", "").correction?.lowercase())
            val knownMistap=english.predict("hou",floatArrayOf(),floatArrayOf(),"let me know if ","")
            assertTrue(knownMistap.known)
            assertEquals("you",knownMistap.correction)
            assertNull(english.predict("don't",floatArrayOf(),floatArrayOf(),"I ","").correction)
            for (mode in listOf(KeyboardInputMode.AlphaNum,KeyboardInputMode.Quick)) {
                KeyboardScenario(initialText="let me know if ",initialMode=mode,englishEngine=english).use { s ->
                    s.engine.lookup={_,_,_,_->emptyList()}
                    s.start();s.type("hou")
                    val words=if(mode==KeyboardInputMode.Quick) s.snapshot().quickCandidates else s.snapshot().englishCandidates
                    assertTrue(words.any { it.text=="you" })
                    assertEquals(KeyboardToolbarContent.Candidates,s.snapshot().toolbar())
                    s.press(KeyboardKeyKind.Space)
                    assertEquals("let me know if you ",s.editor.text)
                    s.press(KeyboardKeyKind.Delete)
                    assertEquals("let me know if ",s.editor.text)
                    assertEquals("hou",s.editor.composing)
                    s.press(KeyboardKeyKind.Space)
                    assertEquals("let me know if hou ",s.editor.text)
                }
            }
            val result=english.predict("hel",floatArrayOf(),floatArrayOf(),"I said ","")
            assertFalse(result.candidates.isEmpty())
            assertTrue(result.candidates.any {it.word.equals("hello",true)})
            assertNull(result.correction)
            assertTrue(english.supportsAutomaticCorrection)
            assertFalse(quick.candidates(3,"a","我","").isEmpty())
            val dfCandidates = quick.candidates(3, "df", "", "")
            assertTrue("Expected >128 candidates for df but got ${dfCandidates.size}", dfCandidates.size > 128)
            val ofCandidates = quick.candidates(3, "of", "", "")
            assertTrue("Expected >128 candidates for of but got ${ofCandidates.size}", ofCandidates.size > 128)
            assertEquals(setOf("hello","你好"),english.rankCandidates("我想講 ","",listOf("hello","你好")).toSet())
            KeyboardScenario(initialMode=KeyboardInputMode.AlphaNum,englishEngine=english).use { s ->
                s.start();s.type("keyboard");s.press(KeyboardKeyKind.Space)
                assertEquals("keyboard ",s.editor.text)
                s.type("varely");s.press(KeyboardKeyKind.Space)
                assertEquals("keyboard barely ",s.editor.text)
                s.type("lokk");s.press(KeyboardKeyKind.Space)
                assertEquals("keyboard barely look ",s.editor.text)
                s.type("thses");s.press(KeyboardKeyKind.Space)
                assertEquals("keyboard barely look thses ",s.editor.text)
                s.type("exmaole");s.press(KeyboardKeyKind.Space)
                assertEquals("keyboard barely look thses exmaole ",s.editor.text)
                s.type("mwrge");s.press(KeyboardKeyKind.Space)
                assertEquals("keyboard barely look thses exmaole merge ",s.editor.text)
                s.type("wrror");s.press(KeyboardKeyKind.Space)
                assertEquals("keyboard barely look thses exmaole merge error ",s.editor.text)
                s.type("youbsupport");s.press(KeyboardKeyKind.Space)
                assertEquals("keyboard barely look thses exmaole merge error you support ",s.editor.text)
            }
            KeyboardScenario(initialMode=KeyboardInputMode.Quick,quickEngine=quick,englishEngine=english).use { s ->
                s.start();s.type("df")
                val candidates = s.snapshot().quickCandidates
                assertTrue("Expected non-empty candidates for df", candidates.isNotEmpty())
                assertTrue("Expected Chinese candidates for df", candidates.any { c -> c.text.any { it.code in 0x4e00..0x9fff } })
            }
        }
    }
}
