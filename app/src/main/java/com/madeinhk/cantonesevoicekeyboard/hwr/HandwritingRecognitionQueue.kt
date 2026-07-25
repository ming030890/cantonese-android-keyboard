package com.madeinhk.cantonesevoicekeyboard.hwr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes recognition across characters while keeping only the latest
 * submitted bitmap for each character.
 */
internal class HandwritingRecognitionQueue<T>(
    private val scope: CoroutineScope,
    private val idleDelayMillis: Long,
    private val process: suspend (characterId: Long, request: T) -> Unit,
) {
    private val processingMutex = Mutex()
    private val jobs = mutableMapOf<Long, Job>()

    fun submit(characterId: Long, request: T): Job {
        jobs.remove(characterId)?.cancel()
        lateinit var job: Job
        job = scope.launch {
            delay(idleDelayMillis)
            processingMutex.withLock {
                if (jobs[characterId] === job) {
                    process(characterId, request)
                }
            }
        }
        jobs[characterId] = job
        job.invokeOnCompletion {
            if (jobs[characterId] === job) {
                jobs.remove(characterId)
            }
        }
        return job
    }

    fun cancel(characterId: Long) {
        jobs.remove(characterId)?.cancel()
    }

    fun cancelAll(): List<Job> {
        val cancelledJobs = jobs.values.toList()
        jobs.clear()
        cancelledJobs.forEach(Job::cancel)
        return cancelledJobs
    }

    fun jobsSnapshot(): List<Job> = jobs.values.toList()
}
