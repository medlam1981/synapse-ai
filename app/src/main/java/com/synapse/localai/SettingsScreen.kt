package com.synapse.localai

import java.io.File
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.synapse.localai.ui.theme.*

private val SCAN_DIRS = listOf(
    "/sdcard/Medlam-Ai/models",
    "/sdcard/Medlam-Ai/Models",
    "/sdcard/Medlam-Ai",
    "/sdcard/Models",
    "/sdcard/Download",
    "/storage/emulated/0/Medlam-Ai/models",
    "/storage/emulated/0/Medlam-Ai/Models",
    "/storage/emulated/0/Medlam-Ai",
    "/storage/emulated/0/Models",
    "/storage/emulated/0/Download",
    "/storage/emulated/0/Documents"
)

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
}

@Composable
fun SettingsScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val config = uiState.config

    SynapseAITheme {
        SettingsScreenContent(viewModel, onBack, uiState, config, context)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    viewModel: AgentViewModel,
    onBack: () -> Unit,
    uiState: AgentUiState,
    config: AgentConfig,
    context: Context
) {
    val coroutineScope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    
    // --- LOCAL FORM STATE ---
    var geminiKeysVisible by remember { mutableStateOf(false) }
    var openAiKeysVisible by remember { mutableStateOf(false) }
    var contextLengthText by remember(config.contextLength) { mutableStateOf(config.contextLength.toString()) }
    var threadCountText by remember(config.threadCount) { mutableStateOf(config.threadCount.toString()) }
    var temperatureState by remember(config.temperature) { mutableStateOf(config.temperature) }
    var gpuLayersState by remember(config.gpuLayers) { mutableStateOf(config.gpuLayers) }

    LaunchedEffect(Unit) {
        isScanning = true
        withContext(Dispatchers.IO) {
            val found = mutableListOf<File>()
            SCAN_DIRS.forEach { path ->
                val dir = File(path)
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles { f -> f.extension.equals("gguf", ignoreCase = true) }?.let { found.addAll(it) }
                }
            }
            models = found.distinctBy { it.name }
        }
        isScanning = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())

        // Top App Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), Color.Transparent)
                    )
                )
                .background(MaterialTheme.colorScheme.surface)
                .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        "SYSTEM SETTINGS",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "CONFIGURATION CORE • AUTO-SAVED",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                IconButton(
                    onClick = { onBack() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ==========================================
            // CARD 1: COGNITIVE ENGINE SELECTOR (AUTO-SAVING)
            // ==========================================
            SettingsCard(
                title = "1. COGNITIVE ENGINE",
                titleColor = NeonCyan,
                statusText = uiState.activeModelType?.name ?: "OFFLINE"
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isGemini = config.preferredModelType == ModelType.GEMINI
                        val isOpenAi = config.preferredModelType == ModelType.OPENAI
                        val isGguf = config.preferredModelType == ModelType.GGUF

                        Button(
                            onClick = {
                                val newConfig = config.copy(preferredModelType = ModelType.GEMINI)
                                viewModel.saveConfig(context, newConfig)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isGemini) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("Gemini", color = if (isGemini) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                val newConfig = config.copy(preferredModelType = ModelType.OPENAI)
                                viewModel.saveConfig(context, newConfig)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isOpenAi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("OpenAI", color = if (isOpenAi) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                val newConfig = config.copy(preferredModelType = ModelType.GGUF)
                                viewModel.saveConfig(context, newConfig)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isGguf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("Local GGUF", color = if (isGguf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Cloud Credentials Section (Auto-Saving)
                    if (config.preferredModelType == ModelType.GEMINI) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = config.geminiApiKeys,
                                onValueChange = {
                                    val newConfig = config.copy(geminiApiKeys = it)
                                    viewModel.saveConfig(context, newConfig)
                                },
                                label = { Text("Gemini API Keys (comma-separated)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = settingsFieldColors(),
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                visualTransformation = if (geminiKeysVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { geminiKeysVisible = !geminiKeysVisible }) {
                                        Icon(if (geminiKeysVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                    }
                                }
                            )

                            OutlinedTextField(
                                value = config.geminiModelName,
                                onValueChange = {
                                    val newConfig = config.copy(geminiModelName = it)
                                    viewModel.saveConfig(context, newConfig)
                                },
                                label = { Text("Gemini Model Name") },
                                placeholder = { Text("gemini-1.5-flash") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = settingsFieldColors(),
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            )
                        }
                    } else if (config.preferredModelType == ModelType.OPENAI) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = config.openaiApiKeys,
                                onValueChange = {
                                    val newConfig = config.copy(openaiApiKeys = it)
                                    viewModel.saveConfig(context, newConfig)
                                },
                                label = { Text("OpenAI API Keys (comma-separated)") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = settingsFieldColors(),
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                visualTransformation = if (openAiKeysVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { openAiKeysVisible = !openAiKeysVisible }) {
                                        Icon(if (openAiKeysVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                    }
                                }
                            )

                            OutlinedTextField(
                                value = config.openAiModelName,
                                onValueChange = {
                                    val newConfig = config.copy(openAiModelName = it)
                                    viewModel.saveConfig(context, newConfig)
                                },
                                label = { Text("OpenAI Model Name") },
                                placeholder = { Text("gpt-4o") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = settingsFieldColors(),
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            )
                        }
                    }
                }
            }

            // ==========================================
            // CARD 2: LOCAL NEURAL CORE (GGUF + MTP)
            // ==========================================
            SettingsCard(
                title = "2. LOCAL NEURAL CORE",
                titleColor = Color(0xFF0284C7),
                statusDot = when {
                    uiState.localModelLoaded && config.preferredModelType == ModelType.GGUF -> Color.Green
                    uiState.localModelLoading -> Color.Yellow
                    else -> Color.Red
                },
                statusText = if (uiState.localModelLoading) "LOADING..." else if (uiState.localModelLoaded) "LOADED" else "OFFLINE"
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Real-Time 0-100% Model Loading Progress Bar
                    if (uiState.localModelLoading) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(BorderStroke(0.5.dp, Color(0xFF0284C7).copy(alpha = 0.6f)), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = uiState.loadingStatusText.ifBlank { "Loading Neural Core..." },
                                    color = Color(0xFF0284C7),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${((uiState.loadingProgress ?: 0f) * 100).toInt()}%",
                                    color = Color(0xFF00D4FF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            LinearProgressIndicator(
                                progress = (uiState.loadingProgress ?: 0.05f).coerceIn(0f, 1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFF0284C7),
                                trackColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }

                    if (isScanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF0284C7))
                    }

                    if (models.isEmpty() && !isScanning) {
                        Text("No .gguf models found in /sdcard/Medlam-Ai/models.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }

                    // Base Model Picker (Auto-Saving)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("BASE NEURAL MODEL:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        models.forEach { file ->
                            val isSelected = config.localModelPath == file.absolutePath
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                    .border(0.5.dp, if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable {
                                        val newConfig = config.copy(
                                            localModelPath = file.absolutePath,
                                            preferredModelType = ModelType.GGUF
                                        )
                                        viewModel.saveConfig(context, newConfig)
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        val newConfig = config.copy(
                                            localModelPath = file.absolutePath,
                                            preferredModelType = ModelType.GGUF
                                        )
                                        viewModel.saveConfig(context, newConfig)
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(file.name, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                    Text(formatSize(file.length()) + " • " + file.parent, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    // Draft / MTP Accelerator Picker (Auto-Saving)
                    if (models.size > 1) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("DRAFT / MTP SPECULATIVE ACCELERATOR:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (config.draftModelPath.isBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                                    .border(0.5.dp, if (config.draftModelPath.isBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable {
                                        val newConfig = config.copy(draftModelPath = "")
                                        viewModel.saveConfig(context, newConfig)
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = config.draftModelPath.isBlank(),
                                    onClick = {
                                        val newConfig = config.copy(draftModelPath = "")
                                        viewModel.saveConfig(context, newConfig)
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("None (Single Model Mode)", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                            models.filter { it.absolutePath != config.localModelPath }.forEach { file ->
                                val isDraftSelected = config.draftModelPath == file.absolutePath
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isDraftSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                        .border(0.5.dp, if (isDraftSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                        .clickable {
                                            val newConfig = config.copy(draftModelPath = file.absolutePath)
                                            viewModel.saveConfig(context, newConfig)
                                        }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isDraftSelected,
                                        onClick = {
                                            val newConfig = config.copy(draftModelPath = file.absolutePath)
                                            viewModel.saveConfig(context, newConfig)
                                        }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(file.name, color = if (isDraftSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        Text(formatSize(file.length()), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.5.sp)
                                    }
                                }
                            }
                        }
                    }

                    // ========================================================
                    // REVAMPED SUB-CARD: GPU LAYERS, MMAP & RE-SCAN ACTION
                    // ========================================================
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Top Row: mmap Direct Memory Switch
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        "mmap Direct Memory",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Direct memory mapping for instant loading",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                                Switch(
                                    checked = config.useMmap,
                                    onCheckedChange = {
                                        val newConfig = config.copy(useMmap = it)
                                        viewModel.saveConfig(context, newConfig)
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.5f))
                                )
                            }

                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)

                            // Middle Row: GPU Offload Layers Slider with Active Count Badge
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "GPU Offload Layers",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val badgeText = when (gpuLayersState) {
                                        0 -> "[ 0 / 33 (CPU Only) ]"
                                        33 -> "[ 33 / 33 (Full GPU) ]"
                                        else -> "[ $gpuLayersState / 33 (Hybrid) ]"
                                    }
                                    Text(
                                        text = badgeText,
                                        color = if (gpuLayersState > 0) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }

                                Slider(
                                    value = gpuLayersState.toFloat(),
                                    onValueChange = { gpuLayersState = it.toInt() },
                                    onValueChangeFinished = {
                                        val newConfig = config.copy(gpuLayers = gpuLayersState)
                                        viewModel.saveConfig(context, newConfig)
                                    },
                                    valueRange = 0f..33f,
                                    steps = 32,
                                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
                                )
                            }

                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)

                            // Bottom Row: Centered single [Re-Scan Models] outline button
                            OutlinedButton(
                                onClick = {
                                    isScanning = true
                                    val found = mutableListOf<File>()
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            SCAN_DIRS.forEach { path ->
                                                val dir = File(path)
                                                if (dir.exists() && dir.isDirectory) {
                                                    dir.listFiles { f -> f.extension.equals("gguf", ignoreCase = true) }?.let { found.addAll(it) }
                                                }
                                            }
                                        }
                                        models = found.distinctBy { it.name }
                                        isScanning = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Re-Scan Models", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ==========================================
            // CARD 3: INFERENCE PARAMETERS (AUTO-SAVING)
            // ==========================================
            SettingsCard(
                title = "3. INFERENCE PARAMETERS",
                titleColor = Color(0xFF0070F3)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = contextLengthText,
                            onValueChange = {
                                contextLengthText = it
                                it.toIntOrNull()?.let { len ->
                                    if (len >= 2048) {
                                        val newConfig = config.copy(contextLength = len)
                                        viewModel.saveConfig(context, newConfig)
                                    }
                                }
                            },
                            label = { Text("Context Size") },
                            modifier = Modifier.weight(1f),
                            colors = settingsFieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = threadCountText,
                            onValueChange = {
                                threadCountText = it
                                it.toIntOrNull()?.let { thr ->
                                    if (thr in 1..32) {
                                        val newConfig = config.copy(threadCount = thr)
                                        viewModel.saveConfig(context, newConfig)
                                    }
                                }
                            },
                            label = { Text("CPU Threads") },
                            modifier = Modifier.weight(1f),
                            colors = settingsFieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Temperature", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(String.format(java.util.Locale.US, "%.2f", temperatureState), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = temperatureState,
                            onValueChange = { temperatureState = it },
                            onValueChangeFinished = {
                                val newConfig = config.copy(temperature = temperatureState)
                                viewModel.saveConfig(context, newConfig)
                            },
                            valueRange = 0f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }

            // ==========================================
            // CARD 4: SYSTEM INTERFACE & PERMISSIONS
            // ==========================================
            SettingsCard(
                title = "4. SYSTEM INTERFACE",
                titleColor = NeonCyan
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ServiceToggle(
                        title = "Autonomous Controller",
                        description = "Screen perception and gesture control.",
                        isActive = uiState.isAccessibilityActive,
                        onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    ServiceToggle(
                        title = "Neural Keyboard",
                        description = "AI text injection.",
                        isActive = uiState.isKeyboardActive,
                        onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    ServiceToggle(
                        title = "Real-time Perceptor",
                        description = "Monitor notifications.",
                        isActive = uiState.isNotificationActive,
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { viewModel.updateServiceStatus(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Sync, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text("REFRESH STATUS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun ServiceToggle(
    title: String,
    description: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.5.sp, lineHeight = 14.sp)
        }
        StatusBadge(isActive)
    }
}

@Composable
private fun StatusBadge(isActive: Boolean) {
    Surface(
        color = if (isActive) Color(0xFF238636).copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(0.5.dp, if (isActive) Color(0xFF238636) else MaterialTheme.colorScheme.error)
    ) {
        Text(
            text = if (isActive) "ACTIVE" else "OFF",
            color = if (isActive) Color(0xFF3FB950) else MaterialTheme.colorScheme.error,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SettingsCard(
    title: String,
    titleColor: Color,
    statusDot: Color? = null,
    statusText: String? = null,
    isCollapsible: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isCollapsible) Modifier.clickable { expanded = !expanded } else Modifier),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (statusDot != null) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(statusDot, CircleShape)
                        )
                    }
                    Text(
                        text = title,
                        color = titleColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
                if (statusText != null) {
                    Text(
                        text = statusText,
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
)
