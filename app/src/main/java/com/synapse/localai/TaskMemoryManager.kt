package com.synapse.localai

import android.content.Context
import android.util.AtomicFile
import com.synapse.localai.AtlasLog as Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.UUID

enum class TaskStatus {
    PENDING,
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_FOR_USER,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val command: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val executeAt: Long = 0L,
    val completedAt: Long = 0L,
    val result: String = "",
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val parentTaskId: String? = null,
    val priority: Int = 0,
    val source: String = "user",
    val cronExpression: String? = null,
    val taskType: String = "COMMAND",
    val isRecurring: Boolean = false,
    val executionCheckpoint: String = "",
    val progress: String = "",
    val lastProgressHash: String = "",
    val stalledCount: Int = 0,
    val acceptanceCriteria: List<String> = emptyList(),
    val verificationReport: String = "",
    val verificationAttempts: Int = 0,
    val criteriaVerified: Boolean = false,
    val statusBeforePause: TaskStatus? = null
)

internal fun canMarkTaskCompleted(task: AgentTask): Boolean =
    if (task.taskType == "AGENT_TASK") {
        task.acceptanceCriteria.isNotEmpty() && task.criteriaVerified
    } else {
        task.acceptanceCriteria.isEmpty() || task.criteriaVerified
    }

class TaskNeedsUserInputException(val question: String) : Exception(question)

class TaskMemoryManager(context: Context) {

    companion object {
        private const val TAG = "TaskMemory"
    }

    private val tasksFile = File(context.filesDir, "agent_tasks.json")
    private val historyFile = File(context.filesDir, "task_history.json")
    private val tasks = Collections.synchronizedList(mutableListOf<AgentTask>())
    private val taskHistory = Collections.synchronizedList(mutableListOf<AgentTask>())

    init {
        loadTasks()
        loadHistory()
    }

    fun addTask(task: AgentTask): AgentTask {
        synchronized(tasks) {
            tasks.add(task)
            saveTasks()
        }
        Log.d(TAG, "Task added: ${task.id} - ${task.description}")
        return task
    }

    fun updateTask(id: String, update: (AgentTask) -> AgentTask) {
        synchronized(tasks) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index != -1) {
                val updated = update(tasks[index])
                tasks[index] = updated
                saveTasks()
                Log.d(TAG, "Task updated: $id -> ${updated.status}")
            } else {
                Log.w(TAG, "Task not found for update: $id")
            }
        }
    }

    private fun readAtomically(file: File): String? {
        if (!file.exists() && !File("${file.path}.bak").exists()) return null
        return AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun pauseForUser(id: String, question: String) {
        updateTask(id) { task ->
            task.copy(status = TaskStatus.WAITING_FOR_USER, progress = question)
        }
    }

    fun resumeWaitingTask(id: String, userInput: String): Boolean {
        if (userInput.isBlank()) return false
        var resumed = false
        updateTask(id) { task ->
            if (task.status != TaskStatus.WAITING_FOR_USER) return@updateTask task
            val history = try {
                JSONArray(task.executionCheckpoint)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot resume task with a corrupt checkpoint: $id", e)
                throw IllegalStateException("Task checkpoint is corrupt; it cannot be resumed safely.", e)
            }
            history.put(JSONObject().put("role", "user").put("content", "User clarification: ${userInput.take(4000)}"))
            resumed = true
            task.copy(
                status = TaskStatus.QUEUED,
                executeAt = 0L,
                executionCheckpoint = history.toString(),
                progress = "Resuming with user-provided clarification",
                stalledCount = 0,
                lastProgressHash = "",
                verificationAttempts = 0,
                verificationReport = "",
                criteriaVerified = false
            )
        }
        return resumed
    }

    fun removeTask(id: String) {
        synchronized(tasks) {
            val removed = tasks.removeAll { it.id == id }
            if (removed) {
                saveTasks()
                Log.d(TAG, "Task removed: $id")
            } else {
                Log.w(TAG, "Task not found for removal: $id")
            }
        }
    }

    fun getTask(id: String): AgentTask? {
        synchronized(tasks) {
            return tasks.find { it.id == id }
        }
    }

    fun getPendingTasks(): List<AgentTask> {
        synchronized(tasks) {
            return tasks
                .filter { it.status == TaskStatus.PENDING }
                .sortedWith(compareByDescending<AgentTask> { it.priority }.thenBy { it.executeAt })
                .toList()
        }
    }

    fun getQueuedTasks(): List<AgentTask> {
        synchronized(tasks) {
            return tasks.filter { it.status == TaskStatus.QUEUED }.toList()
        }
    }

    fun getRunningTasks(): List<AgentTask> {
        synchronized(tasks) {
            return tasks.filter { it.status == TaskStatus.RUNNING }.toList()
        }
    }

    fun recoverInterruptedTasks(): Int {
        val recovered = mutableListOf<AgentTask>()
        synchronized(tasks) {
            val iterator = tasks.listIterator()
            while (iterator.hasNext()) {
                val task = iterator.next()
                if (task.status != TaskStatus.RUNNING) continue
                val retry = task.copy(
                    status = TaskStatus.PENDING,
                    executeAt = System.currentTimeMillis() + 30_000L,
                    result = "Resuming from the last saved checkpoint after app process stopped",
                    progress = "Recovered interrupted task; resuming from its saved checkpoint"
                )
                iterator.set(retry)
                recovered.add(retry)
            }
            if (recovered.isNotEmpty()) saveTasks()
        }
        if (recovered.isNotEmpty()) {
            Log.i(TAG, "Recovered ${recovered.size} interrupted tasks without discarding their checkpoints")
        }
        return recovered.size
    }

    fun deferTask(id: String, retryAt: Long, reason: String) {
        updateTask(id) { task ->
            task.copy(status = TaskStatus.PENDING, executeAt = retryAt, result = reason)
        }
    }

    fun getDueTasks(): List<AgentTask> {
        val now = System.currentTimeMillis()
        synchronized(tasks) {
            return tasks
                .filter { it.status == TaskStatus.PENDING && (it.executeAt <= now || it.executeAt == 0L) }
                .sortedWith(compareByDescending<AgentTask> { it.priority }.thenBy { it.executeAt })
                .toList()
        }
    }

    fun getAllActiveTasks(): List<AgentTask> {
        synchronized(tasks) {
            return tasks
                .filter {
                    it.status in listOf(
                        TaskStatus.PENDING,
                        TaskStatus.QUEUED,
                        TaskStatus.RUNNING,
                        TaskStatus.PAUSED,
                        TaskStatus.WAITING_FOR_USER
                    )
                }
                .toList()
        }
    }

    fun getTasksByStatus(status: TaskStatus): List<AgentTask> {
        synchronized(tasks) {
            return tasks.filter { it.status == status }.toList()
        }
    }

    fun updateTaskStatus(id: String, newStatus: TaskStatus) {
        updateTask(id) { it.copy(status = newStatus) }
    }

    fun pauseTask(id: String): Boolean {
        var paused = false
        updateTask(id) { task ->
            if (task.status !in setOf(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.RUNNING)) {
                return@updateTask task
            }
            paused = true
            task.copy(status = TaskStatus.PAUSED, statusBeforePause = task.status)
        }
        return paused
    }

    fun resumePausedTask(id: String, executionActive: Boolean = false): Boolean {
        var resumed = false
        updateTask(id) { task ->
            if (task.status != TaskStatus.PAUSED) return@updateTask task
            val previous = task.statusBeforePause
            resumed = true
            task.copy(
                status = when {
                    previous == TaskStatus.RUNNING && executionActive -> TaskStatus.RUNNING
                    previous == TaskStatus.PENDING && task.executeAt > System.currentTimeMillis() -> TaskStatus.PENDING
                    else -> TaskStatus.QUEUED
                },
                statusBeforePause = null
            )
        }
        return resumed
    }

    fun updateTask(task: AgentTask) {
        updateTask(task.id) { task }
    }

    fun saveExecutionCheckpoint(
        id: String?,
        checkpoint: String,
        progress: String,
        progressHash: String? = null,
        stalledCount: Int? = null
    ) {
        if (id == null) return
        updateTask(id) { task ->
            task.copy(
                executionCheckpoint = checkpoint,
                progress = progress,
                lastProgressHash = progressHash ?: task.lastProgressHash,
                stalledCount = stalledCount ?: task.stalledCount
            )
        }
    }

    fun getTaskHistory(limit: Int = 20): List<AgentTask> {
        synchronized(taskHistory) {
            return taskHistory
                .sortedByDescending { it.completedAt }
                .take(limit)
                .toList()
        }
    }

    fun getTaskSummary(): String {
        val pending: Int
        val queued: Int
        val running: Int
        val waitingForUser: Int
        synchronized(tasks) {
            pending = tasks.count { it.status == TaskStatus.PENDING }
            queued = tasks.count { it.status == TaskStatus.QUEUED }
            running = tasks.count { it.status == TaskStatus.RUNNING }
            waitingForUser = tasks.count { it.status == TaskStatus.WAITING_FOR_USER }
        }
        val completed: Int
        val failed: Int
        synchronized(taskHistory) {
            completed = taskHistory.count { it.status == TaskStatus.COMPLETED }
            failed = taskHistory.count { it.status == TaskStatus.FAILED }
        }
        return "$pending pending, $queued queued, $running running, $waitingForUser waiting for user, $completed completed, $failed failed"
    }

    fun getContextForAI(): String {
        val sb = StringBuilder()
        sb.appendLine("=== TASK MEMORY ===")

        val active = getAllActiveTasks()
        if (active.isNotEmpty()) {
            sb.appendLine("Active Tasks (${active.size}):")
            active.forEach { task ->
                sb.appendLine("  [${task.status}] ${task.description} (id=${task.id.take(8)}, priority=${task.priority}, source=${task.source})")
                if (task.executeAt > 0 && task.executeAt > System.currentTimeMillis()) {
                    val delayMs = task.executeAt - System.currentTimeMillis()
                    val delaySec = delayMs / 1000
                    sb.appendLine("    Scheduled in ${delaySec}s")
                }
                if (task.retryCount > 0) {
                    sb.appendLine("    Retries: ${task.retryCount}/${task.maxRetries}")
                }
                if (task.progress.isNotBlank()) {
                    sb.appendLine("    Progress: ${task.progress.take(180)}")
                }
                if (task.acceptanceCriteria.isNotEmpty()) {
                    sb.appendLine(
                        "    Acceptance criteria (${if (task.criteriaVerified) "verified" else "pending"}): " +
                            task.acceptanceCriteria.joinToString("; ").take(300)
                    )
                }
                if (task.verificationReport.isNotBlank()) {
                    sb.appendLine("    Latest verification: ${task.verificationReport.take(400)}")
                }
            }
        } else {
            sb.appendLine("No active tasks.")
        }

        val history = getTaskHistory(5)
        if (history.isNotEmpty()) {
            sb.appendLine("Recent History:")
            history.forEach { task ->
                val statusIcon = if (task.status == TaskStatus.COMPLETED) "✓" else "✗"
                sb.appendLine("  $statusIcon ${task.description} — ${task.result.take(100)}")
                if (task.acceptanceCriteria.isNotEmpty()) {
                    sb.appendLine("    Criteria ${if (task.criteriaVerified) "verified" else "not verified"} (${task.acceptanceCriteria.size}); ${task.verificationReport.take(300)}")
                }
            }
        }

        sb.appendLine("Summary: ${getTaskSummary()}")
        sb.appendLine("=== END TASK MEMORY ===")
        return sb.toString()
    }

    fun markCompleted(id: String, result: String) {
        synchronized(tasks) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index != -1) {
                val task = tasks[index]
                if (!canMarkTaskCompleted(task)) {
                    throw IllegalStateException("Task cannot be marked complete before all acceptance criteria are verified.")
                }
                val completed = task.copy(
                    status = TaskStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                    result = result,
                    executionCheckpoint = "",
                    lastProgressHash = "",
                    stalledCount = 0
                )
                tasks.removeAt(index)
                saveTasks()
                synchronized(taskHistory) {
                    taskHistory.add(completed)
                    saveHistory()
                }
                Log.d(TAG, "Task completed: $id")
            } else {
                Log.w(TAG, "Task not found for completion: $id")
            }
        }
    }

    fun markFailed(id: String, error: String) {
        synchronized(tasks) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index != -1) {
                val task = tasks[index]
                if (task.retryCount < task.maxRetries) {
                    // Retry: increment count and set back to PENDING
                    val retried = task.copy(
                        status = TaskStatus.PENDING,
                        retryCount = task.retryCount + 1,
                        result = "Retry ${task.retryCount + 1}/${task.maxRetries}: $error"
                    )
                    tasks[index] = retried
                    saveTasks()
                    Log.d(TAG, "Task retrying (${retried.retryCount}/${retried.maxRetries}): $id")
                } else {
                    // Max retries reached, move to history
                    val failed = task.copy(
                        status = TaskStatus.FAILED,
                        completedAt = System.currentTimeMillis(),
                        result = error,
                        executionCheckpoint = "",
                        lastProgressHash = "",
                        stalledCount = 0
                    )
                    tasks.removeAt(index)
                    saveTasks()
                    synchronized(taskHistory) {
                        taskHistory.add(failed)
                        saveHistory()
                    }
                    Log.d(TAG, "Task failed (max retries): $id")
                }
            } else {
                Log.w(TAG, "Task not found for failure: $id")
            }
        }
    }

    fun markRunning(id: String) {
        updateTask(id) { it.copy(status = TaskStatus.RUNNING) }
    }

    fun markCancelled(id: String) {
        synchronized(tasks) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index != -1) {
                val task = tasks[index]
                val cancelled = task.copy(
                    status = TaskStatus.CANCELLED,
                    completedAt = System.currentTimeMillis(),
                    result = "Cancelled",
                    executionCheckpoint = "",
                    lastProgressHash = "",
                    stalledCount = 0
                )
                tasks.removeAt(index)
                saveTasks()
                synchronized(taskHistory) {
                    taskHistory.add(cancelled)
                    saveHistory()
                }
                Log.d(TAG, "Task cancelled: $id")
            } else {
                Log.w(TAG, "Task not found for cancellation: $id")
            }
        }
    }

    fun cleanOldHistory(maxAge: Long = 7 * 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAge
        synchronized(taskHistory) {
            val sizeBefore = taskHistory.size
            taskHistory.removeAll { it.completedAt in 1 until cutoff }
            val removed = sizeBefore - taskHistory.size
            if (removed > 0) {
                saveHistory()
                Log.d(TAG, "Cleaned $removed old history entries")
            }
        }
    }

    // ==================== Serialization ====================

    private fun saveTasks() {
        try {
            val jsonArray = JSONArray()
            synchronized(tasks) {
                tasks.forEach { task ->
                    jsonArray.put(taskToJson(task))
                }
            }
            writeAtomically(tasksFile, jsonArray.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving tasks", e)
            throw e
        }
    }

    private fun loadTasks() {
        try {
            val content = readAtomically(tasksFile) ?: run {
                Log.d(TAG, "No tasks file found, starting fresh")
                return
            }
            val trimmedContent = content.trim()
            if (trimmedContent.isEmpty()) {
                Log.d(TAG, "Tasks file is empty")
                return
            }
            val jsonArray = JSONArray(trimmedContent)
            synchronized(tasks) {
                tasks.clear()
                for (i in 0 until jsonArray.length()) {
                    try {
                        val task = jsonToTask(jsonArray.getJSONObject(i))
                        tasks.add(task)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping corrupt task entry at index $i: ${e.message}")
                    }
                }
            }
            Log.d(TAG, "Loaded ${tasks.size} active tasks")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading tasks: ${e.message}", e)
        }
    }

    private fun saveHistory() {
        try {
            val jsonArray = JSONArray()
            synchronized(taskHistory) {
                taskHistory.forEach { task ->
                    jsonArray.put(taskToJson(task))
                }
            }
            writeAtomically(historyFile, jsonArray.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving history", e)
            throw e
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val atomicFile = AtomicFile(file)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (e: Exception) {
            output?.let(atomicFile::failWrite)
            throw e
        }
    }

    private fun loadHistory() {
        try {
            val content = readAtomically(historyFile) ?: run {
                Log.d(TAG, "No history file found, starting fresh")
                return
            }
            val trimmedContent = content.trim()
            if (trimmedContent.isEmpty()) {
                Log.d(TAG, "History file is empty")
                return
            }
            val jsonArray = JSONArray(trimmedContent)
            synchronized(taskHistory) {
                taskHistory.clear()
                for (i in 0 until jsonArray.length()) {
                    try {
                        val task = jsonToTask(jsonArray.getJSONObject(i))
                        taskHistory.add(task)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping corrupt history entry at index $i: ${e.message}")
                    }
                }
            }
            Log.d(TAG, "Loaded ${taskHistory.size} history entries")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading history: ${e.message}", e)
        }
    }

    private fun taskToJson(task: AgentTask): JSONObject {
        return JSONObject().apply {
            put("id", task.id)
            put("description", task.description)
            put("command", task.command)
            put("status", task.status.name)
            put("createdAt", task.createdAt)
            put("executeAt", task.executeAt)
            put("completedAt", task.completedAt)
            put("result", task.result)
            put("retryCount", task.retryCount)
            put("maxRetries", task.maxRetries)
            put("parentTaskId", task.parentTaskId ?: JSONObject.NULL)
            put("priority", task.priority)
            put("source", task.source)
            put("cronExpression", task.cronExpression ?: JSONObject.NULL)
            put("taskType", task.taskType)
            put("isRecurring", task.isRecurring)
            put("executionCheckpoint", task.executionCheckpoint)
            put("progress", task.progress)
            put("lastProgressHash", task.lastProgressHash)
            put("stalledCount", task.stalledCount)
            put("acceptanceCriteria", JSONArray(task.acceptanceCriteria))
            put("verificationReport", task.verificationReport)
            put("verificationAttempts", task.verificationAttempts)
            put("criteriaVerified", task.criteriaVerified)
            put("statusBeforePause", task.statusBeforePause?.name ?: JSONObject.NULL)
        }
    }

    private fun jsonToTask(json: JSONObject): AgentTask {
        return AgentTask(
            id = json.getString("id"),
            description = json.getString("description"),
            command = json.getString("command"),
            status = try {
                TaskStatus.valueOf(json.getString("status"))
            } catch (e: IllegalArgumentException) {
                TaskStatus.PENDING
            },
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            executeAt = json.optLong("executeAt", 0L),
            completedAt = json.optLong("completedAt", 0L),
            result = json.optString("result", ""),
            retryCount = json.optInt("retryCount", 0),
            maxRetries = json.optInt("maxRetries", 3),
            parentTaskId = if (json.has("parentTaskId") && !json.isNull("parentTaskId")) json.getString("parentTaskId") else null,
            priority = json.optInt("priority", 0),
            source = json.optString("source", "user"),
            cronExpression = if (json.has("cronExpression") && !json.isNull("cronExpression")) json.getString("cronExpression") else null,
            taskType = json.optString("taskType", "COMMAND"),
            isRecurring = json.optBoolean("isRecurring", false),
            executionCheckpoint = json.optString("executionCheckpoint", ""),
            progress = json.optString("progress", ""),
            lastProgressHash = json.optString("lastProgressHash", ""),
            stalledCount = json.optInt("stalledCount", 0),
            acceptanceCriteria = json.optJSONArray("acceptanceCriteria")?.let { criteria ->
                List(criteria.length()) { index -> criteria.optString(index) }.filter { it.isNotBlank() }
            } ?: emptyList(),
            verificationReport = json.optString("verificationReport", ""),
            verificationAttempts = json.optInt("verificationAttempts", 0),
            criteriaVerified = json.optBoolean("criteriaVerified", false),
            statusBeforePause = json.optString("statusBeforePause").takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { TaskStatus.valueOf(it) }.getOrNull() }
        )
    }
}
