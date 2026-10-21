@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
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
import android.provider.OpenableColumns
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.compose.ui.window.Popup
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

private fun metricGradientColor(percentage: Int, higherIsBetter: Boolean): Color {
    val lowColor = if (higherIsBetter) ErrorCrimson else SuccessGreen
    val middleColor = WarningAmber
    val highColor = if (higherIsBetter) SuccessGreen else ErrorCrimson
    val fraction = percentage.coerceIn(0, 100) / 100f
    return if (fraction <= 0.5f) {
        lerp(lowColor, middleColor, fraction * 2f)
    } else {
        lerp(middleColor, highColor, (fraction - 0.5f) * 2f)
    }
}

private fun temperatureLoadPercent(celsius: Int): Int =
    (((celsius - 20).coerceIn(0, 65) / 65f) * 100).roundToInt()

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
        SystemEventMonitor.start(this, viewModel::queueSystemEvent)
    }

    override fun onPause() {
        SystemEventMonitor.stop()
        super.onPause()
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
    var selectedPreviewArtifact by remember { mutableStateOf<StreamDemultiplexer.DemuxBlock.FileArtifact?>(null) }
    val attachmentScope = rememberCoroutineScope()
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
        onResult = { uri ->
            selectedFileUri = uri
            selectedPreviewArtifact = null
            if (uri != null) {
                attachmentScope.launch {
                    try {
                        selectedPreviewArtifact = withContext(Dispatchers.IO) {
                            stageChatAttachment(context, uri)
                        }
                    } catch (failure: Exception) {
                        Toast.makeText(
                            context,
                            "Could not prepare file preview: ${failure.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
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
                selectedPreviewArtifact = null
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
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Filled.ArrowDownward, null, tint = primaryAccent, modifier = Modifier.size(11.dp))
                                Text(
                                    text = if (uiState.isProcessing) "● Live Stream Tail ↓" else "Jump to Latest ↓",
                                    color = MaterialTheme.colorScheme.headerModel,
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

                    val dotColor = if (isBusy) primaryAccent else MaterialTheme.colorScheme.statusIdle
                    val textColor = if (isBusy) primaryAccent else MaterialTheme.colorScheme.statusIdle

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

                    selectedPreviewArtifact?.let { artifact ->
                        UniversalFilePreviewCard(artifact)
                    }

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
                                            color = MaterialTheme.colorScheme.headerModel,
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
                                                    selectedPreviewArtifact = null
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
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.92f))
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
                        tint = MaterialTheme.colorScheme.onSurface
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
    temperatureCelsius: Int? = null,
    onClick: (() -> Unit)? = null
) {
    val metricValue = percentage?.coerceIn(0, 100)
    val normalColor = when (label) {
        "MEM", "SWAP" -> MaterialTheme.colorScheme.headerSecondary
        "CPU", "NET" -> MaterialTheme.colorScheme.headerActive
        else -> MaterialTheme.colorScheme.primary
    }
    val warningColor = MaterialTheme.colorScheme.statusWarning
    val criticalColor = MaterialTheme.colorScheme.error
    val targetMetricColor = when {
        label == "TEMP" && temperatureCelsius != null -> when {
            temperatureCelsius < 50 -> MaterialTheme.colorScheme.statusSuccess
            temperatureCelsius < 70 -> warningColor
            else -> criticalColor
        }
        label == "MEM" && metricValue != null ->
            if (metricValue < 15) criticalColor else normalColor
        label == "NET" -> if (value == "ON") MaterialTheme.colorScheme.headerActive else criticalColor
        metricValue != null && metricValue > 85 -> criticalColor
        metricValue != null && metricValue > 70 -> warningColor
        else -> normalColor
    }
    val metricColor by animateColorAsState(
        targetValue = targetMetricColor,
        animationSpec = tween(220),
        label = "telemetryMetricColor"
    )
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
        color = MaterialTheme.colorScheme.headerChipBackground,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 26.dp)
                .border(
                    width = 0.7.dp,
                    color = MaterialTheme.colorScheme.headerChipBorder.copy(alpha = if (isDark) 0.25f else 1f),
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                textAlign = TextAlign.Start,
                overflow = TextOverflow.Clip
            )
            Text(
                text = value,
                color = metricColor,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                textAlign = TextAlign.End,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f)
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
        DashboardMetricData("CPU", stats.cpuUsagePercent, stats.cpuUsagePercent?.let { "$it%" } ?: "--"),
        DashboardMetricData("MEM", stats.ramUsedPercent?.let { 100 - it }, stats.ramFree.replace("GB", "G")),
        DashboardMetricData(
            "TEMP",
            stats.cpuTempInt.takeIf { it > 0 }?.let(::temperatureLoadPercent),
            if (stats.cpuTemp == "--") "--" else stats.cpuTemp.removeSuffix("°C") + "°",
            temperatureCelsius = stats.cpuTempInt.takeIf { it > 0 }
        ),
        DashboardMetricData("SWAP", stats.swapUsedPercent, stats.swapUsedPercent?.let { "$it%" } ?: "--"),
        DashboardMetricData("TASK", (totalTasks.coerceIn(0, 10) * 10), totalTasks.toString()),
        DashboardMetricData("NET", if (stats.networkConnected) 100 else 0, if (stats.networkConnected) "ON" else "OFF")
    )

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            metrics.forEach { metric ->
                DashboardMetric(
                    label = metric.label,
                    percentage = metric.percentage,
                    value = metric.value,
                    temperatureCelsius = metric.temperatureCelsius,
                    contentDescription = if (metric.label == "TASK") {
                        if (taskStripExpanded) "Hide active task details" else "Show active task details"
                    } else {
                        metric.label
                    },
                    isDark = isDark,
                    modifier = Modifier.weight(1f),
                    onClick = if (metric.label == "TASK") onTaskGaugeClick else null
                )
            }
        }
    }
}

private data class DashboardMetricData(
    val label: String,
    val percentage: Int?,
    val value: String,
    val temperatureCelsius: Int? = null
)

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
    strokeWidth: Float = 2.5f,
    primaryColorOverride: Color? = null,
    secondaryColorOverride: Color? = null
) {
    val primaryColor = primaryColorOverride ?: MaterialTheme.colorScheme.onSurface
    val secondaryColor = secondaryColorOverride ?: MaterialTheme.colorScheme.onSurfaceVariant

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

/**
 * Fixed HUD above the weighted chat viewport; it is deliberately not attached
 * to the LazyColumn scroll state so model and telemetry context remains visible.
 */
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
    val modelName = if (isCloudMode) uiState.config.geminiModelName.uppercase() else cleanBaseName
    val modelDetails = when {
        isCloudMode -> "GEMINI CLOUD • $modelName • $formattedTps T/s"
        hasMtp && draftTag != null -> "$modelName • MTP: $draftTag • $formattedTps T/s"
        else -> "$modelName • STANDALONE • $formattedTps T/s"
    }
    val modelDetailsStart = modelDetails.indexOf(modelName)
    val modelDetailsEnd = modelDetailsStart + modelName.length

    val headerShape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .clip(headerShape)
            .background(MaterialTheme.colorScheme.headerBackground)
            .border(
                width = 0.7.dp,
                color = MaterialTheme.colorScheme.headerBottomBorder.copy(alpha = if (isDark) 0.15f else 0.5f),
                shape = headerShape
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AtlasAiBranding(
                                fontSize = if (compactHeader) 11.sp else 16.sp,
                                lineHeight = 2.dp,
                                primaryColorOverride = MaterialTheme.colorScheme.headerTitle,
                                secondaryColorOverride = MaterialTheme.colorScheme.headerTitle
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (uiState.isProcessing) primaryAccent.copy(alpha = 0.14f) else subCardBg,
                                border = BorderStroke(
                                    0.7.dp,
                                    (if (uiState.isProcessing) primaryAccent else MaterialTheme.colorScheme.outlineVariant)
                                        .copy(alpha = 0.48f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(if (uiState.isProcessing) ThinkingCyan else IdleSlate)
                                    )
                                    Text(
                                        text = if (uiState.isProcessing) "RUNNING" else "IDLE",
                                        color = if (uiState.isProcessing) ThinkingCyan else textDim,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        Text(
                            text = androidx.compose.ui.text.buildAnnotatedString {
                                append(modelDetails)
                                if (modelDetailsStart >= 0) {
                                    addStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.headerModel,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        modelDetailsStart,
                                        modelDetailsEnd
                                    )
                                }
                            },
                            color = MaterialTheme.colorScheme.headerModel,
                            fontSize = if (compactHeader) 7.sp else 8.sp,
                            lineHeight = if (compactHeader) 8.sp else 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = if (compactHeader) 180.dp else 220.dp)
                        )
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

    val traceColor = MaterialTheme.colorScheme.onSurfaceVariant
    val traceAccent = MaterialTheme.colorScheme.primary
    val gutterColor = MaterialTheme.colorScheme.outlineVariant
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
                        lineHeight = 17.25.sp,
                        textAlign = TextAlign.Start,
                        style = TextStyle(
                            textDirection = if (isThoughtRtl) {
                                androidx.compose.ui.text.style.TextDirection.ContentOrRtl
                            } else {
                                androidx.compose.ui.text.style.TextDirection.ContentOrLtr
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun AccessibleIconAction(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit
) {
    var showTooltip by remember(description) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(showTooltip) {
        if (showTooltip) {
            delay(1400)
            showTooltip = false
        }
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showTooltip = true
                }
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        if (showTooltip) {
            Popup(
                alignment = Alignment.TopCenter,
                onDismissRequest = { showTooltip = false }
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        description,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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
    val haptic = LocalHapticFeedback.current

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

    val langAccent = MaterialTheme.colorScheme.primary
    val pathColor = MaterialTheme.colorScheme.tertiary
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
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

                AccessibleIconAction(
                    icon = Icons.Filled.ContentCopy,
                    description = if (copied) "Code copied" else "Copy code",
                    tint = if (copied) MaterialTheme.colorScheme.primary else textDim
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                    coroutineScope.launch {
                        delay(1800)
                        copied = false
                    }
                }

                AccessibleIconAction(
                    icon = if (isExpanded) Icons.Filled.VisibilityOff else Icons.Filled.Code,
                    description = if (isExpanded) "Hide code" else "Show code",
                    tint = if (isExpanded) MaterialTheme.colorScheme.primary else textDim
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    userExpanded = !isExpanded
                }
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            val highlighted = remember(code, language, isDark) {
                SyntaxHighlighter.highlight(code, language, isDark)
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.codeSurface,
                border = BorderStroke(
                    0.8.dp,
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.58f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clipToBounds()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = highlighted,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.5.sp,
                        style = TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurface
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

    val cmdColor = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
    val bulletColor = textDim
    val gutterColor = MaterialTheme.colorScheme.outlineVariant

    val displayText = rawLine.trim().replace(Regex("^[●*•⚡\\s]+"), "")
    val isDisplayRtl = isArabic(displayText)
    val mainDisplayText = if (isTelemetry) displayText else displayText.substringBefore(" -> ")
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
                text = if (isDisplayRtl) applyBidiIsolation(mainDisplayText) else mainDisplayText,
                color = cmdColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Start,
                style = TextStyle(
                    textDirection = if (isDisplayRtl) {
                        androidx.compose.ui.text.style.TextDirection.ContentOrRtl
                    } else {
                        androidx.compose.ui.text.style.TextDirection.ContentOrLtr
                    }
                ),
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
                        text = if (isDisplayRtl) applyBidiIsolation(output) else output,
                        color = textDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                        textAlign = TextAlign.Start,
                        style = TextStyle(
                            textDirection = if (isDisplayRtl) {
                                androidx.compose.ui.text.style.TextDirection.ContentOrRtl
                            } else {
                                androidx.compose.ui.text.style.TextDirection.ContentOrLtr
                            }
                        )
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

    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant

    val isLogArabic = isArabic(log.text)

    when {
        isUser -> {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (isLogArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .padding(horizontal = 12.dp)
                        .padding(
                            start = if (isLogArabic) 0.dp else 36.dp,
                            end = if (isLogArabic) 36.dp else 0.dp
                        ),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = if (isLogArabic) "\u2066>>\u2069" else "\u2066<<\u2069",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        style = TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = if (isLogArabic) Alignment.End else Alignment.Start
                    ) {
                        CliMathFormattedText(
                            text = log.text,
                            isDark = isDark,
                            isRtl = isLogArabic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            lineHeight = 19.5.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                            markdownStyler = { txt, dark, rtl -> buildOrganizedMarkdownString(txt, dark, rtl) }
                        )

                        Text(
                            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(log.timestamp)),
                            color = textDim.copy(alpha = 0.35f),
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            style = TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 1.dp)
                        )
                    }
                }
            }
        }

        isSystem -> {
            val isError = log.isError
            val bulletColor = textDim
            val marker = if (isError) "✖ Error:" else "● System:"

            CompositionLocalProvider(
                LocalLayoutDirection provides if (isLogArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "\u2066$marker\u2069",
                        color = bulletColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        style = TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)
                    )
                    Text(
                        text = if (isLogArabic) applyBidiIsolation(log.text) else log.text,
                        color = if (isError) MaterialTheme.colorScheme.error else textDim,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.5.sp,
                        textAlign = TextAlign.Start,
                        style = TextStyle(
                            textDirection = if (isLogArabic) {
                                androidx.compose.ui.text.style.TextDirection.ContentOrRtl
                            } else {
                                androidx.compose.ui.text.style.TextDirection.ContentOrLtr
                            }
                        ),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
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
                            is StreamDemultiplexer.DemuxBlock.VisualArtifact -> block.executionId
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
                                    UniversalFilePreviewCard(block)
                                }
                                is StreamDemultiplexer.DemuxBlock.VisualArtifact -> {
                                    CliVisualArtifactCard(block, isDark)
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

    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val cardBg = MaterialTheme.colorScheme.surfaceContainerLow
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant

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
                color = MaterialTheme.colorScheme.surfaceContainer,
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
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${artifact.format.uppercase()} · ${artifact.path}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun CliVisualArtifactCard(
    artifact: StreamDemultiplexer.DemuxBlock.VisualArtifact,
    isDark: Boolean
) {
    val payload = remember(artifact.payloadJson) {
        try {
            org.json.JSONObject(artifact.payloadJson).optJSONObject("data")
        } catch (_: org.json.JSONException) {
            null
        }
    }
    var selectedItem by remember(artifact.executionId) { mutableStateOf<Int?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = artifact.title,
                color = onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            if (payload == null) {
                Text("Artifact data is invalid.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            } else when (artifact.type) {
                "bar_chart", "line_chart" -> {
                    val labels = payload.optJSONArray("labels")
                    val values = payload.optJSONArray("values")
                    if (labels == null || values == null || labels.length() == 0 || labels.length() != values.length()) {
                        Text("Chart data is incomplete.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    } else {
                        val chartValues = (0 until values.length()).map { values.optDouble(it, 0.0) }
                        val maximum = chartValues.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier.fillMaxWidth().height(132.dp)
                        ) {
                            val bottom = size.height - 4.dp.toPx()
                            val step = size.width / chartValues.size
                            drawLine(
                                color = secondary.copy(alpha = 0.5f),
                                start = Offset(0f, bottom),
                                end = Offset(size.width, bottom),
                                strokeWidth = 1.dp.toPx()
                            )
                            if (artifact.type == "bar_chart") {
                                chartValues.forEachIndexed { index, value ->
                                    val barWidth = (step * 0.56f).coerceAtLeast(2.dp.toPx())
                                    val barHeight = ((value / maximum).toFloat() * (bottom - 4.dp.toPx()))
                                        .coerceAtLeast(1.dp.toPx())
                                    drawRoundRect(
                                        color = accent,
                                        topLeft = Offset(index * step + (step - barWidth) / 2f, bottom - barHeight),
                                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                                    )
                                }
                            } else if (chartValues.size > 1) {
                                val points = chartValues.mapIndexed { index, value ->
                                    Offset(
                                        x = index * step + step / 2f,
                                        y = bottom - (value / maximum).toFloat() * (bottom - 4.dp.toPx())
                                    )
                                }
                                points.zipWithNext().forEach { (start, end) ->
                                    drawLine(accent, start, end, strokeWidth = 2.dp.toPx())
                                }
                                points.forEach { point ->
                                    drawCircle(accent, radius = 3.dp.toPx(), center = point)
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            repeat(labels.length()) { index ->
                                val label = labels.optString(index)
                                val selected = selectedItem == index
                                Surface(
                                    modifier = Modifier.clickable { selectedItem = index },
                                    color = if (selected) accent.copy(alpha = 0.18f) else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = label.take(18),
                                        color = if (selected) accent else secondary,
                                        fontSize = 9.sp,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                        selectedItem?.let { index ->
                            Text(
                                "${labels.optString(index)}: ${chartValues[index]}${payload.optString("unit")}",
                                color = onSurface,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                "table" -> {
                    val columns = payload.optJSONArray("columns")
                    val rows = payload.optJSONArray("rows")
                    if (columns == null || rows == null) {
                        Text("Table data is incomplete.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    } else {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                repeat(columns.length()) { index ->
                                    Text(
                                        columns.optString(index).take(32),
                                        color = accent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.widthIn(min = 72.dp, max = 140.dp)
                                    )
                                }
                            }
                            repeat(rows.length()) { rowIndex ->
                                val row = rows.optJSONArray(rowIndex)
                                if (row != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        repeat(minOf(columns.length(), row.length())) { columnIndex ->
                                            Text(
                                                row.optString(columnIndex).take(80),
                                                color = onSurface,
                                                fontSize = 10.sp,
                                                modifier = Modifier.widthIn(min = 72.dp, max = 140.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "mermaid" -> {
                    val nodes = payload.optJSONArray("nodes")
                    val edges = payload.optJSONArray("edges")
                    if (nodes == null || edges == null) {
                        Text("Diagram data is incomplete.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(nodes.length()) { nodeIndex ->
                                val node = nodes.optJSONObject(nodeIndex) ?: return@repeat
                                val nodeId = node.optString("id")
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable { selectedItem = nodeIndex },
                                    color = if (selectedItem == nodeIndex) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, accent.copy(alpha = 0.25f))
                                ) {
                                    Text(
                                        "${node.optString("label")}  [$nodeId]",
                                        color = onSurface,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                    )
                                }
                                val outgoing = (0 until edges.length()).mapNotNull { edgeIndex ->
                                    val edge = edges.optJSONArray(edgeIndex) ?: return@mapNotNull null
                                    if (edge.optString(0) == nodeId) edge.optString(1) else null
                                }
                                outgoing.forEach { target ->
                                    val targetLabel = (0 until nodes.length())
                                        .mapNotNull { targetIndex -> nodes.optJSONObject(targetIndex) }
                                        .firstOrNull { it.optString("id") == target }
                                        ?.optString("label")
                                        ?: target
                                    Text(
                                        "↓  $targetLabel",
                                        color = secondary,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(start = 14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                else -> Text("Unsupported visual artifact type: ${artifact.type}", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "LOCAL STRUCTURED ARTIFACT",
                color = secondary,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }
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

private fun stageChatAttachment(context: Context, uri: Uri): StreamDemultiplexer.DemuxBlock.FileArtifact {
    val displayName = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("""[^\p{L}\p{N}._-]"""), "_")
        .take(120)
        .ifBlank { "attachment" }
    val format = safeName.substringAfterLast('.', "").lowercase(Locale.ROOT).ifBlank {
        context.contentResolver.getType(uri)
            ?.substringAfter('/')
            ?.substringBefore('+')
            ?.lowercase(Locale.ROOT)
            ?: "bin"
    }
    val directory = File(context.cacheDir, "atlas-chat-previews").apply {
        if (!exists() && !mkdirs()) error("Could not create the private attachment preview folder.")
    }
    val destination = File(directory, "${UUID.randomUUID()}.$format")
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Could not open the selected attachment.")
        input.use { source ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var totalBytes = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= 50L * 1024L * 1024L) {
                        "Attachments larger than 50 MB cannot be previewed."
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
        return StreamDemultiplexer.DemuxBlock.FileArtifact(
            executionId = UUID.randomUUID().toString(),
            path = destination.absolutePath,
            fileName = safeName,
            format = format,
            preview = ""
        )
    } catch (failure: Exception) {
        destination.delete()
        throw failure
    }
}

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

    val terminalText = MaterialTheme.colorScheme.onSurfaceVariant
    val terminalAccent = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
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
                            color = if (copiedRecently) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = if (isExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { userExpanded = !isExpanded }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                if (isExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
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
                                color = MaterialTheme.colorScheme.onSurface,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                color = MaterialTheme.colorScheme.onSurface,
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

@Composable
fun CliToolCallEvent(block: StreamDemultiplexer.DemuxBlock.ToolCall, isDark: Boolean) {
    val paramStr = block.params.entries.joinToString(", ") { "${it.key}: \"${it.value}\"" }
    val isRunning = !block.isClosed
    val statusSuffix = if (isRunning) " -> Dispatched" else " -> Completed"
    val toolAccent = MaterialTheme.colorScheme.onSurfaceVariant
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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

@Composable
fun CliFormattedBody(raw: String, isDark: Boolean) {
    val text = remember(raw) { StreamDemultiplexer.sanitizeProse(raw).trim() }
    if (text.isBlank()) return

    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant
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
                                text = "\u2066┌── EXECUTIVE DEPLOYMENT CONFIRMATION ────────────────────────\u2069",
                                color = headingColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                style = TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)
                            )
                            Text(
                                text = "\u2066│ Status: Synced & Verified on Device Storage\u2069",
                                color = textDim,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                style = TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                            Text(
                                text = "\u2066└────────────────────────────────────────────────────────────\u2069",
                                color = headingColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                style = TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)
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
                            lineHeight = 20.25.sp,
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
                                text = "\u2066$bulletMarker\u2069",
                                color = headingColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                style = TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            CliMathFormattedText(
                                text = bulletContent,
                                isDark = isDark,
                                isRtl = isLineArabic,
                                color = textBright,
                                fontSize = 12.5.sp,
                                lineHeight = 18.75.sp,
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
                            lineHeight = 18.75.sp,
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
    val pathBg = if (isDark) NightSurfaceContainerHigh else DaySurfaceContainerHigh
    val codeBg = if (isDark) NightSurfaceContainerLow else DaySurfaceContainerLow
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
                builder.append("\u2066$v\u2069")
                builder.pop()
            }
            v.startsWith("`") && v.endsWith("`") && v.length >= 2 -> {
                val codeContent = v.substring(1, v.length - 1)
                builder.pushStyle(SpanStyle(color = codeColor, background = codeBg, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold))
                builder.append(if (isRtl) " \u2066$codeContent\u2069 " else " $codeContent ")
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
    val pathBg = if (isDark) NightSurfaceContainerHigh else DaySurfaceContainerHigh
    val builder = AnnotatedString.Builder()
    var lastIndex = 0

    pathRegex.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            val part = text.substring(lastIndex, match.range.first)
            builder.append(if (isRtl) applyBidiIsolation(part) else part)
        }
        builder.pushStyle(SpanStyle(color = pathColor, background = pathBg, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
        builder.append("\u2066${match.value}\u2069")
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
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
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
    val textBright = MaterialTheme.colorScheme.onSurface
    val textDim = MaterialTheme.colorScheme.onSurfaceVariant

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
