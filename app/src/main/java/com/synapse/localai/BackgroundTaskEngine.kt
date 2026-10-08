package com.synapse.localai

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background task execution engine for the Synapse AI agent.
 *
 * Processes tasks from [TaskMemoryManager], executes them via
 * [AgentViewModel.executeBackgroundTask], and handles scheduling,
 * retries, and priority-based queuing.
 */
class BackgroundTaskEngine(private val taskMemory: TaskMemoryManager) {

    companion object {
        private const val TAG = "TaskEngine"
        private const val ENGINE_LOOP_DELAY_MS = 2000L
        private const val SCHEDULER_LOOP_DELAY_MS = 30_000L
        private const val VIEWMODEL_WAIT_TIMEOUT_MS = 10_000L
        private const val VIEWMODEL_POLL_INTERVAL_MS = 500L
    }

    // ── Data classes ────────────────────────────────────────────────────

    /** A command queued for execution while the engine is busy. */
    data class QueuedCommand(
        val command: String,
        val source: String,
        val priority: Int = 0
    )

    /** Observable snapshot of the engine's current state. */
    data class EngineState(
        val isRunning: Boolean = false,
        val currentTask: AgentTask? = null,
        val queueSize: Int = 0,
        val completedCount: Int = 0,
        val failedCount: Int = 0
    )

    // ── Internal state ──────────────────────────────────────────────────

    private var engineScope: CoroutineScope? = null
    private val isRunning = AtomicBoolean(false)
    private var currentTaskId: String? = null

    /** Commands that arrive while the engine is busy executing a task. */
    private val commandQueue = ConcurrentLinkedQueue<QueuedCommand>()

    private val _engineState = MutableStateFlow(EngineState())

    /** Public read-only view of the engine state. */
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private var completedCount = 0
    private var failedCount = 0

    var maintenanceDaemon: MaintenanceDaemon? = null

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Starts the engine: creates a supervised coroutine scope,
     * launches the main engine loop and the scheduler loop.
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Engine already running — ignoring start()")
            return
        }

        taskMemory.recoverInterruptedTasks()
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception in engine scope", throwable)
        }

        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler).also { scope ->
            scope.launch { engineLoop() }
            scope.launch { schedulerLoop() }
        }

        updateState()
        Log.i(TAG, "Engine started")
    }

    /**
     * Stops the engine, cancels all coroutines, and resets state.
     */
    fun stop() {
        isRunning.set(false)
        engineScope?.cancel()
        engineScope = null
        currentTaskId = null
        updateState()
        Log.i(TAG, "Engine stopped")
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Creates a new [AgentTask] for the given [command], queues it in
     * [TaskMemoryManager], and returns the generated task ID.
     */
    fun queueCommand(command: String, source: String, priority: Int = 0): String {
        val task = AgentTask(
            description = command.take(80),
            command = command,
            source = source,
            priority = priority,
            status = TaskStatus.QUEUED
        )
        taskMemory.addTask(task)
        commandQueue.add(QueuedCommand(command, source, priority))
        updateState()
        Log.d(TAG, "Queued command from $source: \"$command\" → task ${task.id}")
        return task.id
    }

    /**
     * Submits an externally-created [AgentTask] to the memory store
     * with [TaskStatus.QUEUED].
     */
    fun submitTask(task: AgentTask) {
        val queued = task.copy(status = TaskStatus.QUEUED)
        taskMemory.addTask(queued)
        updateState()
        Log.d(TAG, "Submitted task ${task.id}: \"${task.command}\"")
    }

    /**
     * Schedules a recurring or cron-based task.
     */
    fun scheduleCronTask(
        description: String,
        command: String,
        cronExpr: String,
        taskType: String = "COMMAND",
        priority: Int = 0
    ): String? {
        val nextRun = CronExpressionParser.getNextExecutionTime(cronExpr)
        if (nextRun <= 0L) {
            Log.w(TAG, "Invalid cron expression: $cronExpr")
            return null
        }
        val task = AgentTask(
            description = description,
            command = command,
            executeAt = nextRun,
            cronExpression = cronExpr,
            taskType = taskType,
            isRecurring = true,
            status = TaskStatus.PENDING,
            priority = priority,
            source = "cron_daemon"
        )
        taskMemory.addTask(task)
        updateState()
        Log.i(TAG, "Scheduled cron task ${task.id} '$description' with cron '$cronExpr' for $nextRun")
        return task.id
    }

    /**
     * Attempts to cancel a specific task.
     * Returns `true` if the task was found and cancelled.
     */
    fun cancelTask(taskId: String): Boolean {
        val task = taskMemory.getTask(taskId) ?: return false
        if (task.status in setOf(
                TaskStatus.RUNNING,
                TaskStatus.QUEUED,
                TaskStatus.PENDING,
                TaskStatus.WAITING_FOR_USER
            )
        ) {
            taskMemory.markCancelled(taskId)
            Log.i(TAG, "Cancelled task $taskId")
            updateState()
            return true
        }
        return false
    }

    fun resumeTask(taskId: String, userInput: String): Boolean {
        val resumed = taskMemory.resumeWaitingTask(taskId, userInput)
        if (resumed) {
            updateState()
            Log.i(TAG, "Resumed task $taskId with user input")
        }
        return resumed
    }

    /**
     * Cancels all queued and running tasks.
     */
    fun cancelAllTasks() {
        val tasks = taskMemory.getTasksByStatus(TaskStatus.QUEUED) +
                taskMemory.getTasksByStatus(TaskStatus.RUNNING) +
                taskMemory.getTasksByStatus(TaskStatus.PENDING) +
                taskMemory.getTasksByStatus(TaskStatus.WAITING_FOR_USER)
        tasks.forEach { task ->
            taskMemory.markCancelled(task.id)
        }
        commandQueue.clear()
        updateState()
        Log.i(TAG, "Cancelled all tasks (${tasks.size} affected)")
    }

    /** Returns the number of tasks currently in QUEUED status. */
    fun getQueueSize(): Int =
        taskMemory.getTasksByStatus(TaskStatus.QUEUED).size

    /** Returns `true` if a task is currently being executed. */
    fun isTaskRunning(): Boolean = currentTaskId != null

    /** Returns a human-readable description of the currently running task, if any. */
    fun getCurrentTaskDescription(): String? {
        val id = currentTaskId ?: return null
        return taskMemory.getTask(id)?.command
    }

    // ── Engine loops ────────────────────────────────────────────────────

    /**
     * Main engine loop. Continuously checks for due scheduled tasks,
     * drains queued commands, picks the highest-priority queued task,
     * and executes it.
     */
    private suspend fun engineLoop() {
        Log.d(TAG, "Engine loop started")
        while (isRunning.get()) {
            try {
                // 1. Promote any due scheduled tasks → QUEUED
                val dueTasks = taskMemory.getDueTasks()
                dueTasks.forEach { task ->
                    taskMemory.updateTaskStatus(task.id, TaskStatus.QUEUED)
                    Log.d(TAG, "Scheduled task ${task.id} is now due — moved to QUEUED")
                }

                // 2. Pick the highest-priority queued task
                val queuedTasks = taskMemory.getTasksByStatus(TaskStatus.QUEUED)
                    .sortedByDescending { it.priority }

                val nextTask = queuedTasks.firstOrNull()
                if (nextTask != null) {
                    updateState(currentTask = nextTask)
                    executeTask(nextTask)
                }
            } catch (e: CancellationException) {
                throw e  // always re-throw cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Error in engine loop iteration", e)
            }

            updateState()
            delay(ENGINE_LOOP_DELAY_MS)
        }
        Log.d(TAG, "Engine loop exited")
    }

    /**
     * Scheduler loop. Every 30 seconds, scans for tasks whose
     * [AgentTask.executeAt] has passed and moves them to QUEUED.
     */
    private suspend fun schedulerLoop() {
        Log.d(TAG, "Scheduler loop started")
        while (isRunning.get()) {
            try {
                val dueTasks = taskMemory.getDueTasks()
                dueTasks.forEach { task ->
                    taskMemory.updateTaskStatus(task.id, TaskStatus.QUEUED)
                    Log.d(TAG, "Scheduler: promoted task ${task.id} to QUEUED")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in scheduler loop", e)
            }

            delay(SCHEDULER_LOOP_DELAY_MS)
        }
        Log.d(TAG, "Scheduler loop exited")
    }

    // ── Task execution ──────────────────────────────────────────────────

    /**
     * Executes a single [AgentTask]:
     * 1. Marks it as RUNNING.
     * 2. Waits for [AgentViewModel.instance] (up to 10 s).
     * 3. Delegates to [AgentViewModel.executeBackgroundTask].
     * 4. Handles success, retries, and failure.
     *
     * @return `true` if the task completed successfully.
     */
    private suspend fun executeTask(task: AgentTask): Boolean {
        val internalTask = task.command.startsWith("__INTERNAL_MAINTENANCE_")
        val viewModel = if (internalTask) null else waitForViewModel()
        if (!internalTask && (viewModel == null || !viewModel.isReadyForBackgroundTasks())) {
            val retryAt = System.currentTimeMillis() + 30_000L
            taskMemory.deferTask(
                task.id,
                retryAt,
                if (viewModel == null) "Waiting for the agent runtime to initialize"
                else "Waiting for an AI model to finish loading"
            )
            Log.i(TAG, "Deferred task ${task.id} until the agent model is ready")
            updateState()
            return false
        }

        currentTaskId = task.id
        taskMemory.updateTaskStatus(task.id, TaskStatus.RUNNING)
        updateState(currentTask = task.copy(status = TaskStatus.RUNNING))
        Log.i(TAG, "Executing task ${task.id}: \"${task.command}\"")

        try {
            val result = if (task.command.startsWith("__INTERNAL_MAINTENANCE_")) {
                when (task.command) {
                    "__INTERNAL_MAINTENANCE_SYSTEM_CHECK__" -> maintenanceDaemon?.runSystemCheck() ?: "System check completed"
                    "__INTERNAL_MAINTENANCE_BACKUP__" -> maintenanceDaemon?.runAgentBackup() ?: "Backup deferred"
                    "__INTERNAL_MAINTENANCE_CACHE_CLEANUP__" -> maintenanceDaemon?.runCacheCleanup() ?: "Cleanup finished"
                    else -> "Internal routine finished"
                }
            } else if (task.taskType == "AGENT_TASK") {
                // Route complex agent tasks through the autonomous ReAct loop
                val activeViewModel = requireNotNull(viewModel)
                val orchestrator = com.synapse.localai.orchestration.MultiWorkerOrchestrator(
                    sessionId = task.id,
                    isCloudMode = activeViewModel.isCloudMode(),
                    modelInvoker = { sys, usr, onChunk -> activeViewModel.runSingleLlmPass(sys, usr, onChunk) },
                    onTelemetryUpdate = { _ -> },
                    onTerminalLogUpdate = { log -> Log.d(TAG, "Orchestrator: $log") }
                )
                orchestrator.executeAutonomousAgent(task.command)
            } else {
                requireNotNull(viewModel).executeBackgroundTask(task.command, task.id)
            }

            // Success
            taskMemory.markCompleted(task.id, result)
            completedCount++
            Log.i(TAG, "Task ${task.id} completed successfully")

            // Auto-reschedule if recurring or cron-based
            if (task.isRecurring || !task.cronExpression.isNullOrBlank()) {
                val nextRun = if (!task.cronExpression.isNullOrBlank()) {
                    CronExpressionParser.getNextExecutionTime(task.cronExpression!!, System.currentTimeMillis())
                } else {
                    System.currentTimeMillis() + 3600_000L
                }
                if (nextRun > 0L) {
                    val nextTask = task.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        status = TaskStatus.PENDING,
                        executeAt = nextRun,
                        retryCount = 0,
                        result = "",
                        completedAt = 0L,
                        executionCheckpoint = "",
                        progress = "",
                        lastProgressHash = "",
                        stalledCount = 0
                    )
                    taskMemory.addTask(nextTask)
                    Log.i(TAG, "Rescheduled recurring task ${task.description} for epoch $nextRun")
                }
            }
            return true

        } catch (e: CancellationException) {
            taskMemory.markFailed(task.id, "Cancelled")
            throw e
        } catch (e: TaskNeedsUserInputException) {
            taskMemory.pauseForUser(task.id, e.question)
            Log.i(TAG, "Task ${task.id} is waiting for user input")
            updateState()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Task ${task.id} failed", e)

            if (task.retryCount < task.maxRetries) {
                // Schedule a retry with exponential-ish back-off
                val retryDelay = (task.retryCount + 1) * 5000L
                val retryTask = task.copy(
                    retryCount = task.retryCount + 1,
                    status = TaskStatus.QUEUED,
                    executeAt = System.currentTimeMillis() + retryDelay
                )
                taskMemory.updateTask(retryTask)
                Log.w(
                    TAG,
                    "Task ${task.id} will retry (${retryTask.retryCount}/${task.maxRetries}) " +
                            "in ${retryDelay}ms"
                )
            } else {
                taskMemory.markFailed(task.id, e.message ?: "Unknown error")
                failedCount++
                Log.e(TAG, "Task ${task.id} exhausted retries — marked FAILED")
            }
            return false

        } finally {
            currentTaskId = null
            updateState()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Polls for [AgentViewModel.instance] up to [VIEWMODEL_WAIT_TIMEOUT_MS].
     * Returns the instance or `null` if it doesn't become available in time.
     */
    private suspend fun waitForViewModel(): AgentViewModel? {
        val deadline = System.currentTimeMillis() + VIEWMODEL_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            AgentViewModel.instance?.let { return it }
            delay(VIEWMODEL_POLL_INTERVAL_MS)
        }
        return AgentViewModel.instance
    }

    /** Publishes a fresh [EngineState] snapshot. */
    private fun updateState(currentTask: AgentTask? = null) {
        val resolvedTask = currentTask
            ?: currentTaskId?.let { taskMemory.getTask(it) }

        _engineState.value = EngineState(
            isRunning = isRunning.get(),
            currentTask = resolvedTask,
            queueSize = taskMemory.getTasksByStatus(TaskStatus.QUEUED).size,
            completedCount = completedCount,
            failedCount = failedCount
        )
    }
}
