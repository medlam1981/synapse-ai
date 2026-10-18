@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.synapse.localai

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.FileProvider
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.synapse.localai.ui.SyntaxHighlighter
import com.synapse.localai.ui.tactileGlassGlow
import com.synapse.localai.ui.tactileCardElevation
import com.synapse.localai.latex.MathRenderer
import com.synapse.localai.latex.CliMathFormattedText
import com.synapse.localai.latex.CliBlockMathFormula
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.synapse.localai.ui.theme.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.synapse.localai.AtlasLog as Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import kotlin.math.roundToInt

data class SystemStats(
    val battery: Int,
    val isCharging: Boolean,
    val ramFree: String,
    val cpuUsage: String,
    val storageFree: String,
    val cpuTemp: String,
    val cpuTempInt: Int,
    val currentKeyIndex: Int,
    val totalKeys: Int,
    val swapUsage: String = "--",
    val sdcardFree: String = "--",
    val cpuUsagePercent: Int? = null,
    val ramUsedPercent: Int? = null,
    val swapUsedPercent: Int? = null,
    val storageUsedPercent: Int? = null,
    val networkConnected: Boolean = false
)

private var lastCpuTotal: Long = 0
private var lastCpuIdle: Long = 0
private var cachedCpuUsage: String = "0%"

private fun usagePercent(used: Long, total: Long): Int? {
    if (total <= 0L) return null
    return ((used.coerceIn(0L, total).toDouble() / total) * 100).toInt().coerceIn(0, 100)
}

private fun readCpuStatLine(): String? {
    try {
        val statFile = File("/proc/stat")
        if (statFile.canRead()) {
            val line = statFile.bufferedReader().use { it.readLine() }
            if (line != null && line.startsWith("cpu")) {
                return line
            }
        }
    } catch (_: Exception) {}

    try {
        val line = RootShellManager.execSingleLine("cat /proc/stat")
        if (line != null && line.startsWith("cpu")) {
            return line
        }
    } catch (_: Exception) {}

    return null
}

fun getSystemStats(context: Context): SystemStats {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
    val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val status = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
    val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
    val networkConnected = runCatching {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        connectivityManager.activeNetwork
            ?.let(connectivityManager::getNetworkCapabilities)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ?: false
    }.getOrDefault(false)

    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val memoryInfo = android.app.ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val ramFree = String.format(Locale.US, "%.1fGB", memoryInfo.availMem / 1024.0 / 1024.0 / 1024.0)
    val ramUsedPercent = usagePercent(memoryInfo.totalMem - memoryInfo.availMem, memoryInfo.totalMem)

    val memoryFields = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                val value = parts.getOrNull(1)?.toLongOrNull()
                if (value == null) null else parts.first().removeSuffix(":") to value
            }.toMap()
        }
    }.getOrDefault(emptyMap())
    val swapTotalKb = memoryFields["SwapTotal"] ?: 0L
    val swapFreeKb = memoryFields["SwapFree"] ?: 0L
    val swapUsedKb = (swapTotalKb - swapFreeKb).coerceAtLeast(0L)
    val swapUsage = if (swapTotalKb > 0L) {
        String.format(
            Locale.US,
            "%.1f / %.1f GB",
            swapUsedKb / 1024.0 / 1024.0,
            swapTotalKb / 1024.0 / 1024.0
        )
    } else {
        "Unavailable"
    }
    val swapUsedPercent = usagePercent(swapUsedKb, swapTotalKb)

    val storageStats = runCatching {
        @Suppress("DEPRECATION")
        val sdcardPath = android.os.Environment.getExternalStorageDirectory().path
        val sdcardStats = android.os.StatFs(sdcardPath)
        val totalBytes = sdcardStats.totalBytes
        val availableBytes = sdcardStats.availableBytes
        String.format(Locale.US, "%.1f GB", availableBytes / 1024.0 / 1024.0 / 1024.0) to
            usagePercent(totalBytes - availableBytes, totalBytes)
    }.getOrDefault("Unavailable" to null)
    val sdcardFree = storageStats.first

    val prefs = context.getSharedPreferences("synapse_config", Context.MODE_PRIVATE)
    val geminiKeys = prefs.getString("gemini_api_keys", "") ?: ""
    val totalKeys = if (geminiKeys.isBlank()) 0 else geminiKeys.split(",").filter { it.trim().isNotEmpty() }.size
    val currentKeyIndex = if (totalKeys > 0) (prefs.getInt("current_key_index", 0) + 1) else 0

    val maxTemp = DeviceProfile.readDeviceTemperature()

    var cpuUsage = cachedCpuUsage
    var cpuUsagePercent = cachedCpuUsage.removeSuffix("%").toIntOrNull()
    try {
        val line = readCpuStatLine()
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
                        cpuUsagePercent = pct
                        cachedCpuUsage = cpuUsage
                    }
                }
                lastCpuTotal = currentTotal
                lastCpuIdle = currentIdle
            }
        }
    } catch (_: Exception) {}

    return SystemStats(
        battery = battery,
        isCharging = isCharging,
        ramFree = ramFree,
        cpuUsage = cpuUsage,
        storageFree = sdcardFree,
        cpuTemp = if (maxTemp > 0) "${maxTemp}°C" else "--",
        cpuTempInt = maxTemp,
        currentKeyIndex = currentKeyIndex,
        totalKeys = totalKeys,
        swapUsage = swapUsage,
        sdcardFree = sdcardFree,
        cpuUsagePercent = cpuUsagePercent,
        ramUsedPercent = ramUsedPercent,
        swapUsedPercent = swapUsedPercent,
        storageUsedPercent = storageStats.second,
        networkConnected = networkConnected
    )
}

fun isRtlText(text: String): Boolean {
    val arabicRegex = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
    val firstChar = text.trim().firstOrNull { it.isLetter() } ?: return false
    return arabicRegex.matches(firstChar.toString())
}

fun isArabic(text: String): Boolean = isRtlText(text)

fun applyBidiIsolation(text: String): String {
    if (text.isBlank()) return text

    val arabicColonRegex = Regex("([\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF])(:|：)(\\s*)")
    var processed = arabicColonRegex.replace(text) { m ->
        "${m.groupValues[1]}${m.groupValues[2]}\u200F${m.groupValues[3]}"
    }

    val parenLatinRegex = Regex("(\\([A-Za-z0-9_\\-&/.,\\s+]+\\))")
    processed = parenLatinRegex.replace(processed) { m ->
        "\u2068${m.value}\u2069"
    }

    val bracketLatinRegex = Regex("(\\[[A-Za-z0-9_\\-&/.,\\s+]+\\])")
    processed = bracketLatinRegex.replace(processed) { m ->
        "\u2068${m.value}\u2069"
    }

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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        instance = this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        try {
            LlamaServerJNI.load()
        } catch (t: Throwable) {
            Log.e("MainActivity", "LlamaServerJNI load error: ${t.message}")
        }

        RootShellManager.initGlobalShell()
        try {
            MathRenderer.init(this)
        } catch (t: Throwable) {
            Log.w("MainActivity", "MathRenderer init notice: ${t.message}")
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

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

        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.initTaskSystem(applicationContext)
        }

        lifecycleScope.launch {
            try {
                checkStoragePermissions()
                delay(800)
                checkBatteryOptimizations()
                delay(800)
                checkAndRequestRootAccess()
            } catch (e: Exception) {
                Log.e("MainActivity", "Startup permission logic failed", e)
            }
        }

        setContent {
            AtlasAiTheme {
                val bgColor = MaterialTheme.colorScheme.background

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
            val isRoot = RootShellManager.isRootAvailable()
            viewModel.setRootAvailable(isRoot)
            if (isRoot) {
                Log.i("MainActivity", "Superuser privileges verified via persistent libsu shell (root active)")
                ZramOptimizer.optimizeMemoryAndZram(this@MainActivity)
            } else {
                Log.i("MainActivity", "Standard user execution (non-root)")
            }
        } catch (e: Exception) {
            Log.i("MainActivity", "Superuser elevation check note: ${e.message}")
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CliWorkspaceScreen(viewModel: AgentViewModel) {
    val isDark = isSystemInDarkTheme()
    val uiState by viewModel.uiState.collectAsState()
    val systemStats by viewModel.systemStats.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showTaskManager by remember { mutableStateOf(false) }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    var viewingImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val slashCommands = remember(context) {
        listOf(
            SlashCommandItem(
                command = "/syscheck",
                name = "System Stability Check",
                description = "Execute immediate kernel, CPU, and memory diagnostic via shell",
                action = { 
                    viewModel.sendCommand("$ uptime && top -n 1 -b | head -n 15 && free -m && df -h", null, context)
                }
            ),
            SlashCommandItem(
                command = "/image",
                name = "Synthesize Visual",
                description = "Generate native AI images in-memory via Google Imagen 3",
                action = { textInput = "/image " }
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

    LaunchedEffect(uiState.logs.size) {
        userScrolledAway = false
        if (uiState.logs.isNotEmpty()) {
            listState.animateScrollToItem(uiState.logs.size - 1, scrollOffset = 100_000)
        }
    }

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
                onOpenSettings = { showSettings = true },
                onOpenTaskManager = { showTaskManager = true }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Spacer(modifier = Modifier.statusBarsPadding())
            Spacer(modifier = Modifier.height(8.dp))

            ConsolidatedMasterHeader(
                context = context,
                uiState = uiState,
                stats = systemStats,
                isDark = isDark,
                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
            ) {
                if (uiState.logs.isEmpty() && !uiState.isProcessing) {
                    CliWelcomeScreen(
                        uiState = uiState
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds(),
                        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(uiState.logs, key = { it.id }) { log ->
                            CliLogMessageView(
                                log = log,
                                isDark = isDark,
                                onImageClick = { viewingImageBytes = it }
                            )
                        }
                    }

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
                            border = BorderStroke(1.dp, if (isDark) Color(0xFF30363D) else DayOutlineVariant)
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
                    val isBusy = uiState.isProcessing
                    val statusText = if (isBusy) {
                        uiState.activeStatusDetail.ifBlank { "Thinking & Planning..." }
                    } else {
                        "Idle / Ready"
                    }

                    val dotColor = if (isBusy) primaryAccent else MaterialTheme.colorScheme.primary
                    val textColor = if (isBusy) primaryAccent else textDim

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
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
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
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                    Text(
                                                        text = "— ${item.name}",
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                                Text(
                                                    text = item.description,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                    val inputIsArabic = isArabic(textInput)
                    val isInputReady = !uiState.isProcessing
                    val canSend = isInputReady && (textInput.isNotBlank() || selectedFileUri != null)

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
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 6.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            focusRequester.requestFocus()
                                            keyboardController?.show()
                                        },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    BasicTextField(
                                        value = textInput,
                                        onValueChange = { textInput = it },
                                        enabled = isInputReady,
                                        textStyle = TextStyle(
                                            color = textBright,
                                            fontSize = 13.5.sp,
                                            fontFamily = if (inputIsArabic) FontFamily.SansSerif else FontFamily.Monospace,
                                            textAlign = TextAlign.Start
                                        ),
                                        cursorBrush = SolidColor(cyanAccent),
                                        maxLines = 4,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(focusRequester),
                                        decorationBox = { inner ->
                                            Box(
                                                modifier = Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                if (textInput.isEmpty()) {
                                                    val placeholderText = when {
                                                        uiState.isProcessing -> {
                                                            if (inputIsArabic) "الوكيل يقوم بالتفكير والتنفيذ..." else "Agent is thinking / executing..."
                                                        }
                                                        uiState.initPhase == InitializationPhase.ALLOCATING_MEMORY || uiState.localModelLoading -> {
                                                            if (inputIsArabic) "جارٍ تخصيص الذاكرة وتحميل النموذج..." else "Allocating Memory..."
                                                        }
                                                        uiState.initPhase == InitializationPhase.PARSING_PERSONA -> {
                                                            if (inputIsArabic) "جارٍ تهيئة الشخصية والذاكرة المؤقتة..." else "Parsing Persona..."
                                                        }
                                                        else -> {
                                                            if (inputIsArabic) "رسالة إلى وكيل ATLAS-AI..." else "Message ATLAS-AI Agent..."
                                                        }
                                                    }
                                                    Text(
                                                        text = placeholderText,
                                                        style = TextStyle(
                                                            color = textDim,
                                                            fontSize = 13.sp,
                                                            fontFamily = if (inputIsArabic) FontFamily.SansSerif else FontFamily.Monospace,
                                                            textAlign = TextAlign.Start
                                                        ),
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                }
                                                inner()
                                            }
                                        }
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
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
                                            tint = if (isRecording) MaterialTheme.colorScheme.error else textDim,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                    /* TTS Voice Button Removed */

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
                                            Box(
                                                modifier = Modifier
                                                    .size(11.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(MaterialTheme.colorScheme.error)
                                            )
                                        } else {
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

    if (showTaskManager) {
        ScheduledTaskManagerScreen(viewModel = viewModel, onBack = { showTaskManager = false })
    }

    LiveCanvasDrawer(
        isOpen = uiState.isLiveCanvasOpen,
        onDismiss = { viewModel.closeLiveCanvas() }
    )

    if (viewingImageBytes != null) {
        Dialog(
            onDismissRequest = { viewingImageBytes = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .clickable { viewingImageBytes = null },
                contentAlignment = Alignment.Center
            ) {
                val fullBmp = remember(viewingImageBytes) {
                    viewingImageBytes?.let {
                        try {
                            BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                if (fullBmp != null) {
                    Image(
                        bitmap = fullBmp,
                        contentDescription = "Full View AI Generated Image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
                IconButton(
                    onClick = { viewingImageBytes = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardMetric(
    label: String,
    percentage: Int?,
    value: String,
    contentDescription: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val metricValue = percentage?.coerceIn(0, 100)
    val activeDots = metricValue?.let { (it / 5f).roundToInt().coerceIn(0, 20) } ?: 0
    val activeColor = when {
        metricValue == null -> MaterialTheme.colorScheme.onSurfaceVariant
        metricValue < 60 -> Color(0xFF00E5FF)
        metricValue <= 85 -> Color(0xFFFF9100)
        else -> Color(0xFFFF1744)
    }
    val inactiveColor = if (isDark) Color(0x25FFFFFF) else Color(0x30000000)
    val rowModifier = modifier
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = contentDescription,
                    onClick = onClick
                )
            } else {
                Modifier
            }
        )

    Surface(
        modifier = rowModifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = "$label:",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 8.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.width(34.dp)
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                repeat(20) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(if (index < activeDots) 8.dp else 4.dp)
                            .clip(RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
                            .background(if (index < activeDots) activeColor else inactiveColor)
                    )
                }
            }
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 8.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.width(34.dp)
            )
        }
    }
}

@Composable
private fun DashboardGaugeRow(
    stats: SystemStats,
    isDark: Boolean,
    totalTasks: Int,
    modifier: Modifier = Modifier,
    taskStripExpanded: Boolean = false,
    onTaskGaugeClick: () -> Unit = {}
) {
    val metrics = listOf(
        listOf(
            Triple("RAM", stats.ramUsedPercent, stats.ramUsedPercent?.let { "$it%" } ?: "--"),
            Triple("CPU", stats.cpuUsagePercent, stats.cpuUsagePercent?.let { "$it%" } ?: "--"),
            Triple("MEM", stats.ramUsedPercent?.let { 100 - it }, stats.ramFree),
            Triple("DISK", stats.storageUsedPercent, stats.storageUsedPercent?.let { "$it%" } ?: "--")
        ),
        listOf(
            Triple("TEMP", stats.cpuTempInt.takeIf { it > 0 }?.coerceAtMost(100), if (stats.cpuTemp == "--") "--" else stats.cpuTemp.removeSuffix("°C") + "°"),
            Triple("BATT", stats.battery.takeIf { it in 0..100 }, if (stats.battery in 0..100) "${stats.battery}%" else "--"),
            Triple("SWAP", stats.swapUsedPercent, stats.swapUsedPercent?.let { "$it%" } ?: "--"),
            Triple("TASK", (totalTasks.coerceIn(0, 10) * 10), totalTasks.toString())
        )
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        metrics.forEach { columnMetrics ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                columnMetrics.forEach { (label, percentage, value) ->
                    DashboardMetric(
                        label = label,
                        percentage = percentage,
                        value = value,
                        contentDescription = if (label == "TASK") {
                            if (taskStripExpanded) "Hide active task details" else "Show active task details"
                        } else {
                            label
                        },
                        isDark = isDark,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = if (label == "TASK") onTaskGaugeClick else null
                    )
                }
            }
        }
    }
}

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
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .scale(haloScale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = (1.8f - haloScale) * 0.45f * alphaAnim))
            )
            Box(
                modifier = Modifier
                    .size(5.5.dp)
                    .alpha(alphaAnim)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
            )
        }
    } else {
        val dotColor = when {
            isReady -> MaterialTheme.colorScheme.secondary
            isError -> MaterialTheme.colorScheme.error
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

@Composable
fun AtlasAiBranding(
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.5.sp,
    lineWidth: Dp? = null,
    lineHeight: Dp = 2.5.dp,
    spacing: Dp = 1.5.dp,
    showAgent: Boolean = true,
    strokeWidth: Float = 2.5f
) {
    val primaryColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        text = androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = primaryColor,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.035.em
                )
            )
            append("ATLAS-AI")
            pop()
            
            if (showAgent) {
                pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = secondaryColor,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.07.em
                    )
                )
                append(" AGENT")
                pop()
            }
        },
        fontSize = fontSize,
        fontFamily = FontFamily.SansSerif,
        modifier = modifier
    )
}

@Composable
fun ConsolidatedMasterHeader(
    context: Context,
    uiState: AgentUiState,
    stats: SystemStats,
    isDark: Boolean,
    onOpenDrawer: () -> Unit
) {
    val configuration = LocalConfiguration.current
    var taskStripExpanded by remember { mutableStateOf(false) }
    val compactHeader = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp >= 600
    val headerBg = MaterialTheme.colorScheme.surfaceContainer
    val subCardBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryAccent = MaterialTheme.colorScheme.primary
    val secondaryAccent = MaterialTheme.colorScheme.secondary

    val totalTasks = maxOf(uiState.activeTasks.size, uiState.taskQueueSize)
    val cyanAccent = MaterialTheme.colorScheme.primary
    val cyanBorder = MaterialTheme.colorScheme.primary
    val isCloudMode = uiState.activeModelType == ModelType.GEMINI ||
        (uiState.activeModelType == null && uiState.config.preferredModelType == ModelType.GEMINI)
    val effectiveDraftPath = uiState.activeDraftModelPath
        ?: uiState.config.draftModelPath.takeIf { it != "STANDALONE" && it.isNotBlank() }
    val hasMtp = !isCloudMode && !effectiveDraftPath.isNullOrBlank() && effectiveDraftPath != "STANDALONE"
    val draftName = if (hasMtp && effectiveDraftPath != null) File(effectiveDraftPath).nameWithoutExtension else null
    val draftTag = draftName?.let { name ->
        val quantRegex = Regex("""(?i)(Q\d+_[A-Z0-9_]+|BF16|FP16|F16|F32)""")
        quantRegex.find(name)?.value?.uppercase()
            ?: name.removePrefix("mtp-").removePrefix("gemma-4-").removePrefix("it-").uppercase()
    }
    val rawBaseName = uiState.activeModelName.ifBlank {
        if (uiState.config.localModelPath.isNotBlank()) {
            File(uiState.config.localModelPath).nameWithoutExtension
        } else {
            "GEMMA-4-E2B Q4"
        }
    }
    val cleanBaseName = remember(rawBaseName) {
        var name = rawBaseName
            .replace("(?i)-it".toRegex(), "")
            .replace("(?i)-qat".toRegex(), "")
            .replace("(?i)-ud".toRegex(), "")
            .replace("(?i)-Q2_K_XL".toRegex(), " Q2")
            .replace("(?i)-Q4_K_XL".toRegex(), " Q4")
            .replace("(?i)-Q4_K_M".toRegex(), " Q4")
            .replace("(?i)-Q4_0".toRegex(), " Q4_0")
            .replace("(?i)-Q8_0".toRegex(), " Q8")
            .replace("(?i)-BF16".toRegex(), " BF16")
            .replace("(?i)-F16".toRegex(), " F16")
            .replace("(?i)_".toRegex(), "-")
            .trim()
            .uppercase()
        if (name.isBlank()) "GEMMA-4-E2B Q4" else name
    }
    val formattedTps = String.format(Locale.US, "%.1f", uiState.tokensPerSecond.coerceAtLeast(0f))
    val specChipText = when {
        isCloudMode -> "GEMINI CLOUD • ${uiState.config.geminiModelName.uppercase()} • $formattedTps T/s"
        hasMtp && draftTag != null -> "$cleanBaseName • MTP: $draftTag • $formattedTps T/s"
        else -> "$cleanBaseName • STANDALONE • $formattedTps T/s"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .background(headerBg)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compactHeader) 4.dp else 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .width(24.dp)
                            .height(if (compactHeader) 30.dp else 36.dp)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Open navigation menu",
                                onClick = onOpenDrawer
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        repeat(4) { index ->
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        cyanAccent.copy(
                                            alpha = when (index) {
                                                0 -> if (uiState.isProcessing) 1f else 0.72f
                                                1 -> 0.58f
                                                2 -> 0.4f
                                                else -> 0.25f
                                            }
                                        )
                                    )
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        AtlasAiBranding(
                            fontSize = if (compactHeader) 11.sp else 16.sp,
                            lineHeight = 2.dp
                        )
                        Text(
                            text = specChipText,
                            color = textDim,
                            fontSize = if (compactHeader) 6.sp else 7.sp,
                            lineHeight = if (compactHeader) 8.sp else 9.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = if (compactHeader) 180.dp else 220.dp)
                        )
                    }

                    if (!compactHeader) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (uiState.isProcessing) primaryAccent.copy(alpha = 0.15f) else subCardBg
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
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
                }
            }

            DashboardGaugeRow(
                stats = stats,
                isDark = isDark,
                totalTasks = totalTasks,
                modifier = Modifier.fillMaxWidth(),
                taskStripExpanded = taskStripExpanded,
                onTaskGaugeClick = { taskStripExpanded = !taskStripExpanded }
            )

            if (taskStripExpanded) {
                if (uiState.activeTasks.isEmpty()) {
                    Text(
                        text = "No active tasks",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        color = textDim,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 1.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            uiState.activeTasks.forEach { task ->
                                val (statusLabel, statusColor) = when (task.status) {
                                    TaskStatus.RUNNING -> "RUNNING" to MaterialTheme.colorScheme.primary
                                    TaskStatus.WAITING_FOR_USER -> "NEEDS INPUT" to MaterialTheme.colorScheme.secondary
                                    TaskStatus.QUEUED -> "QUEUED" to MaterialTheme.colorScheme.secondary
                                    TaskStatus.PENDING -> "PENDING" to textDim
                                    TaskStatus.PAUSED -> "PAUSED" to textDim
                                    else -> task.status.name to textDim
                                }
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = statusColor.copy(alpha = 0.08f),
                                    border = BorderStroke(0.6.dp, statusColor.copy(alpha = 0.3f)),
                                    modifier = Modifier.widthIn(max = 240.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Box(
                                            Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(statusColor)
                                        )
                                        Column {
                                            Text(
                                                text = "$statusLabel · ${task.description}",
                                                color = statusColor,
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            val progressText = task.progress.takeIf { it.isNotBlank() }
                                            if (progressText != null) {
                                                Text(
                                                    text = progressText,
                                                    color = textDim,
                                                    fontSize = 7.sp,
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
            }
        }
    }
}

@Composable
fun CliThoughtContainer(
    thinkingText: String,
    isThinking: Boolean,
    isDark: Boolean
) {
    if (thinkingText.isBlank() && !isThinking) return

    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val isExpanded = userExpanded ?: isThinking

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

    val traceColor = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant
    val traceAccent = MaterialTheme.colorScheme.primary
    val gutterColor = if (isDark) NightOutlineVariant else DayOutlineVariant
    val amberBullet = MaterialTheme.colorScheme.secondary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
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
                                append("Thinking & reasoning...")
                                if (isThinking) {
                                    pushStyle(SpanStyle(color = traceAccent.copy(alpha = cursorAlpha), fontWeight = FontWeight.Bold))
                                    append(" ▌")
                                    pop()
                                }
                            }
                        },
                        color = traceColor,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontStyle = FontStyle.Italic,
                        lineHeight = if (isThoughtRtl) 18.sp else 16.5.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun CliCodeBlockContainer(
    code: String,
    language: String,
    filePath: String?,
    isDark: Boolean,
    isStreaming: Boolean = false
) {
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val isExpanded = userExpanded ?: false
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

    val blockBg = if (isDark) NightBackground else DayBackground
    val headerBg = if (isDark) NightSurface else DaySurfaceVariant
    val borderColor = if (isDark) NightOutlineVariant else DayOutlineVariant
    val langAccent = MaterialTheme.colorScheme.primary
    val pathColor = if (isDark) NightTertiary else DayTertiary
    val textDim = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = blockBg,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(headerBg)
                    .clipToBounds()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = "LIVE",
                                color = MaterialTheme.colorScheme.primary,
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
                        color = if (copied) MaterialTheme.colorScheme.primary else textDim,
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
                        text = if (isExpanded) "[HIDE CODE]" else "[SHOW CODE]",
                        color = textDim,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier
                            .clickable { userExpanded = !isExpanded }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

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
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.5.sp,
                        color = if (isDark) NightOnBackground else DayOnBackground
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

@Composable
fun CliTerminalToolLine(
    rawLine: String,
    isDark: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

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

    val cmdColor = if (isDark) NightOnBackground else DayOnBackground
    val textDim = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant
    val bulletColor = textDim
    val gutterColor = if (isDark) NightOutlineVariant else DayOutlineVariant

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
                NeonDottedSpinner(
                    modifier = Modifier.size(14.dp),
                    activeColor = textDim
                )
            } else if (isSuccess) {
                Text(
                    text = "✔",
                    color = textDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            } else if (isFailed) {
                Text(
                    text = "✖",
                    color = textDim,
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
    isDark: Boolean,
    onImageClick: ((ByteArray) -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    val isUser = log.sender == "USER"
    val isSystem = log.sender == "SYSTEM"

    val textBright = if (isDark) NightOnBackground else DayOnBackground
    val textDim = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant
    val userMessageColor = if (isDark) UserMessageDark else UserMessageLight

    val isLogArabic = isArabic(log.text)

    when {
        isUser -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isLogArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    CliMathFormattedText(
                        text = log.text,
                        isDark = isDark,
                        isRtl = isLogArabic,
                        color = userMessageColor,
                        fontSize = 13.sp,
                        lineHeight = if (isLogArabic) 19.sp else 18.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                        markdownStyler = { txt, _, rtl -> AnnotatedString(if (rtl) applyBidiIsolation(txt) else txt) }
                    )
                }

                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp)),
                    color = textDim.copy(alpha = 0.35f),
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 1.dp)
                )
            }
        }

        isSystem -> {
            val isError = log.isError
            val bulletColor = textDim
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
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = log.text,
                    color = if (isError) MaterialTheme.colorScheme.error else textDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        else -> {
            val demuxed = remember(log.text, log.thinkingText, log.isThinking, log.imageBytes) {
                StreamDemultiplexer.demux(
                    rawText = log.text,
                    externalThinkingText = log.thinkingText,
                    externalIsThinking = log.isThinking,
                    externalImageBytes = log.imageBytes
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (demuxed.thinkingText.isNotBlank() || demuxed.isThinking) {
                    CliThoughtContainer(
                        thinkingText = demuxed.thinkingText,
                        isThinking = demuxed.isThinking,
                        isDark = isDark
                    )
                }

                if (demuxed.blocks.isNotEmpty()) {
                    demuxed.blocks.forEach { block ->
                        key(when (block) {
                            is StreamDemultiplexer.DemuxBlock.TerminalFrame -> block.executionId
                            is StreamDemultiplexer.DemuxBlock.ToolCall -> block.executionId
                            is StreamDemultiplexer.DemuxBlock.Code -> "${block.filePath}_${block.language}_${block.code.hashCode()}"
                            is StreamDemultiplexer.DemuxBlock.Prose -> "prose_${block.text.hashCode()}"
                            is StreamDemultiplexer.DemuxBlock.Image -> "image_${block.prompt.hashCode()}_${block.imageBytes.size}"
                            is StreamDemultiplexer.DemuxBlock.FileArtifact -> block.executionId
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
                                is StreamDemultiplexer.DemuxBlock.Image -> {
                                    CliInlineImage(
                                        imageBytes = block.imageBytes,
                                        prompt = block.prompt,
                                        isDark = isDark,
                                        onClick = { onImageClick?.invoke(block.imageBytes) }
                                    )
                                }
                                is StreamDemultiplexer.DemuxBlock.FileArtifact -> {
                                    CliFileArtifactCard(block, isDark)
                                }
                            }
                        }
                    }
                }

                if (log.imageBytes != null && demuxed.blocks.none { it is StreamDemultiplexer.DemuxBlock.Image }) {
                    CliInlineImage(
                        imageBytes = log.imageBytes,
                        prompt = "",
                        isDark = isDark,
                        onClick = { onImageClick?.invoke(log.imageBytes) }
                    )
                }

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
fun CliInlineImage(
    imageBytes: ByteArray,
    prompt: String,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val bitmap = remember(imageBytes) {
        try {
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    val borderColor = if (isDark) NightOutlineVariant else NightOnBackground
    val cardBg = if (isDark) NightBackground else DayBackground
    val textDim = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        if (bitmap != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cardBg,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onClick() }
            ) {
                Column {
                    Image(
                        bitmap = bitmap,
                        contentDescription = prompt.ifBlank { "Visual Synthesis" },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 320.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (prompt.isNotBlank()) prompt else "Generated Visual",
                            color = textDim,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Filled.ZoomIn,
                                contentDescription = "Full Screen",
                                tint = textDim,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "View",
                                color = textDim,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isDark) NightSurfaceContainer else DaySurfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "✖ [Image Rendering Failed: Unable to decode bitmap bytes]",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun CliFileArtifactCard(
    artifact: StreamDemultiplexer.DemuxBlock.FileArtifact,
    isDark: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPreview by remember(artifact.executionId) { mutableStateOf(false) }
    val mimeType = when (artifact.format.lowercase()) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType)
    ) { destination ->
        if (destination != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val source = officeArtifactFile(artifact.path, artifact.format)
                            ?: error("This is not an available Atlas Office document.")
                        context.contentResolver.openOutputStream(destination)?.use { output ->
                            source.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("Could not open the selected download destination.")
                    }
                }
                result.onSuccess {
                    Toast.makeText(context, "Saved ${artifact.fileName}", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "Download failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, if (isDark) MaterialTheme.colorScheme.primary.copy(alpha = 0.32f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = if (artifact.format.equals("xlsx", true)) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        artifact.fileName,
                        color = if (isDark) Color.White else DayOnBackground,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${artifact.format.uppercase()} · ${artifact.path}",
                        color = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showPreview = true }) {
                    Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Preview")
                }
                TextButton(onClick = { saveLauncher.launch(artifact.fileName) }) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Download")
                }
                TextButton(onClick = {
                    runCatching {
                        val file = officeArtifactFile(artifact.path, artifact.format)
                            ?: error("This is not an available Atlas Office document.")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mimeType)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Open ${artifact.fileName}"))
                    }.onFailure {
                        Toast.makeText(context, "Could not open file: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Open")
                }
            }
        }
    }

    if (showPreview) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = {
                Text(
                    artifact.fileName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                    val previewIsArabic = isArabic(artifact.preview)
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (previewIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                    ) {
                        Text(
                            text = artifact.preview.ifBlank { "No text preview is available for this file." },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            fontFamily = if (artifact.format.equals("xlsx", true)) FontFamily.Monospace else FontFamily.Default,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Start
                        )
                    }
            },
            confirmButton = {
                TextButton(onClick = { showPreview = false }) { Text("Close") }
            }
        )
    }
}

private fun officeArtifactFile(path: String, format: String): File? = runCatching {
    require(format.equals("docx", true) || format.equals("xlsx", true))
    val documentsDir = File(ToolExecutor.BASE_DIR, "documents").canonicalFile
    val candidate = File(path).canonicalFile
    require(candidate.parentFile == documentsDir)
    require(candidate.extension.equals(format, ignoreCase = true))
    require(candidate.isFile && candidate.length() <= 50L * 1024L * 1024L)
    candidate
}.getOrNull()

@Composable
fun CliTerminalFrame(block: StreamDemultiplexer.DemuxBlock.TerminalFrame, isDark: Boolean) {
    val clipboard = LocalClipboardManager.current

    val rawOutput = block.output.trim()
    val cleanOutput = rawOutput
        .replace(Regex("""\[(?:Command|Process) completed with exit code \d+(?: \(no output\))?\]""", RegexOption.IGNORE_CASE), "")
        .trim()

    val cmdLines = block.command.lines()
    val outLines = if (cleanOutput.isNotBlank()) cleanOutput.lines() else emptyList()
    val totalLines = cmdLines.size + outLines.size
    val isLongOutput = totalLines > 8

    var userExpanded by remember(block.executionId, block.command) { mutableStateOf<Boolean?>(null) }
    val isExpanded = userExpanded ?: false
    var copiedRecently by remember { mutableStateOf(false) }

    LaunchedEffect(copiedRecently) {
        if (copiedRecently) {
            delay(1500)
            copiedRecently = false
        }
    }

    val obsidianBg = NightBackground
    val subtleBorder = Color.White.copy(alpha = 0.08f)
    val terminalText = MaterialTheme.colorScheme.onSurfaceVariant
    val terminalAccent = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        color = obsidianBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 7.dp)
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (block.isRunning) NeonDottedSpinner(modifier = Modifier.size(12.dp))
                        Text(
                            text = "bash",
                            color = terminalAccent,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (copiedRecently) "[COPIED]" else "[COPY]",
                            color = if (copiedRecently) MaterialTheme.colorScheme.primary else NightOnSurfaceVariant,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable {
                                    val copyText = buildString {
                                        append("$ ").append(block.command).append("\n")
                                        if (cleanOutput.isNotBlank()) {
                                            append(cleanOutput).append("\n")
                                        }
                                    }
                                    clipboard.setText(AnnotatedString(copyText))
                                    copiedRecently = true
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )

                        Text(
                            text = if (isExpanded) "[HIDE]" else "[SHOW]",
                            color = if (isExpanded) MaterialTheme.colorScheme.primary else DayOutlineVariant,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { userExpanded = !isExpanded }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Divider(color = subtleBorder, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(6.dp))

                if (isExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "$ ",
                                color = terminalAccent,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = block.command,
                                color = Color(0xFFF1F5F9),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }

                        if (cleanOutput.isNotBlank()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = cleanOutput,
                                color = terminalText,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        } else if (!block.isRunning) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = if (rawOutput.isNotBlank()) rawOutput else "[No output returned]",
                                color = NightOnSurfaceVariant,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                lineHeight = 16.sp
                            )
                        }

                        if (isLongOutput) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "[-] Hide",
                                color = DayOnSurfaceVariant,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable { userExpanded = false }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "$ ",
                                color = terminalAccent,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = cmdLines.firstOrNull() ?: block.command,
                                color = Color(0xFFF1F5F9),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = if (block.isRunning) "[SHOW • $totalLines]" else "[SHOW • $totalLines]",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { userExpanded = true }
                                .padding(vertical = 2.dp)
                        )
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
    val toolAccent = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant
    val completedColor = toolAccent
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
        color = if (isDark) DayOnBackground.copy(alpha = 0.85f) else Color(0xFFF1F5F9),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(0.5.dp, toolAccent.copy(alpha = 0.35f)),
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
                    color = completedColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = displayText,
                color = if (isRunning) toolAccent else completedColor,
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
    val text = remember(raw) { StreamDemultiplexer.sanitizeProse(raw).trim() }
    if (text.isBlank()) return

    val textBright = if (isDark) NightOnBackground else DayOnBackground
    val textDim = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant
    val headingColor = textBright

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

            val isToolLine = trimmed.startsWith("● ManageTask") || trimmed.startsWith("ManageTask(") ||
                             trimmed.startsWith("● Bash") || trimmed.startsWith("Bash(") ||
                             trimmed.startsWith("● FileWrite") || trimmed.startsWith("File written:") ||
                             trimmed.startsWith("Background task done:") || trimmed.startsWith("$ ") ||
                             trimmed.startsWith("Tool(") || trimmed.startsWith("✔ Tool(") || trimmed.startsWith("✖ Tool(") ||
                             trimmed.startsWith("Orchestrator:") || trimmed.startsWith("Verifier:") ||
                             trimmed.startsWith("Production Sync:") || trimmed.startsWith("Status:") ||
                             trimmed.startsWith("run_shell(") || trimmed.startsWith("● run_shell") ||
                             trimmed.startsWith("  ├─")

            if (isToolLine) {
                CliTerminalToolLine(trimmed, isDark)
                i++
                continue
            }

            if (trimmed.startsWith("$$") && !trimmed.removePrefix("$$").contains("$$")) {
                val blockMathLines = mutableListOf<String>()
                blockMathLines.add(line)
                var j = i + 1
                var foundEnd = false
                while (j < lines.size) {
                    val nextLine = lines[j]
                    blockMathLines.add(nextLine)
                    if (nextLine.contains("$$")) {
                        foundEnd = true
                        i = j
                        break
                    }
                    j++
                }
                if (foundEnd) {
                    val fullBlockMath = blockMathLines.joinToString("\n")
                    val formula = fullBlockMath.replace("$$", "").trim()
                    CliBlockMathFormula(
                        formula = formula,
                        rawText = fullBlockMath,
                        isDark = isDark
                    )
                    i++
                    continue
                }
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
                        CliMathFormattedText(
                            text = clean,
                            isDark = isDark,
                            isRtl = isLineArabic,
                            color = headingColor,
                            fontSize = 13.5.sp,
                            lineHeight = if (isLineArabic) 21.sp else 19.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 2.dp),
                            markdownStyler = { txt, dark, rtl -> buildOrganizedMarkdownString(txt, dark, rtl) }
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
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            CliMathFormattedText(
                                text = bulletContent,
                                isDark = isDark,
                                isRtl = isLineArabic,
                                color = textBright,
                                fontSize = 12.5.sp,
                                lineHeight = if (isLineArabic) 20.sp else 18.sp,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.weight(1f),
                                markdownStyler = { txt, dark, rtl -> buildOrganizedMarkdownString(txt, dark, rtl) }
                            )
                        }
                    }
                    else -> {
                        CliMathFormattedText(
                            text = clean,
                            isDark = isDark,
                            isRtl = isLineArabic,
                            color = textBright,
                            fontSize = 12.5.sp,
                            lineHeight = if (isLineArabic) 20.sp else 18.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                            markdownStyler = { txt, dark, rtl -> buildOrganizedMarkdownString(txt, dark, rtl) }
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

    val pathColor = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant
    val pathBg = if (isDark) NightSurfaceContainer else DaySurfaceContainer
    val codeBg = if (isDark) NightSurface else DaySurfaceContainer
    val codeColor = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant

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
                builder.pushStyle(SpanStyle(color = pathColor, background = pathBg, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
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
    val pathColor = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant
    val pathBg = if (isDark) Color(0xFF1B2332) else NightOnBackground
    val builder = AnnotatedString.Builder()
    var lastIndex = 0

    pathRegex.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            val part = text.substring(lastIndex, match.range.first)
            builder.append(if (isRtl) applyBidiIsolation(part) else part)
        }
        builder.pushStyle(SpanStyle(color = pathColor, background = pathBg, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
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

@Composable
fun CliWelcomeScreen(
    uiState: AgentUiState,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val electricCyan = MaterialTheme.colorScheme.primary
    val neonGreen = if (isDark) SuccessGreen else DaySuccess
    val crimsonRed = MaterialTheme.colorScheme.error
    val vibrantBlue = MaterialTheme.colorScheme.secondary
    val amber = MaterialTheme.colorScheme.secondary
    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
    val cardBg = MaterialTheme.colorScheme.surfaceVariant
    val cardBorder = MaterialTheme.colorScheme.outline

    val currentPhase = when {
        uiState.initPhase == InitializationPhase.SYSTEM_READY || uiState.localModelLoaded -> InitializationPhase.SYSTEM_READY
        uiState.initPhase == InitializationPhase.PARSING_PERSONA -> InitializationPhase.PARSING_PERSONA
        uiState.initPhase == InitializationPhase.ALLOCATING_MEMORY || uiState.localModelLoading -> InitializationPhase.ALLOCATING_MEMORY
        else -> InitializationPhase.IDLE
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            AtlasAiBranding(
                fontSize = 22.sp,
                lineHeight = 3.5.dp,
                spacing = 3.dp,
                strokeWidth = 2.5f,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                text = "NEURAL COGNITIVE MATRIX • ACCELERATED CORE RUNTIME",
                color = textDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.08.em,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(0.5.dp, neonGreen.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "● SIMD NEON",
                        color = neonGreen,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(0.5.dp, vibrantBlue.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "⚡ SPECULATIVE MTP",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                Surface(
                    color = Color(0x33FF2A4D),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(0.5.dp, crimsonRed.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "RAM DRAFT LOCK",
                        color = crimsonRed,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .tactileGlassGlow(cornerRadius = 14.dp, isDark = true, elevation = 3.dp),
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = "Terminal Engine",
                            tint = electricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "NEURAL CORE INITIALIZATION",
                            color = textBright,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.05.em
                        )
                    }

                    val (badgeText, badgeBg, badgeTextColor) = when (currentPhase) {
                        InitializationPhase.ALLOCATING_MEMORY -> Triple("ALLOCATING", amber.copy(alpha = 0.2f), amber)
                        InitializationPhase.PARSING_PERSONA -> Triple("PREFILLING", electricCyan.copy(alpha = 0.2f), electricCyan)
                        InitializationPhase.SYSTEM_READY -> Triple("ONLINE", neonGreen.copy(alpha = 0.2f), neonGreen)
                        InitializationPhase.IDLE -> Triple("STANDBY", textDim.copy(alpha = 0.2f), textDim)
                    }
                    Surface(
                        color = badgeBg,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(0.5.dp, badgeTextColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeTextColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Divider(color = cardBorder.copy(alpha = 0.4f), thickness = 0.5.dp)

                val isState1Active = currentPhase == InitializationPhase.ALLOCATING_MEMORY
                val isState1Done = currentPhase == InitializationPhase.PARSING_PERSONA || currentPhase == InitializationPhase.SYSTEM_READY
                InitializationStateItem(
                    stepNumber = "1",
                    title = if (uiState.activeModelType == ModelType.GEMINI) "Initializing Gemini Cloud Core..." else "Allocating Local Neural Core... [Model & Draft]",
                    description = if (isState1Active) {
                        uiState.loadingStatusText.ifBlank { if (uiState.activeModelType == ModelType.GEMINI) "Establishing encrypted cloud tunnel" else "Mapping Base to storage & locking Draft in RAM" }
                    } else if (isState1Done) {
                        if (uiState.activeModelType == ModelType.GEMINI) "Cloud tunnel established ✔" else "Neural Core & Speculative MTP mapped successfully ✔"
                    } else {
                        if (uiState.activeModelType == ModelType.GEMINI) "Connecting to Gemini servers" else "Base Model (Storage mmap) • Draft Model (RAM Locked)"
                    },
                    isActive = isState1Active,
                    isCompleted = isState1Done,
                    progress = if (isState1Active) uiState.loadingProgress else if (isState1Done) 1.0f else null,
                    showProgressBar = isState1Active,
                    isDark = true
                )

                val isState2Active = currentPhase == InitializationPhase.PARSING_PERSONA
                val isState2Done = currentPhase == InitializationPhase.SYSTEM_READY
                InitializationStateItem(
                    stepNumber = "2",
                    title = "Parsing Unified Persona...",
                    description = if (isState2Active) {
                        "Prefilling KV-cache with ATLAS-AI directives..."
                    } else if (isState2Done) {
                        "ATLAS-AI Persona compiled & KV-cache pre-warmed ✔"
                    } else {
                        "Pre-warming KV-cache with unified system prompt"
                    },
                    isActive = isState2Active,
                    isCompleted = isState2Done,
                    progress = if (isState2Active) 0.95f else if (isState2Done) 1.0f else null,
                    showProgressBar = isState2Active,
                    isDark = true
                )

                val isState3Done = currentPhase == InitializationPhase.SYSTEM_READY
                InitializationStateItem(
                    stepNumber = "3",
                    title = "ATLAS-AI AGENT Core Ready.",
                    description = if (isState3Done) {
                        "Neural link established • Zero-latency inference standby"
                    } else {
                        "Awaiting initialization sequence completion"
                    },
                    isActive = false,
                    isCompleted = isState3Done,
                    progress = null,
                    showProgressBar = false,
                    isDark = true
                )
            }
        }
    }
}

@Composable
private fun InitializationStateItem(
    stepNumber: String,
    title: String,
    description: String,
    isActive: Boolean,
    isCompleted: Boolean,
    progress: Float?,
    showProgressBar: Boolean,
    isDark: Boolean
) {
    val electricCyan = MaterialTheme.colorScheme.primary
    val neonGreen = MaterialTheme.colorScheme.primary
    val textBright = if (isDark) Color(0xFFF8FAFC) else DayOnBackground
    val textDim = if (isDark) NightOnSurfaceVariant else DayOnSurfaceVariant

    val titleColor = when {
        isCompleted -> neonGreen
        isActive -> electricCyan
        else -> textDim
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isCompleted -> {
                        Surface(
                            shape = CircleShape,
                            color = neonGreen.copy(alpha = 0.15f),
                            modifier = Modifier.size(18.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Completed",
                                    tint = neonGreen,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                    isActive -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = electricCyan,
                            strokeWidth = 2.dp
                        )
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .border(1.dp, textDim.copy(alpha = 0.35f), CircleShape)
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "STATE $stepNumber:$title",
                        color = titleColor,
                        fontSize = 12.sp,
                        fontWeight = if (isActive || isCompleted) FontWeight.Bold else FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                    if (isActive && progress != null) {
                        Text(
                            text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                            color = electricCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Text(
                    text = description,
                    color = textDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (showProgressBar) {
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = (progress ?: 0.1f).coerceIn(0.05f, 1.0f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 30.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = electricCyan,
                trackColor = MaterialTheme.colorScheme.surfaceContainer
            )
        }
    }
}

@Composable
fun SidebarContent(
    viewModel: AgentViewModel,
    onCloseDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTaskManager: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val sessions by viewModel.chatSessions.collectAsState()
    val activeSessionId by viewModel.currentSessionId.collectAsState()

    val drawerBg = MaterialTheme.colorScheme.surfaceContainerLow
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
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
                AtlasAiBranding(fontSize = 13.sp, lineHeight = 2.dp)
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
                    onOpenTaskManager()
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
                    Icon(Icons.Outlined.Schedule, "Scheduled tasks", tint = primary, modifier = Modifier.size(20.dp))
                    Text("Scheduled Tasks", color = onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

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
                    Icon(Icons.Outlined.Settings, "Settings", tint = primary, modifier = Modifier.size(20.dp))
                    Text("System Settings", color = onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
