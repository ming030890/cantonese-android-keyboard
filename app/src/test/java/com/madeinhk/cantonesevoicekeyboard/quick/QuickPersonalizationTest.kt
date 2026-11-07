package com.madeinhk.cantonesevoicekeyboard.quick

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executor

class QuickPersonalizationTest {
    private val base = listOf("你", "係", "好", "嗎", "我")
    private fun choose(p: QuickPersonalization, text: String, left: String = "我", count: Int = 2, version: Int = 3) {
        repeat(count) { p.record(version, "of", text, left) }
    }

    @Test fun repeatedChoicesPromoteButOneTapDoesNotAndVersionsStaySeparate() {
        val p = QuickPersonalization()
        choose(p, "好", count = 1)
        assertEquals(base, p.rank(3, "of", "我", base))
        choose(p, "好", count = 1)
        assertEquals("好", p.rank(3, "OF", "我", base).first())
        assertEquals(base, p.rank(5, "of", "我", base))
        assertEquals(base, p.rank(3, "ab", "我", base))
        assertEquals(listOf("你"), p.rank(3, "of", "我", listOf("你")))
    }

    @Test fun matchingContextOutranksGlobalFrequencyAndRecencyDecays() {
        var time = 0L
        val p = QuickPersonalization(now = { time })
        choose(p, "嗎", "其他", 10)
        choose(p, "好", "我", 2)
        assertEquals("好", p.rank(3, "of", "我", base).first())
        assertEquals("嗎", p.rank(3, "of", "別處", base).first())
        time += 400L * 24 * 60 * 60 * 1000
        assertEquals(base, p.rank(3, "of", "我", base))
    }

    @Test fun bilingualSlotsLiteralAndAllOriginalCandidatesArePreserved() {
        val p = QuickPersonalization(now = { 0L })
        listOf("係", "好", "嗎", "我").forEach { choose(p, it, count = 3) }
        val original = listOf("of", "你", "office", "係", "好", "嗎", "我")
        val result = p.rank(3, "of", "我", original)
        assertEquals("of", result[0]); assertEquals("office", result[2])
        assertEquals(original.toSet(), result.toSet())
        assertEquals("我", result.last()) // At most three candidates promoted.
    }

    @Test fun predictionsCanRecallSelectedPhrasesAndRemainBoundedAndUnique() {
        val p = QuickPersonalization()
        choose(p, "食飯", "我想")
        assertEquals(listOf("食飯", "你", "係"), p.rank(3, "", "我想", listOf("你", "係")))
        assertEquals(listOf("食飯", "你"), p.rank(3, "", "我想", listOf("你", "食飯", "係"), 2))
        assertEquals(base, p.rank(3, "", "其他", base))
        assertEquals(listOf("食飯"), p.rank(3, "", "我想", emptyList()))
    }

    @Test fun toggleRetainsMemoryClearForgetsAndEnglishIsIgnored() {
        val p = QuickPersonalization()
        choose(p, "好")
        p.enabled = false
        assertEquals(base, p.rank(3, "of", "我", base))
        choose(p, "嗎", count = 20)
        p.enabled = true
        assertEquals("好", p.rank(3, "of", "我", base).first())
        p.clear()
        choose(p, "hello")
        assertEquals(base, p.rank(3, "of", "我", base))
    }

    @Test fun laterPageCandidateCanBePromotedWithoutDroppingTheRemainingPages() {
        val p = QuickPersonalization()
        val original = (0 until 200).map { "候選$it" }
        choose(p, "候選199")
        val result = p.rank(3, "of", "我", original)
        assertEquals("候選199", result.first())
        assertEquals(200, result.size)
        assertEquals(original.toSet(), result.toSet())
    }

    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun persistenceRestoresHashedUnicodeContextAndClearCannotBeUndoneByQueuedWrites() {
        val directory = Files.createTempDirectory("quick-history").toFile()
        try {
            val file = directory.resolve("history.bin")
            val queue = Queue()
            val p = QuickPersonalization(file, io = queue)
            queue.drain()
            choose(p, "好", "秘密🙂你想唔想食飯")
            queue.drain()
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("秘密"))
            val restored = QuickPersonalization(file, io = Executor { it.run() })
            assertEquals("好", restored.rank(3, "of", "秘密🙂你想唔想食飯", base).first())
            choose(p, "嗎")
            p.clear()
            queue.drain()
            val cleared = QuickPersonalization(file, io = Executor { it.run() })
            assertEquals(base, cleared.rank(3, "of", "我", base))
        } finally { directory.deleteRecursively() }
    }

    @Test fun selectionsBeforeLoadMergeWithDiskAndClearBeforeLoadWins() {
        val directory = Files.createTempDirectory("quick-load").toFile()
        try {
            val file = directory.resolve("history.bin")
            val old = QuickPersonalization(file, io = Executor { it.run() })
            choose(old, "好")
            val queue = Queue()
            val p = QuickPersonalization(file, io = queue)
            choose(p, "嗎")
            queue.drain()
            assertEquals(setOf("好", "嗎"), p.rank(3, "of", "我", base).take(2).toSet())
            val pending = QuickPersonalization(file, io = queue)
            choose(pending, "我")
            pending.clear()
            queue.drain()
            assertEquals(base, pending.rank(3, "of", "我", base))
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptFileAndEvictionFallBackSafely() {
        val directory = Files.createTempDirectory("quick-corrupt").toFile()
        try {
            val file = directory.resolve("history.bin").apply { writeText("bad") }
            val p = QuickPersonalization(file, io = Executor { it.run() }, capacity = 3)
            assertEquals(base, p.rank(3, "of", "我", base))
            choose(p, "好"); choose(p, "嗎")
            assertEquals("嗎", p.rank(3, "of", "我", base).first())
            assertFalse(p.rank(3, "", "我", emptyList()).contains("好"))
        } finally { directory.deleteRecursively() }
    }
}
