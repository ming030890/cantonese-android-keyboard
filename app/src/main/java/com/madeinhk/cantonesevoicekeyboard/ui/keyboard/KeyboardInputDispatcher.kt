package com.madeinhk.cantonesevoicekeyboard.ui.keyboard

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DefaultLongPressDelayMillis = 450L
private const val DefaultRepeatIntervalMillis = 55L

class PressedKeyInfo(
    val downTimeMillis: Long,
    internal var job: Job? = null,
    var blockUp: Boolean = false,
)

interface KeyboardInputReceiver {
    fun onKeyDown(data: KeyData)
    fun onKeyUp(data: KeyData)
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
                    while (isActive) {
                        delay(repeatIntervalMillis)
                        if (!onRepeat()) break
                    }
                }
            }
        }
        return info
    }

    override fun sendUp(data: KeyData, pressedKeyInfo: PressedKeyInfo?) {
        pressedKeyInfo?.job?.cancel()
        if (pressedKeyInfo?.blockUp != true) receiver.onKeyUp(data)
    }

    override fun sendCancel(data: KeyData, pressedKeyInfo: PressedKeyInfo?) {
        pressedKeyInfo?.job?.cancel()
        receiver.onKeyCancel(data)
    }

    override fun dispose() {
        scope.coroutineContext[Job]?.cancel()
    }
}
