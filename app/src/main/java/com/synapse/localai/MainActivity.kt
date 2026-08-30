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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

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

/**
 * Extracts the language specifier from a fenced code block header line.
 * e.g. "```kotlin" -> "kotlin", "```html" -> "html"
 */
fun extractCodeBlocks(text: String): List<Triple<String, String, String>> {
    // Returns list of Triple(language, code, rawMatch)
    val result = mutableListOf<Triple<String, String, String>>()
    val regex = Regex("```([a-zA-Z0-9]*)\\n?(.*?)```", RegexOption.DOT_MATCHES_ALL)
    regex.findAll(text).forEach { match ->
        val lang = match.groupValues[1].ifBlank { "text" }
        val code = match.groupValues[2]
        result.add(Triple(lang, code, match.value))
    }
    return result
}

/**
 * Interactive Collapsible Code Block component with Language Badge, Line Count, Target Path,
 * Syntax Highlighting, and floating Quick Copy button.
 */
@Composable
fun CollapsibleCodeBlock(language: String, code: String, filePath: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    val lineCount = code.lines().size
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val langColor = when (language.lowercase().trim()) {
        "kotlin", "kt", "java" -> Color(0xFF7BC8F6)
        "html", "htm", "xml" -> Color(0xFFE06C75)
        "css" -> Color(0xFF61AFEF)
        "js", "javascript", "ts", "typescript", "jsx", "tsx" -> Color(0xFFE5C07B)
        "python", "py" -> Color(0xFF98C379)
        "bash", "sh" -> Color(0xFF56B6C2)
        "json" -> Color(0xFFD19A66)
        else -> Color(0xFFABB2BF)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        color = CodeBackground,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.5.dp, Color(0xFF30363D))
    ) {
        Column {
            // Interactive Collapsed Header: Displays Language Tag, Line Count, Target Path, and toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Language badge
                    Surface(
                        color = langColor.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, langColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = language.uppercase().take(8),
                            color = langColor,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Line count
                    Text(
                        text = "$lineCount lines",
                        color = Color(0xFF8B949E),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )

                    // Target Path
                    if (!filePath.isNullOrBlank()) {
                        Text(
                            text = filePath,
                            color = Color(0xFF58A6FF),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            maxLines = 1
                        )
                    }
                }

                // Interactive Expand / View Code toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (expanded) "▲ Collapse" else "▼ View Code",
                        color = if (expanded) NeonCyan else NeonPurple,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            // Rich syntax-highlighted code content with Quick [Copy Code] floating button
            if (expanded) {
                Divider(color = Color(0xFF30363D), thickness = 0.5.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CodeBackground)
                ) {
                    Text(
                        text = highlightCode(code),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = Color(0xFFC9D1D9),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )

                    // Floating Quick [Copy Code] button in top-end corner
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clickable {
                                clipboardManager.setText(AnnotatedString(code))
                                copied = true
                            },
                        color = if (copied) Color(0xFF238636) else Color(0xFF21262D),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(0.5.dp, if (copied) Color(0xFF3FB950) else Color(0xFF30363D))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = "Copy Code",
                                tint = if (copied) Color.White else NeonCyan,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = if (copied) "Copied!" else "Copy Code",
                                color = if (copied) Color.White else NeonCyan,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * In-stream Thought Process card — renders <think>...</think> blocks as a
 * collapsible "Thought Process" card above the final response.
 */
@Composable
fun ThoughtProcessCard(thinkContent: String) {
    if (thinkContent.isBlank()) return
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = Color(0xFF1A1D2B),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("✦", color = NeonPurple, fontSize = 12.sp)
                    Text(
                        text = "Thought Process",
                        color = NeonPurple.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
                Text(
                    text = if (expanded) "▲ Collapse" else "▼ Expand",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                )
            }
            if (expanded) {
                Divider(color = NeonPurple.copy(alpha = 0.15f), thickness = 0.5.dp)
                Text(
                    text = thinkContent.trim(),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * Single static sparkle status indicator for the active generation phase.
 * Shows a Frosted Glass shimmer sweep with phase label.
 */
@Composable
fun PhaseStatusIndicator(phase: AgentActionPhase) {
    if (phase == AgentActionPhase.IDLE) return

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    val phaseLabel = when (phase) {
        AgentActionPhase.THINKING -> "Thinking..."
        AgentActionPhase.PLANNING -> "Planning..."
        AgentActionPhase.GENERATING_CODE -> "Generating code..."
        AgentActionPhase.WRITING_FILE -> "Writing file..."
        AgentActionPhase.EXECUTING_TOOL -> "Executing..."
        AgentActionPhase.COMPLETING -> "Completing..."
        AgentActionPhase.IDLE -> ""
    }

    Box(
        modifier = Modifier
            .wrapContentSize()
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF1A1F2B).copy(alpha = 0.90f),
                        Color(0xFF252A3B).copy(alpha = 0.90f)
                    )
                )
            )
            .border(
                width = 0.8.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        NeonPurple.copy(alpha = 0.5f),
                        NeonCyan.copy(alpha = 0.5f)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        // Shimmer sweep overlay
        Canvas(modifier = Modifier.matchParentSize()) {
            val sweep = Brush.horizontalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.0f),
                    Color.White.copy(alpha = 0.25f),
                    Color.White.copy(alpha = 0.90f),
                    Color.White.copy(alpha = 0.25f),
                    Color.White.copy(alpha = 0.0f)
                ),
                startX = size.width * shimmerOffset,
                endX = size.width * (shimmerOffset + 0.4f)
            )
            drawRect(brush = sweep)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("✦", color = NeonPurple, fontSize = 11.sp)
            Text(
                text = phaseLabel,
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            )
        }
    }
}

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)


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

    override fun onResume() {
        super.onResume()
        viewModel.updateServiceStatus(this)
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
            val dir = File("/sdcard/Medlam-Ai")
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

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SidebarContent(
                viewModel = viewModel,
                onCloseDrawer = { coroutineScope.launch { drawerState.close() } },
                onOpenSettings = { showSettings = true }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Spacer(modifier = Modifier.statusBarsPadding())

            val dynamicTelemetry = remember(uiState.activeModelType, uiState.activeModelName, uiState.config, uiState.tokensPerSecond, uiState.currentKeyIndex, uiState.totalKeys) {
                when (uiState.activeModelType) {
                    ModelType.GGUF -> {
                        val name = if (uiState.activeModelName.isNotBlank()) {
                            val clean = uiState.activeModelName.replace("-it", "").replace("-qat", "").replace("-UD", "").replace("-Q4_K_XL", " Q4").replace("-Q4_0", " Q4")
                            if (clean.length > 14) clean.take(14) + ".." else clean
                        } else "Gemma 12B"
                        val hasMtp = uiState.config.draftModelPath.isNotBlank()
                        val tpsStr = if (uiState.tokensPerSecond > 0f) " • ${uiState.tokensPerSecond.toInt()} T/s" else ""
                        "$name • ${if (hasMtp) "MTP ON" else "LOCAL"}$tpsStr"
                    }
                    ModelType.GEMINI -> {
                        val modelShort = uiState.config.geminiModelName.removePrefix("models/").removePrefix("gemini-").replace("-flash", " Flash").replace("-pro", " Pro")
                        "Gemini $modelShort • Key ${uiState.currentKeyIndex + 1}/${uiState.totalKeys.coerceAtLeast(1)}"
                    }
                    ModelType.OPENAI -> {
                        "${uiState.config.openAiModelName} • Key ${uiState.currentKeyIndex + 1}/${uiState.totalKeys.coerceAtLeast(1)}"
                    }
                    else -> "AUTONOMOUS ON-DEVICE NEURAL AGENT"
                }
            }

            // Modern Monochromatic Theme-Aware Top Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left side: Menu hamburger + MEDLAM AI Branding vertically centered
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Menu,
                                    contentDescription = "Sidebar Menu",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(start = 2.dp)
                        ) {
                            Text(
                                text = "MEDLAM AI",
                                style = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.5.sp
                                )
                            )
                            Text(
                                text = dynamicTelemetry,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    
                    // Right side: New Session frosted glass action button + Settings + Clear Chat
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            onClick = { viewModel.startNewSession() },
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "New Session",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "NEW CHAT",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        Surface(
                            onClick = { showSettings = true },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Surface(
                            onClick = { viewModel.clearConversation(context) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Clear Chat",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            SystemDashboard(context, dynamicTelemetry)

            ScheduledTasksTicker(uiState.scheduledTasks)
            
            // Active Tasks Bar from new task engine
            ActiveTasksBar(uiState.activeTasks, uiState.taskSummary, viewModel, context)

            LaunchedEffect(uiState.logs.size, uiState.currentPhase, uiState.isProcessing) {
                val totalCount = uiState.logs.size + if (uiState.isProcessing && uiState.currentPhase != AgentActionPhase.IDLE) 1 else 0
                if (totalCount > 0) {
                    listState.animateScrollToItem(totalCount - 1)
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (uiState.logs.isEmpty() && !uiState.isProcessing) {
                    WelcomeHub(onSelectPrompt = { prompt ->
                        viewModel.sendCommand(prompt, context = context)
                    })
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.logs, key = { it.id }) { log ->
                            AgentMessageBubble(log)
                        }

                        // In-stream Frosted Glass status badge placed directly inside the LazyColumn at the bottom
                        // with its own top padding (8.dp) so it NEVER overlaps any preceding message bubble!
                        if (uiState.isProcessing && uiState.currentPhase != AgentActionPhase.IDLE) {
                            item(key = "active_stream_phase_status") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp, start = 4.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    PhaseStatusIndicator(phase = uiState.currentPhase)
                                }
                            }
                        }
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
                            "• Neural Agent & Voice Synthesis",
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

/**
 * Detects if a text string contains Arabic script characters.
 */
fun isArabic(text: String): Boolean {
    return text.any { ch ->
        ch in '\u0600'..'\u06FF' || ch in '\u0750'..'\u077F' || ch in '\u08A0'..'\u08FF' || ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF'
    }
}

/**
 * FormattedTextParagraph — Clean styled markdown paragraph with native Arabic RTL support,
 * high-contrast title typography, and right-aligned numbered lists.
 */
@Composable
fun FormattedTextParagraph(
    text: String,
    isError: Boolean = false,
    isThinking: Boolean = false
) {
    val lines = text.lines()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lines.forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                return@forEach
            }

            val isArabicLine = isArabic(trimmed)
            val layoutDir = if (isArabicLine) LayoutDirection.Rtl else LayoutDirection.Ltr
            val textAlign = if (isArabicLine) TextAlign.End else TextAlign.Start

            // Check if this line is a header (###, ##, #, or starts with Title marker)
            val isHeader = trimmed.startsWith("#") || trimmed.startsWith("===") || (trimmed.endsWith(":") && trimmed.length < 60 && !trimmed.contains("http"))
            val cleanLine = trimmed.replace(Regex("^#+\\s*"), "")
                .replace("**", "")
                .replace("*", "")
                .replace("`", "")

            val isListItem = cleanLine.matches(Regex("^[0-9]+[.)]\\s+.*")) || cleanLine.startsWith("- ") || cleanLine.startsWith("• ")

            CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                if (isHeader) {
                    Text(
                        text = cleanLine,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = if (isArabicLine) FontFamily.Default else FontFamily.Monospace,
                            fontSize = 13.5.sp,
                            letterSpacing = if (isArabicLine) 0.sp else 0.5.sp
                        ),
                        color = NeonCyan,
                        textAlign = textAlign,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 2.dp)
                    )
                } else if (isListItem) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        horizontalArrangement = if (isArabicLine) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = cleanLine,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = if (isArabicLine) FontFamily.Default else FontFamily.Monospace
                            ),
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            textAlign = textAlign,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Text(
                        text = cleanLine,
                        style = if (isThinking)
                            MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, fontSize = 12.5.sp, lineHeight = 18.sp)
                        else
                            MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                        color = when {
                            isError -> MaterialTheme.colorScheme.error
                            isThinking -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun AgentMessageBubble(log: LogEntry) {
    val isUser = log.sender == "USER"
    val isSystem = log.sender == "SYSTEM"
    val clipboardManager = LocalClipboardManager.current
    
    if (isSystem) {
        val isVisibleMarker = log.isError || log.text.startsWith("✅") || log.text.startsWith("📎")
            || log.text.startsWith("⚠️") || log.text.startsWith("⏰") || log.text.startsWith("⚡")
        if (!isVisibleMarker) return
    }

    // ---- Extract thinking blocks before processing ----
    val thinkRegex = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
    val thinkContent = if (log.sender == "AGENT") {
        thinkRegex.findAll(log.text).joinToString("\n\n") { it.groupValues[1] }
    } else ""

    // Build display text with thinking stripped
    var displayOutput = log.text
    var isCurrentlyThinking = false

    if (log.sender == "AGENT") {
        if (displayOutput.contains("<think>") && !displayOutput.contains("</think>")) {
            isCurrentlyThinking = true
        }

        // 1. Remove complete thinking blocks (shown in ThoughtProcessCard instead)
        displayOutput = displayOutput.replace(thinkRegex, "")

        // 2. Hide ongoing thinking process during streaming
        if (displayOutput.contains("<think>")) {
             displayOutput = displayOutput.substringBefore("<think>")
        }

        // Hide ALL XML tags (function calls, invokes, parameters) for a clean UI
        displayOutput = displayOutput.replace(Regex("<.*?>", RegexOption.DOT_MATCHES_ALL), "")

        // Remove ALL asterisks (markdown bold/italic markers)
        displayOutput = displayOutput.replace("*", "")

        // Hide technical xml/bash code blocks (they're tool calls, not user-facing code)
        displayOutput = displayOutput.replace(Regex("```xml.*?```", RegexOption.DOT_MATCHES_ALL), "")
                                     .replace(Regex("```bash.*?```", RegexOption.DOT_MATCHES_ALL), "")
                                     .trim()

        if (displayOutput.isBlank() && isCurrentlyThinking) {
            displayOutput = "MEDLAM AI is thinking..."
        }
    }

    if (displayOutput.isBlank() && thinkContent.isBlank()) return

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
                    else -> "MEDLAM AI"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isUser) NeonCyan else if (isSystem) MaterialTheme.colorScheme.outline else NeonPurple,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ---- Thought Process Card (shown above main response for AGENT messages) ----
        if (log.sender == "AGENT" && thinkContent.isNotBlank()) {
            ThoughtProcessCard(thinkContent = thinkContent)
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (displayOutput.isNotBlank()) {
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
                    // ---- Render code blocks as rich interactive collapsible cards ----
                    if (log.sender == "AGENT" && displayOutput.contains("```")) {
                        val blocks = ToolExecutor.parseCodeBlocksWithPaths(displayOutput)
                        var searchIndex = 0
                        blocks.forEach { block ->
                            val blockStartIndex = displayOutput.indexOf(block.rawMatch, searchIndex)
                            if (blockStartIndex > searchIndex) {
                                val textBefore = displayOutput.substring(searchIndex, blockStartIndex).trim()
                                if (textBefore.isNotBlank()) {
                                    FormattedTextParagraph(text = textBefore, isError = log.isError)
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                            CollapsibleCodeBlock(
                                language = block.language,
                                code = block.code,
                                filePath = block.targetPath
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            searchIndex = if (blockStartIndex != -1) blockStartIndex + block.rawMatch.length else searchIndex
                        }
                        if (searchIndex < displayOutput.length) {
                            val textAfter = displayOutput.substring(searchIndex).trim()
                            if (textAfter.isNotBlank()) {
                                FormattedTextParagraph(text = textAfter, isError = log.isError)
                            }
                        }
                    } else {
                        // Regular message (non-code): formatted paragraph with Arabic RTL and styled titles
                        FormattedTextParagraph(
                            text = displayOutput,
                            isError = log.isError,
                            isThinking = isCurrentlyThinking
                        )
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
}


/**
 * SidebarContent — Modern Monochromatic Drawer showing Chronological Session History,
 * New Chat trigger, and Quick System Navigation.
 */
@Composable
fun SidebarContent(
    viewModel: AgentViewModel,
    onCloseDrawer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val sessions by viewModel.chatSessions.collectAsState()
    val activeSessionId by viewModel.currentSessionId.collectAsState()

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(310.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Spacer(modifier = Modifier.statusBarsPadding())

            // Header: MEDLAM AI Branding
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "✦",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Column {
                        Text(
                            text = "MEDLAM AI",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        )
                        Text(
                            text = "NEURAL SESSION HUB",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
                IconButton(onClick = onCloseDrawer) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Sidebar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // + New Chat Prominent Button
            Button(
                onClick = {
                    viewModel.startNewSession()
                    onCloseDrawer()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddComment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "+ New Session",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // Sessions Header
            Text(
                text = "CONVERSATION HISTORY (${sessions.size})",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Sessions List
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No saved conversations",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        val isSelected = session.id == activeSessionId
                        val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(session.updatedAt))

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.switchSession(session.id)
                                    onCloseDrawer()
                                },
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                0.5.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Column {
                                        Text(
                                            text = session.title,
                                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            maxLines = 1
                                        )
                                        Text(
                                            text = dateStr,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { viewModel.deleteSession(session.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))

            // Footer info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenSettings()
                        onCloseDrawer()
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "System Settings",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }

            Text(
                text = "📁 Root: /sdcard/Medlam-Ai/",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * WelcomeHub — Clean monochromatic welcome screen with quick-action prompt chips.
 */
@Composable
fun WelcomeHub(onSelectPrompt: (String) -> Unit) {
    val quickActions = listOf(
        Pair("⚡ Modern Web Application", "Create a complete, responsive modern web application with HTML, CSS, and JavaScript."),
        Pair("💻 Python Automation Script", "Write a robust Python automation script for file and system tasks."),
        Pair("📱 Inspect Device Diagnostics", "Inspect device hardware, memory, available storage, and performance status."),
        Pair("🛠 HTML5 Dashboard", "Build a clean, interactive HTML5 dashboard with dark mode and analytics cards."),
        Pair("🔍 Code Optimization", "Help me analyze, optimize, and refactor code for maximum performance.")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Monochromatic Emblem
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "✦",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "MEDLAM AI",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
        )

        Text(
            text = "Autonomous On-Device Neural Agent",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "QUICK ACTIONS",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            ),
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickActions.forEach { (label, prompt) ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectPrompt(prompt) },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SystemDashboard(context: android.content.Context, dynamicTelemetry: String = "") {
    var stats by remember { mutableStateOf(SystemStats(0, false, "--", "--", "--", "--", 0, 0, 0)) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Fetch immediate first load
            val initialStats = getSystemStats(context)
            stats = initialStats
            
            while (true) {
                kotlinx.coroutines.delay(3000)
                val newStats = getSystemStats(context)
                stats = newStats
            }
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
                    if (dynamicTelemetry.isNotBlank()) "$dynamicTelemetry • ONLINE" else "SYSTEM ONLINE • NEURAL INTERFACE ACTIVE",
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
                Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(Icons.Filled.DeveloperBoard, null, tint = NeonCyan, modifier = Modifier.size(12.dp)) }, label = "SYS CPU", value = stats.cpuUsage) }
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
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Slim Capsule input field with theme-aware background
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp, max = 100.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.5.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        decorationBox = { innerTextField ->
                            if (inputText.isEmpty()) {
                                Text(
                                    text = "Message MEDLAM AI...",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            // Circular Attachment (Clip) button
            Surface(
                onClick = { onFilePickerClick() },
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (selectedFileUri != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.5.dp, if (selectedFileUri != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = "Attach",
                        tint = if (selectedFileUri != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            // Circular Voice (Mic) button
            Surface(
                onClick = { onRecordingClick() },
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (isRecording) MaterialTheme.colorScheme.error.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(0.5.dp, if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isRecording) Icons.Filled.Mic else Icons.Outlined.Mic,
                        contentDescription = "Voice",
                        tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            // Circular Send / Stop (Airplane) button
            val canSend = inputText.isNotBlank() || selectedFileUri != null
            Surface(
                onClick = {
                    if (isProcessing) onStop()
                    else if (canSend) {
                        onSend(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = when {
                    isProcessing -> MaterialTheme.colorScheme.error
                    canSend -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                enabled = isProcessing || canSend
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isProcessing) Icons.Default.Stop else Icons.Default.Send,
                        contentDescription = if (isProcessing) "Stop" else "Send",
                        tint = when {
                            isProcessing -> MaterialTheme.colorScheme.onError
                            canSend -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ScheduledTasksTicker(tasks: List<ScheduledTask>) {
    val pendingTasks = tasks.filter { t -> t.status == "pending" }.sortedBy { it.executeAt }
    if (pendingTasks.isEmpty()) return
    
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val tickerText = pendingTasks.joinToString("   \u2022   ") { "\u23f3 ${fmt.format(Date(it.executeAt))} - ${it.description}" }
    
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ActiveTasksBar(tasks: List<AgentTask>, summary: String, viewModel: AgentViewModel, context: android.content.Context) {
    if (tasks.isEmpty() && summary.isBlank()) return
    
    var expanded by remember { mutableStateOf(false) }
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val scheduled = tasks.filter { it.status == TaskStatus.PENDING && it.executeAt > System.currentTimeMillis() }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF0D1B2A).copy(alpha = 0.9f),
                        Color(0xFF1B2838).copy(alpha = 0.9f)
                    )
                )
            )
            .border(0.5.dp, Color(0xFF00D4FF).copy(alpha = 0.3f), RectangleShape)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Summary line
        if (summary.isNotBlank()) {
            Text(
                text = "\u26a1 Tasks: $summary",
                color = Color(0xFF00D4FF),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
        }
        
        // Running tasks
        val running = tasks.filter { it.status == TaskStatus.RUNNING }
        running.forEach { task ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "\u25b6 Running: ${task.command.take(60)}",
                    color = Color(0xFF4CAF50),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.cancelTask(task.id) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, "Stop Task", tint = Color.Red, modifier = Modifier.size(18.dp))
                }
            }
        }
        
        if (scheduled.isNotEmpty()) {
            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("SCHEDULED TASKS", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Divider(color = NeonCyan.copy(alpha=0.3f), modifier = Modifier.padding(vertical = 4.dp))
                
                scheduled.forEach { task ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("\u23f0 ${fmt.format(Date(task.executeAt))}", color = Color(0xFFFFAB00), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(task.description, color = MaterialTheme.colorScheme.onSurface, fontSize = 9.sp, maxLines = 2)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { 
                                val cal = java.util.Calendar.getInstance()
                                cal.timeInMillis = task.executeAt
                                android.app.TimePickerDialog(
                                    context,
                                    { _, h, m ->
                                        val newCal = java.util.Calendar.getInstance()
                                        newCal.set(java.util.Calendar.HOUR_OF_DAY, h)
                                        newCal.set(java.util.Calendar.MINUTE, m)
                                        if (newCal.timeInMillis < System.currentTimeMillis()) {
                                            newCal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                                        }
                                        viewModel.updateScheduledTaskTime(task.id, newCal.timeInMillis)
                                    },
                                    cal.get(java.util.Calendar.HOUR_OF_DAY),
                                    cal.get(java.util.Calendar.MINUTE),
                                    true
                                ).show()
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.Edit, "Edit", tint = NeonCyan, modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { viewModel.deleteScheduledTask(task.id) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Filled.Delete, "Delete", tint = Color.Red, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            } else {
                val tickerText = scheduled.joinToString("   \u2022   ") { 
                    "\u23f0 ${fmt.format(Date(it.executeAt))} - ${it.description.take(30)}" 
                }
                Text(
                    text = tickerText,
                    color = Color(0xFFFFAB00),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        velocity = 30.dp
                    )
                )
            }
        }
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

private var lastCpuTotal: Long = 0
private var lastCpuIdle: Long = 0

fun getSystemStats(context: android.content.Context): SystemStats {
    val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
    val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val status = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL

    val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val memoryInfo = android.app.ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val ramFree = String.format("%.1fGB", memoryInfo.availMem / 1024.0 / 1024.0 / 1024.0)

    val statFs = android.os.StatFs(android.os.Environment.getDataDirectory().path)
    val storageFree = String.format("%.0fGB", statFs.availableBlocksLong * statFs.blockSizeLong / 1024.0 / 1024.0 / 1024.0)

    val prefs = context.getSharedPreferences("synapse_config", android.content.Context.MODE_PRIVATE)
    val geminiKeys = prefs.getString("gemini_api_keys", "") ?: ""
    val openaiKeys = prefs.getString("openai_api_keys", "") ?: ""
    val preferredType = prefs.getString("preferred_model_type", "GEMINI")
    
    val keyList = if (preferredType == "OPENAI") openaiKeys else geminiKeys
    val totalKeys = if (keyList.isBlank()) 0 else keyList.split(",").size
    val currentKeyIndex = if (totalKeys > 0) (prefs.getInt("current_key_index", 0) + 1) else 0
    var maxTemp = 0
    try {
        // Broad search for ANY thermal sensor that isn't a static fallback
        val thermalDir = File("/sys/class/thermal")
        val zones = thermalDir.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") } ?: emptyArray()
        
        for (zone in zones) {
            val typeFile = File(zone, "type")
            val tempFile = File(zone, "temp")
            if (tempFile.exists()) {
                val type = if (typeFile.exists()) typeFile.readText().lowercase() else ""
                val t = tempFile.readText().trim().toIntOrNull() ?: 0
                val normalized = if (t > 1000) t / 1000 else t
                
                // Prioritize CPU sensors, ignore static 37C if others exist
                if (normalized in 20..100) {
                    if (type.contains("cpu") || type.contains("tsens") || type.contains("cluster")) {
                        maxTemp = normalized
                        break // Found a dedicated CPU sensor
                    }
                    if (normalized > maxTemp && normalized != 37) {
                        maxTemp = normalized
                    }
                }
            }
        }
        // Final fallback if everything is 37 or missing
        if (maxTemp == 0 || maxTemp == 37) {
             val t0 = File("/sys/class/thermal/thermal_zone0/temp")
             if (t0.exists()) {
                 val t = t0.readText().trim().toIntOrNull() ?: 0
                 maxTemp = if (t > 1000) t / 1000 else t
             }
        }
    } catch (_: Exception) {}

    var cpuUsage = "0%"
    try {
        val statFile = File("/proc/stat")
        if (statFile.exists()) {
            val line = statFile.bufferedReader().use { it.readLine() }
            if (line != null && line.startsWith("cpu")) {
                val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (parts.size >= 5) {
                    val user = parts[1].toLong()
                    val nice = parts[2].toLong()
                    val system = parts[3].toLong()
                    val idle = parts[4].toLong()
                    val iowait = parts.getOrNull(5)?.toLong() ?: 0L
                    val irq = parts.getOrNull(6)?.toLong() ?: 0L
                    val softirq = parts.getOrNull(7)?.toLong() ?: 0L
                    
                    val currentTotal = user + nice + system + idle + iowait + irq + softirq
                    val currentIdle = idle + iowait
                    
                    if (lastCpuTotal > 0) {
                        val deltaTotal = currentTotal - lastCpuTotal
                        val deltaIdle = currentIdle - lastCpuIdle
                        if (deltaTotal > 0) {
                            val pct = (100 * (deltaTotal - deltaIdle) / deltaTotal).toInt().coerceIn(0, 100)
                            cpuUsage = "$pct%"
                        }
                    }
                    lastCpuTotal = currentTotal
                    lastCpuIdle = currentIdle
                }
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
