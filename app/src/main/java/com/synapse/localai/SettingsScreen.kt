package com.synapse.localai

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
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
import com.synapse.localai.ui.tactileGlassGlow
import java.io.File

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
    val isDark = isSystemInDarkTheme()
    val coroutineScope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var mtpModels by remember { mutableStateOf<List<File>>(emptyList()) }
    var personas by remember { mutableStateOf<List<String>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var isScanningPersonas by remember { mutableStateOf(false) }

    // Dynamic Material 3 Color Tokens
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceColor = MaterialTheme.colorScheme.background
    val surfaceContainer = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceContainerLow = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface
    
    // --- LOCAL FORM STATE ---
    var geminiKeysVisible by remember { mutableStateOf(false) }
    var geminiKeyPoolExpanded by remember { mutableStateOf(false) }
    var geminiModelText by remember(config.geminiModelName) { mutableStateOf(config.geminiModelName) }
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
            .background(surfaceColor)
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())

        // Top App Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(primary.copy(alpha = 0.08f), Color.Transparent)
                    )
                )
                .background(surfaceColor)
                .padding(horizontal = 6.dp, vertical = 8.dp)
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
                        color = primary,
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
                        tint = primary
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ==========================================
            // CARD 1: COGNITIVE ENGINE SELECTOR (AUTO-SAVING)
            // ==========================================
            SettingsCard(
                title = "1. COGNITIVE ENGINE",
                titleColor = primary,
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
                                containerColor = if (isGemini) primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isGemini) onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("Gemini Cloud", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                val newConfig = config.copy(preferredModelType = ModelType.GGUF)
                                viewModel.saveConfig(context, newConfig)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isGguf) primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isGguf) onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text("Local GGUF", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Cloud Credentials Section (Auto-Saving)
                    if (config.preferredModelType == ModelType.GEMINI) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Collapsible API Key Pool Drawer (Accordion Pattern)
                            val activeKeysList = remember(config.geminiApiKeys) {
                                config.geminiApiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            }
                            val keyCount = activeKeysList.size
                            val activeKeyNum = if (keyCount > 0) (uiState.currentKeyIndex + 1).coerceIn(1, keyCount) else 0

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tactileGlassGlow(cornerRadius = 10.dp, isDark = isDark, borderWidth = 1.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = surfaceContainer
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Header / Summary Row (Clean Horizontal Bar with nowrap typography)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "⚡ Gemini API Key Pool",
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            // Key Count Badge
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (keyCount > 0) primaryContainer else MaterialTheme.colorScheme.errorContainer
                                            ) {
                                                Text(
                                                    text = if (keyCount > 0) "$keyCount Keys" else "0 Keys",
                                                    color = if (keyCount > 0) onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                )
                                            }

                                            if (geminiKeyPoolExpanded) {
                                                // Mask / Unmask Toggle (Eye glyph)
                                                IconButton(
                                                    onClick = { geminiKeysVisible = !geminiKeysVisible },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = if (geminiKeysVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                        contentDescription = if (geminiKeysVisible) "Mask keys" else "Unmask keys",
                                                        tint = primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }

                                                // Collapse Toggle Button [▲ Done]
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = primary,
                                                    modifier = Modifier
                                                        .wrapContentWidth()
                                                        .clickable { geminiKeyPoolExpanded = false }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.KeyboardArrowUp,
                                                            contentDescription = "Collapse",
                                                            tint = onPrimary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Text(
                                                            text = "Done",
                                                            color = onPrimary,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            softWrap = false
                                                        )
                                                    }
                                                }
                                            } else {
                                                // Expand / Edit Button [Edit ▼]
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = primaryContainer,
                                                    modifier = Modifier
                                                        .wrapContentWidth()
                                                        .clickable { geminiKeyPoolExpanded = true }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                    ) {
                                                        Text(
                                                            text = "Edit",
                                                            color = onPrimaryContainer,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            softWrap = false
                                                        )
                                                        Icon(
                                                            imageVector = Icons.Default.KeyboardArrowDown,
                                                            contentDescription = "Expand",
                                                            tint = onPrimaryContainer,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (!geminiKeyPoolExpanded) {
                                        // Default Collapsed State Subtitle
                                        Text(
                                            text = if (keyCount > 0) {
                                                "Current Active: Key #$activeKeyNum/$keyCount (Auto-failover enabled)"
                                            } else {
                                                "No API keys configured. Tap [Edit] to configure API keys."
                                            },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Expanded State (Bounded Height & Internal Scroll)
                                    AnimatedVisibility(
                                        visible = geminiKeyPoolExpanded,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Current Active: Key #$activeKeyNum/$keyCount (Auto-failover enabled)",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )

                                            // Bounded container with strict maximum height limit & internal vertical scroll
                                            val keyScrollState = rememberScrollState()
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 70.dp, max = 150.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface)
                                                    .verticalScroll(keyScrollState)
                                            ) {
                                                OutlinedTextField(
                                                    value = config.geminiApiKeys,
                                                    onValueChange = {
                                                        val newConfig = config.copy(geminiApiKeys = it)
                                                        viewModel.saveConfig(context, newConfig)
                                                    },
                                                    placeholder = {
                                                        Text(
                                                            "AIzaSy..., AIzaSy...",
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                            fontSize = 11.5.sp,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    },
                                                    label = { Text("Gemini API Keys (comma-separated)", fontSize = 11.sp) },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = settingsFieldColors(),
                                                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                                    visualTransformation = if (geminiKeysVisible) VisualTransformation.None else PasswordVisualTransformation()
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Freeform Manual Model Input Field (Dynamic Custom Model Entry & Zero Client-Side Restrictions)
                            OutlinedTextField(
                                value = geminiModelText,
                                onValueChange = { input ->
                                    geminiModelText = input
                                    val newConfig = config.copy(geminiModelName = input.trim())
                                    viewModel.saveConfig(context, newConfig)
                                },
                                label = { Text("GEMINI CLOUD MODEL IDENTIFIER", color = primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
                                placeholder = { Text("e.g. gemini-2.5-flash, gemini-2.5-pro, gemini-2.0-flash...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = settingsFieldColors(),
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (geminiModelText.isNotBlank()) {
                                            IconButton(
                                                onClick = {
                                                    geminiModelText = ""
                                                    val newConfig = config.copy(geminiModelName = "")
                                                    viewModel.saveConfig(context, newConfig)
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Filled.Clear, "Clear model ID", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                val defaultModel = "gemini-2.0-flash"
                                                geminiModelText = defaultModel
                                                val newConfig = config.copy(geminiModelName = defaultModel)
                                                viewModel.saveConfig(context, newConfig)
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Filled.Refresh, "Reset to default", tint = primary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            )

                            // Quick Selector Chips for Gemini Cloud Models
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("gemini-2.0-flash", "gemini-2.5-flash", "gemini-2.5-pro").forEach { modelOption ->
                                    val isSelected = geminiModelText.trim() == modelOption
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                geminiModelText = modelOption
                                                val newConfig = config.copy(geminiModelName = modelOption)
                                                viewModel.saveConfig(context, newConfig)
                                            },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) primaryContainer else surfaceContainer
                                    ) {
                                        Text(
                                            text = modelOption.removePrefix("gemini-"),
                                            color = if (isSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            fontSize = 10.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }

                            // Inline Key & Model Status (Confirms active model ID currently queued for cloud queries)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(if (config.geminiModelName.isNotBlank()) primary else MaterialTheme.colorScheme.error)
                                        )
                                        Text(
                                            text = "ACTIVE INFERENCE TARGET",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Text(
                                        text = config.geminiModelName.ifBlank { "NO MODEL CONFIGURED" },
                                        color = if (config.geminiModelName.isNotBlank()) primary else MaterialTheme.colorScheme.error,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
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
                                        checkedThumbColor = primary,
                                        checkedTrackColor = primaryContainer
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // LOCAL GGUF ENGINE EXCLUSIVE SETTINGS
            // ==========================================
            if (config.preferredModelType == ModelType.GGUF) {

            // ==========================================
            // CARD 2: LOCAL NEURAL CORE (GGUF + MTP)
            // ==========================================
            SettingsCard(
                title = "2. LOCAL NEURAL CORE",
                titleColor = primary,
                statusDot = when {
                    uiState.localModelLoaded && config.preferredModelType == ModelType.GGUF -> primary
                    uiState.localModelLoading -> Color.Yellow
                    else -> MaterialTheme.colorScheme.error
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
                                .background(surfaceContainer)
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
                                    color = primary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${((uiState.loadingProgress ?: 0f) * 100).toInt()}%",
                                    color = primary,
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
                                color = primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }

                    if (isScanning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = primary)
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
                                    .background(if (isSelected) primaryContainer else surfaceContainer)
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
                                    colors = RadioButtonDefaults.colors(selectedColor = primary)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(file.name, color = if (isSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                    Text(formatSize(file.length()) + " • " + file.parent, color = if (isSelected) onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
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
                                .background(if (isStandalone) primaryContainer else surfaceContainer)
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
                                colors = RadioButtonDefaults.colors(selectedColor = primary)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Standalone Execution (No MTP)", color = if (isStandalone) onPrimaryContainer else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Standard autoregressive generation without companion draft", color = if (isStandalone) onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                        }

                        // Option 2: Discovered MTP models from storage
                        mtpModels.forEach { mtpFile ->
                            val isMtpSelected = config.draftModelPath == mtpFile.absolutePath
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isMtpSelected) primaryContainer else surfaceContainer)
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
                                    colors = RadioButtonDefaults.colors(selectedColor = primary)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(mtpFile.name, color = if (isMtpSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(formatSize(mtpFile.length()) + " • " + mtpFile.parent, color = if (isMtpSelected) onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
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
                                    checkedThumbColor = primary,
                                    checkedTrackColor = primaryContainer
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
                                Text("$gpuLayersState Layers", color = primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
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
                                colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
                            )
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)

                        // Bottom Row: Centered single [Re-Scan Models] button
                        Button(
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
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryContainer,
                                contentColor = onPrimaryContainer
                            )
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = onPrimaryContainer
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Scanning Storage...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = onPrimaryContainer)
                                Spacer(Modifier.width(8.dp))
                                Text("Re-Scan Models", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                titleColor = primary
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
                                    color = if (isSelected) primaryContainer else surfaceContainer
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
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
                            Text(String.format(java.util.Locale.US, "%.2f", temperatureState), color = primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = temperatureState,
                            onValueChange = { temperatureState = it },
                            onValueChangeFinished = {
                                val newConfig = config.copy(temperature = temperatureState)
                                viewModel.saveConfig(context, newConfig)
                            },
                            valueRange = 0f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
                        )
                    }
                }
            }
            }

            // ==========================================
            // CARD: OPERATIONAL PERSONAS (.MD MULTI-PROFILE)
            // ==========================================
            SettingsCard(
                title = "OPERATIONAL PERSONAS (.MD)",
                titleColor = primary,
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
                        color = surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
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
                                        color = primary
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Re-index Personas",
                                        tint = primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Active Stacking Overview Banner
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = primaryContainer
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
                                    color = onPrimaryContainer,
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
                                        color = primary.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = "✓ $pName",
                                            color = onPrimaryContainer,
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
                                color = if (isChecked) primaryContainer.copy(alpha = 0.5f) else surfaceContainer
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
                                            checkedColor = primary,
                                            checkmarkColor = onPrimary
                                        )
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = pName,
                                                color = if (isChecked) primary else MaterialTheme.colorScheme.onSurface,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            if (isChecked) {
                                                Surface(
                                                    shape = RoundedCornerShape(3.dp),
                                                    color = primary.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = "ACTIVE",
                                                        color = primary,
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
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun SettingsCard(
    title: String,
    titleColor: Color = MaterialTheme.colorScheme.primary,
    statusDot: Color? = null,
    statusText: String? = null,
    isCollapsible: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .tactileGlassGlow(cornerRadius = 12.dp, isDark = isDark, borderWidth = 0.dp),
        color = cardColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        color = MaterialTheme.colorScheme.primary,
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.15f)
)
