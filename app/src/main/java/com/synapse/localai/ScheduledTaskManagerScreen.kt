package com.synapse.localai

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.synapse.localai.ui.theme.surfaceContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Calendar

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTaskManagerScreen(viewModel: AgentViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var creatingTask by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.initTaskSystem(context.applicationContext)
        while (true) {
            viewModel.updateTaskState()
            delay(1500L)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(
                            "Scheduled tasks",
                            fontSize = 18.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1
                        )
                        Text(
                            "Manage your task queue",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { creatingTask = true },
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "Create task",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (uiState.activeTasks.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "No scheduled tasks",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Create a task to see it here.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            items(uiState.activeTasks, key = { it.id }) { task ->
                ScheduledTaskCard(task, viewModel)
            }
        }
    }
    if (creatingTask) {
        CreateScheduledTaskDialog(viewModel, onDismiss = { creatingTask = false })
    }
}

@Composable
private fun ScheduledTaskCard(task: AgentTask, viewModel: AgentViewModel) {
    var editing by remember(task.id) { mutableStateOf(false) }
    val statusColor = when (task.status) {
        TaskStatus.FAILED, TaskStatus.CANCELLED -> MaterialTheme.colorScheme.error
        TaskStatus.WAITING_FOR_USER -> MaterialTheme.colorScheme.secondary
        TaskStatus.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val whenText = if (task.executeAt > 0L) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(task.executeAt)
    } else {
        "When ready"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    task.description,
                    modifier = Modifier.weight(1f),
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Surface(
                    color = statusColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        task.status.name.lowercase().replace('_', ' '),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = statusColor,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
            Text(
                if (task.cronExpression.isNullOrBlank()) "Next run  ·  $whenText" else "Repeats  ·  ${task.cronExpression}",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.65f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    task.command,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3
                )
            }
            if (task.progress.isNotBlank()) {
                Text(
                    task.progress,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, androidx.compose.ui.Alignment.CenterHorizontally),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                if (task.status == TaskStatus.PAUSED) {
                    TextButton(onClick = { viewModel.resumeManagedTask(task.id) }) {
                        Text("Resume", fontSize = 12.sp)
                    }
                } else {
                    TextButton(
                        enabled = task.status in setOf(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.RUNNING),
                        onClick = { viewModel.pauseManagedTask(task.id) }
                    ) { Text("Pause", fontSize = 12.sp) }
                }
                TextButton(
                    enabled = task.status != TaskStatus.RUNNING,
                    onClick = { editing = true }
                ) { Text("Edit", fontSize = 12.sp) }
                TextButton(onClick = { viewModel.cancelManagedTask(task.id) }) {
                    Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (editing) {
        EditScheduledTaskDialog(task, viewModel, onDismiss = { editing = false })
    }
}

@Composable
private fun EditScheduledTaskDialog(
    task: AgentTask,
    viewModel: AgentViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var description by remember(task.id) { mutableStateOf(task.description) }
    var command by remember(task.id) { mutableStateOf(task.command) }
    var cron by remember(task.id) { mutableStateOf(task.cronExpression.orEmpty()) }
    var executeAt by remember(task.id) {
        mutableStateOf(task.executeAt.takeIf { it > System.currentTimeMillis() } ?: System.currentTimeMillis() + 60_000L)
    }

    val calendar = remember(task.id) { Calendar.getInstance().apply { timeInMillis = executeAt } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit scheduled task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, singleLine = true)
                OutlinedTextField(command, { command = it }, label = { Text("Command") })
                OutlinedTextField(
                    cron,
                    { cron = it },
                    label = { Text("Cron recurrence (optional)") },
                    supportingText = { Text("Leave empty for a one-time execution.") },
                    singleLine = true
                )
                Button(onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            calendar.set(year, month, day)
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                                    calendar.set(Calendar.MINUTE, minute)
                                    calendar.set(Calendar.SECOND, 0)
                                    executeAt = calendar.timeInMillis
                                },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                true
                            ).show()
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH)
                    ).show()
                }) {
                    Text("Run at ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(executeAt)}")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val saved = viewModel.updateManagedTask(task.id, description, command, executeAt, cron)
                if (saved) onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CreateScheduledTaskDialog(
    viewModel: AgentViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var description by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var cron by remember { mutableStateOf("") }
    var executeAt by remember { mutableStateOf(System.currentTimeMillis() + 60_000L) }
    val calendar = remember { Calendar.getInstance().apply { timeInMillis = executeAt } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create scheduled task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text("Task title") },
                    singleLine = true
                )
                OutlinedTextField(command, { command = it }, label = { Text("Command") })
                OutlinedTextField(
                    cron,
                    { cron = it },
                    label = { Text("Cron recurrence (optional)") },
                    supportingText = { Text("Leave empty for a one-time execution.") },
                    singleLine = true
                )
                Button(onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            calendar.set(year, month, day)
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                                    calendar.set(Calendar.MINUTE, minute)
                                    calendar.set(Calendar.SECOND, 0)
                                    calendar.set(Calendar.MILLISECOND, 0)
                                    executeAt = calendar.timeInMillis
                                },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                true
                            ).show()
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH)
                    ).show()
                }) {
                    Text("Run at ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(executeAt)}")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (viewModel.createManagedTask(description, command, executeAt, cron)) onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
