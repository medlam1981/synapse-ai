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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("Synapse AI Capabilities \uD83E\uDDE0", color = NeonCyan, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Synapse is an advanced, fully autonomous on-device Neural Agent.\n\n" +
                    "• ⚡ 100% Local Inference: Your data never leaves the device.\n" +
                    "• \uD83D\uDC41\uFE0F Visual Perception: Sees screen UI trees instantly.\n" +
                    "• \uD83E\uDD16 Autonomous Control: Operates your device like a human.\n" +
                    "• \uD83D\uDD11 Deep System Hooks: Custom IME and Accessibility tracking.\n" +
                    "• \uD83D\uDE80 Built for Speed: Utilizes mmap and advanced thread batching.",
                    color = Color.White,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("OK", color = NeonPurple)
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
                    modifier = Modifier.size(32.dp),
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
                        modifier = Modifier.basicMarquee()
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

        // System Dashboard
        SystemDashboard(context = context)

        // Chat Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(uiState.logs, key = { it.id }) { log ->
                val isLastAgentMessage = uiState.isProcessing && log.id == uiState.logs.lastOrNull { it.sender == "AGENT" }?.id
                ChatBubble(log, isLastAgentMessage = isLastAgentMessage)
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
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        "Ask me anything...",
                        color = Color.White.copy(alpha = 0.15f),
                        fontSize = 12.sp
                    )
                },
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.sp,
                    color = Color.White
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan.copy(alpha = 0.3f),
                    unfocusedBorderColor = NeonPurple.copy(alpha = 0.1f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = NeonCyan,
                    focusedContainerColor = MidNavy.copy(alpha = 0.5f),
                    unfocusedContainerColor = DarkCard.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(24.dp),
                singleLine = false,
                maxLines = 3,
                leadingIcon = {
                    IconButton(
                        onClick = { launcher.launch("*/*") },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("📎", fontSize = 18.sp, color = NeonCyan.copy(alpha = 0.7f))
                    }
                },
                trailingIcon = {
                    Box(modifier = Modifier.padding(end = 4.dp)) {
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
                                if (isProcessing) "\u25A0" else "\u25B6",
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
    
    val bubbleColor = when {
        isSystem -> Color.Transparent
        isUser -> MidNavy.copy(alpha = 0.6f)
        else -> Color.Transparent
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
            .background(bubbleColor, RoundedCornerShape(if (isUser) 12.dp else 0.dp))
            .padding(if (isUser) 12.dp else 0.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        Spacer(modifier = Modifier.height(4.dp))
        
        val fullText = log.text
        if (log.sender == "AGENT" && fullText.contains("<think>")) {
            val thinkStart = fullText.indexOf("<think>")
            val thinkEnd = fullText.indexOf("</think>")
            
            val beforeThink = fullText.substring(0, thinkStart).trim()
            val thinkContent = if (thinkEnd != -1) {
                fullText.substring(thinkStart + 7, thinkEnd).trim()
            } else {
                fullText.substring(thinkStart + 7).trim()
            }
            val afterThink = if (thinkEnd != -1) {
                fullText.substring(thinkEnd + 8).trim()
            } else {
                ""
            }

            if (beforeThink.isNotEmpty()) {
                Text(
                    text = beforeThink,
                    color = textColor,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            
            if (thinkContent.isNotEmpty() || thinkEnd == -1) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                        .border(1.dp, NeonPurple.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AnimatedAtom(isSpinning = isLastAgentMessage && thinkEnd == -1)
                            Text(
                                "Thought Process", 
                                color = NeonPurple.copy(alpha=0.7f), 
                                fontSize = 10.sp, 
                                fontWeight = FontWeight.Bold, 
                                letterSpacing = 1.sp
                            )
                        }
                        if (thinkContent.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = thinkContent,
                                color = Color.White.copy(alpha = 0.5f),
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 11.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
            
            if (afterThink.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = afterThink,
                    modifier = if (isLastAgentMessage) Modifier.semantics { invisibleToUser() } else Modifier,
                    color = textColor,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        } else {
            Text(
                text = log.text,
                modifier = if (isLastAgentMessage) Modifier.semantics { invisibleToUser() } else Modifier,
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
        val tempFile = java.io.File("/sys/class/thermal/thermal_zone0/temp")
        if (tempFile.exists()) {
            val tempRaw = tempFile.readText().trim().toIntOrNull() ?: 0
            val tempC = if (tempRaw > 1000) tempRaw / 1000 else tempRaw
            if (tempC in 1..150) return "${tempC}°C"
        }
    } catch (e: Exception) {}
    return "--°C"
}

data class SystemStats(
    val battery: Int = 100,
    val ramFree: String = "0GB",
    val storageFree: String = "0GB",
    val cpuTemp: String = "--°C",
    val cpuUsage: String = "0%"
)

fun getSystemStats(context: android.content.Context): SystemStats {
    // Battery
    val bm = context.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
    val batLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

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
            .background(DarkCard, RoundedCornerShape(12.dp))
            .border(1.dp, NeonCyan.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedAtom(isSpinning = false)
            Text(
                "SYNAPSE SYSTEM ACTIVE",
                color = NeonCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
        }
        
        Text(
            "WELCOME TO SYNAPSE. YOUR PRIVATE NEURAL ENGINE IS ACTIVE. EXPERIENCE UNRIVALED ON-DEVICE INTELLIGENCE WITH ZERO CLOUD LATENCY. SYSTEM READY FOR COMPUTATION.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 9.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            fontFamily = FontFamily.SansSerif
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val weight = 1f
            Box(Modifier.weight(weight)) { StatBadge(icon = { BatteryIcon(stats.battery) }, label = "PWR", value = "${stats.battery}%") }
            Box(Modifier.weight(weight)) { StatBadge(icon = { ChipIcon() }, label = "RAM", value = stats.ramFree) }
            Box(Modifier.weight(weight)) { StatBadge(icon = { CpuIcon() }, label = "CPU", value = stats.cpuUsage) }
            Box(Modifier.weight(weight)) { StatBadge(icon = { StorageIcon() }, label = "DISK", value = stats.storageFree) }
            Box(Modifier.weight(weight)) { StatBadge(icon = { TempIcon() }, label = "TEMP", value = stats.cpuTemp) }
        }
    }
}

@Composable
fun BatteryIcon(level: Int) {
    Canvas(modifier = Modifier.size(12.dp)) {
        val stroke = 1.dp.toPx()
        val w = size.width
        val h = size.height
        // Body
        drawRoundRect(
            color = if (level < 20) Color(0xFFEF4444) else NeonCyan.copy(alpha = 0.8f),
            size = androidx.compose.ui.geometry.Size(w * 0.85f, h * 0.6f),
            topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.2f),
            style = Stroke(width = stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        )
        // Tip
        drawRect(
            color = NeonCyan.copy(alpha = 0.8f),
            size = androidx.compose.ui.geometry.Size(w * 0.1f, h * 0.25f),
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.375f)
        )
        // Fill
        drawRect(
            color = if (level < 20) Color(0xFFEF4444) else NeonCyan.copy(alpha = 0.5f),
            size = androidx.compose.ui.geometry.Size((w * 0.75f) * (level / 100f), h * 0.4f),
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.05f, h * 0.3f)
        )
    }
}

@Composable
fun ChipIcon() {
    Canvas(modifier = Modifier.size(12.dp)) {
        val stroke = 0.8.dp.toPx()
        val s = size.width
        // Main square
        drawRect(
            color = NeonPurple.copy(alpha = 0.8f),
            style = Stroke(width = stroke),
            size = androidx.compose.ui.geometry.Size(s * 0.7f, s * 0.7f),
            topLeft = androidx.compose.ui.geometry.Offset(s * 0.15f, s * 0.15f)
        )
        // Pins
        val pinLen = s * 0.15f
        for (i in 0..2) {
            val pos = s * 0.3f + (i * s * 0.2f)
            // Top
            drawLine(NeonPurple.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(pos, 0f), androidx.compose.ui.geometry.Offset(pos, pinLen), stroke)
            // Bottom
            drawLine(NeonPurple.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(pos, s), androidx.compose.ui.geometry.Offset(pos, s - pinLen), stroke)
            // Left
            drawLine(NeonPurple.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(0f, pos), androidx.compose.ui.geometry.Offset(pinLen, pos), stroke)
            // Right
            drawLine(NeonPurple.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(s, pos), androidx.compose.ui.geometry.Offset(s - pinLen, pos), stroke)
        }
    }
}

@Composable
fun StorageIcon() {
    Canvas(modifier = Modifier.size(12.dp)) {
        val stroke = 0.8.dp.toPx()
        val w = size.width
        val h = size.height
        // Drive body
        drawRect(
            color = AccentGlow.copy(alpha = 0.8f),
            style = Stroke(width = stroke),
            size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.9f),
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.1f, 0f)
        )
        // Platters circle
        drawCircle(
            color = AccentGlow.copy(alpha = 0.4f),
            radius = w * 0.25f,
            center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.4f),
            style = Stroke(width = stroke)
        )
        // Arm
        drawLine(
            color = AccentGlow.copy(alpha = 0.8f),
            start = androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.8f),
            end = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f),
            strokeWidth = stroke
        )
    }
}

@Composable
fun TempIcon() {
    Canvas(modifier = Modifier.size(12.dp)) {
        val stroke = 1.dp.toPx()
        val w = size.width
        val h = size.height
        // Thermometer stem
        drawRoundRect(
            color = Color(0xFFFF9F43).copy(alpha = 0.8f),
            size = androidx.compose.ui.geometry.Size(w * 0.3f, h * 0.7f),
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.35f, 0f),
            style = Stroke(width = stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.15f)
        )
        // Bulb
        drawCircle(
            color = Color(0xFFFF9F43).copy(alpha = 0.8f),
            radius = w * 0.25f,
            center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.75f),
            style = Stroke(width = stroke)
        )
        // Inner fill
        drawCircle(
            color = Color(0xFFFF9F43).copy(alpha = 0.6f),
            radius = w * 0.12f,
            center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.75f)
        )
    }
}

@Composable
fun StatBadge(icon: @Composable () -> Unit, label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon()
            Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(value, color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}
