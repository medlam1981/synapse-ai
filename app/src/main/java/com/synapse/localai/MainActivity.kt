package com.synapse.localai

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.VpnKey // Icon for API Key Index
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

// Theme colors matching the brain logo
val NeonCyan     = Color(0xFF00D4FF)
val NeonPurple   = Color(0xFFBB44FF)
val NeonMagenta  = Color(0xFFFF44DD)
val DarkNavy     = Color(0xFF0A0E1A)
val DarkCard     = Color(0xFF0F1328)
val MidNavy      = Color(0xFF151A30)
val AccentGlow   = Color(0xFF3B82F6)

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val isError: Boolean = false
)

class MainActivity : ComponentActivity() {
    private val viewModel = AgentViewModel()
    
    companion object { 
        private const val TAG = "SynapseMain" 
        var instance: MainActivity? = null
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        
        checkStoragePermissions()
        checkBatteryOptimizations()
        
        setContent {
            MedlamAITheme {
                ChatApp(viewModel)
            }
        }
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivity(intent)
                }
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            }
        }
    }

    private fun checkBatteryOptimizations() {
        val intent = Intent()
        val packageName = packageName
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request battery optimization ignore", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveConversation(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w(TAG, "onTrimMemory level=$level")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // System is critically low on memory - trim conversation history aggressively
                viewModel.trimMemory(aggressive = true)
                System.gc()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                viewModel.trimMemory(aggressive = false)
            }
        }
    }
}

@Composable
fun MedlamAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonCyan,
            background = DarkNavy,
            surface = DarkCard,
            onPrimary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatApp(viewModel: AgentViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var expandedMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // --- Voice & File State ---
    var isRecording by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    val speechRecognizer = remember { android.speech.SpeechRecognizer.createSpeechRecognizer(context) }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedFileUri = uri
        if (uri != null) {
            viewModel.addSystemLog("📎 File attached: ${uri.lastPathSegment}")
        }
    }

    val speechRecognizerIntent = remember {
        Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Support ALL languages + Moroccan Darija specifically
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ar-MA")
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-MA")
            putExtra(android.speech.RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    DisposableEffect(Unit) {
        val listener = object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isRecording = false }
            override fun onError(error: Int) { isRecording = false }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    viewModel.sendCommand(text, selectedFileUri, context)
                    selectedFileUri = null
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)
        onDispose { speechRecognizer.destroy() }
    }

    LaunchedEffect(Unit) {
        viewModel.loadConfig(context)
    }

    // Auto-scroll to bottom during streaming without jitter
    LaunchedEffect(uiState.logs.size, uiState.streamVersion) {
        if (uiState.logs.isNotEmpty()) {
            val lastIndex = uiState.logs.size - 1
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            
            // Check if the user is reasonably close to the bottom (e.g., viewing the last 2 items)
            val isNearBottom = visibleItems.isEmpty() || (visibleItems.last().index >= lastIndex - 2)
            
            if (isNearBottom) {
                // Use scrollToItem instead of animateScrollToItem during fast streaming to prevent jitter
                listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
            }
        }
    }

    // Minimize app when agent interacts with the device
    LaunchedEffect(uiState.shouldMinimize) {
        if (uiState.shouldMinimize) {
            val activity = context as? android.app.Activity
            activity?.moveTaskToBack(true)
            viewModel.onMinimized()
        }
    }

    if (showSettings) {
        SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
        return
    }

    if (showAboutDialog) {
        val engravedBorder = BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.5f),
                    Color.White.copy(alpha = 0.08f)
                )
            )
        )
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            modifier = Modifier.border(engravedBorder, RoundedCornerShape(28.dp)),
            title = { Text("Medlam-AI Capabilities 🧠", color = NeonCyan, fontWeight = FontWeight.Bold, modifier = Modifier.offset(1.dp, 1.dp)) },
            text = {
                Text(
                    "I am Medlam-AI, your autonomous on-device Neural Agent.\n\n" +
                            "• 100% Local & Private Execution\n" +
                            "• Visual Screen Perception & Analysis\n" +
                            "• Multi-step Autonomous Task Planning\n" +
                            "• Rootless Device Control (Gestures & Text)\n" +
                            "• Ollama-Compatible Local Server",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.offset(1.dp, 1.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("AWESOME", color = NeonPurple)
                }
            },
            containerColor = DarkCard,
            titleContentColor = NeonCyan,
            textContentColor = Color.White
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070A)) // True dark terminal black
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())

        // --- MODERN FULL-WIDTH NEON HEADER ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(NeonCyan.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
                .background(DarkCard.copy(alpha = 0.95f))
                .padding(horizontal = 12.dp, vertical = 4.dp) // Reduced horizontal padding to push icon right
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnimatedAtom(isSpinning = uiState.isProcessing)
                    val textMeasurer = rememberTextMeasurer()
                    Column {
                        Text(
                            text = "MEDLAM-AI HYBRID HUB",
                            style = androidx.compose.ui.text.TextStyle(
                                color = Color.Transparent, // Transparent so the background shows through if we wanted, but we use Brush below
                                fontSize = 19.sp, // Larger font
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp,
                                drawStyle = androidx.compose.ui.graphics.drawscope.Fill
                            ),
                            modifier = Modifier.graphicsLayer(alpha = 0.9f)
                                .drawWithCache {
                                    onDrawWithContent {
                                        drawContent()
                                        // Create the engraved/inner shadow effect
                                        drawText(
                                            textMeasurer = textMeasurer,
                                            text = "MEDLAM-AI HYBRID HUB",
                                            style = androidx.compose.ui.text.TextStyle(
                                                color = Color.Black.copy(alpha = 0.8f),
                                                fontSize = 19.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = FontFamily.Monospace,
                                                letterSpacing = 2.sp
                                            ),
                                            topLeft = androidx.compose.ui.geometry.Offset(2f, 2f)
                                        )
                                        drawText(
                                            textMeasurer = textMeasurer,
                                            text = "MEDLAM-AI HYBRID HUB",
                                            style = androidx.compose.ui.text.TextStyle(
                                                brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.9f), NeonCyan.copy(alpha = 0.6f))),
                                                fontSize = 19.sp,
                                                fontWeight = FontWeight.Black,
                                                fontFamily = FontFamily.Monospace,
                                                letterSpacing = 2.sp
                                            ),
                                            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f)
                                        )
                                    }
                                }
                        )
                        Text(
                            text = "LOCAL OLLAMA SERVER • CLOUD NEURAL AGENT",
                            color = NeonCyan.copy(alpha = 0.8f),
                            fontSize = 7.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                
                Box {
                    IconButton(onClick = { expandedMenu = true }) {
                        Icon(androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = "Menu", tint = Color.White.copy(alpha = 0.5f))
                    }
                    DropdownMenu(
                        expanded = expandedMenu, 
                        onDismissRequest = { expandedMenu = false },
                        modifier = Modifier.background(DarkCard).border(0.5.dp, NeonCyan.copy(alpha = 0.2f))
                    ) {
                        DropdownMenuItem(text = { Text("⚙ System Settings", color = Color.White) }, onClick = { expandedMenu = false; showSettings = true })
                        DropdownMenuItem(text = { Text("🗑 Wipe Console", color = Color(0xFFEF4444)) }, onClick = { expandedMenu = false; viewModel.clearConversation(context) })
                    }
                }
            }
        }

        // System Dashboard (Fixed)
        SystemDashboard(context = context)

        if (uiState.scheduledTasks.isNotEmpty()) {
            ScheduledTasksTicker(tasks = uiState.scheduledTasks)
        }

        // --- CONSOLE STREAM ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .border(0.5.dp, Color.White.copy(alpha = 0.05f), RectangleShape)
        ) {
            val listState = rememberLazyListState()
            
            // Advanced auto-scroll that forces view to the bottom even after layout changes
            LaunchedEffect(uiState.logs.size, uiState.streamVersion) {
                if (uiState.logs.isNotEmpty()) {
                    kotlinx.coroutines.delay(50) // Give layout a moment to measure new text
                    listState.animateScrollToItem(index = uiState.logs.size) // Scroll past the last item to hit the very bottom padding
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(uiState.logs) { log ->
                    TerminalEntry(log)
                }
            }
            
            // Subtle Scanline Effect
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = 4.dp.toPx()
                for (y in 0 until size.height.toInt() step step.toInt()) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.015f),
                        start = androidx.compose.ui.geometry.Offset(0f, y.toFloat()),
                        end = androidx.compose.ui.geometry.Offset(size.width, y.toFloat()),
                        strokeWidth = 1f
                    )
                }
            }
        }

        // --- NEW: FIXED BOTTOM STATUS BAR (Thinking Indicator) ---
        if (uiState.isProcessing) {
            val infiniteTransition = rememberInfiniteTransition(label = "thinking_pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .border(0.5.dp, NeonPurple.copy(alpha = 0.3f), RectangleShape)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = NeonPurple,
                        strokeWidth = 1.5.dp
                    )
                    Text(
                        text = "Medlam-AI is thinking and executing tasks in the background...",
                        color = NeonPurple.copy(alpha = alpha),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // --- INPUT AREA ---
        ChatInputBar(
            isProcessing = uiState.isProcessing,
            isRecording = isRecording,
            selectedFileUri = selectedFileUri,
            onSend = { text -> 
                viewModel.sendCommand(text, selectedFileUri, context)
                selectedFileUri = null
            },
            onStop = { viewModel.stopAgent() },
            onRecordingClick = {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    (context as? ComponentActivity)?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 102)
                } else {
                    if (isRecording) {
                        speechRecognizer.stopListening()
                        isRecording = false
                    } else {
                        speechRecognizer.startListening(speechRecognizerIntent)
                        isRecording = true
                    }
                }
            },
            onFilePickerClick = {
                filePickerLauncher.launch("*/*")
            }
        )
    }
}

@Composable
private fun ChatInputBar(
    isProcessing: Boolean,
    isRecording: Boolean,
    selectedFileUri: Uri?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRecordingClick: () -> Unit,
    onFilePickerClick: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // Automatically clear focus when processing ends to prevent keyboard from popping up
    LaunchedEffect(isProcessing) {
        if (!isProcessing) {
            focusManager.clearFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp) // Clear spacing
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        "ENTER_COMMAND...",
                        color = Color.White.copy(alpha = 0.2f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 42.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.White
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    cursorColor = NeonCyan,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 3
            )

            // Custom Voice Input Button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) NeonMagenta.copy(alpha = 0.2f) else NeonPurple.copy(alpha = 0.1f))
                    .border(0.5.dp, if (isRecording) NeonMagenta.copy(alpha = 0.5f) else NeonPurple.copy(alpha = 0.3f), CircleShape)
                    .clickable { onRecordingClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Voice Input",
                    tint = if (isRecording) NeonMagenta else NeonPurple,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Custom File Upload Button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (selectedFileUri != null) NeonCyan.copy(alpha = 0.3f) else NeonCyan.copy(alpha = 0.1f))
                    .border(0.5.dp, if (selectedFileUri != null) NeonMagenta.copy(alpha = 0.6f) else NeonCyan.copy(alpha = 0.3f), CircleShape)
                    .clickable { onFilePickerClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = "Attach File",
                    tint = if (selectedFileUri != null) NeonMagenta else NeonCyan,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Custom Send/Stop Button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isProcessing) NeonMagenta.copy(alpha = 0.1f) else NeonCyan.copy(alpha = 0.1f))
                    .border(0.5.dp, if (isProcessing) NeonMagenta.copy(alpha = 0.3f) else NeonCyan.copy(alpha = 0.3f), CircleShape)
                    .clickable { 
                        if (isProcessing) onStop()
                        else if (inputText.isNotBlank() || selectedFileUri != null) {
                            onSend(inputText)
                            inputText = ""
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isProcessing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (isProcessing) "Stop" else "Send",
                    tint = if (isProcessing) NeonMagenta else NeonCyan,
                    modifier = Modifier.size(if (isProcessing) 14.dp else 18.dp)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun ChatBubble(log: LogEntry, isLastAgentMessage: Boolean = false) {
    val isUser = log.sender == "USER"
    val isSystem = log.sender == "SYSTEM"

    val engravedBorder = BorderStroke(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Black.copy(alpha = 0.4f),
                Color.White.copy(alpha = 0.08f)
            )
        )
    )

    val contentModifier = if (isUser) {
        Modifier
            .border(engravedBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    } else {
        Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
    }
    
    val textColor = when {
        log.isError -> Color(0xFFEF4444)
        isSystem -> Color.White.copy(alpha = 0.4f)
        isUser -> Color.White
        else -> Color.White.copy(alpha = 0.9f)
    }

    val labelColor = when {
        isUser -> NeonCyan.copy(alpha = 0.7f)
        isSystem -> Color.White.copy(alpha = 0.3f)
        else -> NeonPurple.copy(alpha = 0.8f)
    }
    
    val labelText = when (log.sender) {
        "USER" -> "You"
        "AGENT" -> "Medlam-AI"
        else -> "System"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(contentModifier)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, 
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = if (isUser) Modifier.offset(x = 1.dp, y = 1.dp) else Modifier
        ) {
            if (!isSystem) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(labelColor, androidx.compose.foundation.shape.CircleShape)
                )
            }
            Text(
                labelText,
                color = labelColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        
        val fullText = log.text
        var displayOutput = fullText

        // Hide raw tool execution codes, thinking processes, and plans from the UI
        if (log.sender == "AGENT") {
            var previous = ""
            while (displayOutput != previous) {
                previous = displayOutput
                displayOutput = displayOutput
                    .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<plan>.*?</plan>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<function_calls>.*?</function_calls>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<tool_code>.*?</tool_code>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<execute_bash>.*?</execute_bash>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<action>.*?</action>", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("```xml.*?```", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("```bash.*?```", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("```[a-zA-Z]*\\n.*?```", RegexOption.DOT_MATCHES_ALL), "")
                    .trim()
            }
            
            // Clean up standalone unclosed tags if stream is ongoing
            displayOutput = displayOutput.replace(Regex("<think>.*", RegexOption.DOT_MATCHES_ALL), "")
                                         .replace(Regex("<plan>.*", RegexOption.DOT_MATCHES_ALL), "")
                                         .replace(Regex("<function_calls>.*", RegexOption.DOT_MATCHES_ALL), "")
                                         .replace(Regex("```.*", RegexOption.DOT_MATCHES_ALL), "")
                                         .trim()

            // If the agent only output code/thoughts, show a generic processing message instead of empty bubble
            if (displayOutput.isEmpty() && fullText.isNotBlank()) {
                displayOutput = "⚡ Executing automated sequence..."
            }
        }

        if (displayOutput.isNotEmpty()) {
            Text(
                text = displayOutput,
                modifier = (if (isLastAgentMessage) Modifier.semantics { invisibleToUser() } else Modifier).then(if (isUser) Modifier.offset(x = 1.dp, y = 1.dp) else Modifier),
                color = textColor,
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                fontWeight = if (isUser) FontWeight.Medium else FontWeight.Normal,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
fun AnimatedAtom(isSpinning: Boolean = true) {
    val infiniteTransition = rememberInfiniteTransition(label = "atom_rotation")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle"
    )

    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 1.dp.toPx()
            val size = size.minDimension
            val rectSize = androidx.compose.ui.geometry.Size(size, size * 0.35f)
            val topLeft = androidx.compose.ui.geometry.Offset(0f, size * 0.325f)

            val colors = listOf(NeonCyan, NeonPurple, AccentGlow)
            val angles = listOf(0f, 120f, 240f)

            for (i in 0..2) {
                rotate(angles[i]) {
                    // Static orbit path
                    drawOval(
                        color = colors[i].copy(alpha = 0.2f),
                        style = Stroke(width = strokeWidth),
                        size = rectSize,
                        topLeft = topLeft
                    )

                    if (isSpinning) {
                        val currentAngle = angle + (i * 80f) // Offset each quark
                        // Quark with trail
                        for (j in 0..8) {
                            val trailAngle = currentAngle - (j * 6f)
                            val alpha = java.lang.Math.max(0f, 1f - (j * 0.12f))
                            val width = strokeWidth * java.lang.Math.max(0.1f, 1.8f - j * 0.15f)
                            drawArc(
                                color = colors[i].copy(alpha = alpha),
                                startAngle = trailAngle,
                                sweepAngle = 7f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = rectSize,
                                style = Stroke(width = width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = "AI",
            color = Color.White,
            fontSize = 6.sp,
            fontWeight = FontWeight.Light,
            fontFamily = FontFamily.SansSerif,
            style = androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = NeonCyan,
                    blurRadius = 8f
                )
            )
        )
    }
}

fun getCpuTemp(): Int {
    try {
        var maxTempC = 0
        val dir = java.io.File("/sys/class/thermal/")
        if (dir.exists() && dir.isDirectory) {
            val zones = dir.listFiles { file -> file.name.startsWith("thermal_zone") }
            if (zones != null) {
                for (zone in zones) {
                    val tempFile = java.io.File(zone, "temp")
                    if (tempFile.exists()) {
                        try {
                            val tempRaw = tempFile.readText().trim().toIntOrNull() ?: continue
                            val tempC = if (tempRaw > 1000) tempRaw / 1000 else tempRaw
                            if (tempC in 25..110 && tempC > maxTempC) {
                                maxTempC = tempC
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        }
        return maxTempC
    } catch (e: Exception) {}
    return 0
}

data class SystemStats(
    val battery: Int = 100,
    val isCharging: Boolean = false,
    val ramFree: String = "0GB",
    val storageFree: String = "0GB",
    val cpuTemp: String = "--°C",
    val cpuTempInt: Int = 0,
    val cpuUsage: String = "0%",
    val currentKeyIndex: Int = 0,
    val totalKeys: Int = 0
)

fun getSystemStats(context: android.content.Context): SystemStats {
    // Battery
    val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
    val batLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    
    val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
    val batteryStatus = context.registerReceiver(null, ifilter)
    val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                     status == android.os.BatteryManager.BATTERY_STATUS_FULL

    // RAM
    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val memInfo = android.app.ActivityManager.MemoryInfo()
    am.getMemoryInfo(memInfo)
    val ramFree = String.format("%.1f GB", memInfo.availMem / (1024.0 * 1024.0 * 1024.0))

    // Storage
    val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
    val storageFree = String.format("%.1f GB", stat.availableBlocksLong * stat.blockSizeLong / (1024.0 * 1024.0 * 1024.0))

    // Reliable Temperature from Battery API (Standard Android)
    val batTempRaw = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
    val tempInt = batTempRaw / 10 
    val tempStr = if (tempInt > 0) "${tempInt}°C" else "N/A" // Display N/A if temperature is zero or less

    val vm = AgentViewModel.instance
    val curKey = vm?.uiState?.value?.currentKeyIndex ?: 0
    val totKeys = vm?.uiState?.value?.totalKeys ?: 0

    Log.d("SynapseStats", "Battery Level: $batLevel%")
    Log.d("SynapseStats", "Battery Temp Raw: $batTempRaw")
    Log.d("SynapseStats", "Calculated Temp Int: $tempInt°C")
    Log.d("SynapseStats", "Formatted Temp String: $tempStr")
    Log.d("SynapseStats", "Final SystemStats.cpuTemp: $tempStr")

    return SystemStats(
        battery = batLevel, 
        isCharging = isCharging,
        ramFree = ramFree, 
        storageFree = storageFree, 
        cpuTemp = tempStr,
        cpuTempInt = tempInt,
        cpuUsage = if (AgentViewModel.instance?.uiState?.value?.isProcessing == true) "HIGH" else "IDLE",
        currentKeyIndex = curKey,
        totalKeys = totKeys
    )
}

@Composable
fun CpuIcon() {
    Canvas(modifier = Modifier.size(12.dp)) {
        val stroke = 0.8.dp.toPx()
        val s = size.width
        // Core center
        drawCircle(
            color = NeonCyan.copy(alpha = 0.8f),
            radius = s * 0.2f,
            center = androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.5f),
            style = Stroke(width = stroke)
        )
        // Outer box
        drawRect(
            color = NeonCyan.copy(alpha = 0.4f),
            style = Stroke(width = stroke),
            size = androidx.compose.ui.geometry.Size(s * 0.8f, s * 0.8f),
            topLeft = androidx.compose.ui.geometry.Offset(s * 0.1f, s * 0.1f)
        )
        // Lines connecting
        drawLine(NeonCyan.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(s*0.5f, 0f), androidx.compose.ui.geometry.Offset(s*0.5f, s*0.3f), stroke)
        drawLine(NeonCyan.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(s*0.5f, s), androidx.compose.ui.geometry.Offset(s*0.5f, s*0.7f), stroke)
        drawLine(NeonCyan.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(0f, s*0.5f), androidx.compose.ui.geometry.Offset(s*0.3f, s*0.5f), stroke)
        drawLine(NeonCyan.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(s, s*0.5f), androidx.compose.ui.geometry.Offset(s*0.7f, s*0.5f), stroke)
    }
}

@Composable
fun SystemDashboard(context: android.content.Context) {
    var stats by remember { mutableStateOf(getSystemStats(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(15000) // Lower frequency to save energy
            stats = getSystemStats(context)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkCard.copy(alpha = 0.5f))
            .border(
                0.5.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)),
                RectangleShape
            )
            .padding(horizontal = 12.dp, vertical = 12.dp) // Adjusted padding
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "SYSTEM ONLINE. WELCOME TO THE NEXT EVOLUTION.",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    "I am Medlam-AI, your advanced hybrid interface. By fusing local computational power with cloud-scale reasoning, I provide seamless device control, intelligent data processing, and real-time automation. Your digital ecosystem has never been this smart. Awaiting input.",
                    color = NeonCyan.copy(alpha = 0.7f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Divider(color = Color.White.copy(alpha = 0.05f), thickness = 0.5.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp) // Tighter spacing
            ) {
                val weight = 1f
                val iconSize = 9.dp
                Box(Modifier.weight(weight)) { 
                    StatBadge(
                        icon = { 
                            Icon(
                                if (stats.isCharging) androidx.compose.material.icons.Icons.Filled.BatteryChargingFull else androidx.compose.material.icons.Icons.Filled.BatteryFull, 
                                contentDescription = "Battery", 
                                tint = if (stats.isCharging) NeonMagenta else NeonCyan,
                                modifier = Modifier.size(iconSize)
                            ) 
                        }, 
                        label = "PWR", 
                        value = "${stats.battery}%"
                    ) 
                }
                Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(androidx.compose.material.icons.Icons.Filled.Memory, contentDescription = "RAM", tint = NeonPurple, modifier = Modifier.size(iconSize)) }, label = "RAM", value = stats.ramFree) }
                Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(androidx.compose.material.icons.Icons.Filled.DeveloperBoard, contentDescription = "CPU", tint = NeonCyan, modifier = Modifier.size(iconSize)) }, label = "CPU", value = stats.cpuUsage) }
                Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(androidx.compose.material.icons.Icons.Filled.VpnKey, contentDescription = "Key", tint = NeonMagenta, modifier = Modifier.size(iconSize)) }, label = "KEY", value = "${stats.currentKeyIndex}/${stats.totalKeys}") }
                Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(androidx.compose.material.icons.Icons.Filled.Storage, contentDescription = "Disk", tint = AccentGlow, modifier = Modifier.size(iconSize)) }, label = "DISK", value = stats.storageFree) }
                
                val tempColor = when {
                    stats.cpuTempInt <= 0 -> Color(0xFFFF9F43) 
                    stats.cpuTempInt < 45 -> NeonCyan 
                    stats.cpuTempInt < 65 -> Color(0xFFFF9F43) 
                    else -> Color(0xFFFF3B30) 
                }
                Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(androidx.compose.material.icons.Icons.Filled.Thermostat, contentDescription = "Temperature", tint = tempColor, modifier = Modifier.size(iconSize)) }, label = "TEMP", value = stats.cpuTemp) }
            }
        }
    }
}

@Composable
fun TerminalEntry(log: LogEntry) {
    // 1. SILENCE SYSTEM LOGS: Only show errors or specific success/info markers
    if (log.sender == "SYSTEM") {
        val isVisibleMarker = log.isError || log.text.startsWith("✅") || log.text.startsWith("📎")
        if (!isVisibleMarker) return
    }

    val prefix = when (log.sender) {
        "USER" -> ">> "
        "AGENT" -> "● "
        "SYSTEM" -> "!! "
        else -> ""
    }
    val color = when (log.sender) {
        "USER" -> NeonCyan
        "AGENT" -> NeonPurple
        "SYSTEM" -> if (log.isError) Color(0xFFEF4444) else Color(0xFF34D399)
        else -> Color.White
    }

    var displayOutput = log.text

    if (log.sender == "AGENT") {
        // 2. ULTRA-STRICT FILTER: If the message contains ANY technical tag, hide the entire entry.
        // We rely on the 'complete' tool's SYSTEM log to show the final human-readable result.
        val technicalMarkers = listOf("<think", "<plan", "<function", "<tool", "<execute", "<action", "```")
        if (technicalMarkers.any { displayOutput.contains(it) }) {
            return 
        }
        
        // Clean up remaining small artifacts like long URLs
        displayOutput = displayOutput.replace(Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE), "[LINK]").trim()
    }

    // Don't render empty entries or purely whitespace messages
    if (displayOutput.isBlank()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                    append(prefix)
                }
                append(displayOutput)
            },
            color = if (log.sender == "AGENT") Color.White.copy(alpha = 0.9f) else color.copy(alpha = 0.8f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 14.sp
        )
    }
}

@Composable
fun StatBadge(icon: @Composable () -> Unit, label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon()
            Text(label, color = Color.White.copy(alpha = 0.4f), fontSize = 7.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Text(value, color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ScheduledTasksTicker(tasks: List<ScheduledTask>) {
    val pendingTasks = tasks.filter { it.status == "pending" }.sortedBy { it.executeAt }
    if (pendingTasks.isEmpty()) return
    
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    val tickerText = pendingTasks.joinToString("   •   ") { "⏳ ${fmt.format(java.util.Date(it.executeAt))} - ${it.description}" }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .border(0.5.dp, Brush.verticalGradient(listOf(Color.Transparent, NeonPurple.copy(alpha = 0.2f))), RectangleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = tickerText,
            color = NeonCyan,
            fontSize = 9.sp,
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
