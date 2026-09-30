package com.mypersonalassistent.core.mcp.impl

import com.mypersonalassistent.core.mcp.api.McpOperationCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A process-local bridge; durable normalization remains owned by the registered Chat Store. */
internal class DefaultMcpOperationCoordinator : McpOperationCoordinator {
    private data class Registration(val job: Job, val invalidate: () -> Unit, val normalize: suspend () -> Unit)
    private val mutex = Mutex()
    private val operations = mutableMapOf<String, MutableMap<Job, Registration>>()

    override fun register(serverId: String, job: Job, invalidateBeforeCancellation: () -> Unit, normalizeAfterCancellation: suspend () -> Unit) {
        synchronized(operations) {
            operations.getOrPut(serverId, ::mutableMapOf)[job] = Registration(job, invalidateBeforeCancellation, normalizeAfterCancellation)
        }
    }

    override fun unregister(serverId: String, job: Job) {
        synchronized(operations) {
            operations[serverId]?.remove(job)
            if (operations[serverId].isNullOrEmpty()) operations.remove(serverId)
        }
    }

    override suspend fun cancelAndJoin(serverId: String) = mutex.withLock {
        val registrations = synchronized(operations) { operations.remove(serverId)?.values?.toList() } ?: return@withLock
        // Invalidate UI callbacks synchronously, then cancel the jobs so engine command lanes are
        // released before durable UNKNOWN normalization tries to acquire them.
        registrations.forEach { it.invalidate() }
        registrations.forEach { it.job.cancel() }
        try {
            registrations.forEach { it.normalize() }
        } finally {
            registrations.map { it.job }.joinAll()
        }
    }
}
