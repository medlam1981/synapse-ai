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
    
    // --- LOCAL DRAFT STATE ---
    var preferredModelType by remember(config.preferredModelType) { mutableStateOf(config.preferredModelType) }
    var apiKeysState by remember(config.geminiApiKeys) { mutableStateOf(config.geminiApiKeys) }
    var geminiModelNameState by remember(config.geminiModelName) { mutableStateOf(config.geminiModelName) }
    var geminiKeysVisible by remember { mutableStateOf(false) }
    
    var openAiKeysState by remember(config.openaiApiKeys) { mutableStateOf(config.openaiApiKeys) }
    var openAiModelNameState by remember(config.openAiModelName) { mutableStateOf(config.openAiModelName) }
    var openAiKeysVisible by remember { mutableStateOf(false) }
    var contextLengthState by remember(config.contextLength) { mutableStateOf(config.contextLength.toString()) }
    var threadCountState by remember(config.threadCount) { mutableStateOf(config.threadCount.toString()) }
    var temperatureState by remember(config.temperature) { mutableStateOf(config.temperature) }
    var localModelPathState by remember(config.localModelPath) { mutableStateOf(config.localModelPath) }
    var draftModelPathState by remember(config.draftModelPath) { mutableStateOf(config.draftModelPath) }
    var gpuLayersState by remember(config.gpuLayers) { mutableStateOf(config.gpuLayers) }
    var useMmapState by remember(config.useMmap) { mutableStateOf(config.useMmap) }

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
                        "CONFIGURATION CORE",
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
            // CARD 1: COGNITIVE ENGINE SELECTOR
            // ==========================================
            SettingsCard(
                title = "1. COGNITIVE ENGINE",
                titleColor = NeonCyan,
                statusText = uiState.activeModelType?.name ?: "OFFLINE"
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { preferredModelType = ModelType.GEMINI },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (preferredModelType == ModelType.GEMINI) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("Gemini", color = if (preferredModelType == ModelType.GEMINI) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { preferredModelType = ModelType.OPENAI },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (preferredModelType == ModelType.OPENAI) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("OpenAI", color = if (preferredModelType == ModelType.OPENAI) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { preferredModelType = ModelType.GGUF },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (preferredModelType == ModelType.GGUF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("Local GGUF", color = if (preferredModelType == ModelType.GGUF) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Cloud Credentials (if cloud engine chosen)
                    if (preferredModelType == ModelType.GEMINI) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = apiKeysState,
                                onValueChange = { apiKeysState = it },
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
                                value = geminiModelNameState,
                                onValueChange = { geminiModelNameState = it },
                                label = { Text("Gemini Model Name") },
                                placeholder = { Text("gemini-1.5-flash") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = settingsFieldColors(),
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            )
                        }
                    } else if (preferredModelType == ModelType.OPENAI) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = openAiKeysState,
                                onValueChange = { openAiKeysState = it },
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
                                value = openAiModelNameState,
                                onValueChange = { openAiModelNameState = it },
                                label = { Text("OpenAI Model Name") },
                                placeholder = { Text("gpt-4o") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = settingsFieldColors(),
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val newConfig = config.copy(
                                preferredModelType = preferredModelType,
                                geminiApiKeys = apiKeysState,
                                geminiModelName = geminiModelNameState,
                                openaiApiKeys = openAiKeysState,
                                openAiModelName = openAiModelNameState
                            )
                            viewModel.saveConfig(context, newConfig)
                            Toast.makeText(context, "Engine set to ${preferredModelType.name} ✓", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Apply Engine", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ==========================================
            // CARD 2: LOCAL CORE (GGUF + MTP SPECULATIVE)
            // ==========================================
            SettingsCard(
                title = "2. LOCAL NEURAL CORE",
                titleColor = NeonMagenta,
                statusDot = when {
                    uiState.localModelLoaded && preferredModelType == ModelType.GGUF -> Color.Green
                    uiState.localModelLoading -> Color.Yellow
                    else -> Color.Red
                }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (isScanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeonMagenta)
                    }

                    if (models.isEmpty()) {
                        Text("No .gguf models found in /sdcard/Medlam-Ai/models.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }

                    // Base Model Picker
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("BASE NEURAL MODEL:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        models.forEach { file ->
                            val isSelected = localModelPathState == file.absolutePath
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                    .border(0.5.dp, if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable { localModelPathState = file.absolutePath }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = isSelected, onClick = { localModelPathState = file.absolutePath })
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(file.name, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                    Text(formatSize(file.length()) + " • " + file.parent, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    // Draft / MTP Accelerator Picker
                    if (models.size > 1) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("DRAFT / MTP SPECULATIVE ACCELERATOR:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (draftModelPathState.isBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                                    .border(0.5.dp, if (draftModelPathState.isBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable { draftModelPathState = "" }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = draftModelPathState.isBlank(), onClick = { draftModelPathState = "" })
                                Spacer(Modifier.width(8.dp))
                                Text("None (Single Model Mode)", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                            models.filter { it.absolutePath != localModelPathState }.forEach { file ->
                                val isDraftSelected = draftModelPathState == file.absolutePath
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isDraftSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                        .border(0.5.dp, if (isDraftSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                        .clickable { draftModelPathState = file.absolutePath }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = isDraftSelected, onClick = { draftModelPathState = file.absolutePath })
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(file.name, color = if (isDraftSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                        Text(formatSize(file.length()), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.5.sp)
                                    }
                                }
                            }
                        }
                    }

                    // GPU Layers Offload Slider
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "GPU Layers Offload: $gpuLayersState ${if (gpuLayersState == 0) "(CPU Only)" else if (gpuLayersState >= 33) "(Full GPU Offload)" else "(Hybrid GPU/CPU)"}",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Slider(
                            value = gpuLayersState.toFloat(),
                            onValueChange = { gpuLayersState = it.toInt() },
                            valueRange = 0f..33f,
                            steps = 32,
                            colors = SliderDefaults.colors(thumbColor = NeonMagenta, activeTrackColor = NeonMagenta)
                        )
                    }

                    // mmap Fast Loading Switch with explicit spacious layout
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "mmap Fast Loading",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Direct memory mapping for instant loading",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.5.sp
                                )
                            }
                            Switch(
                                checked = useMmapState,
                                onCheckedChange = { useMmapState = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.5f))
                            )
                        }
                    }

                    // Action Row: RE-SCAN and Apply Model
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
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
                                        models = found.distinctBy { it.name }
                                        isScanning = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text("RE-SCAN", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val newConfig = config.copy(
                                    localModelPath = localModelPathState,
                                    draftModelPath = draftModelPathState,
                                    gpuLayers = gpuLayersState,
                                    useMmap = useMmapState,
                                    preferredModelType = ModelType.GGUF
                                )
                                viewModel.saveConfig(context, newConfig)
                                Toast.makeText(context, "Local Engine Saved ✓", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Apply Model", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }

            // ==========================================
            // CARD 3: INFERENCE PARAMETERS
            // ==========================================
            SettingsCard(
                title = "3. INFERENCE PARAMETERS",
                titleColor = NeonPurple
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
                            value = contextLengthState,
                            onValueChange = { contextLengthState = it },
                            label = { Text("Context Size") },
                            modifier = Modifier.weight(1f),
                            colors = settingsFieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = threadCountState,
                            onValueChange = { threadCountState = it },
                            label = { Text("CPU Threads") },
                            modifier = Modifier.weight(1f),
                            colors = settingsFieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Temperature: ${String.format("%.2f", temperatureState)}", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Slider(
                            value = temperatureState,
                            onValueChange = { temperatureState = it },
                            valueRange = 0f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                        )
                    }

                    Button(
                        onClick = {
                            val newConfig = config.copy(
                                contextLength = contextLengthState.toIntOrNull() ?: config.contextLength,
                                threadCount = threadCountState.toIntOrNull() ?: config.threadCount,
                                temperature = temperatureState,
                                maxTokens = -1
                            )
                            viewModel.saveConfig(context, newConfig)
                            Toast.makeText(context, "Inference Parameters Saved ✓", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Apply Inference Settings", fontWeight = FontWeight.Bold)
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
