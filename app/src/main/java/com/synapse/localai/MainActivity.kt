package com.synapse.localai

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import com.synapse.localai.ui.SyntaxHighlighter
import com.synapse.localai.ui.tactileGlassGlow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synapse.localai.ui.theme.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
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

fun getSystemStats(context: Context): SystemStats {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
    val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val status = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL

    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val memoryInfo = android.app.ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val ramFree = String.format(Locale.US, "%.1fGB", memoryInfo.availMem / 1024.0 / 1024.0 / 1024.0)

    val statFs = android.os.StatFs(android.os.Environment.getDataDirectory().path)
    val storageFree = String.format(Locale.US, "%.0fGB", statFs.availableBlocksLong * statFs.blockSizeLong / 1024.0 / 1024.0 / 1024.0)

    val prefs = context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
    val geminiKeys = prefs.getString("gemini_api_keys", "") ?: ""
    val totalKeys = if (geminiKeys.isBlank()) 0 else geminiKeys.split(",").filter { it.trim().isNotEmpty() }.size
    val currentKeyIndex = if (totalKeys > 0) (prefs.getInt("current_key_index", 0) + 1) else 0

    var maxTemp = 0
    try {
        val thermalDir = File("/sys/class/thermal")
        val zones = thermalDir.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") } ?: emptyArray()
        
        for (zone in zones) {
            val typeFile = File(zone, "type")
            val tempFile = File(zone, "temp")
            if (tempFile.exists()) {
                val type = if (typeFile.exists()) typeFile.readText().lowercase() else ""
                val t = tempFile.readText().trim().toIntOrNull() ?: 0
                val normalized = if (t > 1000) t / 1000 else t
                
                if (normalized in 20..100) {
                    if (type.contains("cpu") || type.contains("tsens") || type.contains("cluster")) {
                        maxTemp = normalized
                        break
                    }
                    if (normalized > maxTemp && normalized != 37) {
                        maxTemp = normalized
                    }
                }
            }
        }
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

fun isArabic(text: String): Boolean {
    if (text.isBlank()) return false
    return text.any { ch ->
        ch in '\u0600'..'\u06FF' || // Arabic & Moroccan Darija script
        ch in '\u0750'..'\u077F' || // Arabic Supplement
        ch in '\u08A0'..'\u08FF' || // Arabic Extended-A
        ch in '\uFB50'..'\uFDFF' || // Arabic Presentation Forms-A
        ch in '\uFE70'..'\uFEFF'    // Arabic Presentation Forms-B
    }
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

        lifecycleScope.launch {
            try {
                checkStoragePermissions()
                delay(800)
                checkBatteryOptimizations()
                delay(800)
                checkAndRequestRootAccess()
            } catch (e: Exception) {
                logToCrashFile("Startup Permission Logic Error: ${e.message}")
            }
        }

        setContent {
            SynapseAITheme {
                val isDark = isSystemInDarkTheme()
                val bgColor = if (isDark) Color(0xFF070A0F) else Color(0xFFF4F6F9)

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = bgColor
                ) {
                    CliWorkspaceScreen(viewModel)
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
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            intent.action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private suspend fun checkAndRequestRootAccess() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            val isRoot = exitCode == 0 && output.contains("uid=0")
            viewModel.setRootAvailable(isRoot)
            if (isRoot) {
                Log.i("MainActivity", "Superuser privileges verified (root active): $output")
                ZramOptimizer.optimizeMemoryAndZram(this@MainActivity)
                ZramOptimizer.disableOomKiller()
            } else {
                Log.i("MainActivity", "Standard user execution (non-root)")
            }
        } catch (e: Exception) {
            Log.i("MainActivity", "Superuser elevation not available: ${e.message}")
            viewModel.setRootAvailable(false)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            lifecycleScope.launch(Dispatchers.IO) {
                ZramOptimizer.optimizeMemoryAndZram(this@MainActivity)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CliWorkspaceScreen(viewModel: AgentViewModel) {
    val isDark = isSystemInDarkTheme()
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    var textInput by remember { mutableStateOf("") }

    val surfaceColor = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
    val indigoAccent = MaterialTheme.colorScheme.primary
    val cyanAccent = indigoAccent
    val primaryAccent = MaterialTheme.colorScheme.primary
    val secondaryAccent = MaterialTheme.colorScheme.secondary
    val inputBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

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

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                true
            } else {
                val lastVisible = visibleItems.last()
                val isLastIndex = lastVisible.index >= layoutInfo.totalItemsCount - 1
                val lastItemBottom = lastVisible.offset + lastVisible.size
                val viewportEnd = layoutInfo.viewportEndOffset
                isLastIndex && (lastItemBottom <= viewportEnd + 140)
            }
        }
    }

    var userScrolledAway by remember { mutableStateOf(false) }

    // User-Aware Scroll State: release auto-scroll lock on manual scroll up, re-engage on scroll to bottom
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            if (!isAtBottom) {
                userScrolledAway = true
            }
        } else {
            if (isAtBottom) {
                userScrolledAway = false
            }
        }
    }

    // New prompt sent -> re-engage lock and scroll smoothly to newest message bottom
    LaunchedEffect(uiState.logs.size) {
        userScrolledAway = false
        if (uiState.logs.isNotEmpty()) {
            listState.animateScrollToItem(uiState.logs.size - 1, scrollOffset = 100_000)
        }
    }

    // Kinetic Stream-Tracking: instantaneous, zero-jitter tracking of live streaming tail
    LaunchedEffect(uiState.streamVersion, uiState.isProcessing, uiState.activeStatusDetail) {
        if (!userScrolledAway && !listState.isScrollInProgress && uiState.logs.isNotEmpty()) {
            listState.scrollToItem(uiState.logs.size - 1, scrollOffset = 100_000)
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

            // Top Header: Spacious Navigation Bar with Heavyweight Typography
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = surfaceColor,
                border = BorderStroke(0.5.dp, borderColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                onClick = { coroutineScope.launch { drawerState.open() } },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(0.5.dp, borderColor),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Widgets,
                                        contentDescription = "Menu",
                                        tint = primaryAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Column {
                                Text(
                                    text = "MEDLAM-AI AGENT",
                                    color = textBright,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.SansSerif,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (uiState.isProcessing) primaryAccent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.tactileGlassGlow(cornerRadius = 12.dp, isDark = isDark, glowAlpha = 0.12f, borderWidth = 0.6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (uiState.isProcessing) primaryAccent else textDim)
                                    )
                                    Text(
                                        text = if (uiState.isProcessing) "RUNNING" else "IDLE",
                                        color = if (uiState.isProcessing) primaryAccent else textDim,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            IconButton(
                                onClick = { showSettings = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Outlined.Settings, "Settings", tint = textDim, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Unified Model & MTP Capsule (Automatically hides when Cloud/Gemini mode is active)
                    val isCloudMode = uiState.activeModelType == ModelType.GEMINI || (uiState.activeModelType == null && uiState.config.preferredModelType == ModelType.GEMINI)
                    if (!isCloudMode) {
                        val hasMtp = uiState.config.draftModelPath.isNotBlank() && File(uiState.config.draftModelPath).exists()
                        val draftName = if (hasMtp) File(uiState.config.draftModelPath).nameWithoutExtension else null
                        val rawBaseName = uiState.activeModelName.ifBlank {
                            if (uiState.config.localModelPath.isNotBlank()) File(uiState.config.localModelPath).nameWithoutExtension else "GEMMA-4-E2B"
                        }
                        val cleanBaseName = rawBaseName
                            .replace("-it", "")
                            .replace("-qat", "")
                            .replace("-UD", "")
                            .replace("-Q2_K_XL", " Q2")
                            .replace("-Q4_K_XL", " Q4")
                            .replace("-BF16", "")

                        val unifiedCapsuleText = if (draftName != null) {
                            "$cleanBaseName • MTP: $draftName"
                        } else {
                            "$cleanBaseName • STANDALONE"
                        }

                        // Reactive Neural Core Status Beacon (Loading Pulse -> Solid Beacon -> Offline Muted)
                        val isModelLoading = uiState.localModelLoading || (!uiState.localModelLoaded && uiState.loadingProgress != null)
                        val isModelReady = uiState.localModelLoaded && !isModelLoading

                        val beaconGreen = Color(0xFF10B981) // High-Tech Neural Core Green
                        val offlineDotColor = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)

                        // Breathing animation for Loading / Initializing phase
                        val beaconTransition = rememberInfiniteTransition(label = "beaconPulseTransition")
                        val pulseAlpha by beaconTransition.animateFloat(
                            initialValue = 0.25f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "beaconAlpha"
                        )
                        val pulseHaloScale by beaconTransition.animateFloat(
                            initialValue = 0.85f,
                            targetValue = 1.45f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "beaconHaloScale"
                        )

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.55f else 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tactileGlassGlow(cornerRadius = 8.dp, isDark = isDark)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(10.dp)
                                ) {
                                    when {
                                        isModelLoading -> {
                                            // Loading / Initializing: Actively pulses with rhythmic green glow (breathing animation)
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .scale(pulseHaloScale)
                                                    .clip(CircleShape)
                                                    .background(beaconGreen.copy(alpha = pulseAlpha * 0.35f))
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(5.5.dp)
                                                    .clip(CircleShape)
                                                    .background(beaconGreen.copy(alpha = pulseAlpha))
                                            )
                                        }
                                        isModelReady -> {
                                            // Ready / Operational: Settles into a stable, solid green beacon with ambient halo
                                            Box(
                                                modifier = Modifier
                                                    .size(9.dp)
                                                    .clip(CircleShape)
                                                    .background(beaconGreen.copy(alpha = 0.22f))
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .size(5.5.dp)
                                                    .clip(CircleShape)
                                                    .background(beaconGreen)
                                            )
                                        }
                                        else -> {
                                            // Offline / Unloaded: Muted neutral or gray tone
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(offlineDotColor)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = unifiedCapsuleText.uppercase(),
                                    color = textDim,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Unified Capsule (Battery, RAM, CPU, Temp, Keys, TSK Capsule + Clock Capsule)
            UnifiedTelemetryBar(context = context, isDark = isDark, uiState = uiState)

            // CLI Workspace
            Box(modifier = Modifier.weight(1f)) {
                if (uiState.logs.isEmpty() && !uiState.isProcessing) {
                    CliWelcomeScreen(
                        onSelectPrompt = { p ->
                            viewModel.sendCommand(p, context = context)
                        }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(uiState.logs, key = { it.id }) { log ->
                            CliLogMessageView(log = log, isDark = isDark)
                        }
                    }

                    // Floating Jump to Latest Button when user manually scrolled away
                    if (userScrolledAway && uiState.logs.isNotEmpty()) {
                        Surface(
                            onClick = {
                                userScrolledAway = false
                                coroutineScope.launch {
                                    listState.animateScrollToItem(uiState.logs.size - 1, scrollOffset = 100_000)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                            border = BorderStroke(0.5.dp, primaryAccent.copy(alpha = 0.6f)),
                            shadowElevation = 6.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Filled.ArrowDownward, null, tint = primaryAccent, modifier = Modifier.size(12.dp))
                                Text(
                                    text = if (uiState.isProcessing) "Live Stream Tail ↓" else "Jump to Latest ↓",
                                    color = primaryAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Dock: Docked Real-Time Activity Monitor + Floating Message Input Capsule
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                color = surfaceColor,
                border = BorderStroke(0.5.dp, borderColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    // 4. Docked Real-Time Dynamic Activity Bar (Impenetrable Lower Boundary & Visual Floor)
                    val infiniteTransition = rememberInfiniteTransition(label = "docked_activity_pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.35f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(750, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "docked_pulse_alpha"
                    )

                    val isBusy = uiState.isProcessing
                    val statusText = if (isBusy) {
                        uiState.activeStatusDetail.ifBlank { "Thinking & Planning..." }
                    } else {
                        "Idle / Ready"
                    }

                    val dotColor = if (isBusy) primaryAccent else Color(0xFF10B981)
                    val textColor = if (isBusy) primaryAccent else textDim

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 2.dp)
                            .tactileGlassGlow(cornerRadius = 8.dp, isDark = isDark),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isBusy) 0.5f else 0.3f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .then(if (isBusy) Modifier.alpha(pulseAlpha) else Modifier)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = statusText,
                                color = textColor,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Floating Message Input Capsule Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val inputIsArabic = isArabic(textInput)
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (inputIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp, max = 100.dp)
                                .tactileGlassGlow(cornerRadius = 22.dp, isDark = isDark, borderWidth = 0.8.dp),
                            color = inputBg,
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp),
                                contentAlignment = if (inputIsArabic) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = textInput,
                                    onValueChange = { textInput = it },
                                    textStyle = TextStyle(
                                        color = textBright,
                                        fontSize = 12.5.sp,
                                        fontFamily = if (inputIsArabic) FontFamily.SansSerif else FontFamily.Monospace,
                                        textAlign = if (inputIsArabic) TextAlign.End else TextAlign.Start
                                    ),
                                    cursorBrush = SolidColor(cyanAccent),
                                    maxLines = 4,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    decorationBox = { inner ->
                                        if (textInput.isEmpty()) {
                                            Text(
                                                text = if (inputIsArabic) "رسالة إلى MEDLAM..." else "Message MEDLAM...",
                                                style = TextStyle(
                                                    color = textDim,
                                                    fontSize = 12.sp,
                                                    fontFamily = if (inputIsArabic) FontFamily.SansSerif else FontFamily.Monospace,
                                                    textAlign = if (inputIsArabic) TextAlign.End else TextAlign.Start
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        inner()
                                    }
                                )
                            }
                        }
                    }

                    Surface(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .size(38.dp)
                            .tactileGlassGlow(cornerRadius = 19.dp, isDark = isDark, glowAlpha = 0.12f, borderWidth = 0.6.dp),
                        shape = CircleShape,
                        color = if (selectedFileUri != null) cyanAccent.copy(alpha = 0.2f) else borderColor.copy(alpha = 0.5f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.AttachFile, "Attach", tint = if (selectedFileUri != null) cyanAccent else textDim, modifier = Modifier.size(17.dp))
                        }
                    }

                    Surface(
                        onClick = {
                            isRecording = true
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-MA")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
                            }
                            try { speechLauncher.launch(intent) } catch (_: Exception) { isRecording = false }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .tactileGlassGlow(cornerRadius = 19.dp, isDark = isDark, glowAlpha = 0.12f, borderWidth = 0.6.dp),
                        shape = CircleShape,
                        color = if (isRecording) Color.Red.copy(alpha = 0.2f) else borderColor.copy(alpha = 0.5f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (isRecording) Icons.Filled.Mic else Icons.Outlined.Mic, "Mic", tint = if (isRecording) Color.Red else textDim, modifier = Modifier.size(17.dp))
                        }
                    }

                    val canSend = textInput.isNotBlank() || selectedFileUri != null
                    Surface(
                        onClick = {
                            if (uiState.isProcessing) {
                                viewModel.stopAgent()
                            } else if (canSend) {
                                val cmd = textInput
                                textInput = ""
                                focusManager.clearFocus()
                                viewModel.sendCommand(cmd, selectedFileUri, context)
                                selectedFileUri = null
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .tactileGlassGlow(cornerRadius = 19.dp, isDark = isDark, glowAlpha = 0.12f, borderWidth = 0.6.dp),
                        shape = CircleShape,
                        color = if (uiState.isProcessing) Color.Red else if (canSend) cyanAccent else borderColor.copy(alpha = 0.5f),
                        enabled = uiState.isProcessing || canSend
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (uiState.isProcessing) Icons.Default.Stop else Icons.Default.ArrowUpward,
                                contentDescription = "Send",
                                tint = if (canSend || uiState.isProcessing) (if (isDark) Color.Black else Color.White) else textDim,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    }
                }
            }
        }
    }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
    }
}

/**
 * Full Width Capsule: Metrics on left, TSK Capsule + Clock on right
 */
@Composable
fun UnifiedTelemetryBar(context: Context, isDark: Boolean, uiState: AgentUiState) {
    var stats by remember { mutableStateOf(SystemStats(0, false, "--", "--", "--", "--", 0, 0, 0)) }
    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                stats = getSystemStats(context)
                currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    val capsuleBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val capsuleBorder = MaterialTheme.colorScheme.outlineVariant
    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryAccent = MaterialTheme.colorScheme.primary
    val secondaryAccent = MaterialTheme.colorScheme.secondary

    val currentKey = if (uiState.totalKeys > 0) "Key ${uiState.currentKeyIndex + 1}/${uiState.totalKeys}" else if (stats.totalKeys > 0) "Key ${stats.currentKeyIndex}/${stats.totalKeys}" else "Key 0/0"
    val totalTasks = maxOf(uiState.activeTasks.size, uiState.taskQueueSize)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .tactileGlassGlow(cornerRadius = 10.dp, isDark = isDark),
        shape = RoundedCornerShape(10.dp),
        color = capsuleBg
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Metrics (Battery, RAM, CPU, Temp, Keys) - strictly single line with scroll if narrow
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(
                        if (stats.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                        null,
                        tint = if (stats.isCharging) primaryAccent else textDim,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = if (stats.isCharging) "⚡${stats.battery}%" else "${stats.battery}%",
                        color = textBright,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Text("•", color = textDim, fontSize = 7.5.sp, maxLines = 1, softWrap = false)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Filled.Memory, null, tint = primaryAccent, modifier = Modifier.size(11.dp))
                    Text(stats.ramFree, color = textBright, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
                }

                Text("•", color = textDim, fontSize = 7.5.sp, maxLines = 1, softWrap = false)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Filled.DeveloperBoard, null, tint = primaryAccent, modifier = Modifier.size(11.dp))
                    Text(stats.cpuUsage, color = textBright, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
                }

                Text("•", color = textDim, fontSize = 7.5.sp, maxLines = 1, softWrap = false)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Filled.Thermostat, null, tint = if (stats.cpuTempInt > 55) MaterialTheme.colorScheme.error else primaryAccent, modifier = Modifier.size(11.dp))
                    Text(stats.cpuTemp, color = textBright, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
                }

                if (uiState.config.showApiKeyTracker) {
                    Text("•", color = textDim, fontSize = 7.5.sp, maxLines = 1, softWrap = false)

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Filled.VpnKey, null, tint = secondaryAccent, modifier = Modifier.size(10.dp))
                        Text(
                            text = if (uiState.activeModelType == ModelType.GEMINI || (uiState.activeModelType == null && uiState.config.preferredModelType == ModelType.GEMINI)) {
                                if (stats.totalKeys > 0) currentKey else "CLOUD"
                            } else {
                                if (uiState.config.draftModelPath.isNotBlank()) "⚡ MTP" else "LOCAL"
                            },
                            color = secondaryAccent,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Right Side: TSK Capsule + Clock Capsule (rigidly single-line, zero wrapping)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (totalTasks > 0) primaryAccent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(0.5.dp, if (totalTasks > 0) primaryAccent else capsuleBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "tsk",
                            color = primaryAccent,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = "$totalTasks",
                            color = textBright,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(0.5.dp, capsuleBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Outlined.Schedule, null, tint = primaryAccent, modifier = Modifier.size(9.dp))
                        Text(
                            text = currentTime,
                            color = textBright,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Collapsible Thought Container for streaming reasoning progression in real time.
 */
@Composable
fun CliThoughtContainer(
    thinkingText: String,
    isThinking: Boolean,
    isDark: Boolean
) {
    if (thinkingText.isBlank() && !isThinking) return

    var userExpanded by remember { mutableStateOf<Boolean?>(false) }
    val isExpanded = userExpanded ?: false

    val transition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val primaryColor = MaterialTheme.colorScheme.primary
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .tactileGlassGlow(cornerRadius = 8.dp, isDark = isDark),
        shape = RoundedCornerShape(8.dp),
        color = surfaceColor
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { userExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = "💭",
                        fontSize = 11.sp,
                        modifier = if (isThinking) Modifier.alpha(alphaAnim) else Modifier
                    )
                    Text(
                        text = if (isThinking) {
                            if (thinkingText.isBlank()) "Thinking & formulating architecture..." else "Thinking in real-time..."
                        } else {
                            "Thought process (${thinkingText.split(Regex("\\s+")).filter { it.isNotBlank() }.size} tokens)"
                        },
                        color = if (isThinking) primaryColor else textDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = if (isThinking) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isThinking) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .alpha(alphaAnim)
                                .clip(CircleShape)
                                .background(primaryColor)
                        )
                    }
                }

                Text(
                    text = if (isExpanded) "Hide ▴" else "Show ▾",
                    color = textDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Divider(
                        modifier = Modifier.padding(bottom = 6.dp),
                        thickness = 0.5.dp,
                        color = borderColor
                    )
                    Text(
                        text = if (thinkingText.isNotBlank()) thinkingText else "Formulating architecture and reasoning progression...",
                        color = textDim,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.SansSerif,
                        lineHeight = 15.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Interactive & Syntax-Highlighted Code Block Container with actionable top bar.
 */
@Composable
fun CliCodeBlockContainer(
    code: String,
    language: String,
    filePath: String?,
    isDark: Boolean
) {
    var isExpanded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val containerBg = if (isDark) Color(0xFF0D1117) else Color(0xFFF6F8FA)
    val topBarBg = if (isDark) Color(0xFF161B22) else Color(0xFFEAEEF2)
    val borderColor = if (isDark) Color(0xFF30363D) else Color(0xFFD0D7DE)
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .tactileGlassGlow(cornerRadius = 8.dp, isDark = isDark),
        shape = RoundedCornerShape(8.dp),
        color = containerBg
    ) {
        Column {
            // Actionable Top Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = topBarBg,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Language badge + Target path
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = primaryColor.copy(alpha = 0.15f),
                            border = BorderStroke(0.5.dp, primaryColor.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = language.uppercase().ifBlank { "CODE" },
                                color = primaryColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }

                        if (!filePath.isNullOrBlank()) {
                            Text(
                                text = filePath,
                                color = textDim,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Right: One-click copy + Expand/Collapse toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (copied) primaryColor.copy(alpha = 0.18f) else Color.Transparent,
                            modifier = Modifier.clickable {
                                clipboard.setText(AnnotatedString(code))
                                copied = true
                                coroutineScope.launch {
                                    delay(2000)
                                    copied = false
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy code",
                                    tint = if (copied) primaryColor else textDim,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = if (copied) "COPIED" else "COPY",
                                    color = if (copied) primaryColor else textDim,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color.Transparent,
                            modifier = Modifier.clickable { isExpanded = !isExpanded }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Toggle code",
                                    tint = textDim,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                val highlighted = remember(code, language, isDark) {
                    SyntaxHighlighter.highlight(code, language, isDark)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        text = highlighted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

sealed class MessageSegment {
    data class Text(val content: String) : MessageSegment()
    data class Code(val language: String, val code: String, val filePath: String?) : MessageSegment()
}

val EXECUTABLE_LANGUAGES = setOf(
    "html", "htm", "css", "scss", "sass", "less",
    "js", "javascript", "jsx", "ts", "typescript", "tsx",
    "py", "python", "pyw",
    "sh", "bash", "zsh", "shell",
    "json", "yaml", "yml", "xml", "toml",
    "c", "cpp", "cxx", "cc", "h", "hpp",
    "java", "kotlin", "kt", "kts",
    "rust", "rs", "go", "golang",
    "sql", "php", "ruby", "rb", "swift", "dart", "lua", "r"
)

fun isExecutableCode(lang: String, detectedPath: String?): Boolean {
    val cleanLang = lang.lowercase().trim()
    if (cleanLang in setOf("markdown", "md", "text", "txt", "plain", "plaintext", "thought", "thinking", "reasoning", "plan", "notes")) {
        return false
    }
    if (cleanLang in EXECUTABLE_LANGUAGES) {
        return true
    }
    if (detectedPath != null) {
        val ext = detectedPath.substringAfterLast('.', "").lowercase()
        return ext in EXECUTABLE_LANGUAGES
    }
    return false
}

fun filterCodeLeaksFromText(text: String): String {
    val lines = text.lines()
    val sanitized = mutableListOf<String>()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            sanitized.add(line)
            continue
        }
        val isCodeLeak = (trimmed.startsWith("<") && (trimmed.endsWith(">") || trimmed.contains("</"))) ||
                         trimmed.startsWith("function ") || trimmed.startsWith("const ") ||
                         trimmed.startsWith("var ") || trimmed.startsWith("let ") ||
                         trimmed.startsWith("import ") || trimmed.startsWith("export ") ||
                         trimmed.startsWith("class ") || trimmed.startsWith("def ") ||
                         (trimmed.contains("{") && trimmed.contains("}")) ||
                         trimmed.contains("margin:") || trimmed.contains("padding:") ||
                         trimmed.contains("color:") || trimmed.contains("display:") ||
                         trimmed.contains("background:")

        if (!isCodeLeak) {
            sanitized.add(line)
        }
    }
    return sanitized.joinToString("\n").trim()
}

fun parseMessageSegments(text: String): List<MessageSegment> {
    val segments = mutableListOf<MessageSegment>()
    val codeRegex = Regex("""```([a-zA-Z0-9_-]*)\n([\s\S]*?)```""")
    var currentIndex = 0

    for (match in codeRegex.findAll(text)) {
        if (match.range.first > currentIndex) {
            val textPart = text.substring(currentIndex, match.range.first).trim()
            val cleanTextPart = filterCodeLeaksFromText(textPart)
            if (cleanTextPart.isNotEmpty()) {
                segments.add(MessageSegment.Text(cleanTextPart))
            }
        }

        val lang = match.groupValues[1].trim()
        val code = match.groupValues[2].trimEnd()

        var detectedPath: String? = null
        if (match.range.first > 0) {
            val precedingText = text.substring(0, match.range.first)
            val lines = precedingText.lines().takeLast(3)
            for (line in lines) {
                val p = ToolExecutor.detectFilePath(line)
                if (p != null) {
                    detectedPath = p
                    break
                }
            }
        }

        if (isExecutableCode(lang, detectedPath)) {
            segments.add(MessageSegment.Code(language = lang.ifBlank { "code" }, code = code, filePath = detectedPath))
        } else {
            // Natural prose typography: markdown, planning, notes, and general text render as standard conversational text
            segments.add(MessageSegment.Text(code))
        }
        currentIndex = match.range.last + 1
    }

    if (currentIndex < text.length) {
        val remainder = text.substring(currentIndex)
        val unclosedStart = remainder.indexOf("```")
        if (unclosedStart != -1) {
            val beforeCode = remainder.substring(0, unclosedStart).trim()
            val cleanBeforeCode = filterCodeLeaksFromText(beforeCode)
            if (cleanBeforeCode.isNotEmpty()) {
                segments.add(MessageSegment.Text(cleanBeforeCode))
            }
            val afterBackticks = remainder.substring(unclosedStart + 3)
            val langLine = afterBackticks.substringBefore('\n').trim()
            val codeContent = if (afterBackticks.contains('\n')) afterBackticks.substringAfter('\n') else ""
            if (isExecutableCode(langLine, null)) {
                segments.add(MessageSegment.Code(language = langLine.ifBlank { "code" }, code = codeContent, filePath = null))
            } else {
                val fullText = (cleanBeforeCode + "\n" + codeContent).trim()
                if (fullText.isNotEmpty()) {
                    segments.add(MessageSegment.Text(fullText))
                }
            }
        } else {
            val remainingText = remainder.trim()
            val cleanRemaining = filterCodeLeaksFromText(remainingText)
            if (cleanRemaining.isNotEmpty()) {
                segments.add(MessageSegment.Text(cleanRemaining))
            }
        }
    }

    if (segments.isEmpty() && text.isNotBlank()) {
        segments.add(MessageSegment.Text(filterCodeLeaksFromText(text)))
    }

    return segments
}

@Composable
fun CliLogMessageView(
    log: LogEntry, 
    isDark: Boolean
) {
    val clipboard = LocalClipboardManager.current
    val isUser = log.sender == "USER"
    val isSystem = log.sender == "SYSTEM"

    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryAccent = MaterialTheme.colorScheme.primary

    if (isSystem && !log.isError) return

    val isLogArabic = isArabic(log.text)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        if (isUser) {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (isLogArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        text = if (isLogArabic) "المستخدم >> " else "USER >> ",
                        color = primaryAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = log.text,
                        color = textBright,
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = if (isLogArabic) 20.sp else 18.sp,
                        letterSpacing = if (isLogArabic) 0.sp else 0.2.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        } else {
            val cleanText = log.text
                .replace(Regex("<\\|?think\\|?>[\\s\\S]*?<\\|?/(?:think|thought)\\|?>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<\\|?think\\|?>[\\s\\S]*$", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<thought>[\\s\\S]*?</thought>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<thought>[\\s\\S]*$", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<reasoning>[\\s\\S]*?</reasoning>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<reasoning>[\\s\\S]*$", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("^💭[\\s\\S]*?\n\n", RegexOption.DOT_MATCHES_ALL), "")
                .trim()

            val isAgentArabic = if (cleanText.isNotBlank()) isArabic(cleanText) else false

            // 1. Agent message header (MEDLAM >>)
            CompositionLocalProvider(
                LocalLayoutDirection provides if (isAgentArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isAgentArabic) "ميدلام >>" else "MEDLAM >>",
                        color = primaryAccent,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (cleanText.isNotBlank()) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(cleanText)) }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Outlined.ContentCopy, "Copy", tint = textDim, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }

            // 2. Immediate Post-Prompt Reasoning Anchor:
            // Collapsible thinking tray renders immediately beneath prompt / header as first component
            if (log.thinkingText.isNotBlank() || log.isThinking) {
                CliThoughtContainer(
                    thinkingText = log.thinkingText,
                    isThinking = log.isThinking,
                    isDark = isDark
                )
            }

            // 3. Interactive code blocks and conversational text segments
            if (cleanText.isNotBlank()) {
                val segments = remember(cleanText) { parseMessageSegments(cleanText) }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    segments.forEach { segment ->
                        when (segment) {
                            is MessageSegment.Text -> {
                                CliFormattedBody(segment.content, isDark)
                            }
                            is MessageSegment.Code -> {
                                CliCodeBlockContainer(
                                    code = segment.code,
                                    language = segment.language,
                                    filePath = segment.filePath,
                                    isDark = isDark
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CliFormattedBody(raw: String, isDark: Boolean) {
    val text = raw
        .replace(Regex("<.*?>", RegexOption.DOT_MATCHES_ALL), "")
        .replace("*", "")
        .trim()

    if (text.isBlank()) return

    val textBright = MaterialTheme.colorScheme.onSurface
    val primaryAccent = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            val isLineArabic = isArabic(trimmed)
            val isHeader = trimmed.startsWith("#") || (trimmed.endsWith(":") && trimmed.length < 50 && !trimmed.contains("http"))
            val isBullet = trimmed.startsWith("- ") || trimmed.startsWith("• ") || trimmed.matches(Regex("^[0-9]+[.)].*"))
            val clean = trimmed.replace(Regex("^#+\\s*"), "")

            CompositionLocalProvider(
                LocalLayoutDirection provides if (isLineArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                if (isHeader) {
                    Text(
                        text = clean,
                        color = primaryAccent,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        textAlign = if (isLineArabic) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                } else if (isBullet) {
                    val bulletContent = clean.replace(Regex("^[-•0-9.)]+\\s*"), "")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = if (isLineArabic) Arrangement.End else Arrangement.Start
                    ) {
                        if (!isLineArabic) {
                            Text("• ", color = primaryAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = buildPathHighlightedString(bulletContent, isDark),
                            color = textBright,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.SansSerif,
                            lineHeight = if (isLineArabic) 20.sp else 18.sp,
                            letterSpacing = if (isLineArabic) 0.sp else 0.2.sp,
                            textAlign = if (isLineArabic) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isLineArabic) {
                            Text(" •", color = primaryAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Text(
                        text = buildPathHighlightedString(clean, isDark),
                        color = textBright,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.SansSerif,
                        lineHeight = if (isLineArabic) 20.sp else 18.sp,
                        letterSpacing = if (isLineArabic) 0.sp else 0.2.sp,
                        textAlign = if (isLineArabic) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

fun buildPathHighlightedString(text: String, isDark: Boolean): AnnotatedString {
    val pathRegex = Regex("(/sdcard/[a-zA-Z0-9_.-]+(?:/[a-zA-Z0-9_.-]+)*|/(?:data|storage|system|etc|proc|sys)/[a-zA-Z0-9_.-]+(?:/[a-zA-Z0-9_.-]+)*)")
    val amberAccent = if (isDark) Color(0xFFFFB86C) else Color(0xFFD97706)
    val pathBg = if (isDark) Color(0xFF1B2332) else Color(0xFFE2E8F0)
    val builder = AnnotatedString.Builder()
    var lastIndex = 0

    pathRegex.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            builder.append(text.substring(lastIndex, match.range.first))
        }
        builder.pushStyle(SpanStyle(color = amberAccent, background = pathBg, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
        builder.append("\u2066${match.value}\u2069")
        builder.pop()
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        builder.append(text.substring(lastIndex))
    }
    return builder.toAnnotatedString()
}

@Composable
fun CliWelcomeScreen(
    onSelectPrompt: (String) -> Unit
) {
    val indigoAccent = MaterialTheme.colorScheme.primary
    val cyanAccent = indigoAccent
    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("MEDLAM-AI AGENT", color = cyanAccent, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text("Autonomous On-Device Agent & Chat CLI", color = textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(16.dp))

        listOf(
            "Create a responsive modern web dashboard in /sdcard/Medlam-Ai/webapp",
            "Write a python automation script to organize downloads folder",
            "Inspect system hardware, memory cache, and optimize background performance"
        ).forEach { prompt ->
            Surface(
                onClick = { onSelectPrompt(prompt) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .tactileGlassGlow(cornerRadius = 8.dp, isDark = isSystemInDarkTheme()),
                color = surfaceColor,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = prompt,
                        color = textBright,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ArrowForward, null, tint = cyanAccent, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
fun SidebarContent(
    viewModel: AgentViewModel,
    onCloseDrawer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val sessions by viewModel.chatSessions.collectAsState()
    val activeSessionId by viewModel.currentSessionId.collectAsState()
    val surfaceColor = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
    val indigoAccent = MaterialTheme.colorScheme.primary
    val cyanAccent = indigoAccent

    ModalDrawerSheet(
        drawerContainerColor = surfaceColor,
        drawerContentColor = textBright,
        modifier = Modifier.width(300.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Spacer(modifier = Modifier.statusBarsPadding())

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("SESSION ARCHIVE", color = cyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onCloseDrawer) {
                    Icon(Icons.Outlined.Close, "Close", tint = textDim, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    viewModel.startNewSession()
                    onCloseDrawer()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = borderColor.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("+ New Session", color = cyanAccent, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = borderColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(10.dp))

            if (sessions.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No archived sessions", color = textDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        val selected = s.id == activeSessionId
                        val isSessionArabic = isArabic(s.title)
                        Surface(
                            onClick = {
                                viewModel.switchSession(s.id)
                                onCloseDrawer()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (selected) cyanAccent.copy(alpha = if (isDark) 0.15f else 0.08f) else Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                            border = if (selected) BorderStroke(0.5.dp, cyanAccent.copy(alpha = 0.5f)) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = s.title,
                                        color = if (selected) cyanAccent else textBright,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        fontFamily = if (isSessionArabic) FontFamily.SansSerif else FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(s.updatedAt)),
                                        color = textDim,
                                        fontSize = 8.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                IconButton(onClick = { viewModel.deleteSession(s.id) }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Outlined.DeleteOutline, "Del", tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }

            Divider(color = borderColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(10.dp))

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
                Icon(Icons.Outlined.Settings, "Settings", tint = cyanAccent, modifier = Modifier.size(18.dp))
                Text("System Settings", color = textBright, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}