package com.synapse.localai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.UUID

enum class TaskStatus {
    PENDING,
    QUEUED,
    RUNNING,
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
    val isRecurring: Boolean = false
)

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
        val failed = mutableListOf<AgentTask>()
        synchronized(tasks) {
            val iterator = tasks.listIterator()
            while (iterator.hasNext()) {
                val task = iterator.next()
                if (task.status != TaskStatus.RUNNING) continue
                if (task.retryCount < task.maxRetries) {
                    val retry = task.copy(
                        status = TaskStatus.PENDING,
                        executeAt = System.currentTimeMillis() + 30_000L,
                        retryCount = task.retryCount + 1,
                        result = "Resuming after app process stopped"
                    )
                    iterator.set(retry)
                    recovered.add(retry)
                } else {
                    val interrupted = task.copy(
                        status = TaskStatus.FAILED,
                        completedAt = System.currentTimeMillis(),
                        result = "Stopped before completion after ${task.retryCount} retries"
                    )
                    iterator.remove()
                    failed.add(interrupted)
                }
            }
            if (recovered.isNotEmpty() || failed.isNotEmpty()) saveTasks()
        }
        if (failed.isNotEmpty()) {
            synchronized(taskHistory) {
                taskHistory.addAll(failed)
                saveHistory()
            }
        }
        if (recovered.isNotEmpty() || failed.isNotEmpty()) {
            Log.i(TAG, "Recovered ${recovered.size} interrupted tasks; archived ${failed.size} exhausted tasks")
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
                .filter { it.status in listOf(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.RUNNING) }
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

    fun updateTask(task: AgentTask) {
        updateTask(task.id) { task }
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
        synchronized(tasks) {
            pending = tasks.count { it.status == TaskStatus.PENDING }
            queued = tasks.count { it.status == TaskStatus.QUEUED }
            running = tasks.count { it.status == TaskStatus.RUNNING }
        }
        val completed: Int
        val failed: Int
        synchronized(taskHistory) {
            completed = taskHistory.count { it.status == TaskStatus.COMPLETED }
            failed = taskHistory.count { it.status == TaskStatus.FAILED }
        }
        return "$pending pending, $queued queued, $running running, $completed completed, $failed failed"
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
                val completed = task.copy(
                    status = TaskStatus.COMPLETED,
                    completedAt = System.currentTimeMillis(),
                    result = result
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
                        result = error
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
                    result = "Cancelled"
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
            tasksFile.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving tasks: ${e.message}", e)
        }
    }

    private fun loadTasks() {
        try {
            if (!tasksFile.exists()) {
                Log.d(TAG, "No tasks file found, starting fresh")
                return
            }
            val content = tasksFile.readText().trim()
            if (content.isEmpty()) {
                Log.d(TAG, "Tasks file is empty")
                return
            }
            val jsonArray = JSONArray(content)
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
            historyFile.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving history: ${e.message}", e)
        }
    }

    private fun loadHistory() {
        try {
            if (!historyFile.exists()) {
                Log.d(TAG, "No history file found, starting fresh")
                return
            }
            val content = historyFile.readText().trim()
            if (content.isEmpty()) {
                Log.d(TAG, "History file is empty")
                return
            }
            val jsonArray = JSONArray(content)
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
            isRecurring = json.optBoolean("isRecurring", false)
        )
    }
}
