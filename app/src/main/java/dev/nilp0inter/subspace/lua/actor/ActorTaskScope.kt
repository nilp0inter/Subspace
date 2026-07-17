package dev.nilp0inter.subspace.lua.actor

import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Generation-owned background-task scope.
 *
 * Bounded by the generation's lifetime and invocation gate. Tracks every
 * actor task so that generation retirement cancels or drains all descendants
 * and no work escapes the generation to become process-global. Enforces
 * maximum concurrent tasks and per-task deadlines.
 *
 * The scope is a mechanism owned by the runtime. It does not create threads
 * and does not execute outside the generation's invocation gate. It is not a
 * process-global singleton.
 */
internal class ActorTaskScope(
    parentScope: CoroutineScope,
    private val scopeIdentity: CapabilityScopeIdentity,
    private val maxConcurrentTasks: Int,
    private val perTaskDeadlineMillis: Long,
) {
    init {
        require(maxConcurrentTasks > 0) { "Max concurrent tasks must be positive" }
        require(perTaskDeadlineMillis > 0) { "Per-task deadline must be positive" }
    }

    private val supervisorJob = SupervisorJob(
        requireNotNull(parentScope.coroutineContext[Job]) {
            "Actor task scope requires a parent Job"
        },
    )
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisorJob)

    private val tasks = ConcurrentHashMap<ActorTaskId, ActorTaskHandle>()
    private val activeCount = AtomicInteger(0)
    private val taskCounter = AtomicLong(0L)

    val isActive: Boolean
        get() = supervisorJob.isActive

    val activeTaskCount: Int
        get() = activeCount.get()

    val totalTaskCount: Int
        get() = tasks.size

    /**
     * Launch one background task bounded by the generation. Returns the task
     * identity, or null if the scope is closed or at capacity.
     *
     * Capacity admission is atomic: concurrent launches never exceed
     * [maxConcurrentTasks] under a CAS loop.
     */
    fun launch(task: suspend (ActorTaskIdentity) -> Unit): ActorTaskIdentity? {
        if (!supervisorJob.isActive) return null
        while (true) {
            val current = activeCount.get()
            if (current >= maxConcurrentTasks) return null
            if (activeCount.compareAndSet(current, current + 1)) break
        }

        val taskId = ActorTaskId(taskCounter.incrementAndGet())
        val identity = ActorTaskIdentity(scope = scopeIdentity, taskId = taskId)
        tasks[taskId] = ActorTaskHandle(taskId)

        scope.launch {
            try {
                withTimeoutOrNull(perTaskDeadlineMillis) {
                    task(identity)
                }
            } catch (_: CancellationException) {
                // generation cancelled — expected during retirement
            } finally {
                activeCount.decrementAndGet()
                tasks.remove(taskId)
            }
        }
        return identity
    }

    /**
     * Launch a timer coroutine for this generation without counting against
     * [maxConcurrentTasks] capacity. The timer is cancelled when the generation
     * retires or closes. Used for operation deadlines.
     */
    fun launchTimer(block: suspend CoroutineScope.() -> Unit) {
        scope.launch { block() }
    }

    /**
     * Cancel all descendants. Called during retirement or close.
     */
    fun cancelAll() {
        supervisorJob.cancel()
    }

    /**
     * Join all active tasks within a bounded timeout. Returns true if all
     * tasks completed or were cancelled within the bound.
     */
    suspend fun joinAllWithin(timeoutMillis: Long): Boolean {
        supervisorJob.cancel()
        return withTimeoutOrNull(timeoutMillis) {
            supervisorJob.join()
            true
        } ?: false
    }

    private class ActorTaskHandle(val taskId: ActorTaskId)
}