package com.synapse.localai

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import com.synapse.localai.ui.SyntaxHighlighter
import com.synapse.localai.ui.tactileGlassGlow
import com.synapse.localai.ui.tactileCardElevation
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.synapse.localai.ui.theme.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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

    val maxTemp = DeviceProfile.readDeviceTemperature()

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

/**
 * MODULE 1: First Strong Directional Character Detection
 * Dynamically determines if text flow should be RTL based on the first strong letter.
 */
fun isRtlText(text: String): Boolean {
    val arabicRegex = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
    val firstChar = text.trim().firstOrNull { it.isLetter() } ?: return false
    return arabicRegex.matches(firstChar.toString())
}

fun isArabic(text: String): Boolean = isRtlText(text)

/**
 * MODULE 2.2: BiDi Isolation for Mixed Content (English/Latin inside Arabic)
 * Wraps embedded Latin technical terms and parenthesized text in Unicode Bidi isolation
 * markers (\u2068...\u2069) and ensures colons anchor correctly to Arabic phrases.
 */
fun applyBidiIsolation(text: String): String {
    if (text.isBlank()) return text

    // 1. Anchor colons immediately following Arabic phrases (\u200F RLM right after colon)
    val arabicColonRegex = Regex("([\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF])(:|：)(\\s*)")
    var processed = arabicColonRegex.replace(text) { m ->
        "${m.groupValues[1]}${m.groupValues[2]}\u200F${m.groupValues[3]}"
    }

    // 2. Wrap parenthesized Latin/ASCII terms: e.g. (Shell & Terminal), (File I/O)
    val parenLatinRegex = Regex("(\\([A-Za-z0-9_\\-&/.,\\s+]+\\))")
    processed = parenLatinRegex.replace(processed) { m ->
        "\u2068${m.value}\u2069"
    }

    // 3. Wrap bracketed Latin terms: e.g. [Shell & Terminal]
    val bracketLatinRegex = Regex("(\\[[A-Za-z0-9_\\-&/.,\\s+]+\\])")
    processed = bracketLatinRegex.replace(processed) { m ->
        "\u2068${m.value}\u2069"
    }

    // 4. Wrap standalone Latin words/phrases not already inside an isolate (\u2068...\u2069)
    val sb = StringBuilder()
    var inIsolate = false
    var i = 0
    while (i < processed.length) {
        val ch = processed[i]
        if (ch == '\u2068' || ch == '\u2066') {
            inIsolate = true
            sb.append(ch)
            i++
        } else if (ch == '\u2069') {
            inIsolate = false
            sb.append(ch)
            i++
        } else if (!inIsolate) {
            val nextIsolate = processed.indexOfAny(charArrayOf('\u2068', '\u2066'), i)
            val chunkEnd = if (nextIsolate != -1) nextIsolate else processed.length
            val chunk = processed.substring(i, chunkEnd)
            val isolatedChunk = chunk.replace(Regex("(?<![/`])\\b([A-Za-z][A-Za-z0-9_\\-&./]*(?:\\s+[A-Za-z][A-Za-z0-9_\\-&./]*)*)\\b(?!`)")) { m ->
                val v = m.value
                if (v.isBlank() || v.startsWith("http") || v.startsWith("/")) v else "\u2068$v\u2069"
            }
            sb.append(isolatedChunk)
            i = chunkEnd
        } else {
            sb.append(ch)
            i++
        }
    }
    return sb.toString()
}

class MainActivity : ComponentActivity() {
    private val viewModel: AgentViewModel by viewModels()

    companion object {
        var instance: MainActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        setupGlobalCrashHandler()

        // Initialize Native MedlamEngine (mmap, speculative decoding, ARM NEON acceleration)
        try {
            LlamaNativeEngine.load()
        } catch (t: Throwable) {
            Log.e("MainActivity", "LlamaNativeEngine load error: ${t.message}")
        }

        // Prevent activity destruction/restart on back press: move task to back cleanly
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        // Start Foreground Service in the app process to prevent OS background eviction
        try {
            val serviceIntent = Intent(this, ModelEngineService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "ModelEngineService launch note: ${e.message}")
        }

        // Early App Process Initialization: Background Neural Priming & Persistent Persona KV Cache
        NeuralPrimingManager.primeNeuralCore(applicationContext)

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
                val bgColor = MaterialTheme.colorScheme.surface

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
            val pb = ProcessBuilder("su", "-c", "id")
            pb.redirectErrorStream(true)
            val process = pb.start()
            try { process.outputStream.close() } catch (_: Exception) {}
            val outputSb = StringBuilder()
            val reader = Thread {
                try {
                    outputSb.append(process.inputStream.bufferedReader().use { it.readText() })
                } catch (_: Exception) {}
            }
            reader.isDaemon = true
            reader.start()

            val finished = process.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                try { process.destroyForcibly() } catch (_: Exception) {}
                try { reader.join(300) } catch (_: Exception) {}
                Log.w("MainActivity", "Root check timed out after 3s")
                viewModel.setRootAvailable(false)
                return@withContext
            }

            try { reader.join(500) } catch (_: Exception) {}
            val exitCode = try { process.exitValue() } catch (_: Exception) { -1 }
            val output = outputSb.toString()
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

private data class SlashCommandItem(
    val command: String,
    val name: String,
    val description: String,
    val action: () -> Unit
)

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
    var textInput by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val slashCommands = remember(context) {
        listOf(
            SlashCommandItem(
                command = "/code",
                name = "Coding Persona",
                description = "Toggle active persona coding.md (pure headless synthesis, multi-worker staging)",
                action = { viewModel.togglePersonaQuick("coding.md", context) }
            ),
            SlashCommandItem(
                command = "/sys",
                name = "System Controller",
                description = "Toggle active persona android_controller.md (root/shell execution, hardware tools)",
                action = { viewModel.togglePersonaQuick("android_controller.md", context) }
            ),
            SlashCommandItem(
                command = "/chat",
                name = "Conversational",
                description = "Toggle active persona chat.md (natural reasoning, dialect mirroring)",
                action = { viewModel.togglePersonaQuick("chat.md", context) }
            ),
            SlashCommandItem(
                command = "/rollback",
                name = "Instant Revert",
                description = "Atomically swap production files with the latest local snapshot",
                action = { viewModel.executeRollback(context) }
            ),
            SlashCommandItem(
                command = "/preview",
                name = "Live Canvas",
                description = "Launch the responsive in-app WebView drawer",
                action = { viewModel.openLiveCanvas() }
            ),
            SlashCommandItem(
                command = "/clear",
                name = "Workspace Flush",
                description = "Clear the central terminal frame list and reset turn state",
                action = { viewModel.clearConversation(context) }
            )
        )
    }

    val surfaceColor = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
    val indigoAccent = MaterialTheme.colorScheme.primary
    val cyanAccent = indigoAccent
    val primaryAccent = MaterialTheme.colorScheme.primary
    val secondaryAccent = MaterialTheme.colorScheme.secondary
    val inputBg = MaterialTheme.colorScheme.surfaceContainer

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

    // Kinetic Stream-Tracking: collect the lightweight scrollSignal SharedFlow instead of
    // re-keying on streamVersion (which triggered a full LaunchedEffect relaunch per token).
    LaunchedEffect(viewModel.scrollSignal) {
        viewModel.scrollSignal.collect {
            if (!userScrolledAway && !listState.isScrollInProgress && uiState.logs.isNotEmpty()) {
                listState.scrollToItem(uiState.logs.size - 1, scrollOffset = 100_000)
            }
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

            // Consolidated Master Header Frame (Single Structured Container)
            ConsolidatedMasterHeader(
                context = context,
                uiState = uiState,
                isDark = isDark,
                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                onOpenSettings = { showSettings = true }
            )

            // Fixed Central Workspace Chassis (Outer Frame Geometry)
            // Dedicated structural frame wrapping the entire dynamic interaction zone
            val chassisBg = if (isDark) Color(0xFF0B0F17) else Color(0xFFFFFFFF)
            val chassisBorder = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 5.dp, end = 5.dp, top = 5.dp, bottom = 5.dp)
                    .clip(RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp),
                color = chassisBg,
                border = BorderStroke(1.dp, chassisBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                ) {
                    if (uiState.logs.isEmpty() && !uiState.isProcessing) {
                        CliWelcomeScreen(
                            onSelectPrompt = { p ->
                                viewModel.sendCommand(p, context = context)
                            }
                        )
                    } else {
                        // Isolated Internal Scroll Engine (Strict Boundary Clipping)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds(),
                            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(uiState.logs, key = { it.id }) { log ->
                                CliLogMessageView(log = log, isDark = isDark)
                            }
                        }

                        // Floating Jump to Latest Button inside the Chassis Viewport
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
                                shape = RoundedCornerShape(6.dp),
                                color = if (isDark) Color(0xFF161B22).copy(alpha = 0.95f) else Color(0xFFF1F5F9).copy(alpha = 0.95f),
                                border = BorderStroke(1.dp, if (isDark) Color(0xFF30363D) else Color(0xFFCBD5E1))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Filled.ArrowDownward, null, tint = primaryAccent, modifier = Modifier.size(11.dp))
                                    Text(
                                        text = if (uiState.isProcessing) "● Live Stream Tail ↓" else "Jump to Latest ↓",
                                        color = primaryAccent,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
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
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    // 2. Containerless Dynamic Activity Indicator (Pure Typographic Indicator)
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

                    val dotColor = if (isBusy) primaryAccent else MaterialTheme.colorScheme.primary
                    val textColor = if (isBusy) primaryAccent else textDim

                    // Raw Inline Status: Completely unboxed, zero card, zero elevation, plain floating text
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isBusy) {
                            NeonDottedSpinner(modifier = Modifier.size(14.dp))
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                        }
                        Text(
                            text = statusText,
                            color = textColor,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // MODULE 2: Slash Command Autocomplete Drop-Up Card (/ Trigger)
                    if (textInput.startsWith("/")) {
                        val query = textInput.lowercase().trim()
                        val matchingCommands = slashCommands.filter { 
                            it.command.startsWith(query) || it.name.lowercase().contains(query.removePrefix("/"))
                        }
                        if (matchingCommands.isNotEmpty()) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 5.dp, end = 5.dp, bottom = 8.dp)
                                    .heightIn(max = 200.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF111827),
                                border = BorderStroke(1.dp, Color(0xFF00D2FF).copy(alpha = 0.3f)),
                                shadowElevation = 8.dp
                            ) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    items(matchingCommands, key = { it.command }) { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    val action = item.action
                                                    textInput = ""
                                                    action()
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = item.command,
                                                        color = Color(0xFF00D2FF),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                    Text(
                                                        text = "— ${item.name}",
                                                        color = Color(0xFFE2E8F0),
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                                Text(
                                                    text = item.description,
                                                    color = Color(0xFF94A3B8),
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // MODULE 6: Persona & Action Quick-Chips Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 5.dp, end = 5.dp, bottom = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val activePersonasSet by viewModel.activePersonas.collectAsState()
                        val isCodeActive = activePersonasSet.contains("coding.md")
                        val isSysActive = activePersonasSet.contains("android_controller.md")
                        val isChatActive = activePersonasSet.contains("chat.md")

                        // [/code]
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.togglePersonaQuick("coding.md", context) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isCodeActive) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF161B22),
                            border = BorderStroke(1.dp, if (isCodeActive) Color(0xFF00E5FF) else Color(0xFF30363D))
                        ) {
                            Text(
                                text = "/code",
                                color = if (isCodeActive) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isCodeActive) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }

                        // [/sys]
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.togglePersonaQuick("android_controller.md", context) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSysActive) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF161B22),
                            border = BorderStroke(1.dp, if (isSysActive) Color(0xFF00E5FF) else Color(0xFF30363D))
                        ) {
                            Text(
                                text = "/sys",
                                color = if (isSysActive) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSysActive) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }

                        // [/chat]
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.togglePersonaQuick("chat.md", context) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isChatActive) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF161B22),
                            border = BorderStroke(1.dp, if (isChatActive) Color(0xFF00E5FF) else Color(0xFF30363D))
                        ) {
                            Text(
                                text = "/chat",
                                color = if (isChatActive) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isChatActive) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }

                        // [/rollback]
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.executeRollback(context) },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF161B22),
                            border = BorderStroke(1.dp, Color(0xFF30363D))
                        ) {
                            Text(
                                text = "/rollback",
                                color = Color(0xFFF59E0B),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }

                        // [/preview]
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.toggleLiveCanvas() },
                            shape = RoundedCornerShape(8.dp),
                            color = if (uiState.isLiveCanvasOpen) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFF161B22),
                            border = BorderStroke(1.dp, if (uiState.isLiveCanvasOpen) Color(0xFF10B981) else Color(0xFF30363D))
                        ) {
                            Text(
                                text = "/preview",
                                color = if (uiState.isLiveCanvasOpen) Color(0xFF10B981) else Color(0xFF94A3B8),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (uiState.isLiveCanvasOpen) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Unified Slim Input Capsule (Internalized Inline Actions)
                    val inputIsArabic = isArabic(textInput)
                    val canSend = textInput.isNotBlank() || selectedFileUri != null

                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (inputIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 5.dp, end = 5.dp, bottom = 6.dp)
                                .heightIn(min = 42.dp, max = 96.dp)
                                .tactileGlassGlow(cornerRadius = 14.dp, isDark = isDark, borderWidth = 0.dp),
                            color = inputBg,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: Expandable Text Field Area
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    BasicTextField(
                                        value = textInput,
                                        onValueChange = { textInput = it },
                                        textStyle = TextStyle(
                                            color = textBright,
                                            fontSize = 12.5.sp,
                                            fontFamily = if (inputIsArabic) FontFamily.SansSerif else FontFamily.Monospace,
                                            textAlign = TextAlign.Start
                                        ),
                                        cursorBrush = SolidColor(cyanAccent),
                                        maxLines = 4,
                                        modifier = Modifier.fillMaxWidth(),
                                        decorationBox = { inner ->
                                            if (textInput.isEmpty()) {
                                                Text(
                                                    text = if (inputIsArabic) "رسالة إلى MEDLAM..." else "Message MEDLAM...",
                                                    style = TextStyle(
                                                        color = textDim,
                                                        fontSize = 12.sp,
                                                        fontFamily = if (inputIsArabic) FontFamily.SansSerif else FontFamily.Monospace,
                                                        textAlign = TextAlign.Start
                                                    ),
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                            inner()
                                        }
                                    )
                                }

                                // Right: Internalized Inline Action Controls (Unified 30dp footprint, shared surface, zero overflow)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    // 1. File Attachment Action
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .clickable { filePickerLauncher.launch(arrayOf("*/*")) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.AttachFile,
                                            contentDescription = "Attach",
                                            tint = if (selectedFileUri != null) cyanAccent else textDim,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    // 2. Microphone / Voice Input Action
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                isRecording = true
                                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-MA")
                                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
                                                }
                                                try { speechLauncher.launch(intent) } catch (_: Exception) { isRecording = false }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isRecording) Icons.Filled.Mic else Icons.Outlined.Mic,
                                            contentDescription = "Mic",
                                            tint = if (isRecording) Color(0xFFEF4444) else textDim,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    // 3. Minimalist Red Square Stop Glyph / Idle Send Arrow Action
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .clickable(enabled = uiState.isProcessing || canSend) {
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
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (uiState.isProcessing) {
                                            // Minimalist Red Square Stop Glyph (⏹) anchored directly on shared surface
                                            Box(
                                                modifier = Modifier
                                                    .size(11.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(Color(0xFFEF4444))
                                            )
                                        } else {
                                            // Clean Send Arrow Icon (↑)
                                            Icon(
                                                imageVector = Icons.Default.ArrowUpward,
                                                contentDescription = "Send",
                                                tint = if (canSend) cyanAccent else textDim.copy(alpha = 0.35f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
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

    LiveCanvasDrawer(
        isOpen = uiState.isLiveCanvasOpen,
        onDismiss = { viewModel.closeLiveCanvas() }
    )
}

/**
 * Vibrant Micro-3D Telemetry Indicator Chip.
 * Features subtle depth, specular highlight gradients, and distinct hardware theme color-coding.
 */
@Composable
private fun TelemetryMicroChip(
    icon: ImageVector,
    text: String,
    accentColor: Color,
    contentDescription: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(3.5.dp),
        color = accentColor.copy(alpha = 0.08f),
        border = BorderStroke(
            0.5.dp,
            Brush.verticalGradient(
                listOf(accentColor.copy(alpha = 0.40f), accentColor.copy(alpha = 0.12f))
            )
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = accentColor,
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = text,
                color = textColor,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Model Engine Lifecycle Beacon (Neural Core Status Indicator).
 * Lifecycle State Machine:
 * - Loading / Booting (INITIALIZING / LOADING): Animated breathing green beacon (#10B981 / #22C55E) with soft outward halo.
 * - Ready / Operational (LOADED / READY): Crisp, solid static green indicator (#22C55E) without animation.
 * - Error / Fallback: Solid red/amber error indicator (#EF4444 / #F59E0B).
 */
@Composable
fun ModelLifecycleBeacon(
    isLoading: Boolean,
    isReady: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        val pulseTransition = rememberInfiniteTransition(label = "beaconPulse")
        val alphaAnim by pulseTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(750, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "beaconAlpha"
        )
        val haloScale by pulseTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(750, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "beaconScale"
        )

        Box(
            modifier = modifier.size(10.dp),
            contentAlignment = Alignment.Center
        ) {
            // Soft outward green halo (40% to 100% alpha breathing pulse)
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .scale(haloScale)
                    .clip(CircleShape)
                    .background(Color(0xFF22C55E).copy(alpha = (1.8f - haloScale) * 0.45f * alphaAnim))
            )
            // Vibrant green core beacon (#10B981 / #22C55E)
            Box(
                modifier = Modifier
                    .size(5.5.dp)
                    .alpha(alphaAnim)
                    .clip(CircleShape)
                    .background(Color(0xFF22C55E))
            )
        }
    } else {
        val dotColor = when {
            isReady -> Color(0xFF22C55E)
            isError -> Color(0xFFEF4444)
            else -> MaterialTheme.colorScheme.outline
        }
        Box(
            modifier = modifier.size(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(5.5.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}

/**
 * Consolidated Master Header Frame docked at the top of the viewport.
 * Single structured container docked at top of viewport with 3 organized internal rows:
 * - Top Row: Tactile micro-card drawer toggle, centered MEDLAM-AI AGENT title with status badge (IDLE / RUNNING), tactile micro-card settings gear.
 * - Middle Row: Compact inline spec chip detailing active Base Model & Companion MTP profile (● GEMMA-4-E2B Q2 • MTP: MTP-GEMMA-4-E2B-IT-BF16).
 * - Bottom Row: Vibrant micro-3D hardware telemetry ribbon: CPU Usage • RAM • Thermals • Storage • Auth Route • MTP Accelerator • Task Queue.
 * Minimal internal vertical padding to retain maximum screen real estate for message stream.
 */
@Composable
fun ConsolidatedMasterHeader(
    context: Context,
    uiState: AgentUiState,
    isDark: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var stats by remember { mutableStateOf(SystemStats(0, false, "--", "--", "--", "--", 0, 0, 0)) }

    LaunchedEffect(Unit) {
        while (true) {
            val nextStats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                getSystemStats(context)
            }
            stats = nextStats
            kotlinx.coroutines.delay(2500)
        }
    }

    val headerBg = MaterialTheme.colorScheme.surfaceContainer
    val subCardBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceVariant
    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryAccent = MaterialTheme.colorScheme.primary
    val secondaryAccent = MaterialTheme.colorScheme.secondary

    val totalTasks = maxOf(uiState.activeTasks.size, uiState.taskQueueSize)

    // Solid Master Header Card docked at top of viewport along the unified 5.dp horizontal grid rail
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 5.dp, end = 5.dp, top = 4.dp, bottom = 2.dp)
            .tactileCardElevation(isDark, cornerRadius = 14.dp),
        color = headerBg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // -------------------------------------------------------------
            // 1. TOP ROW: Left-Aligned [Menu Card] MEDLAM-AI AGENT [Status Badge] ──── [Settings Card] (Right)
            // -------------------------------------------------------------
            val cyanAccent = Color(0xFF00D2FF)
            val cyanBorder = Color(0xFF00B4D8)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left Anchor: Tactile micro-card drawer toggle button + MEDLAM-AI AGENT title + status badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Sidebar Drawer Button: Tactile micro-plate container with ambient cyan back-glow
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(cyanAccent.copy(alpha = 0.12f), RoundedCornerShape(7.dp))
                    ) {
                        Surface(
                            onClick = onOpenDrawer,
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(cyanAccent.copy(alpha = 0.6f), cyanBorder.copy(alpha = 0.25f))
                                )
                            ),
                            shadowElevation = 2.dp,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    imageVector = Icons.Outlined.Widgets,
                                    contentDescription = "Menu",
                                    tint = cyanAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "MEDLAM-AI AGENT",
                        color = textBright,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.09.em
                    )

                    // Solid Status Badge docked inline on exact baseline
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (uiState.isProcessing) primaryAccent.copy(alpha = 0.15f) else subCardBg
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(if (uiState.isProcessing) primaryAccent else textDim)
                            )
                            Text(
                                text = if (uiState.isProcessing) "RUNNING" else "IDLE",
                                color = if (uiState.isProcessing) primaryAccent else textDim,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Right: Symmetric micro-plate settings gear icon anchored flush to far right
                // Full visual parity with left drawer button: neon cyan accent, gradient border, and ambient cyan back-glow
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(cyanAccent.copy(alpha = 0.12f), RoundedCornerShape(7.dp))
                ) {
                    Surface(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(cyanAccent.copy(alpha = 0.6f), cyanBorder.copy(alpha = 0.25f))
                            )
                        ),
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Settings",
                                tint = cyanAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // 2. MIDDLE ROW: Centered active model chip (Base Model & Companion MTP)
            // (● GEMMA-4-E2B Q2 • MTP: MTP-GEMMA-4-E2B-IT-BF16)
            // -------------------------------------------------------------
            val isCloudMode = uiState.activeModelType == ModelType.GEMINI || (uiState.activeModelType == null && uiState.config.preferredModelType == ModelType.GEMINI)
            val isLocalMode = !isCloudMode
            val effectiveDraftPath = if (uiState.localModelLoaded) {
                uiState.activeDraftModelPath
            } else {
                uiState.config.draftModelPath.ifBlank { null }
            }
            val hasMtp = !effectiveDraftPath.isNullOrBlank() && effectiveDraftPath != "STANDALONE" && File(effectiveDraftPath).exists()
            val draftFile = if (hasMtp) File(effectiveDraftPath!!) else null
            val draftName = draftFile?.nameWithoutExtension
            val draftTag = if (hasMtp && draftName != null) {
                val quantRegex = Regex("""(?i)(Q\d+_[A-Z0-9_]+|BF16|FP16|F16|F32)""")
                val match = quantRegex.find(draftName)
                match?.value?.uppercase() ?: draftName.removePrefix("mtp-").removePrefix("gemma-4-").removePrefix("it-")
            } else null
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

            val tpsSuffix = if (uiState.tokensPerSecond > 0f) {
                val formattedTps = String.format(Locale.US, "%.1f", uiState.tokensPerSecond)
                " • $formattedTps T/s"
            } else ""

            val specChipText = if (isCloudMode) {
                "GEMINI CLOUD • ${uiState.config.geminiModelName.uppercase()}$tpsSuffix"
            } else if (hasMtp && draftTag != null) {
                "${cleanBaseName.uppercase()} • MTP: $draftTag$tpsSuffix"
            } else {
                "STANDALONE$tpsSuffix"
            }

            val isModelLoading = uiState.localModelLoading || (!uiState.localModelLoaded && uiState.loadingProgress != null)
            val isModelReady = (uiState.localModelLoaded && !isModelLoading) || (isCloudMode && uiState.config.geminiApiKeys.isNotBlank())
            val isModelError = uiState.modelLoadFailed || (isCloudMode && uiState.config.geminiApiKeys.isBlank()) || (isLocalMode && !uiState.localModelLoaded && !isModelLoading && uiState.config.localModelPath.isNotBlank() && uiState.streamInterruptMsg != null)

            Surface(
                shape = RoundedCornerShape(5.dp),
                color = subCardBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    ModelLifecycleBeacon(
                        isLoading = isModelLoading,
                        isReady = isModelReady,
                        isError = isModelError
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = specChipText,
                        color = textDim,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // -------------------------------------------------------------
            // 3. BOTTOM ROW: Hardware telemetry ribbon (Disentangled 7-item sequence)
            // Showing: CPU Usage • RAM • Thermals • Storage • Auth Route • MTP Accelerator • Task Queue
            // -------------------------------------------------------------
            val cpuColor = Color(0xFF10B981) // Dynamic emerald
            val ramColor = Color(0xFF06B6D4) // Electric cyan
            val thermalColor = when {
                stats.cpuTempInt <= 0 -> Color(0xFF2DD4BF)
                stats.cpuTempInt < 50 -> Color(0xFF2DD4BF) // Turquoise <50°C
                stats.cpuTempInt <= 75 -> Color(0xFFF59E0B) // Warm amber 50–75°C
                else -> Color(0xFFEF4444) // Coral red >75°C
            }
            val storageColor = Color(0xFF38BDF8) // Titanium / azure
            val registeredKeysList = remember(uiState.config.geminiApiKeys) {
                uiState.config.geminiApiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
            val keyPoolSize = if (uiState.totalKeys > 0) uiState.totalKeys else registeredKeysList.size
            val authText = if (isCloudMode) {
                if (keyPoolSize > 0) {
                    val activeKeyNum = (uiState.currentKeyIndex % keyPoolSize) + 1
                    "$activeKeyNum/$keyPoolSize"
                } else {
                    "NO KEY"
                }
            } else {
                if (uiState.localModelLoaded) "LOCAL" else "N/A"
            }
            val authColor = if (isCloudMode) {
                if (keyPoolSize > 0) Color(0xFFA855F7) else Color(0xFFEF4444)
            } else {
                if (uiState.localModelLoaded) Color(0xFFA855F7) else textDim.copy(alpha = 0.6f)
            }
            val mtpColor = if (hasMtp) Color(0xFF00E5FF) else textDim.copy(alpha = 0.6f) // Active cyan / dim
            val mtpText = if (hasMtp) "MTP ON" else "OFF"
            val taskColor = Color(0xFF818CF8) // Vibrant indigo

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. CPU Usage: Dedicated chip icon [CPU] followed by live load %
                TelemetryMicroChip(
                    icon = Icons.Filled.DeveloperBoard,
                    text = stats.cpuUsage,
                    accentColor = cpuColor,
                    contentDescription = "CPU Usage"
                )

                // 2. RAM Allocation: Memory module icon [RAM] followed by active working set size
                TelemetryMicroChip(
                    icon = Icons.Filled.Memory,
                    text = stats.ramFree,
                    accentColor = ramColor,
                    contentDescription = "RAM Allocation"
                )

                // 3. Thermals: Dedicated thermometer glyph followed strictly by hardware temp in °C
                TelemetryMicroChip(
                    icon = Icons.Filled.Thermostat,
                    text = stats.cpuTemp,
                    accentColor = thermalColor,
                    contentDescription = "Thermals"
                )

                // 4. Storage: Hard drive icon followed strictly by available storage
                TelemetryMicroChip(
                    icon = Icons.Filled.Storage,
                    text = stats.storageFree,
                    accentColor = storageColor,
                    contentDescription = "Storage"
                )

                // 5. Auth / Engine Route: Key glyph with active key counter (e.g. 1/5, 2/8) or LOCAL fallback
                TelemetryMicroChip(
                    icon = Icons.Filled.VpnKey,
                    text = authText,
                    accentColor = authColor,
                    contentDescription = "Active API Key / Pool"
                )

                // 6. Hardware Accelerator: Lightning bolt indicating MTP status (MTP ON or OFF)
                TelemetryMicroChip(
                    icon = Icons.Filled.Bolt,
                    text = mtpText,
                    accentColor = mtpColor,
                    textColor = mtpColor,
                    contentDescription = "Hardware Accelerator"
                )

                // 7. Task Queue: Clipboard/task glyph with active task count (TSK 0)
                TelemetryMicroChip(
                    icon = Icons.Filled.Assignment,
                    text = "TSK $totalTasks",
                    accentColor = taskColor,
                    contentDescription = "Task Queue"
                )
            }
        }
    }
}

/**
 * CLI Terminal Activity Trace for Thinking & Reasoning.
 * Replaces the nested "Reasoning Tray" card with an authentic live terminal activity trace:
 * > Thinking Process: [● LIVE / (N tokens)] (collapse / expand)
 * Followed by the reasoning body indented with a thin vertical left gutter line,
 * dimmed / italicized monospace text, and real-time blinking block cursor ▌.
 */
@Composable
fun CliThoughtContainer(
    thinkingText: String,
    isThinking: Boolean,
    isDark: Boolean
) {
    if (thinkingText.isBlank() && !isThinking) return

    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val isExpanded = userExpanded ?: isThinking

    // Conditional animation transitions: isolated strictly to when streaming/thinking is actively running
    val alphaAnim = if (isThinking) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val alpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )
        alpha
    } else {
        1f
    }

    val cursorAlpha = if (isThinking) {
        val cursorTransition = rememberInfiniteTransition(label = "cursor")
        val cAlpha by cursorTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cursorAlpha"
        )
        cAlpha
    } else {
        0f
    }

    val traceColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val traceAccent = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    val gutterColor = if (isDark) Color(0xFF334155) else Color(0xFFCBD5E1)
    val amberBullet = if (isDark) Color(0xFFF59E0B) else Color(0xFFB45309)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        // Line Header: > Thinking Process : [● LIVE / (N tokens)] (collapse / expand)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = ">",
                color = traceAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Thinking Process :",
                color = traceColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            if (isThinking) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Single crisp pulsing beacon dot preceding LIVE text
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .alpha(alphaAnim)
                            .clip(CircleShape)
                            .background(amberBullet)
                    )
                    Text(
                        text = "LIVE",
                        color = amberBullet,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                val tokenCount = thinkingText.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                Text(
                    text = "($tokenCount tokens)",
                    color = traceColor.copy(alpha = 0.7f),
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = if (isExpanded) "(collapse)" else "(expand)",
                color = traceAccent,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable { userExpanded = !isExpanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        // Expanded Reasoning Body with vertical gutter line
        AnimatedVisibility(visible = isExpanded) {
            val isThoughtRtl = isRtlText(thinkingText)
            CompositionLocalProvider(
                LocalLayoutDirection provides if (isThoughtRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 3.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .padding(top = 2.dp)
                            .background(gutterColor)
                    )

                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            if (thinkingText.isNotBlank()) {
                                val content = if (isThoughtRtl) applyBidiIsolation(thinkingText) else thinkingText
                                append(content)
                                if (isThinking) {
                                    pushStyle(SpanStyle(color = traceAccent.copy(alpha = cursorAlpha), fontWeight = FontWeight.Bold))
                                    append(" ▌")
                                    pop()
                                }
                            } else {
                                append("Formulating architecture & execution strategy...")
                                if (isThinking) {
                                    pushStyle(SpanStyle(color = traceAccent.copy(alpha = cursorAlpha), fontWeight = FontWeight.Bold))
                                    append(" ▌")
                                    pop()
                                }
                            }
                        },
                        color = traceColor,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontStyle = FontStyle.Italic,
                        lineHeight = if (isThoughtRtl) 17.sp else 15.5.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Sleek Terminal Code Block Container with terminal ASCII header bar:
 * ┌── <LANG> • <FILE_PATH> ─────── [COPY] [-]
 * Monospace syntax highlighting, horizontally scrollable, 4dp corner radius.
 */
@Composable
fun CliCodeBlockContainer(
    code: String,
    language: String,
    filePath: String?,
    isDark: Boolean,
    isStreaming: Boolean = false
) {
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val isExpanded = userExpanded ?: true
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val livePulse = if (isStreaming) {
        val transition = rememberInfiniteTransition(label = "code_live_pulse")
        val alpha by transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(650, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "code_live_alpha"
        )
        alpha
    } else {
        1f
    }

    val blockBg = if (isDark) Color(0xFF0D1117) else Color(0xFFF8FAFC)
    val headerBg = if (isDark) Color(0xFF161B22) else Color(0xFFF1F5F9)
    val borderColor = if (isDark) Color(0xFF21262D) else Color(0xFFE2E8F0)
    val langAccent = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
    val pathColor = if (isDark) Color(0xFFFFB86C) else Color(0xFFD97706)
    val textDim = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(4.dp),
        color = blockBg,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column {
            // Header Bar: Minimalist Terminal Bar with Locked 30dp Height & Strict Single-Row Guardrails
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(headerBg)
                    .clipToBounds()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: ┌── <LANG> • <FILE_PATH> (Strict Single Line)
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "┌──",
                        color = textDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Text(
                        text = language.uppercase().ifBlank { "CODE" },
                        color = langAccent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    if (!filePath.isNullOrBlank()) {
                        Text(
                            text = "•",
                            color = textDim,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        Text(
                            text = filePath,
                            color = pathColor,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Right-Hand Action Cluster: [● LIVE] [COPY] [-]/[+] on strict single center baseline
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Micro-Scale Live Badge: docked directly to the left of the [COPY] action button
                    if (isStreaming) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(4.5.dp)
                                    .alpha(livePulse)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                            Text(
                                text = "LIVE",
                                color = Color(0xFF10B981),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.04.em,
                                maxLines = 1
                            )
                        }
                    }

                    Text(
                        text = if (copied) "[COPIED]" else "[COPY]",
                        color = if (copied) Color(0xFF10B981) else textDim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier
                            .clickable {
                                clipboard.setText(AnnotatedString(code))
                                copied = true
                                coroutineScope.launch {
                                    delay(1800)
                                    copied = false
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    Text(
                        text = if (isExpanded) "[-]" else "[+]",
                        color = textDim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier
                            .clickable { userExpanded = !isExpanded }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // Horizontally Scrollable Monospace Code
            AnimatedVisibility(visible = isExpanded) {
                val highlighted = remember(code, language, isDark) {
                    SyntaxHighlighter.highlight(code, language, isDark)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = highlighted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.5.sp,
                        color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF0F172A)
                    )
                }
            }
        }
    }
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
    if (cleanLang in setOf("thought", "thinking", "reasoning")) {
        return false
    }
    return true
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
        val lower = trimmed.lowercase()
        val isCodeLeak = (trimmed.startsWith("<") && (trimmed.endsWith(">") || trimmed.contains("</") || lower.startsWith("<!doctype"))) ||
                         lower.startsWith("function ") || lower.startsWith("function(") ||
                         lower.startsWith("const ") || lower.startsWith("var ") || lower.startsWith("let ") ||
                         lower.startsWith("import ") || lower.startsWith("export ") ||
                         lower.startsWith("class ") || lower.startsWith("def ") ||
                         lower.startsWith("return ") ||
                         (trimmed.contains("{") && trimmed.contains("}")) ||
                         lower.contains("margin:") || lower.contains("padding:") ||
                         lower.contains("color:") || lower.contains("display:") ||
                         lower.contains("background:") || lower.contains("font-size:")

        if (!isCodeLeak) {
            sanitized.add(line)
        }
    }
    return sanitized.joinToString("\n").trim()
}

/**
 * CLI Terminal Execution Line for Tool Invocations, Shell Commands & Process Outputs.
 * Formats:
 * ● ManageTask(...) [expand/collapse]
 * ● Bash(...)
 * ● FileWrite(...)
 */
@Composable
fun CliTerminalToolLine(
    rawLine: String,
    isDark: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    val isManageTask = rawLine.contains("ManageTask", ignoreCase = true)
    val isBash = rawLine.contains("Bash", ignoreCase = true) || rawLine.startsWith("$ ")
    val isFileWrite = rawLine.contains("File written", ignoreCase = true) || rawLine.contains("Writing to", ignoreCase = true) || rawLine.contains("FileWrite", ignoreCase = true)
    val isSuccess = rawLine.contains("done", ignoreCase = true) || rawLine.contains("success", ignoreCase = true) ||
                    rawLine.contains("exit 0", ignoreCase = true) || rawLine.contains("Ready / Operational", ignoreCase = true) ||
                    rawLine.contains("Atomically promoted", ignoreCase = true) || rawLine.contains("Storage Synced", ignoreCase = true) ||
                    rawLine.contains("SAVED", ignoreCase = true) || rawLine.contains("LAUNCHED", ignoreCase = true) ||
                    rawLine.contains("VERIFIED", ignoreCase = true)
    val isFailed = rawLine.contains("failed", ignoreCase = true) || rawLine.contains("aborted", ignoreCase = true) ||
                   rawLine.contains("FAILED", ignoreCase = true) || rawLine.contains("error", ignoreCase = true) ||
                   rawLine.contains("Exhausted", ignoreCase = true)

    val isRunningOrchestrator = rawLine.contains("Orchestrator: Initializing", ignoreCase = true) ||
                                rawLine.contains("Orchestrator: Decomposed", ignoreCase = true) ||
                                rawLine.contains("Verifier: Auditing", ignoreCase = true) ||
                                rawLine.contains("Running...", ignoreCase = true) ||
                                rawLine.contains("Auto-extending token stream", ignoreCase = true) ||
                                rawLine.contains("Initiating remediation pass", ignoreCase = true)

    val isTelemetry = rawLine.contains("Storage Synced", ignoreCase = true) ||
                      rawLine.contains("Generation Chunk", ignoreCase = true) || rawLine.contains("Auto-extending", ignoreCase = true) ||
                      rawLine.contains("Execution Complete", ignoreCase = true) || rawLine.contains("Storage Sentinel", ignoreCase = true) ||
                      rawLine.contains("Orchestrator:", ignoreCase = true) || rawLine.contains("Verifier:", ignoreCase = true) ||
                      rawLine.contains("Production Sync:", ignoreCase = true) || rawLine.contains("Status:", ignoreCase = true)

    val bulletColor = when {
        rawLine.contains("Storage Sentinel", ignoreCase = true) || isFailed -> if (isDark) Color(0xFFEF4444) else Color(0xFFDC2626) // Red warning
        isRunningOrchestrator || isTelemetry -> if (isDark) Color(0xFF00D2FF) else Color(0xFF0284C7) // Neon Cyan
        isManageTask -> if (isDark) Color(0xFFF59E0B) else Color(0xFFB45309) // Amber/Orange
        isBash -> if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)       // Electric Cyan / Cobalt
        isSuccess || isFileWrite -> if (isDark) Color(0xFF10B981) else Color(0xFF059669) // Emerald / Green
        else -> if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    }

    val cmdColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF0F172A)
    val textDim = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val gutterColor = if (isDark) Color(0xFF334155) else Color(0xFFCBD5E1)

    val displayText = rawLine.trim().replace(Regex("^[●*•⚡\\s]+"), "")
    val hasExpandableOutput = !isTelemetry && (displayText.contains(" -> ") || displayText.contains("\n"))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isRunningOrchestrator) {
                NeonDottedSpinner(modifier = Modifier.size(14.dp))
            } else if (isSuccess) {
                Text(
                    text = "✔",
                    color = if (isDark) Color(0xFF10B981) else Color(0xFF059669),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            } else if (isFailed) {
                Text(
                    text = "✖",
                    color = if (isDark) Color(0xFFEF4444) else Color(0xFFDC2626),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = "●",
                    color = bulletColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = if (isTelemetry) displayText else displayText.substringBefore(" -> "),
                color = cmdColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (hasExpandableOutput) {
                Text(
                    text = if (expanded) "(collapse)" else "(expand)",
                    color = bulletColor,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        if (hasExpandableOutput && expanded) {
            val output = displayText.substringAfter(" -> ", "")
            if (output.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .background(gutterColor)
                    )
                    Text(
                        text = output,
                        color = textDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CliLogMessageView(
    log: LogEntry,
    isDark: Boolean
) {
    val clipboard = LocalClipboardManager.current
    val isUser = log.sender == "USER"
    val isSystem = log.sender == "SYSTEM"

    val textBright = if (isDark) Color(0xFFE2E8F0) else Color(0xFF0F172A)
    val textDim = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val primaryAccent = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)

    val isLogArabic = isArabic(log.text)

    when {
        isUser -> {
            // Authentic Developer Terminal Input Prompt: ❯ <prompt>
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
            ) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isLogArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isLogArabic) "❮" else "❯",
                            color = primaryAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 1.dp)
                        )

                        Text(
                            text = if (isLogArabic) applyBidiIsolation(log.text) else log.text,
                            color = textBright,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal,
                            lineHeight = if (isLogArabic) 18.5.sp else 17.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp)),
                    color = textDim.copy(alpha = 0.35f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 1.dp)
                )
            }
        }

        isSystem -> {
            // Clean Terminal System & Error Output Lines
            val isError = log.isError
            val bulletColor = if (isError) Color(0xFFEF4444) else textDim
            val marker = if (isError) "✖ Error:" else "● System:"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = marker,
                    color = bulletColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = log.text,
                    color = if (isError) Color(0xFFEF4444) else textDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        else -> {
            // AI Agent Terminal Output Stream: Direct continuous vertical flow, NO outer card container
            val demuxed = remember(log.text, log.thinkingText, log.isThinking) {
                StreamDemultiplexer.demux(
                    rawText = log.text,
                    externalThinkingText = log.thinkingText,
                    externalIsThinking = log.isThinking
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1. Thinking Process Activity Trace
                if (demuxed.thinkingText.isNotBlank() || demuxed.isThinking) {
                    CliThoughtContainer(
                        thinkingText = demuxed.thinkingText,
                        isThinking = demuxed.isThinking,
                        isDark = isDark
                    )
                }

                // 2. Sequential Blocks (Prose, Code, Tools, and Terminal Frames)
                if (demuxed.blocks.isNotEmpty()) {
                    demuxed.blocks.forEach { block ->
                        key(when (block) {
                            is StreamDemultiplexer.DemuxBlock.TerminalFrame -> block.executionId
                            is StreamDemultiplexer.DemuxBlock.ToolCall -> block.executionId
                            is StreamDemultiplexer.DemuxBlock.Code -> "${block.filePath}_${block.language}_${block.code.hashCode()}"
                            is StreamDemultiplexer.DemuxBlock.Prose -> "prose_${block.text.hashCode()}"
                        }) {
                            when (block) {
                                is StreamDemultiplexer.DemuxBlock.Prose -> {
                                    CliFormattedBody(block.text, isDark)
                                }
                                is StreamDemultiplexer.DemuxBlock.Code -> {
                                    CliCodeBlockContainer(
                                        code = block.code,
                                        language = block.language,
                                        filePath = block.filePath,
                                        isDark = isDark,
                                        isStreaming = !block.isClosed
                                    )
                                }
                                is StreamDemultiplexer.DemuxBlock.ToolCall -> {
                                    CliToolCallEvent(block, isDark)
                                }
                                is StreamDemultiplexer.DemuxBlock.TerminalFrame -> {
                                    CliTerminalFrame(block, isDark)
                                }
                            }
                        }
                    }
                }

                // 3. Subtle Inline Turn Indicator with Copy Action
                if (!demuxed.isThinking && log.text.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "[copy turn]",
                            color = textDim.copy(alpha = 0.6f),
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { clipboard.setText(AnnotatedString(log.text)) }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Text(
                            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp)),
                            color = textDim.copy(alpha = 0.4f),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CliTerminalFrame(block: StreamDemultiplexer.DemuxBlock.TerminalFrame, isDark: Boolean) {
    val clipboard = LocalClipboardManager.current

    // Clean output to filter out redundant completion status strings
    val rawOutput = block.output.trim()
    val cleanOutput = rawOutput
        .replace(Regex("""\[(?:Command|Process) completed with exit code \d+(?: \(no output\))?\]""", RegexOption.IGNORE_CASE), "")
        .trim()

    // Auto-collapse if command + output exceeds 8 lines
    val cmdLines = block.command.lines()
    val outLines = if (cleanOutput.isNotBlank()) cleanOutput.lines() else emptyList()
    val totalLines = cmdLines.size + outLines.size
    val isLongOutput = totalLines > 8

    var isExpanded by remember(block.executionId, block.command) { mutableStateOf(!isLongOutput) }
    var copiedRecently by remember { mutableStateOf(false) }

    LaunchedEffect(copiedRecently) {
        if (copiedRecently) {
            delay(1500)
            copiedRecently = false
        }
    }

    val accentColor = when {
        block.isRunning -> Color(0xFF38BDF8) // Cyan for running
        block.exitCode == 0 -> Color(0xFF10B981) // Emerald for exit 0
        else -> Color(0xFFEF4444) // Coral Red for non-zero errors
    }

    val obsidianBg = Color(0xFF0B0F17)
    val subtleBorder = Color.White.copy(alpha = 0.08f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(6.dp),
        color = obsidianBg,
        border = BorderStroke(1.dp, subtleBorder)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Left Accent Indicator Bar
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .fillMaxHeight()
                    .background(accentColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // Header: ┌─ TERMINAL • bash [exit: 0]      [COPY] [-] ──┐
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (block.isRunning) {
                            NeonDottedSpinner(modifier = Modifier.size(14.dp))
                        } else {
                            Text(
                                text = if (block.exitCode == 0) "✔" else "✖",
                                color = accentColor,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = if (block.isRunning) "TERMINAL • bash [running...]" else "TERMINAL • bash [exit: ${block.exitCode}]",
                            color = accentColor,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (copiedRecently) "[COPIED]" else "[COPY]",
                            color = if (copiedRecently) Color(0xFF10B981) else Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable {
                                    val copyText = buildString {
                                        append("$ ").append(block.command).append("\n")
                                        if (cleanOutput.isNotBlank()) {
                                            append(cleanOutput).append("\n")
                                        }
                                        if (!block.isRunning) {
                                            append("[Process completed with exit code ").append(block.exitCode).append("]")
                                        }
                                    }
                                    clipboard.setText(AnnotatedString(copyText))
                                    copiedRecently = true
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )

                        Text(
                            text = if (isExpanded) "[-]" else "[+]",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { isExpanded = !isExpanded }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Divider(color = subtleBorder, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(6.dp))

                // Terminal Execution Body
                if (isExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        // Command line: $ <command>
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "$ ",
                                color = accentColor,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = block.command,
                                color = Color(0xFFF1F5F9),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }

                        // Output content
                        if (cleanOutput.isNotBlank()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = cleanOutput,
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }

                        // Process completion indicator
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (block.isRunning) {
                                "[Process running...]"
                            } else {
                                "[Process completed with exit code ${block.exitCode}]"
                            },
                            color = accentColor.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )

                        if (isLongOutput) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "[-] Collapse console output",
                                color = Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable { isExpanded = false }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                } else {
                    // Collapsed preview: single-line prompt preview
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "$ ",
                                color = accentColor,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = cmdLines.firstOrNull() ?: block.command,
                                color = Color(0xFFF1F5F9),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = "[+] Show full console output ($totalLines lines)",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { isExpanded = true }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CliToolCallEvent(block: StreamDemultiplexer.DemuxBlock.ToolCall, isDark: Boolean) {
    val paramStr = block.params.entries.joinToString(", ") { "${it.key}: \"${it.value}\"" }
    val isRunning = !block.isClosed
    val statusSuffix = if (isRunning) " -> Dispatched" else " -> Completed"
    val displayText = buildString {
        append("Tool(")
        append(block.toolName)
        if (paramStr.isNotBlank()) {
            append(": ")
            append(paramStr)
        }
        append(")")
        append(statusSuffix)
    }

    Surface(
        color = if (isDark) Color(0xFF0F172A).copy(alpha = 0.85f) else Color(0xFFF1F5F9),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(0.5.dp, if (isDark) Color(0xFF38BDF8).copy(alpha = 0.35f) else Color(0xFF0284C7).copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isRunning) {
                NeonDottedSpinner(modifier = Modifier.size(14.dp))
            } else {
                Text(
                    text = "✔",
                    color = if (isDark) Color(0xFF10B981) else Color(0xFF059669),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = displayText,
                color = if (isRunning) (if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)) else (if (isDark) Color(0xFF10B981) else Color(0xFF059669)),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun CliFormattedBody(raw: String, isDark: Boolean) {
    // Cache sanitize + split only when raw text changes (avoids regex re-run on every recompose)
    val text = remember(raw) { StreamDemultiplexer.sanitizeProse(raw).trim() }
    if (text.isBlank()) return

    val textBright = if (isDark) Color(0xFFE2E8F0) else Color(0xFF0F172A)
    val textDim = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val headingColor = if (isDark) Color(0xFF38BDF8) else Color(0xFF0369A1)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val lines = remember(text) { text.lines() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // Check if this line is a tool or process event line
            val isToolLine = trimmed.startsWith("● ManageTask") || trimmed.startsWith("ManageTask(") ||
                             trimmed.startsWith("● Bash") || trimmed.startsWith("Bash(") ||
                             trimmed.startsWith("● FileWrite") || trimmed.startsWith("File written:") ||
                             trimmed.startsWith("Background task done:") || trimmed.startsWith("$ ") ||
                             trimmed.startsWith("Tool(") || trimmed.startsWith("✔ Tool(") || trimmed.startsWith("✖ Tool(") ||
                             trimmed.startsWith("Orchestrator:") || trimmed.startsWith("Verifier:") ||
                             trimmed.startsWith("Production Sync:") || trimmed.startsWith("Status:") ||
                             trimmed.startsWith("  ├─")

            if (isToolLine) {
                CliTerminalToolLine(trimmed, isDark)
                i++
                continue
            }

            val isLineArabic = isArabic(trimmed)
            val isExecConfirmation = trimmed.contains("Executive Deployment Confirmation")
            val isHeader = trimmed.startsWith("#") || (trimmed.endsWith(":") && trimmed.length < 50 && !trimmed.contains("http") && !trimmed.contains("/"))
            val isBullet = trimmed.startsWith("- ") || trimmed.startsWith("• ") || trimmed.matches(Regex("^[0-9]+[.)].*"))
            val clean = trimmed.replace(Regex("^#+\\s*"), "")

            CompositionLocalProvider(
                LocalLayoutDirection provides if (isLineArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                when {
                    isExecConfirmation -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "┌── EXECUTIVE DEPLOYMENT CONFIRMATION ────────────────────────",
                                color = headingColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "│ Status: Synced & Verified on Device Storage",
                                color = textDim,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                            Text(
                                text = "└────────────────────────────────────────────────────────────",
                                color = headingColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    isHeader -> {
                        Text(
                            text = if (isLineArabic) applyBidiIsolation(clean) else clean,
                            color = headingColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 2.dp)
                        )
                    }
                    isBullet -> {
                        val bulletMarker = if (trimmed.matches(Regex("^[0-9]+[.)].*"))) {
                            trimmed.substringBefore(" ") + " "
                        } else {
                            "• "
                        }
                        val bulletContent = clean.replace(Regex("^[-•0-9.)]+\\s*"), "")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = bulletMarker,
                                color = headingColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                text = buildOrganizedMarkdownString(bulletContent, isDark, isRtl = isLineArabic),
                                color = textBright,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = if (isLineArabic) 19.sp else 16.sp,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = buildOrganizedMarkdownString(clean, isDark, isRtl = isLineArabic),
                            color = textBright,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = if (isLineArabic) 19.sp else 16.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            i++
        }
    }
}

fun buildOrganizedMarkdownString(text: String, isDark: Boolean, isRtl: Boolean = false): AnnotatedString {
    val pathRegex = "(/sdcard/[a-zA-Z0-9_.-]+(?:/[a-zA-Z0-9_.-]+)*|/(?:data|storage|system|etc|proc|sys)/[a-zA-Z0-9_.-]+(?:/[a-zA-Z0-9_.-]+)*)"
    val inlineCodeRegex = "(`[^`]+`)"
    val boldRegex = "(\\*\\*[^*]+\\*\\*)"
    val combinedRegex = Regex("$pathRegex|$inlineCodeRegex|$boldRegex")

    val amberAccent = if (isDark) Color(0xFFFFB86C) else Color(0xFFD97706)
    val pathBg = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)
    val codeBg = if (isDark) Color(0xFF161B22) else Color(0xFFF1F5F9)
    val codeColor = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)

    val builder = AnnotatedString.Builder()
    var lastIndex = 0

    combinedRegex.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            val part = text.substring(lastIndex, match.range.first)
            builder.append(if (isRtl) applyBidiIsolation(part) else part)
        }

        val v = match.value
        when {
            v.startsWith("/sdcard/") || v.startsWith("/data/") || v.startsWith("/storage/") -> {
                builder.pushStyle(SpanStyle(color = amberAccent, background = pathBg, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                builder.append("\u2068$v\u2069")
                builder.pop()
            }
            v.startsWith("`") && v.endsWith("`") && v.length >= 2 -> {
                val codeContent = v.substring(1, v.length - 1)
                builder.pushStyle(SpanStyle(color = codeColor, background = codeBg, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold))
                builder.append(if (isRtl) "\u2068 $codeContent \u2069" else " $codeContent ")
                builder.pop()
            }
            v.startsWith("**") && v.endsWith("**") && v.length >= 4 -> {
                val boldContent = v.substring(2, v.length - 2)
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                builder.append(if (isRtl) applyBidiIsolation(boldContent) else boldContent)
                builder.pop()
            }
            else -> {
                builder.append(if (isRtl) applyBidiIsolation(v) else v)
            }
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        val part = text.substring(lastIndex)
        builder.append(if (isRtl) applyBidiIsolation(part) else part)
    }
    return builder.toAnnotatedString()
}

fun buildPathHighlightedString(text: String, isDark: Boolean, isRtl: Boolean = false): AnnotatedString {
    val pathRegex = Regex("(/sdcard/[a-zA-Z0-9_.-]+(?:/[a-zA-Z0-9_.-]+)*|/(?:data|storage|system|etc|proc|sys)/[a-zA-Z0-9_.-]+(?:/[a-zA-Z0-9_.-]+)*)")
    val amberAccent = if (isDark) Color(0xFFFFB86C) else Color(0xFFD97706)
    val pathBg = if (isDark) Color(0xFF1B2332) else Color(0xFFE2E8F0)
    val builder = AnnotatedString.Builder()
    var lastIndex = 0

    pathRegex.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            val part = text.substring(lastIndex, match.range.first)
            builder.append(if (isRtl) applyBidiIsolation(part) else part)
        }
        builder.pushStyle(SpanStyle(color = amberAccent, background = pathBg, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
        builder.append("\u2068${match.value}\u2069")
        builder.pop()
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        val part = text.substring(lastIndex)
        builder.append(if (isRtl) applyBidiIsolation(part) else part)
    }
    return builder.toAnnotatedString()
}

data class QuickActionCardItem(
    val title: String,
    val subtitle: String,
    val prompt: String,
    val icon: ImageVector
)

@Composable
fun CliWelcomeScreen(
    onSelectPrompt: (String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val electricCyan = Color(0xFF00D2FF)

    val quickActions = remember {
        listOf(
            QuickActionCardItem(
                title = "Web Dashboard",
                subtitle = "Build modern glassmorphic dashboard in webapp/",
                prompt = "Build a modern responsive glassmorphic dashboard in /sdcard/Medlam-Ai/webapp/ with self-contained styles and live charts.",
                icon = Icons.Filled.Dashboard
            ),
            QuickActionCardItem(
                title = "System Watchdog",
                subtitle = "Inspect hardware, memory cache & thermals",
                prompt = "Inspect system hardware, memory cache, thermals, and optimize memory performance.",
                icon = Icons.Filled.MonitorHeart
            ),
            QuickActionCardItem(
                title = "Drop Caches",
                subtitle = "Flush pagecache, dentries & inodes",
                prompt = "sync && echo 3 > /proc/sys/vm/drop_caches",
                icon = Icons.Filled.CleaningServices
            ),
            QuickActionCardItem(
                title = "Network Check",
                subtitle = "Test telephony, ping gateway & data status",
                prompt = "dumpsys telephony.registry | grep -E 'mState|mServiceState|mSignalStrength'",
                icon = Icons.Filled.NetworkCheck
            ),
            QuickActionCardItem(
                title = "Python Daemon",
                subtitle = "Scaffold & execute headless background script",
                prompt = "Write and run a headless Python script to log device uptime and RAM usage to /sdcard/Medlam-Ai/system_log.json",
                icon = Icons.Filled.Terminal
            ),
            QuickActionCardItem(
                title = "Clean Storage",
                subtitle = "Scan downloads & report large files",
                prompt = "du -ah /sdcard/Download | sort -rh | head -n 10",
                icon = Icons.Filled.Storage
            ),
            QuickActionCardItem(
                title = "Cycle API Keys",
                subtitle = "Test failover & advance to next key",
                prompt = "Test active Gemini API key quota and verify failover rotation.",
                icon = Icons.Filled.VpnKey
            ),
            QuickActionCardItem(
                title = "Device Telemetry",
                subtitle = "Inspect battery capacity, voltage & SoC",
                prompt = "dumpsys battery",
                icon = Icons.Filled.BatteryChargingFull
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Centered Brand Hero
        Text(
            text = "MEDLAM-AI AGENT",
            color = electricCyan,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.1.em,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp)
        )

        // 2-Column Responsive Quick-Actions Grid
        val chunkedActions = remember(quickActions) { quickActions.chunked(2) }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            chunkedActions.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { item ->
                        QuickActionCard(
                            item = item,
                            isDark = isDark,
                            onClick = { onSelectPrompt(item.prompt) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    item: QuickActionCardItem,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
    val cardBorder = if (isDark) Color(0x0FFFFFFF) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val titleColor = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A)
    val subtitleColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val iconColor = Color(0xFF00D2FF)

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .tactileGlassGlow(cornerRadius = 12.dp, isDark = isDark, elevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = iconColor,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = item.title,
                    color = titleColor,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = item.subtitle,
                color = subtitleColor,
                fontSize = 9.5.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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

    val drawerBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceContainer = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val errorColor = MaterialTheme.colorScheme.error

    ModalDrawerSheet(
        drawerContainerColor = drawerBg,
        drawerContentColor = onSurface,
        modifier = Modifier.width(300.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Spacer(modifier = Modifier.statusBarsPadding())

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("SESSION ARCHIVE", color = primary, fontSize = 12.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onCloseDrawer) {
                    Icon(Icons.Outlined.Close, "Close", tint = onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    viewModel.startNewSession()
                    onCloseDrawer()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryContainer,
                    contentColor = onPrimaryContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("+ New Session", color = onPrimaryContainer, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = dividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(10.dp))

            if (sessions.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No archived sessions", color = onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        val selected = s.id == activeSessionId
                        val isSessionArabic = isArabic(s.title)
                        Surface(
                            onClick = {
                                viewModel.switchSession(s.id)
                                onCloseDrawer()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .tactileGlassGlow(cornerRadius = 8.dp, isDark = isDark, elevation = if (selected) 4.dp else 2.dp),
                            color = if (selected) primaryContainer else surfaceContainer,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (selected) primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = s.title,
                                        color = if (selected) onPrimaryContainer else onSurface,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        fontFamily = if (isSessionArabic) FontFamily.SansSerif else FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(s.updatedAt)),
                                        color = if (selected) onPrimaryContainer.copy(alpha = 0.75f) else onSurfaceVariant,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                IconButton(onClick = { viewModel.deleteSession(s.id) }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Outlined.DeleteOutline, "Del", tint = if (selected) onPrimaryContainer.copy(alpha = 0.8f) else errorColor, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                }
            }

            Divider(color = dividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                onClick = {
                    onOpenSettings()
                    onCloseDrawer()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .tactileGlassGlow(cornerRadius = 8.dp, isDark = isDark, elevation = 2.dp),
                shape = RoundedCornerShape(8.dp),
                color = surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Outlined.Settings, "Settings", tint = primary, modifier = Modifier.size(18.dp))
                    Text("System Settings", color = onSurface, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}