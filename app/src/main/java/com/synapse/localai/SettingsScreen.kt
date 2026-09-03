package com.synapse.localai

import java.io.File
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.synapse.localai.ui.theme.*

// Signature Soft Mint / Emerald Palette
private val EmeraldMint = Color(0xFF10B981)
private val EmeraldMintDark = Color(0xFF059669)
private val EmeraldMintLight = Color(0xFF34D399)
private val EmeraldMintSubtle = Color(0xFF10B981).copy(alpha = 0.12f)
private val EmeraldMintBorder = Color(0xFF10B981).copy(alpha = 0.45f)

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
}

@Composable
fun SettingsScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
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
    var mtpModels by remember { mutableStateOf<List<File>>(emptyList()) }
    var personas by remember { mutableStateOf<List<String>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var isScanningPersonas by remember { mutableStateOf(false) }
    
    // --- LOCAL FORM STATE ---
    var geminiKeysVisible by remember { mutableStateOf(false) }
    var contextLengthText by remember(config.contextLength) { mutableStateOf(config.contextLength.toString()) }
    var threadCountText by remember(config.threadCount) { mutableStateOf(config.threadCount.toString()) }
    var temperatureState by remember(config.temperature) { mutableStateOf(config.temperature) }
    var gpuLayersState by remember(config.gpuLayers) { mutableStateOf(config.gpuLayers) }

    LaunchedEffect(Unit) {
        // Zero-Overhead: Read exclusively from cached manifests (NO automatic filesystem sweep on resume)
        models = ModelScanner.getBaseModels(context, forceRescan = false)
        mtpModels = ModelScanner.getMtpModels(context, forceRescan = false)
        personas = PersonaManager.getPersonas(context, forceRescan = false)
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
                        colors = listOf(EmeraldMint.copy(alpha = 0.08f), Color.Transparent)
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
                        color = EmeraldMint,
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
                        tint = EmeraldMint
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
                titleColor = EmeraldMint,
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
                        val isGguf = config.preferredModelType == ModelType.GGUF

                        Button(
                            onClick = {
                                val newConfig = config.copy(preferredModelType = ModelType.GEMINI)
                                viewModel.saveConfig(context, newConfig)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isGemini) EmeraldMint else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("Gemini Cloud", color = if (isGemini) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                val newConfig = config.copy(preferredModelType = ModelType.GGUF)
                                viewModel.saveConfig(context, newConfig)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isGguf) EmeraldMint else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("Local GGUF", color = if (isGguf) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                        Icon(if (geminiKeysVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = EmeraldMint)
                                    }
                                }
                            )

                            // Cloud Model Selection Matrix
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("CLOUD MODEL SELECTION:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                val cloudModels = listOf(
                                    Triple("gemini-2.0-flash", "Gemini 2.0 Flash", "Ultra-fast multimodal, real-time reasoning"),
                                    Triple("gemini-2.0-flash-lite", "Gemini 2.0 Flash Lite", "Cost-efficient lightweight stream"),
                                    Triple("gemini-1.5-pro", "Gemini 1.5 Pro", "Complex architecture & long context"),
                                    Triple("gemini-1.5-flash", "Gemini 1.5 Flash", "High-speed balanced reasoning")
                                )
                                cloudModels.forEach { (mId, mLabel, mDesc) ->
                                    val isSelected = config.geminiModelName == mId
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                val newConfig = config.copy(geminiModelName = mId)
                                                viewModel.saveConfig(context, newConfig)
                                            },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) EmeraldMintSubtle else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        border = BorderStroke(0.5.dp, if (isSelected) EmeraldMint else MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = {
                                                    val newConfig = config.copy(geminiModelName = mId)
                                                    viewModel.saveConfig(context, newConfig)
                                                },
                                                colors = RadioButtonDefaults.colors(selectedColor = EmeraldMint)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(mLabel, color = if (isSelected) EmeraldMint else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                Text(mDesc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.5.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text("Show Key Counter in Capsule", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("Conceal active API key rotation index during recordings/demos", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                }
                                Switch(
                                    checked = config.showApiKeyTracker,
                                    onCheckedChange = {
                                        val newConfig = config.copy(showApiKeyTracker = it)
                                        viewModel.saveConfig(context, newConfig)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = EmeraldMint,
                                        checkedTrackColor = EmeraldMint.copy(alpha = 0.4f)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // CARD: OPERATIONAL PERSONAS (.MD MULTI-PROFILE)
            // ==========================================
            SettingsCard(
                title = "OPERATIONAL PERSONAS (.MD)",
                titleColor = EmeraldMint,
                statusText = "${uiState.activePersonas.size} ACTIVE"
            ) {
                val activePersonas = uiState.activePersonas
                val currentPersonas = personas.ifEmpty { listOf("coding.md", "chat.md", "android_controller.md") }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // External Storage Path Badge with Inline Manual Re-Scan Button
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = EmeraldMint, modifier = Modifier.size(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "/sdcard/Medlam-Ai/personas/",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Public shared storage • Plain .md profiles • Multi-select composition",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 9.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    isScanningPersonas = true
                                    coroutineScope.launch {
                                        val refreshed = withContext(Dispatchers.IO) {
                                            viewModel.rescanPersonas(context)
                                        }
                                        personas = refreshed
                                        isScanningPersonas = false
                                        Toast.makeText(context, "Discovered ${refreshed.size} personas in storage", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                if (isScanningPersonas) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = EmeraldMint
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Re-index Personas",
                                        tint = EmeraldMint,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Active Stacking Overview Banner
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = EmeraldMintSubtle,
                        border = BorderStroke(0.5.dp, EmeraldMintBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ACTIVE ROLE COMPOSITION (${activePersonas.size} COMBINED):",
                                    color = EmeraldMint,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.5.sp
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                activePersonas.forEach { pName ->
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = EmeraldMint.copy(alpha = 0.2f),
                                        border = BorderStroke(0.5.dp, EmeraldMint)
                                    ) {
                                        Text(
                                            text = "✓ $pName",
                                            color = EmeraldMint,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Multi-Select Checklist Matrix
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "AVAILABLE PERSONAS (TOGGLE TO COMPOSE ROLES):",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )

                        currentPersonas.forEach { pName ->
                            val isChecked = activePersonas.contains(pName)

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.togglePersona(pName, context)
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isChecked) EmeraldMintSubtle else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                border = BorderStroke(0.5.dp, if (isChecked) EmeraldMint else MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { viewModel.togglePersona(pName, context) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = EmeraldMint,
                                            checkmarkColor = Color.White
                                        )
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = pName,
                                                color = if (isChecked) EmeraldMint else MaterialTheme.colorScheme.onSurface,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            if (isChecked) {
                                                Surface(
                                                    shape = RoundedCornerShape(3.dp),
                                                    color = EmeraldMint.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = "ACTIVE",
                                                        color = EmeraldMint,
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = PersonaManager.getPersonaDescription(pName),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 9.5.sp,
                                            lineHeight = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // CARD 2: LOCAL NEURAL CORE (GGUF + MTP)
            // ==========================================
            SettingsCard(
                title = "2. LOCAL NEURAL CORE",
                titleColor = EmeraldMint,
                statusDot = when {
                    uiState.localModelLoaded && config.preferredModelType == ModelType.GGUF -> EmeraldMint
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
                    if (uiState.localModelLoading && !uiState.localModelLoaded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(BorderStroke(0.5.dp, EmeraldMintBorder), RoundedCornerShape(10.dp))
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
                                    color = EmeraldMint,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${((uiState.loadingProgress ?: 0f) * 100).toInt()}%",
                                    color = EmeraldMint,
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
                                color = EmeraldMint,
                                trackColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }

                    if (isScanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = EmeraldMint)
                    }

                    if (models.isEmpty() && !isScanning) {
                        Text("No .gguf models found in /sdcard/Medlam-Ai/models.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }

                    // Base Model Picker
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("BASE NEURAL MODEL:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        models.forEach { file ->
                            val isSelected = config.localModelPath == file.absolutePath

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) EmeraldMintSubtle else Color.Transparent)
                                    .border(0.5.dp, if (isSelected) EmeraldMint else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable {
                                        val matchedMtp = ModelScanner.findMatchingMtpModel(file.absolutePath, context)
                                        val newConfig = config.copy(
                                            localModelPath = file.absolutePath,
                                            draftModelPath = matchedMtp?.absolutePath ?: "",
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
                                        val matchedMtp = ModelScanner.findMatchingMtpModel(file.absolutePath, context)
                                        val newConfig = config.copy(
                                            localModelPath = file.absolutePath,
                                            draftModelPath = matchedMtp?.absolutePath ?: "",
                                            preferredModelType = ModelType.GGUF
                                        )
                                        viewModel.saveConfig(context, newConfig)
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = EmeraldMint)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(file.name, color = if (isSelected) EmeraldMint else MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                    Text(formatSize(file.length()) + " • " + file.parent, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    // User-Driven Companion Draft / MTP Model Selection
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("COMPANION DRAFT / MTP MODEL:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

                        // Option 1: Standalone Execution (No MTP)
                        val isStandalone = config.draftModelPath.isBlank()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isStandalone) EmeraldMintSubtle else Color.Transparent)
                                .border(0.5.dp, if (isStandalone) EmeraldMint else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .clickable {
                                val newConfig = config.copy(draftModelPath = "")
                                viewModel.saveConfig(context, newConfig)
                            }
                            .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isStandalone,
                                onClick = {
                                    val newConfig = config.copy(draftModelPath = "")
                                    viewModel.saveConfig(context, newConfig)
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = EmeraldMint)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Standalone Execution (No MTP)", color = if (isStandalone) EmeraldMint else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Standard autoregressive generation without companion draft", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                        }

                        // Option 2: Discovered MTP models from storage
                        mtpModels.forEach { mtpFile ->
                            val isMtpSelected = config.draftModelPath == mtpFile.absolutePath
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isMtpSelected) EmeraldMintSubtle else Color.Transparent)
                                    .border(0.5.dp, if (isMtpSelected) EmeraldMint else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable {
                                        val newConfig = config.copy(draftModelPath = mtpFile.absolutePath)
                                        viewModel.saveConfig(context, newConfig)
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isMtpSelected,
                                    onClick = {
                                        val newConfig = config.copy(draftModelPath = mtpFile.absolutePath)
                                        viewModel.saveConfig(context, newConfig)
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = EmeraldMint)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(mtpFile.name, color = if (isMtpSelected) EmeraldMint else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(formatSize(mtpFile.length()) + " • " + mtpFile.parent, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    // Hardware Offloading Controls (GPU Layers & MMAP)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("HARDWARE OFFLOADING & ACCELERATION:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Memory-Mapped Files (mmap)", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Direct disk paging to optimize RAM footprint", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                            Switch(
                                checked = config.useMmap,
                                onCheckedChange = {
                                    val newConfig = config.copy(useMmap = it)
                                    viewModel.saveConfig(context, newConfig)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = EmeraldMint,
                                    checkedTrackColor = EmeraldMint.copy(alpha = 0.4f)
                                )
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("GPU Offloaded Layers (ngl)", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("$gpuLayersState Layers", color = EmeraldMint, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
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
                                colors = SliderDefaults.colors(thumbColor = EmeraldMint, activeTrackColor = EmeraldMint)
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)

                        // Bottom Row: Centered single [Re-Scan Models] outline button
                        OutlinedButton(
                            onClick = {
                                isScanning = true
                                coroutineScope.launch {
                                    val (freshBase, freshMtp) = withContext(Dispatchers.IO) {
                                        viewModel.rescanModels(context)
                                    }
                                    models = freshBase
                                    mtpModels = freshMtp
                                    isScanning = false
                                    Toast.makeText(context, "Discovered ${freshBase.size} models, ${freshMtp.size} MTP drafts", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.5.dp, EmeraldMintBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = EmeraldMint
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Scanning Storage...", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = EmeraldMint)
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
                titleColor = EmeraldMint
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Context Length Presets (up to 16K)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("CONTEXT WINDOW HORIZON (UP TO 16K):", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(2048, 4096, 8192, 16384).forEach { size ->
                                val label = if (size == 16384) "16K" else if (size == 8192) "8K" else if (size == 4096) "4K" else "2K"
                                val isSelected = config.contextLength == size
                                Surface(
                                    modifier = Modifier.weight(1f).clickable {
                                        contextLengthText = size.toString()
                                        val newConfig = config.copy(contextLength = size)
                                        viewModel.saveConfig(context, newConfig)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) EmeraldMint.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(0.5.dp, if (isSelected) EmeraldMint else MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) EmeraldMint else MaterialTheme.colorScheme.onSurface,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = contextLengthText,
                            onValueChange = {
                                contextLengthText = it
                                it.toIntOrNull()?.let { len ->
                                    if (len in 1024..16384) {
                                        val newConfig = config.copy(contextLength = len)
                                        viewModel.saveConfig(context, newConfig)
                                    }
                                }
                            },
                            label = { Text("Context Size (up to 16K)") },
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
                            Text(String.format(java.util.Locale.US, "%.2f", temperatureState), color = EmeraldMint, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = temperatureState,
                            onValueChange = { temperatureState = it },
                            onValueChangeFinished = {
                                val newConfig = config.copy(temperature = temperatureState)
                                viewModel.saveConfig(context, newConfig)
                            },
                            valueRange = 0f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = EmeraldMint, activeTrackColor = EmeraldMint)
                        )
                    }
                }
            }

            // ==========================================
            // CARD 4: SYSTEM INTERFACE & PERMISSIONS
            // ==========================================
            SettingsCard(
                title = "4. SYSTEM INTERFACE",
                titleColor = EmeraldMint
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
                        Icon(Icons.Default.Sync, null, modifier = Modifier.size(16.dp), tint = EmeraldMint)
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
        color = if (isActive) EmeraldMint.copy(alpha = 0.15f) else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(0.5.dp, if (isActive) EmeraldMint else MaterialTheme.colorScheme.error)
    ) {
        Text(
            text = if (isActive) "ACTIVE" else "OFF",
            color = if (isActive) EmeraldMint else MaterialTheme.colorScheme.error,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SettingsCard(
    title: String,
    titleColor: Color = EmeraldMint,
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
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
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
                    modifier = Modifier.weight(1f, fill = false),
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
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (statusText != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        color = EmeraldMint,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
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
    focusedBorderColor = EmeraldMint,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = EmeraldMint,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
)
