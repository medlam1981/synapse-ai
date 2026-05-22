package com.synapse.localai

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synapse.localai.ui.theme.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

// --- CODE HIGHLIGHTING UTILITIES ---

@Composable
fun parseMarkdownWithCodeBlocks(text: String): List<AnnotatedString> {
    val result = mutableListOf<AnnotatedString>()
    val codeBlockRegex = Regex("```(?:[a-zA-Z]*\\n)?(.*?)```", RegexOption.DOT_MATCHES_ALL)
    
    var lastIndex = 0
    codeBlockRegex.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            result.add(AnnotatedString(text.substring(lastIndex, match.range.first)))
        }
        val code = match.groupValues[1]
        result.add(highlightCode(code))
        lastIndex = match.range.last + 1
    }
    
    if (lastIndex < text.length) {
        result.add(AnnotatedString(text.substring(lastIndex)))
    }
    
    return if (result.isEmpty() && text.isNotEmpty()) listOf(AnnotatedString(text)) else result
}

fun highlightCode(code: String): AnnotatedString {
    val builder = AnnotatedString.Builder(code)
    
    val keywords = Regex("\\b(val|var|fun|class|interface|object|if|else|when|for|while|return|import|package|def|function|let|const|break|continue|switch|case|default|public|private|protected|static|final|try|catch|finally|throw|throws)\\b")
    keywords.findAll(code).forEach { match ->
        builder.addStyle(SpanStyle(color = CodeKeyword, fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
    }
    
    val strings = Regex("\"(.*?)\"|'(.*?)'")
    strings.findAll(code).forEach { match ->
        builder.addStyle(SpanStyle(color = CodeString), match.range.first, match.range.last + 1)
    }
    
    val comments = Regex("//.*|/\\*.*?\\*/|#.*", RegexOption.DOT_MATCHES_ALL)
    comments.findAll(code).forEach { match ->
        builder.addStyle(SpanStyle(color = CodeComment), match.range.first, match.range.last + 1)
    }
    
    val numbers = Regex("\\b\\d+(\\.\\d+)?\\b")
    numbers.findAll(code).forEach { match ->
        builder.addStyle(SpanStyle(color = Color(0xFFD19A66)), match.range.first, match.range.last + 1)
    }

    return builder.toAnnotatedString()
}

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val isError: Boolean = false
)

class MainActivity : ComponentActivity() {
    private val viewModel = AgentViewModel()
    
    companion object {
        var instance: MainActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        setupGlobalCrashHandler()
        
        // coordinated startup
        lifecycleScope.launch {
            try {
                checkStoragePermissions()
                delay(1500)
                checkBatteryOptimizations()
            } catch (e: Exception) {
                logToCrashFile("Startup Permission Logic Error: ${e.message}")
            }
        }
        
        setContent {
            SynapseAITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatApp(viewModel)
                }
            }
        }
    }

    private fun setupGlobalCrashHandler() {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()
            
            Log.e("CRITICAL_CRASH", stackTrace)
            logToCrashFile("CRASH in thread ${thread.name}:\n$stackTrace")
            
            oldHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun logToCrashFile(message: String) {
        try {
            val dir = File("/sdcard/MedlamAI")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "crash_report.txt")
            val fos = FileOutputStream(file, true)
            fos.write("\n--- SYSTEM LOG AT ${java.util.Date()} ---\n".toByteArray())
            fos.write(message.toByteArray())
            fos.write("\n-------------------------------\n".toByteArray())
            fos.close()
        } catch (e: Exception) {
            Log.e("LOG_ERROR", "Failed to write to crash file: ${e.message}")
        }
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:" + applicationContext.packageName)
                startActivity(intent)
            }
        }
    }

    private fun checkBatteryOptimizations() {
        val intent = Intent()
        val packageName = packageName
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            intent.action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            viewModel.closeAllModels()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatApp(viewModel: AgentViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var expandedMenu by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> selectedFileUri = uri }
    )

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isRecording = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val text = matches[0]
                viewModel.sendCommand(text, selectedFileUri, context)
                selectedFileUri = null
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadConfig(context)
    }

    LaunchedEffect(uiState.logs.size, uiState.streamVersion) {
        if (uiState.logs.isNotEmpty()) {
            listState.animateScrollToItem(uiState.logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnimatedCdDisc(isSpinning = uiState.isProcessing)
                    Column {
                        Text(
                            text = "MEDLAM-AI HYBRID HUB",
                            style = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp
                            ),
                            modifier = Modifier.graphicsLayer(alpha = 0.9f)
                        )
                        Text(
                            text = "LOCAL OLLAMA SERVER • CLOUD NEURAL AGENT",
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            fontSize = 7.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                
                Box {
                    IconButton(onClick = { expandedMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    DropdownMenu(
                        expanded = expandedMenu, 
                        onDismissRequest = { expandedMenu = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface).border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    ) {
                        DropdownMenuItem(
                            text = { Text("⚙ System Settings", color = MaterialTheme.colorScheme.onSurface) },
                            onClick = { expandedMenu = false; showSettings = true }
                        )
                        DropdownMenuItem(
                            text = { Text("🗑 Wipe Console", color = MaterialTheme.colorScheme.error) },
                            onClick = { expandedMenu = false; viewModel.clearConversation(context) }
                        )
                    }
                }
            }
        }

        SystemDashboard(context)

        ScheduledTasksTicker(uiState.scheduledTasks)

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.logs, key = { it.id }) { log ->
                    AgentMessageBubble(log)
                }
            }
            
            Canvas(modifier = Modifier.fillMaxSize()) {
                val height = size.height
                val scanlineSpacing = 4.dp.toPx()
                for (y in 0..height.toInt() step scanlineSpacing.toInt()) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.015f),
                        start = androidx.compose.ui.geometry.Offset(0f, y.toFloat()),
                        end = androidx.compose.ui.geometry.Offset(size.width, y.toFloat()),
                        strokeWidth = 1f
                    )
                }
            }
        }

        ChatInputBar(
            isProcessing = uiState.isProcessing,
            isRecording = isRecording,
            selectedFileUri = selectedFileUri,
            onSend = { text -> 
                focusManager.clearFocus()
                viewModel.sendCommand(text, selectedFileUri, context)
                selectedFileUri = null
            },
            onStop = { viewModel.stopAgent() },
            onFilePickerClick = { filePickerLauncher.launch(arrayOf("*/*")) },
            onRecordingClick = {
                isRecording = true
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-MA")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Awaiting voice command...")
                }
                try {
                    speechLauncher.launch(intent)
                } catch (e: Exception) {
                    isRecording = false
                    Toast.makeText(context, "Speech recognition not available", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showSettings) {
        SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("Medlam-AI Capabilities 🧠", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "I am Medlam-AI, your autonomous on-device Neural Agent.\n\n" +
                            "• 100% Local & Private Execution\n" +
                            "• Visual Screen Perception & Analysis\n" +
                            "• Multi-step Autonomous Task Planning\n" +
                            "• Rootless Device Control (Gestures & Text)\n" +
                            "• Ollama-Compatible Local Server",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("AWESOME", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.primary,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun AgentMessageBubble(log: LogEntry) {
    val isUser = log.sender == "USER"
    val isSystem = log.sender == "SYSTEM"
    val clipboardManager = LocalClipboardManager.current
    
    if (isSystem) {
        val isVisibleMarker = log.isError || log.text.startsWith("✅") || log.text.startsWith("📎")
        if (!isVisibleMarker) return
    }

    var displayOutput = log.text
    var isCurrentlyThinking = false
    
    if (log.sender == "AGENT") {
        if (displayOutput.contains("<think>") && !displayOutput.contains("</think>")) {
            isCurrentlyThinking = true
        }

        // 1. Remove complete thinking blocks
        displayOutput = displayOutput.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        
        // 2. Hide ongoing thinking process during streaming
        if (displayOutput.contains("<think>")) {
             displayOutput = displayOutput.substringBefore("<think>")
        }

        // PROFESSOR FIX: Hide ALL XML tags (function calls, invokes, parameters) for a clean UI
        displayOutput = displayOutput.replace(Regex("<.*?>", RegexOption.DOT_MATCHES_ALL), "")
        
        // Remove ALL asterisks (markdown bold/italic markers) as requested
        displayOutput = displayOutput.replace("*", "")
        
        // Hide technical code blocks
        displayOutput = displayOutput.replace(Regex("```xml.*?```", RegexOption.DOT_MATCHES_ALL), "")
                                     .replace(Regex("```bash.*?```", RegexOption.DOT_MATCHES_ALL), "")
                                     .trim()
        
        if (displayOutput.isBlank() && isCurrentlyThinking) {
            displayOutput = "Medlam-AI is thinking..."
        }
    }

    if (displayOutput.isBlank()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isUser && !isSystem) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = NeonPurple,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = when {
                    isUser -> "You"
                    isSystem -> "System"
                    else -> "Medlam-AI"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isUser) NeonCyan else if (isSystem) MaterialTheme.colorScheme.outline else NeonPurple,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else if (isSystem) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .border(
                    width = 1.dp,
                    color = if (isUser) NeonCyan.copy(alpha = 0.3f)
                            else if (isSystem) MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            else NeonPurple.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .padding(12.dp)
        ) {
            Column {
                val annotatedParts = parseMarkdownWithCodeBlocks(displayOutput)
                annotatedParts.forEach { part ->
                    val isCode = part.spanStyles.any { it.item.color == CodeKeyword || it.item.color == CodeString }
                    
                    if (isCode) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = CodeBackground,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = part,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    ),
                                    color = Color(0xFFC9D1D9)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = part,
                            style = if (isCurrentlyThinking && displayOutput == "Medlam-AI is thinking...") 
                                MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic) 
                                else MaterialTheme.typography.bodyMedium,
                            color = if (log.isError) MaterialTheme.colorScheme.error 
                                else if (isCurrentlyThinking && displayOutput == "Medlam-AI is thinking...")
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                
                if (!isSystem && !isUser) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(displayOutput)) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnimatedCdDisc(isSpinning: Boolean = false) {
    val infiniteTransition = rememberInfiniteTransition(label = "cd_disc")
    val rotation by if (isSpinning) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val surfaceColor = MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .size(42.dp)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2

            rotate(rotation) {
                // Main disc surface with iridescent gradient
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color(0xFFE2E2E2), Color(0xFFFFCCCC), Color(0xFFE2E2E2),
                            Color(0xFFCCCCFF), Color(0xFFCCFFFF), Color(0xFFE2E2E2)
                        ),
                        center = center
                    ),
                    radius = radius
                )
                
                // Subtle holographic ring
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.3f), Color.Transparent),
                        center = center,
                        radius = radius * 0.8f
                    ),
                    radius = radius * 0.8f,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Outer border
            drawCircle(
                color = Color.White.copy(alpha = 0.4f),
                radius = radius,
                style = Stroke(width = 0.5.dp.toPx())
            )

            // Inner hole (Center)
            drawCircle(
                color = surfaceColor,
                radius = radius * 0.2f
            )
            
            // Inner plastic ring shadow
            drawCircle(
                color = Color.Black.copy(alpha = 0.2f),
                radius = radius * 0.2f,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

@Composable
fun SystemDashboard(context: android.content.Context) {
    var stats by remember { mutableStateOf(getSystemStats(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(15000)
            stats = getSystemStats(context)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "SYSTEM ONLINE • NEURAL INTERFACE ACTIVE",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val weight = 1f
                Box(Modifier.weight(weight)) { 
                    StatBadge(
                        icon = { Icon(if (stats.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull, null, tint = if (stats.isCharging) NeonMagenta else NeonCyan, modifier = Modifier.size(12.dp)) }, 
                        label = "PWR", 
                        value = "${stats.battery}%"
                    ) 
                }
                Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(Icons.Filled.Memory, null, tint = NeonPurple, modifier = Modifier.size(12.dp)) }, label = "RAM", value = stats.ramFree) }
                Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(Icons.Filled.DeveloperBoard, null, tint = NeonCyan, modifier = Modifier.size(12.dp)) }, label = "CPU", value = stats.cpuUsage) }
                Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(Icons.Filled.VpnKey, null, tint = NeonMagenta, modifier = Modifier.size(12.dp)) }, label = "KEY", value = "${stats.currentKeyIndex}/${stats.totalKeys}") }
                
                val tempColor = when {
                    stats.cpuTempInt <= 0 -> MaterialTheme.colorScheme.primary
                    stats.cpuTempInt < 45 -> NeonCyan 
                    stats.cpuTempInt < 65 -> Color(0xFFFF9F43) 
                    else -> MaterialTheme.colorScheme.error
                }
                Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(Icons.Filled.Thermostat, null, tint = tempColor, modifier = Modifier.size(12.dp)) }, label = "TMP", value = stats.cpuTemp) }
            }
        }
    }
}

@Composable
fun StatBadge(icon: @Composable () -> Unit, label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon()
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp))
        }
        Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBar(
    isProcessing: Boolean,
    isRecording: Boolean,
    selectedFileUri: Uri?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onFilePickerClick: () -> Unit,
    onRecordingClick: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        "Message Medlam-AI...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 4
            )

            IconButton(
                onClick = { onFilePickerClick() },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (selectedFileUri != null) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = "Attach",
                    tint = if (selectedFileUri != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = { onRecordingClick() },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Voice",
                    tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            IconButton(
                onClick = { 
                    if (isProcessing) onStop()
                    else if (inputText.isNotBlank() || selectedFileUri != null) {
                        onSend(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isProcessing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary),
                enabled = isProcessing || inputText.isNotBlank() || selectedFileUri != null
            ) {
                Icon(
                    imageVector = if (isProcessing) Icons.Filled.Stop else Icons.Filled.Send,
                    contentDescription = if (isProcessing) "Stop" else "Send",
                    tint = if (isProcessing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ScheduledTasksTicker(tasks: List<ScheduledTask>) {
    val pendingTasks = tasks.filter { t -> t.status == "pending" }.sortedBy { it.executeAt }
    if (pendingTasks.isEmpty()) return
    
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    val tickerText = pendingTasks.joinToString("   •   ") { "⏳ ${fmt.format(java.util.Date(it.executeAt))} - ${it.description}" }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
            .border(0.5.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f), RectangleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = tickerText,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                velocity = 35.dp
            )
        )
    }
}

data class SystemStats(
    val battery: Int,
    val isCharging: Boolean,
    val ramFree: String,
    val cpuUsage: String,
    val storageFree: String,
    val cpuTemp: String,
    val cpuTempInt: Int,
    val currentKeyIndex: Int,
    val totalKeys: Int
)

fun getSystemStats(context: android.content.Context): SystemStats {
    val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
    val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val status = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL

    val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val memoryInfo = android.app.ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val ramFree = String.format("%.1fGB", memoryInfo.availMem / 1024.0 / 1024.0 / 1024.0)

    val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
    val storageFree = String.format("%.0fGB", stat.availableBlocksLong * stat.blockSizeLong / 1024.0 / 1024.0 / 1024.0)

    val prefs = context.getSharedPreferences("synapse_config", android.content.Context.MODE_PRIVATE)
    val geminiKeys = prefs.getString("gemini_api_keys", "") ?: ""
    val openaiKeys = prefs.getString("openai_api_keys", "") ?: ""
    val preferredType = prefs.getString("preferred_model_type", "GEMINI")
    
    val keyList = if (preferredType == "OPENAI") openaiKeys else geminiKeys
    val totalKeys = if (keyList.isBlank()) 0 else keyList.split(",").size
    val currentKeyIndex = if (totalKeys > 0) (prefs.getInt("current_key_index", 0) + 1) else 0

    var maxTemp = 0
    try {
        // Scan multiple common CPU thermal zones to avoid static fallback values like 37C
        val zones = listOf(
            "/sys/class/thermal/thermal_zone16/temp",
            "/sys/class/thermal/thermal_zone17/temp",
            "/sys/class/thermal/thermal_zone18/temp",
            "/sys/class/thermal/thermal_zone7/temp",
            "/sys/class/thermal/thermal_zone0/temp"
        )
        for (path in zones) {
            val f = File(path)
            if (f.exists()) {
                val t = f.readText().trim().toIntOrNull() ?: 0
                val normalized = if (t > 1000) t / 1000 else t
                if (normalized in 20..100 && normalized > maxTemp) {
                    maxTemp = normalized
                }
            }
        }
    } catch (e: Exception) {
        Log.e("SYNAPSE_STATS", "Thermal read failed: ${e.message}")
    }

    var cpuUsage = "LOW"
    try {
        val loadFile = File("/proc/loadavg")
        if (loadFile.exists()) {
            val line = loadFile.readText().split(" ")
            if (line.isNotEmpty()) {
                val load = line[0].toDoubleOrNull() ?: 0.0
                val cores = Runtime.getRuntime().availableProcessors()
                val pct = ((load / cores) * 100).toInt().coerceIn(0, 100)
                cpuUsage = "$pct%"
            }
        }
    } catch (_: Exception) {}

    return SystemStats(
        battery = battery,
        isCharging = isCharging,
        ramFree = ramFree,
        cpuUsage = cpuUsage,
        storageFree = storageFree,
        cpuTemp = if (maxTemp > 0) "${maxTemp}°C" else "--",
        cpuTempInt = maxTemp,
        currentKeyIndex = currentKeyIndex,
        totalKeys = totalKeys
    )
}
