package dev.sebastiano.jewel.tooling.mcp.runtime

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll

internal class RequestJobs {
    private val jobs = LinkedHashSet<Job>()
    private var stopping = false

    fun admit(job: Job): Boolean {
        val admitted = synchronized(jobs) { if (stopping) false else jobs.add(job) }
        if (admitted) job.invokeOnCompletion { synchronized(jobs) { jobs.remove(job) } }
        return admitted
    }

    suspend fun close() {
        val active =
            synchronized(jobs) {
                stopping = true
                jobs.toList()
            }
        active.forEach { it.cancel() }
        active.joinAll()
    }
}
