package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.madeinhk.cantonesevoicekeyboard.english.EnglishTouchEvidence

internal const val DefaultLongPressDelayMillis = 350L
internal const val DefaultRepeatIntervalMillis = 45L
internal const val DefaultMinRepeatIntervalMillis = 18L

internal fun calculateRepeatInterval(
    repeatCount: Int,
    initialInterval: Long = DefaultRepeatIntervalMillis,
    minInterval: Long = DefaultMinRepeatIntervalMillis,
): Long {
    val floor = minOf(initialInterval, minInterval)
    return when {
        repeatCount < 6 -> initialInterval
        repeatCount < 15 -> (initialInterval - (repeatCount - 5) * 2L).coerceAtLeast(floor)
        repeatCount < 30 -> (initialInterval - 20L).coerceAtLeast(floor)
        else -> floor
    }
}

class PressedKeyInfo(
    val downTimeMillis: Long,
    internal var job: Job? = null,
    var blockUp: Boolean = false,
    var touchEvidence: EnglishTouchEvidence? = null,
)

interface KeyboardInputReceiver {
    fun onKeyDown(data: KeyData)
    fun onKeyUp(data: KeyData)
    fun onKeyUp(data: KeyData, touch: EnglishTouchEvidence?) = onKeyUp(data)
    fun onKeyCancel(data: KeyData)
    fun onKeyRepeat(data: KeyData)
}

interface KeyboardInputDispatcher {
    fun sendDown(
        data: KeyData,
        onLongPress: () -> Boolean = { false },
        onRepeat: () -> Boolean = { false },
    ): PressedKeyInfo?

    fun sendUp(data: KeyData, pressedKeyInfo: PressedKeyInfo? = null)
    fun sendCancel(data: KeyData, pressedKeyInfo: PressedKeyInfo? = null)
    fun dispose()
}

class CoroutineKeyboardInputDispatcher(
    private val receiver: KeyboardInputReceiver,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate,
    ),
    private val longPressDelayMillis: Long = DefaultLongPressDelayMillis,
    private val repeatIntervalMillis: Long = DefaultRepeatIntervalMillis,
    private val minRepeatIntervalMillis: Long = DefaultMinRepeatIntervalMillis,
    private val nowMillis: () -> Long = SystemClock::uptimeMillis,
) : KeyboardInputDispatcher {
    override fun sendDown(
        data: KeyData,
        onLongPress: () -> Boolean,
        onRepeat: () -> Boolean,
    ): PressedKeyInfo {
        receiver.onKeyDown(data)
        val info = PressedKeyInfo(nowMillis())
        if (data.longPressEnabled) {
            info.job = scope.launch {
                delay(longPressDelayMillis)
                if (!isActive) return@launch
                val consumed = onLongPress()
                info.blockUp = consumed
                if (!consumed && data.repeatable) {
                    var repeatCount = 0
                    while (isActive) {
                        val interval = calculateRepeatInterval(
                            repeatCount = repeatCount,
                            initialInterval = repeatIntervalMillis,
                            minInterval = minRepeatIntervalMillis,
                        )
                        delay(interval)
                        repeatCount++
                        if (!onRepeat()) break
                    }
                }
            }
        }
        return info
    }

    override fun sendUp(data: KeyData, pressedKeyInfo: PressedKeyInfo?) {
        pressedKeyInfo?.job?.cancel()
        if (pressedKeyInfo?.blockUp != true) receiver.onKeyUp(data, pressedKeyInfo?.touchEvidence)
    }

    override fun sendCancel(data: KeyData, pressedKeyInfo: PressedKeyInfo?) {
        pressedKeyInfo?.job?.cancel()
        receiver.onKeyCancel(data)
    }

    override fun dispose() {
        scope.coroutineContext[Job]?.cancel()
    }
}
