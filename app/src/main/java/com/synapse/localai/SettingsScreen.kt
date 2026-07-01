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
import androidx.compose.ui.graphics.RectangleShape
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
    "/storage/emulated/0/Download",
    "/storage/emulated/0/Models",
    "/storage/emulated/0/Documents",
    "/storage/emulated/0/MedlamAI/Models"
)

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
    
    // --- LOCAL DRAFT STATE (Prevents immediate reloads/crashes during typing) ---
    var preferredModelType by remember(config.preferredModelType) { mutableStateOf(config.preferredModelType) }
    var apiKeysState by remember(config.geminiApiKeys) { mutableStateOf(config.geminiApiKeys) }
    var geminiModelNameState by remember(config.geminiModelName) { mutableStateOf(config.geminiModelName) }
    var geminiKeysVisible by remember { mutableStateOf(false) }
    
    var openAiKeysState by remember(config.openaiApiKeys) { mutableStateOf(config.openaiApiKeys) }
    var openAiModelNameState by remember(config.openAiModelName) { mutableStateOf(config.openAiModelName) }
    var openAiKeysVisible by remember { mutableStateOf(false) }
    var contextLengthState by remember(config.contextLength) { mutableStateOf(config.contextLength.toString()) }
    var threadCountState by remember(config.threadCount) { mutableStateOf(config.threadCount.toString()) }
    var maxTokensState by remember(config.maxTokens) { mutableStateOf(config.maxTokens.toString()) }
    var temperatureState by remember(config.temperature) { mutableStateOf(config.temperature) }
    var localModelPathState by remember(config.localModelPath) { mutableStateOf(config.localModelPath) }
    var enabledAgents by remember(config.enabledSpecializedAgents) { mutableStateOf(config.enabledSpecializedAgents) }

    val hasChanges = remember(
        preferredModelType, apiKeysState, geminiModelNameState, openAiKeysState, 
        openAiModelNameState, 
        contextLengthState, threadCountState, maxTokensState, temperatureState, 
        localModelPathState, enabledAgents
    ) {
        preferredModelType != config.preferredModelType ||
        apiKeysState != config.geminiApiKeys ||
        geminiModelNameState != config.geminiModelName ||
        openAiKeysState != config.openaiApiKeys ||
        openAiModelNameState != config.openAiModelName ||
        contextLengthState != config.contextLength.toString() ||
        threadCountState != config.threadCount.toString() ||
        maxTokensState != config.maxTokens.toString() ||
        temperatureState != config.temperature ||
        localModelPathState != config.localModelPath ||
        enabledAgents != config.enabledSpecializedAgents
    }

    LaunchedEffect(Unit) {
        isScanning = true
        withContext(Dispatchers.IO) {
            val found = mutableListOf<File>()
            SCAN_DIRS.forEach { path ->
                val dir = File(path)
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles { f -> f.extension == "gguf" }?.let { found.addAll(it) }
                }
            }
            models = found.distinctBy { it.canonicalPath }
        }
        isScanning = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())

        // --- MODERN FULL-WIDTH SETTINGS HEADER ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .padding(horizontal = 20.dp, vertical = 8.dp)
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
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasChanges) {
                        Button(
                            onClick = {
                                val newConfig = config.copy(
                                    preferredModelType = preferredModelType,
                                    geminiApiKeys = apiKeysState,
                                    geminiModelName = geminiModelNameState,
                                    openaiApiKeys = openAiKeysState,
                                    openAiModelName = openAiModelNameState,
                                    contextLength = contextLengthState.toIntOrNull() ?: config.contextLength,
                                    threadCount = threadCountState.toIntOrNull() ?: config.threadCount,
                                    maxTokens = maxTokensState.toIntOrNull() ?: config.maxTokens,
                                    temperature = temperatureState,
                                    localModelPath = localModelPathState,
                                    enabledSpecializedAgents = enabledAgents
                                )
                                viewModel.saveConfig(context, newConfig)
                                Toast.makeText(context, "Settings Applied ✓", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("APPLY", fontWeight = FontWeight.Bold)
                        }
                    }

                    IconButton(
                        onClick = { onBack() },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowForward,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
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
            // 1. ENGINE SELECTION
            SettingsCard(
                title = "COGNITIVE ENGINE",
                titleColor = NeonCyan,
                statusText = uiState.activeModelType?.name ?: "OFFLINE"
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
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Gemini", color = if (preferredModelType == ModelType.GEMINI) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { preferredModelType = ModelType.OPENAI },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (preferredModelType == ModelType.OPENAI) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("OpenAI", color = if (preferredModelType == ModelType.OPENAI) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { preferredModelType = ModelType.GGUF },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (preferredModelType == ModelType.GGUF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Local", color = if (preferredModelType == ModelType.GGUF) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
            }

            // 2. CLOUD CORE
            SettingsCard(
                title = "CLOUD NEURAL CORE (Tap to Expand)",
                titleColor = NeonPurple,
                statusDot = if (uiState.activeModelType == ModelType.GEMINI || uiState.activeModelType == ModelType.OPENAI) Color.Green else Color.Gray,
                isCollapsible = true
            ) {
                OutlinedTextField(
                    value = apiKeysState,
                    onValueChange = { apiKeysState = it },
                    label = { Text("Gemini API Keys") },
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
                    placeholder = { Text("models/gemini-1.5-flash") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = settingsFieldColors(),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
                
                OutlinedTextField(
                    value = openAiKeysState,
                    onValueChange = { openAiKeysState = it },
                    label = { Text("OpenAI API Keys") },
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
                
                Text(
                    "Keys are rotated automatically. Separate with commas.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // 3. LOCAL CORE (GGUF)
            SettingsCard(
                title = "LOCAL NEURAL CORE",
                titleColor = NeonMagenta,
                statusDot = when {
                    uiState.localModelLoaded && preferredModelType == ModelType.GGUF -> Color.Green
                    uiState.localModelLoading -> Color.Yellow
                    else -> Color.Red
                },
                statusText = if (uiState.localModelLoading) "LOADING..." else if (uiState.localModelLoaded) "LOADED" else "OFFLINE"
            ) {
                if (isScanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeonMagenta)
                }

                if (models.isEmpty()) {
                    Text("No .gguf models found.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }

                models.forEach { file ->
                    val isSelected = localModelPathState == file.absolutePath
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable { localModelPathState = file.absolutePath }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = isSelected, onClick = { localModelPathState = file.absolutePath })
                        Column {
                            Text(file.name, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(formatSize(file.length()), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                    }
                }
                
                Button(
                    onClick = { 
                        isScanning = true
                        coroutineScope.launch {
                            val found = mutableListOf<File>()
                            withContext(Dispatchers.IO) {
                                SCAN_DIRS.forEach { path ->
                                    val dir = File(path)
                                    if (dir.exists() && dir.isDirectory) {
                                        dir.listFiles { f -> f.extension == "gguf" }?.let { found.addAll(it) }
                                    }
                                }
                            }
                            models = found.distinctBy { it.canonicalPath }
                            isScanning = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("RE-SCAN FILES", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 4. BEHAVIOR & PROMPTS

            // 5. INFERENCE PARAMETERS
            SettingsCard(title = "INFERENCE PARAMETERS", titleColor = NeonMagenta) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = contextLengthState,
                        onValueChange = { contextLengthState = it },
                        label = { Text("Context") },
                        modifier = Modifier.weight(1f),
                        colors = settingsFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = threadCountState,
                        onValueChange = { threadCountState = it },
                        label = { Text("Threads") },
                        modifier = Modifier.weight(1f),
                        colors = settingsFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                OutlinedTextField(
                    value = maxTokensState,
                    onValueChange = { maxTokensState = it },
                    label = { Text("Max Output Tokens") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = settingsFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                
                Text("Temperature: ${String.format("%.2f", temperatureState)}", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                Slider(
                    value = temperatureState,
                    onValueChange = { temperatureState = it },
                    valueRange = 0f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                )
            }
            


            // 6. PERMISSIONS & SERVICES
            SettingsCard(title = "SYSTEM INTERFACE", titleColor = NeonPurple) {
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
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.updateServiceStatus(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Sync, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("REFRESH STATUS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.5f))
        )
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
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
        }
        StatusBadge(isActive)
    }
}

@Composable
private fun StatusBadge(active: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) Color(0xFF1B5E20).copy(alpha = 0.2f) else Color(0xFFB71C1C).copy(alpha = 0.2f))
            .border(1.dp, if (active) Color(0xFF4CAF50) else Color(0xFFF44336), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            if (active) "ACTIVE" else "OFFLINE",
            color = if (active) Color(0xFF81C784) else Color(0xFFE57373),
            fontSize = 8.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    titleColor: Color,
    statusDot: Color? = null,
    statusText: String? = null,
    isCollapsible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(!isCollapsible) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isCollapsible) Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp) else Modifier),
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
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (isCollapsible) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Expand/Collapse",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
