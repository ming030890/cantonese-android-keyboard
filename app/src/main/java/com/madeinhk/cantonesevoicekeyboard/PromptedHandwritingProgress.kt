package com.madeinhk.cantonesevoicekeyboard

import android.content.Context
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

internal data class PromptedChallengeProgress(
    val date: LocalDate,
    val todayCount: Int,
    val streakDays: Int,
    val lifetimeCount: Int,
    val completedClassIds: Set<Int>,
)

internal object PromptedHandwritingProgress {
    const val DAILY_GOAL = 50
    private const val PREFS = "prompted_handwriting_challenge"
    private const val WRITER_ID = "writer_id"
    private const val COMPLETED_IDS = "completed_class_ids"
    private const val ACTIVE_DATE = "active_date"
    private const val TODAY_COUNT = "today_count"
    private const val STREAK_DAYS = "streak_days"
    private const val LAST_GOAL_DATE = "last_goal_date"

    fun writerId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(WRITER_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(WRITER_ID, it).apply()
        }
    }

    fun load(context: Context, date: LocalDate = LocalDate.now()): PromptedChallengeProgress {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedDate = prefs.getString(ACTIVE_DATE, null)?.let(LocalDate::parse)
        val todayCount = if (storedDate == date) prefs.getInt(TODAY_COUNT, 0) else 0
        if (storedDate != date) {
            prefs.edit().putString(ACTIVE_DATE, date.toString()).putInt(TODAY_COUNT, 0).apply()
        }
        val completed = decodeIds(prefs.getString(COMPLETED_IDS, "").orEmpty())
        return PromptedChallengeProgress(
            date = date,
            todayCount = todayCount,
            streakDays = prefs.getInt(STREAK_DAYS, 0),
            lifetimeCount = completed.size,
            completedClassIds = completed,
        )
    }

    fun recordAccepted(
        context: Context,
        classId: Int,
        date: LocalDate = LocalDate.now(),
    ): PromptedChallengeProgress {
        val before = load(context, date)
        if (classId in before.completedClassIds) return before
        val completed = before.completedClassIds + classId
        val count = before.todayCount + 1
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var streak = before.streakDays
        val editor = prefs.edit()
            .putString(COMPLETED_IDS, completed.sorted().joinToString(","))
            .putString(ACTIVE_DATE, date.toString())
            .putInt(TODAY_COUNT, count)
        if (count == DAILY_GOAL) {
            val previousGoal = prefs.getString(LAST_GOAL_DATE, null)?.let(LocalDate::parse)
            streak = if (previousGoal == date.minusDays(1)) streak + 1 else 1
            editor.putInt(STREAK_DAYS, streak).putString(LAST_GOAL_DATE, date.toString())
        }
        check(editor.commit()) { "Could not persist handwriting challenge progress" }
        return PromptedChallengeProgress(date, count, streak, completed.size, completed)
    }

    fun shuffledClassIds(
        labels: List<String>,
        writerId: String,
        inventoryHash: String,
    ): List<Int> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$writerId:$inventoryHash".toByteArray())
        val seed = ByteBuffer.wrap(digest.copyOfRange(0, Long.SIZE_BYTES)).long
        return labels.indices.shuffled(Random(seed))
    }

    fun nextClassId(
        order: List<Int>,
        completed: Set<Int>,
        deferred: Set<Int> = emptySet(),
    ): Int? = order.firstOrNull { it !in completed && it !in deferred }
        ?: order.firstOrNull { it !in completed }

    fun inventoryHash(labelsBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(labelsBytes).toHex()

    private fun decodeIds(value: String): Set<Int> =
        value.split(',').mapNotNull(String::toIntOrNull).toSet()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
