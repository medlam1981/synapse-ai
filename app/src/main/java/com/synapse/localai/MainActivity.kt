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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
    private companion object { private const val TAG = "SynapseMain" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkStoragePermissions()
        checkBatteryOptimizations()
        
        setContent {
            SynapseAITheme {
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
fun SynapseAITheme(content: @Composable () -> Unit) {
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
            title = { Text("Synapse AI Capabilities 🧠", color = NeonCyan, fontWeight = FontWeight.Bold, modifier = Modifier.offset(1.dp, 1.dp)) },
            text = {
                Text(
                    "I am Synapse, your autonomous on-device Neural Agent.\n\n" +
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
            .background(DarkNavy)
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())

        // Header with logo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(DarkCard, MidNavy, DarkCard)
                    )
                )
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // New Super Android Logo
                Image(
                    painter = painterResource(id = R.drawable.super_android_logo),
                    contentDescription = "Synapse AI",
                    modifier = Modifier
                        .size(36.dp)
                        .shadow(elevation = 8.dp, shape = CircleShape, clip = false)
                        .offset(y = 2.dp),
                    colorFilter = ColorFilter.tint(Color.White),
                    contentScale = ContentScale.Fit
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(color = NeonPurple)) {
                                append("SYNAPSE ")
                            }
                            withStyle(style = SpanStyle(color = Color.White)) {
                                append("LOCAL LLM • OLLAMA SERVER")
                            }
                        },
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .basicMarquee()
                            .offset(x = 1.dp, y = 1.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    when {
                                        uiState.localModelLoaded -> Color(0xFF34D399)
                                        uiState.localModelLoading -> Color(0xFFFBBF24)
                                        else -> Color(0xFFEF4444)
                                    },
                                    RoundedCornerShape(3.dp)
                                )
                        )
                        Text(
                            text = when {
                                uiState.localModelLoading -> "Loading Model... ⏳"
                                uiState.localModelLoaded -> "${uiState.activeModelName} • ${uiState.activeModelType?.name ?: ""}"
                                else -> "No model loaded"
                            },
                            modifier = Modifier.basicMarquee(),
                            color = NeonCyan.copy(alpha = 0.7f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            Box {
                IconButton(
                    onClick = { expandedMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.5.dp)
                    ) {
                        repeat(4) {
                            Box(
                                modifier = Modifier
                                    .size(3.8.dp)
                                    .background(NeonPurple.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape)
                            )
                        }
                    }
                }
                DropdownMenu(
                    expanded = expandedMenu,
                    onDismissRequest = { expandedMenu = false },
                    modifier = Modifier.background(DarkCard)
                ) {
                    DropdownMenuItem(
                        text = { Text("⚙ Settings", color = Color.White) },
                        onClick = {
                            expandedMenu = false
                            showSettings = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("\uD83D\uDDD1 Clear Chat", color = Color(0xFFEF4444)) },
                        onClick = {
                            expandedMenu = false
                            viewModel.clearConversation(context)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("ℹ\uFE0F About Agent", color = NeonCyan) },
                        onClick = {
                            expandedMenu = false
                            showAboutDialog = true
                        }
                    )
                }
            }
        }

        // Subtle separator line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, NeonCyan.copy(alpha = 0.3f), NeonPurple.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )

        // Subtle separator line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, NeonCyan.copy(alpha = 0.3f), NeonPurple.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )

        // System Dashboard (Fixed)
        SystemDashboard(context = context)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val lastUserLog = uiState.logs.lastOrNull { it.sender == "USER" }
            val lastAgentLog = uiState.logs.lastOrNull { it.sender == "AGENT" }
            val fullText = lastAgentLog?.text ?: ""

            // Extract Thought Process
            val thinkContent = if (fullText.contains("<think>")) {
                val start = fullText.indexOf("<think>") + 7
                val end = fullText.indexOf("</think>")
                if (end != -1) fullText.substring(start, end).trim()
                else fullText.substring(start).trim()
            } else ""

            // Extract Actions/Results
            val executionContent = if (fullText.contains("</think>")) {
                fullText.substringAfter("</think>").trim()
            } else if (!fullText.contains("<think>") && fullText.isNotEmpty()) {
                fullText
            } else ""

            // --- BOX 1: EXECUTION & RESULTS ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2f) // Takes twice the space
                    .border(
                        BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.4f)))),
                        RoundedCornerShape(12.dp)
                    )
                    .background(MidNavy.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().offset(x = 1.dp, y = 1.dp)) {
                    // Fixed Header
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AnimatedAtom(isSpinning = uiState.isProcessing)
                        Text(if (uiState.isProcessing) "AGENT EXECUTION & RESULTS" else "STANDBY", color = NeonCyan.copy(alpha=0.8f), fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    // Scrollable Content
                    val scrollState = rememberScrollState()
                    LaunchedEffect(fullText) { scrollState.animateScrollTo(scrollState.maxValue) }
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                        if (thinkContent.isNotEmpty()) {
                            Text(
                                text = thinkContent,
                                color = Color.Gray.copy(alpha = 0.8f),
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 11.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        if (executionContent.isNotEmpty()) {
                            Text(
                                text = executionContent,
                                color = Color.White, 
                                fontFamily = FontFamily.SansSerif, 
                                fontSize = 12.sp, 
                                lineHeight = 18.sp
                            )
                        } else if (uiState.isProcessing && thinkContent.isEmpty()) {
                            Text(
                                text = "Analyzing data and generating response...",
                                color = Color.White.copy(alpha = 0.5f), 
                                fontFamily = FontFamily.SansSerif, 
                                fontSize = 12.sp, 
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            // --- BOX 2: USER COMMAND ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Takes less space
                    .border(
                        BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.2f)))),
                        RoundedCornerShape(12.dp)
                    )
                    .background(MidNavy.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().offset(x = 1.dp, y = 1.dp)) {
                    // Fixed Header
                    Text("USER COMMAND", color = NeonCyan.copy(alpha=0.6f), fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    // Scrollable Content
                    val scrollState = rememberScrollState()
                    Text(
                        text = lastUserLog?.text ?: "Waiting for input...", 
                        color = Color.White.copy(alpha = if (lastUserLog != null) 1f else 0.3f), 
                        fontFamily = FontFamily.SansSerif, 
                        fontSize = 11.sp, 
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                    )
                }
            }
        }

        // Input Area (isolated to prevent streaming recomposition lag)
        ChatInputBar(
            isProcessing = uiState.isProcessing,
            onSend = { text -> viewModel.sendCommand(text) },
            onStop = { viewModel.stopAgent() }
        )
    }
}

@Composable
private fun ChatInputBar(
    isProcessing: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val fileIndicator = "[File Attached: ${it.lastPathSegment ?: "unknown"}]"
            inputText = if (inputText.isBlank()) fileIndicator else "$inputText\n$fileIndicator"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, DarkNavy.copy(alpha = 0.95f), DarkNavy)
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        val engravedBorder = BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.6f),
                    Color.White.copy(alpha = 0.1f)
                )
            )
        )
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            placeholder = {
                Text(
                    "Ask me anything...",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 12.sp,
                    modifier = Modifier.offset(x = 1.dp, y = 1.dp)
                )
            },
            modifier = Modifier
                .weight(1f)
                .border(engravedBorder, RoundedCornerShape(24.dp)),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 11.sp,
                color = Color.White
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = NeonCyan,
                focusedContainerColor = Color.Black.copy(alpha = 0.2f),
                unfocusedContainerColor = Color.Black.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(24.dp),
            singleLine = false,
            maxLines = 3,
            leadingIcon = {
                IconButton(
                    onClick = { launcher.launch("*/*") },
                    modifier = Modifier.size(36.dp).offset(x = 1.dp, y = 1.dp)
                ) {
                    Text("📎", fontSize = 18.sp, color = NeonCyan.copy(alpha = 0.7f))
                }
            },
            trailingIcon = {
                Box(modifier = Modifier.padding(end = 4.dp).offset(x = 1.dp, y = 1.dp)) {
                    IconButton(
                        onClick = {
                            if (isProcessing) {
                                onStop()
                            } else if (inputText.isNotBlank()) {
                                onSend(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() || isProcessing,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(
                            if (isProcessing) "■" else "▶",
                            fontSize = 18.sp,
                            color = when {
                                isProcessing -> Color(0xFFFF3B30)
                                inputText.isNotBlank() -> NeonCyan
                                else -> NeonCyan.copy(alpha = 0.3f)
                            }
                        )
                    }
                }
            }
        )
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
        "AGENT" -> "Synapse"
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
        val displayOutput = if (log.sender == "AGENT" && fullText.contains("</think>")) {
            fullText.substringAfter("</think>").trim()
        } else if (log.sender == "AGENT" && fullText.contains("<think>")) {
            "" // Still thinking, nothing to show in history yet
        } else {
            fullText
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

fun getCpuTemp(): String {
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
        if (maxTempC > 0) return "${maxTempC}°C"
    } catch (e: Exception) {}
    return "--°C"
}

data class SystemStats(
    val battery: Int = 100,
    val isCharging: Boolean = false,
    val ramFree: String = "0GB",
    val storageFree: String = "0GB",
    val cpuTemp: String = "--°C",
    val cpuUsage: String = "0%"
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

    return SystemStats(
        battery = batLevel, 
        isCharging = isCharging,
        ramFree = ramFree, 
        storageFree = storageFree, 
        cpuTemp = getCpuTemp(),
        cpuUsage = if (AgentViewModel.instance?.uiState?.value?.isProcessing == true) "HIGH" else "IDLE"
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
            kotlinx.coroutines.delay(5000)
            stats = getSystemStats(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(
                BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.5f),
                            Color.White.copy(alpha = 0.08f)
                        )
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "SYNAPSE SYSTEM ACTIVE",
            color = NeonCyan,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        
        Text(
            "WELCOME TO SYNAPSE. YOUR PRIVATE NEURAL ENGINE IS ACTIVE. EXPERIENCE UNRIVALED ON-DEVICE INTELLIGENCE WITH ZERO CLOUD LATENCY. SYSTEM READY FOR COMPUTATION.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 9.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier.fillMaxWidth()
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val weight = 1f
            val iconSize = 10.dp
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
            Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(androidx.compose.material.icons.Icons.Filled.Storage, contentDescription = "Disk", tint = AccentGlow, modifier = Modifier.size(iconSize)) }, label = "DISK", value = stats.storageFree) }
            Box(Modifier.weight(weight)) { StatBadge(icon = { Icon(androidx.compose.material.icons.Icons.Filled.Thermostat, contentDescription = "Temperature", tint = Color(0xFFFF9F43), modifier = Modifier.size(iconSize)) }, label = "TEMP", value = stats.cpuTemp) }
        }
    }
}

@Composable
fun StatBadge(icon: @Composable () -> Unit, label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(12.dp))
            .fillMaxWidth()
            .border(
                BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.1f),
                            Color.Black.copy(alpha = 0.2f)
                        )
                    )
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .background(DarkCard)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon()
            Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}
