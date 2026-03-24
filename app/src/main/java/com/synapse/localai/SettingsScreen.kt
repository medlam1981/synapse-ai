package com.synapse.localai

import java.io.File
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Colors imported from MainActivity.kt — no duplicates

private val SCAN_DIRS = listOf(
    "/sdcard/Download",
    "/sdcard/Models",
    "/storage/emulated/0/Download",
    "/storage/emulated/0/Models",
    "/data/local/tmp/synapse"
)

private fun scanForModels(): List<File> {
    val extensions = setOf("bin", "gguf")
    return SCAN_DIRS.flatMap { dir ->
        try {
            val folder = File(dir)
            if (!folder.exists()) return@flatMap emptyList<File>()
            folder.walkTopDown().maxDepth(5).filter { f ->
                f.isFile && f.extension.lowercase() in extensions && f.length() > 500_000_000 // Min 500MB
            }.toList()
        } catch (_: Exception) { emptyList() }
    }.distinctBy { it.canonicalPath }.sortedByDescending { it.lastModified() }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val config = uiState.config

    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        isScanning = true
        models = withContext(kotlinx.coroutines.Dispatchers.IO) {
            scanForModels()
        }
        isScanning = false
    }

    var selectedPath by remember(config.localModelPath) { mutableStateOf(config.localModelPath) }
    var customPath by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }

    // Server settings state
    var serverHost by remember(config.serverHost) { mutableStateOf(config.serverHost) }
    var serverPort by remember(config.serverPort) { mutableStateOf(config.serverPort.toString()) }
    var contextLength by remember(config.contextLength) { mutableStateOf(config.contextLength.toString()) }
    var threadCount by remember(config.threadCount) { mutableStateOf(config.threadCount.toString()) }
    var maxTokens by remember(config.maxTokens) { mutableStateOf(config.maxTokens.toString()) }
    var temperature by remember(config.temperature) { mutableStateOf(config.temperature) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070A))
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())

        // --- MODERN FULL-WIDTH SETTINGS HEADER ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(NeonPurple.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
                .background(DarkCard.copy(alpha = 0.95f))
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Title on the LEFT
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        "SYSTEM SETTINGS",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "CONFIGURATION CORE",
                        color = NeonPurple.copy(alpha = 0.7f),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Sleek Modern Back Button on the RIGHT
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(NeonCyan.copy(alpha = 0.1f), androidx.compose.foundation.shape.CircleShape)
                        .border(BorderStroke(1.2.dp, NeonCyan.copy(alpha = 0.4f)), androidx.compose.foundation.shape.CircleShape)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.ArrowForward,
                        contentDescription = "Back",
                        tint = NeonPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp), // Removed horizontal padding
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            
            var useLocalModel by remember(config.useLocalModel) { mutableStateOf(config.useLocalModel) }
            var geminiKeys by remember(config.geminiApiKeys) { mutableStateOf(config.geminiApiKeys) }
            var showBubble by remember(config.showFloatingBubble) { mutableStateOf(config.showFloatingBubble) }
            var visualHistory by remember(config.enableVisualHistory) { mutableStateOf(config.enableVisualHistory) }
            var neuralReflexes by remember(config.enableNeuralReflexes) { mutableStateOf(config.enableNeuralReflexes) }
            var enabledAgents by remember(config.enabledSpecializedAgents) { mutableStateOf(config.enabledSpecializedAgents) }

            // ═══════ QUOTA MANAGEMENT SECTION ═══════
            SettingsCard(title = "QUOTA MANAGEMENT", titleColor = Color(0xFFFBDB44)) {
                Text("Control background API calls to save tokens.", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                
                // Visual History Toggle
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Auto Visual History", color = Color.White, fontSize = 13.sp)
                    Switch(
                        checked = visualHistory,
                        onCheckedChange = { 
                            visualHistory = it
                            viewModel.saveConfig(context, config.copy(enableVisualHistory = it))
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFBDB44))
                    )
                }
                
                // Neural Reflexes Toggle
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Neural Reflexes (Auto-Reply)", color = Color.White, fontSize = 13.sp)
                    Switch(
                        checked = neuralReflexes,
                        onCheckedChange = { 
                            neuralReflexes = it
                            viewModel.saveConfig(context, config.copy(enableNeuralReflexes = it))
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFBDB44))
                    )
                }
            }

            // ═══════ APPEARANCE SECTION ═══════
            SettingsCard(title = "APPEARANCE", titleColor = NeonCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Visibility, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Floating Action Bubble", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Show/hide the overlay trigger", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        }
                    }
                    Switch(
                        checked = showBubble,
                        onCheckedChange = { 
                            showBubble = it
                            viewModel.saveConfig(context, config.copy(showFloatingBubble = it))
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.3f))
                    )
                }
            }

            // ═══════ SPECIALIZED AGENTS SECTION ═══════
            SettingsCard(
                title = "SPECIALIZED AGENTS (EXPERTS)", 
                titleColor = NeonPurple,
                statusDot = if (uiState.specializedAgents.isNotEmpty()) Color(0xFF34D399) else Color.White.copy(alpha = 0.2f),
                statusText = "${uiState.specializedAgents.size} Experts Found"
            ) {
                Text(
                    "Load expert personas from /sdcard/MedlamAI/Agents/ (*.md files).",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                if (uiState.specializedAgents.isEmpty()) {
                    Text(
                        "No .md expert files found in directory.",
                        color = Color.White.copy(alpha = 0.2f),
                        fontSize = 10.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    for (file in uiState.specializedAgents) {
                        val isEnabled = enabledAgents.contains(file.name)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isEnabled) NeonPurple.copy(alpha = 0.05f) else Color.Transparent, RoundedCornerShape(8.dp))
                                .border(0.5.dp, if (isEnabled) NeonPurple.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(androidx.compose.material.icons.Icons.Filled.Psychology, contentDescription = null, tint = if (isEnabled) NeonPurple else Color.White.copy(alpha = 0.2f), modifier = Modifier.size(16.dp))
                                Text(file.nameWithoutExtension, color = if (isEnabled) Color.White else Color.White.copy(alpha = 0.4f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { active ->
                                    val newSet = enabledAgents.toMutableSet()
                                    if (active) newSet.add(file.name) else newSet.remove(file.name)
                                    enabledAgents = newSet
                                    viewModel.saveConfig(context, config.copy(enabledSpecializedAgents = newSet))
                                },
                                modifier = Modifier.scale(0.8f),
                                colors = SwitchDefaults.colors(checkedThumbColor = NeonPurple, checkedTrackColor = NeonPurple.copy(alpha = 0.3f))
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.scanSpecializedAgents() },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.3f)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonPurple)
                    Spacer(Modifier.width(8.dp))
                    Text("REFRESH EXPERT LIST", color = NeonPurple, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            // Mode Toggle
            Row(
                modifier = Modifier.fillMaxWidth().background(DarkCard, RoundedCornerShape(12.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Execution Mode", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(if (useLocalModel) "Local (GGUF/LiteRT)" else "Cloud (Gemini API)", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
                Switch(
                    checked = useLocalModel,
                    onCheckedChange = { useLocalModel = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.3f))
                )
            }

            if (useLocalModel) {
                // ═══════ AI MODEL SECTION (LOCAL) ═══════
                SettingsCard(title = "LOCAL MODELS", titleColor = NeonCyan, statusDot = when {
                    uiState.localModelLoaded && uiState.activeModelType != ModelType.GEMINI -> Color(0xFF34D399)
                    uiState.localModelLoading -> Color(0xFFFBBF24)
                    else -> Color(0xFFEF4444)
                }, statusText = when {
                    uiState.localModelLoading -> "Loading..."
                    uiState.localModelLoaded && uiState.activeModelType != ModelType.GEMINI -> "Ready"
                    else -> "Idle"
                }) {
                                    if (isScanning) {
                                        Text("Scanning device for models... Please wait.", color = NeonCyan, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                                    } else if (models.isNotEmpty()) {
                                        Text("Discovered Models", color = Color.White.copy(alpha = 0.3f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        models.forEach { file ->
                            val isSelected = selectedPath == file.absolutePath
                            val isCurrentlyLoaded = isSelected && uiState.localModelLoaded && uiState.activeModelType != ModelType.GEMINI
                            val modelType = AgentViewModel.detectModelType(file.absolutePath)
                            val typeLabel = if (modelType == ModelType.GGUF) "GGUF" else "LiteRT"
                            val typeColor = if (modelType == ModelType.GGUF) Color(0xFFFF9F43) else NeonCyan

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                    if (isSelected) NeonCyan.copy(alpha = 0.08f) else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) NeonCyan.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.04f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { selectedPath = file.absolutePath; showCustomInput = false }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .border(2.dp, if (isSelected) NeonCyan else Color.White.copy(alpha = 0.15f), RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Box(modifier = Modifier.size(10.dp).background(NeonCyan, RoundedCornerShape(5.dp)))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.nameWithoutExtension, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${formatSize(file.length())} • $typeLabel", color = Color.White.copy(alpha = 0.35f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            Box(
                                modifier = Modifier
                                    .background(typeColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text(typeLabel, color = typeColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            if (isCurrentlyLoaded) {
                                Text("✓", color = Color(0xFF34D399), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MidNavy),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("No models found", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                            Text(
                                "Place .gguf files in:\n/sdcard/Download/\n/sdcard/Models/",
                                color = Color.White.copy(alpha = 0.25f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                val scanScope = rememberCoroutineScope()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { 
                            scanScope.launch {
                                isScanning = true
                                models = withContext(kotlinx.coroutines.Dispatchers.IO) { scanForModels() }
                                isScanning = false
                            }
                        },
                        enabled = !isScanning,
                        shape = RoundedCornerShape(10.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(NeonCyan.copy(alpha = 0.2f))
                        )
                    ) {
                        Text(if (isScanning) "Scanning..." else "↻ Rescan", color = NeonCyan.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { showCustomInput = !showCustomInput },
                        shape = RoundedCornerShape(10.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(NeonPurple.copy(alpha = 0.2f))
                        )
                    ) {
                        Text("Custom path", color = NeonPurple.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }

                if (showCustomInput) {
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = { customPath = it },
                        label = { Text("Full path to model file", color = Color.White.copy(alpha = 0.3f)) },
                        placeholder = { Text("/sdcard/Download/model.gguf", color = Color.White.copy(alpha = 0.12f), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = settingsFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                    if (customPath.isNotBlank()) {
                        val exists = try { File(customPath).exists() } catch (_: Exception) { false }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (exists) "✓ Found" else "✗ Not found",
                                color = if (exists) Color(0xFF34D399) else Color(0xFFEF4444),
                                fontSize = 11.sp
                            )
                            if (exists) {
                                TextButton(onClick = { selectedPath = customPath }) {
                                    Text("Select", color = NeonCyan, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Text(
                    "Scans: ${SCAN_DIRS.joinToString(", ") { it.replace("/sdcard/", "~/") }}",
                    color = Color.White.copy(alpha = 0.15f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            } else {
                // ═══════ AI MODEL SECTION (CLOUD) ═══════
                SettingsCard(title = "CLOUD MODELS", titleColor = Color(0xFF34D399), statusDot = when {
                    uiState.localModelLoaded && uiState.activeModelType == ModelType.GEMINI -> Color(0xFF34D399)
                    uiState.localModelLoading -> Color(0xFFFBBF24)
                    else -> Color(0xFFEF4444)
                }, statusText = when {
                    uiState.localModelLoading -> "Connecting..."
                    uiState.localModelLoaded && uiState.activeModelType == ModelType.GEMINI -> "Connected"
                    else -> "Disconnected"
                }) {
                    Text("Zero Rate Limit Mode (Key Pool)", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Enter multiple free Gemini API keys separated by commas. Medlam-AI will automatically rotate them if a rate limit (429) is hit.", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, lineHeight = 16.sp)
                    
                    OutlinedTextField(
                        value = geminiKeys,
                        onValueChange = { geminiKeys = it },
                        label = { Text("Gemini API Keys", color = Color.White.copy(alpha = 0.3f)) },
                        placeholder = { Text("AIzaSy..., AIzaSy...", color = Color.White.copy(alpha = 0.12f), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = settingsFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 3,
                        maxLines = 5,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    )
                }
            }

            // Load Model Button
            Button(
                onClick = {
                    val newConfig = config.copy(
                        useLocalModel = useLocalModel, 
                        localModelPath = selectedPath,
                        geminiApiKeys = geminiKeys,
                        serverHost = serverHost,
                        serverPort = serverPort.toIntOrNull() ?: 11434,
                        contextLength = contextLength.toIntOrNull() ?: 4096,
                        threadCount = threadCount.toIntOrNull() ?: 6,
                        maxTokens = maxTokens.toIntOrNull() ?: 2048,
                        temperature = temperature
                    )
                    viewModel.saveConfig(context, newConfig)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = (useLocalModel && selectedPath.isNotBlank() && !uiState.localModelLoading) || (!useLocalModel && geminiKeys.isNotBlank() && !uiState.localModelLoading),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (useLocalModel) NeonCyan else Color(0xFF34D399),
                    disabledContainerColor = if (useLocalModel) NeonCyan.copy(alpha = 0.15f) else Color(0xFF34D399).copy(alpha = 0.15f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    if (uiState.localModelLoading) "Connecting..." else if (useLocalModel) "Load Selected Model" else "Connect to Gemini",
                    color = if (uiState.localModelLoading) Color.White.copy(alpha = 0.4f) else DarkNavy,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            // ═══════ SERVER CONFIG SECTION ═══════
            SettingsCard(title = "SERVER CONFIG", titleColor = Color(0xFFFF9F43)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = serverHost,
                        onValueChange = { serverHost = it },
                        label = { Text("Host", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp) },
                        modifier = Modifier.weight(2f),
                        colors = settingsFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Port", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        colors = settingsFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = contextLength,
                        onValueChange = { contextLength = it.filter { c -> c.isDigit() } },
                        label = { Text("Context", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        colors = settingsFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = threadCount,
                        onValueChange = { threadCount = it.filter { c -> c.isDigit() } },
                        label = { Text("Threads", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        colors = settingsFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
                        label = { Text("Max Tokens", color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        colors = settingsFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    )
                }
                
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Temperature", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                        Text(String.format("%.2f", temperature), color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0.0f..2.0f,
                        steps = 19, // 0.1 steps between 0 and 2.0
                        colors = SliderDefaults.colors(
                            thumbColor = NeonCyan,
                            activeTrackColor = NeonPurple,
                            inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                    Text("0.0 (Precise/Robot) ➔ 2.0 (Creative/Chaotic)", color = Color.White.copy(alpha = 0.3f), fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                Button(
                    onClick = {
                        val newConfig = config.copy(
                            serverHost = serverHost,
                            serverPort = serverPort.toIntOrNull() ?: 11434,
                            contextLength = contextLength.toIntOrNull() ?: 4096,
                            threadCount = threadCount.toIntOrNull() ?: 6,
                            maxTokens = maxTokens.toIntOrNull() ?: 4096,
                            temperature = temperature
                        )
                        viewModel.saveConfig(context, newConfig)
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9F43).copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Save Server Settings", color = DarkNavy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Text(
                    "Default: 0.0.0.0:11434 • Context: 8192 • Threads: 6",
                    color = Color.White.copy(alpha = 0.2f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // ═══════ PORTAL & KEYBOARD SECTION ═══════
            SettingsCard(
                title = "ANDROID PORTAL", 
                titleColor = NeonCyan, 
                statusDot = Color(0xFF34D399), 
                statusText = "Active (8081)"
            ) {
                Text(
                    "This app acts as a Portal for external agents to control this device via port 8081.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val btnModifier = Modifier.weight(1f).height(38.dp)
                    val btnShape = RoundedCornerShape(6.dp)
                    val textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 8.sp,
                        textAlign = TextAlign.Center
                    )

                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                        modifier = btnModifier,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple.copy(alpha = 0.25f)),
                        shape = btnShape,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("ACCESSIBILITY", color = Color.White, style = textStyle)
                    }
                    
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                        modifier = btnModifier,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan.copy(alpha = 0.25f)),
                        shape = btnShape,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("KEYBOARD", color = Color.White, style = textStyle)
                    }
                    
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                        modifier = btnModifier,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9F43).copy(alpha = 0.25f)),
                        shape = btnShape,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("NOTIFICATIONS", color = Color.White, style = textStyle)
                    }
                }
                
                Text(
                    "Note: Medlam-AI will 'magically' auto-select its keyboard during tasks if enabled above.",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 10.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }

            // ═══════ ABOUT SECTION ═══════
            SettingsCard(title = "ABOUT", titleColor = Color.White.copy(alpha = 0.5f)) {
                Text("Medlam-AI v1.0", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Local AI assistant with device control capabilities.\nRuns GGUF models via bundled llama-server (no root).",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ═══════ REUSABLE COMPONENTS ═══════

@Composable
private fun SettingsCard(
    title: String,
    titleColor: Color,
    statusDot: Color? = null,
    statusText: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
                if (statusText != null) {
                    Text(
                        text = statusText,
                        color = Color.White.copy(alpha = 0.3f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                content()
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NeonPurple.copy(alpha = 0.4f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = NeonCyan,
    focusedContainerColor = MidNavy.copy(alpha = 0.3f),
    unfocusedContainerColor = MidNavy.copy(alpha = 0.15f)
)
