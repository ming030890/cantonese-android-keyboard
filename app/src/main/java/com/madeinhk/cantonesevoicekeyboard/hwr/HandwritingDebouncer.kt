package com.madeinhk.cantonesevoicekeyboard.hwr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Schedules only the latest handwriting frame after the user goes idle. */
internal class HandwritingDebouncer<T>(
    private val scope: CoroutineScope,
    private val idleDelayMillis: Long,
    private val onIdle: suspend (T) -> Unit,
) {
    private var generation = 0L
    private var pendingJob: Job? = null

    fun submit(request: T): Job {
        val requestGeneration = ++generation
        pendingJob?.cancel()
        val job = scope.launch {
            delay(idleDelayMillis)
            if (requestGeneration == generation) {
                onIdle(request)
            }
        }
        pendingJob = job
        job.invokeOnCompletion {
            if (pendingJob === job) {
                pendingJob = null
            }
        }
        return job
    }

    fun cancel() {
        generation++
        pendingJob?.cancel()
        pendingJob = null
    }
}
