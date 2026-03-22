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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.graphics.RectangleShape
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
                    Column {
                        Text(
                            text = "SYNAPSE HYBRID HUB",
                            style = androidx.compose.ui.text.TextStyle(
                                brush = Brush.horizontalGradient(listOf(Color.White, NeonCyan)),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.5.sp
                            )
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
                
                if (uiState.isProcessing) {
                    item {
                        Text("● AGENT_REASONING_IN_PROGRESS...", color = NeonPurple.copy(alpha = 0.5f), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    }
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

        // --- INPUT AREA ---
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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

            if (isProcessing) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(40.dp)
                        .background(NeonPurple.copy(alpha = 0.1f), CircleShape)
                        .border(0.5.dp, NeonPurple.copy(alpha = 0.3f), CircleShape)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Stop,
                        contentDescription = "Stop",
                        tint = NeonPurple,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSend(inputText)
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(NeonCyan.copy(alpha = 0.1f), CircleShape)
                        .border(0.5.dp, NeonCyan.copy(alpha = 0.3f), CircleShape)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.PlayArrow,
                        contentDescription = "Send",
                        tint = NeonCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
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

    val tempInt = getCpuTemp()
    val tempStr = if (tempInt > 0) "${tempInt}°C" else "--°C"

    return SystemStats(
        battery = batLevel, 
        isCharging = isCharging,
        ramFree = ramFree, 
        storageFree = storageFree, 
        cpuTemp = tempStr,
        cpuTempInt = tempInt,
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkCard.copy(alpha = 0.5f))
            .border(
                0.5.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)),
                RectangleShape
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "CORE SYSTEM INITIALIZED",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Text(
                    "GREETINGS USER. I AM SYNAPSE, YOUR ADVANCED NEURAL INTERFACE. I AM CAPABLE OF AUTONOMOUS DEVICE NAVIGATION, LOCAL OLLAMA SERVING, AND SECURE DATA PROCESSING. ALL SYSTEMS OPERATIONAL.",
                    color = NeonCyan.copy(alpha = 0.6f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Divider(color = Color.White.copy(alpha = 0.05f), thickness = 0.5.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
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

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) {
                    append(prefix)
                }
                append(log.text)
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
