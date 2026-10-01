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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
    settingsViewModel: SettingsViewModel = viewModel.settingsViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val config = uiState.config

    AtlasAiTheme {
        SettingsScreenContent(viewModel, settingsViewModel, onBack, uiState, config, context)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenContent(
    viewModel: AgentViewModel,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    uiState: AgentUiState,
    config: AgentConfig,
    context: Context
) {
    val isDark = isSystemInDarkTheme()
    val coroutineScope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var mtpModels by remember { mutableStateOf<List<File>>(emptyList()) }
    var acousticModels by remember { mutableStateOf<List<File>>(emptyList()) }
    var vocoderModels by remember { mutableStateOf<List<File>>(emptyList()) }
    var speakerModels by remember { mutableStateOf<List<File>>(emptyList()) }
    
    // UI state fixed: initialized to true to prevent state flash
    var isScanning by remember { mutableStateOf(true) }

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
    var gpuLayersText by remember(config.gpuLayers) { mutableStateOf(config.gpuLayers.toString()) }
    var temperatureState by remember(config.temperature) { mutableStateOf(config.temperature) }

    LaunchedEffect(Unit) {
        models = ModelScanner.getBaseModels(context, forceRescan = false)
        mtpModels = ModelScanner.getMtpModels(context, forceRescan = false)
        if (mtpModels.isEmpty()) {
            val (freshBase, freshMtp) = ModelScanner.rescanModels(context)
            models = freshBase
            mtpModels = freshMtp
        }
        acousticModels = ModelScanner.getAcousticModels(context, forceRescan = false)
        vocoderModels = ModelScanner.getVocoderModels(context, forceRescan = false)
        speakerModels = ModelScanner.getSpeakerEmbeddings(context, forceRescan = false)
        isScanning = false // End of initial scan
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
                    AtlasAiBranding(fontSize = 14.sp, lineHeight = 2.dp)
                    Spacer(Modifier.height(3.dp))
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
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isGemini) primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isGemini) onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Gemini Cloud", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Button(
                            onClick = {
                                val newConfig = config.copy(preferredModelType = ModelType.GGUF)
                                viewModel.saveConfig(context, newConfig)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isGguf) primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isGguf) onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Local GGUF", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // ==========================================
            // GEMINI CLOUD EXCLUSIVE SECTIONS
            // ==========================================
            if (config.preferredModelType == ModelType.GEMINI) {
                // ==========================================
                // CARD 2: GEMINI CLOUD ENGINE
                // ==========================================
                SettingsCard(
                    title = "2. GEMINI CLOUD ENGINE",
                    titleColor = primary,
                    statusText = config.geminiModelName.ifBlank { "OFFLINE" }
                ) {
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
                                            text = "🔑 Gemini API Key Pool",
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

                            val clipboardManager = LocalClipboardManager.current

                            // Manual Model Input Field (Purely manual model identifier entry)
                            OutlinedTextField(
                                value = geminiModelText,
                                onValueChange = { input ->
                                    val clean = AgentViewModel.sanitizeGeminiModelName(input)
                                    geminiModelText = if (clean != AgentViewModel.DEFAULT_GEMINI_MODEL && clean.length < input.length) clean else input
                                    val newConfig = config.copy(geminiModelName = clean)
                                    viewModel.saveConfig(context, newConfig)
                                },
                                label = { Text("GEMINI MODEL IDENTIFIER", color = primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
                                placeholder = { Text("Enter model identifier...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
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
                                                clipboardManager.getText()?.text?.let { pasteText ->
                                                    if (pasteText.isNotBlank()) {
                                                        val clean = AgentViewModel.sanitizeGeminiModelName(pasteText)
                                                        geminiModelText = clean
                                                        val newConfig = config.copy(geminiModelName = clean)
                                                        viewModel.saveConfig(context, newConfig)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Filled.ContentPaste, "Paste model ID", tint = primary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            )

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

                    // ==========================================
                    // CARD 3: CLOUD INFERENCE PARAMETERS
                    // ==========================================
                    SettingsCard(
                        title = "3. CLOUD INFERENCE PARAMETERS",
                        titleColor = primary
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "TEMPERATURE & FOCUS DYNAMICS",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    val tempDesc = when {
                                        temperatureState <= 0.20f -> "🎯 Hyper-Focused (Strict Commands & Code)"
                                        temperatureState <= 0.40f -> "⚡ Optimal Logic (Recommended 0.30)"
                                        temperatureState <= 0.70f -> "⚖️ Balanced (General Discussions)"
                                        else -> "🎨 High Creativity (Exploratory)"
                                    }
                                    Text(tempDesc, color = primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = primaryContainer
                                ) {
                                    Text(
                                        text = String.format(java.util.Locale.US, "%.2f", temperatureState),
                                        color = onPrimaryContainer,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Slider(
                                value = temperatureState,
                                onValueChange = { temperatureState = it },
                                onValueChangeFinished = {
                                    val newConfig = config.copy(temperature = temperatureState)
                                    viewModel.saveConfig(context, newConfig)
                                },
                                valueRange = 0.0f..1.0f,
                                colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    Triple("0.15 STRICT", 0.15f, false),
                                    Triple("0.30 OPTIMAL", 0.30f, true),
                                    Triple("0.60 BALANCED", 0.60f, false)
                                ).forEach { (label, value, _) ->
                                    val isSelected = Math.abs(temperatureState - value) < 0.05f
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) primaryContainer else surfaceContainer,
                                        border = BorderStroke(1.dp, if (isSelected) primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                temperatureState = value
                                                val newConfig = config.copy(temperature = value)
                                                viewModel.saveConfig(context, newConfig)
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                textAlign = TextAlign.Center,
                                                fontSize = 10.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (isSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ==========================================
                    // CARD 4: UNIFIED INTERNAL PERSONA (GOD MODE)
                    // ==========================================
                    UnifiedPersonaCard(
                        cardNumber = 4,
                        primary = primary,
                        surfaceContainer = surfaceContainer,
                        settingsViewModel = settingsViewModel,
                        viewModel = viewModel,
                        context = context
                    )
                } else {
                    // ==========================================
                    // LOCAL GGUF ENGINE EXCLUSIVE SECTIONS
                    // ==========================================

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
                        Text("BASE NEURAL MODEL (PRIMARY BRAIN):", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        val distinctBaseModels = models.filter { ModelScanner.isBaseModelFile(it) && !it.name.startsWith(".") }.distinctBy { it.name.lowercase() }

                        if (distinctBaseModels.isEmpty() && !isScanning) {
                            Text("No base .gguf models detected in /sdcard/Medlam-Ai/models.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                        }

                        distinctBaseModels.forEach { file ->
                            val isSelected = config.localModelPath == file.absolutePath ||
                                (config.localModelPath.isNotBlank() && File(config.localModelPath).name.equals(file.name, ignoreCase = true))

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) primaryContainer else surfaceContainer)
                                    .clickable {
                                        val matchedMtp = ModelScanner.findMatchingMtpModel(file.absolutePath, context)
                                        val newDraft = if (config.draftModelPath.isNotBlank()) config.draftModelPath else (matchedMtp?.absolutePath ?: "STANDALONE")
                                        val newConfig = config.copy(
                                            localModelPath = file.absolutePath,
                                            draftModelPath = newDraft,
                                            preferredModelType = ModelType.GGUF
                                        )
                                        viewModel.saveConfig(context, newConfig)
                                    }
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            val matchedMtp = ModelScanner.findMatchingMtpModel(file.absolutePath, context)
                                            val newDraft = if (config.draftModelPath.isNotBlank()) config.draftModelPath else (matchedMtp?.absolutePath ?: "STANDALONE")
                                            val newConfig = config.copy(
                                                localModelPath = file.absolutePath,
                                                draftModelPath = newDraft,
                                                preferredModelType = ModelType.GGUF
                                            )
                                            viewModel.saveConfig(context, newConfig)
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = primary)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.name, color = if (isSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            formatSize(file.length()) + " • " + (if (isSelected) (if (config.useMmap) "💾 DISK (mmap)" else "⚡ RAM (loaded)") + " • " else "") + file.parent,
                                            color = if (isSelected) onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Spacer(Modifier.height(6.dp))
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "BASE MEMORY MODE:",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Option A: RAM (mlock)
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = if (config.pinModelInRam || !config.useMmap) Color(0xFF00E676).copy(alpha = 0.25f) else Color.Transparent,
                                                    border = BorderStroke(1.dp, if (config.pinModelInRam || !config.useMmap) Color(0xFF00E676) else MaterialTheme.colorScheme.outlineVariant),
                                                    modifier = Modifier.clickable(enabled = !uiState.localModelLoading) {
                                                        viewModel.togglePinModelInRam(true, context)
                                                    }
                                                ) {
                                                    Text(
                                                        "⚡ RAM (mlock)",
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                        fontSize = 9.5.sp,
                                                        fontWeight = if (config.pinModelInRam || !config.useMmap) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (config.pinModelInRam || !config.useMmap) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }

                                                // Option B: DISK (mmap)
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = if (!config.pinModelInRam && config.useMmap) Color(0xFF007BFF).copy(alpha = 0.25f) else Color.Transparent,
                                                    border = BorderStroke(1.dp, if (!config.pinModelInRam && config.useMmap) Color(0xFF007BFF) else MaterialTheme.colorScheme.outlineVariant),
                                                    modifier = Modifier.clickable(enabled = !uiState.localModelLoading) {
                                                        viewModel.togglePinModelInRam(false, context)
                                                    }
                                                ) {
                                                    Text(
                                                        "💾 DISK (mmap)",
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                        fontSize = 9.5.sp,
                                                        fontWeight = if (!config.pinModelInRam && config.useMmap) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (!config.pinModelInRam && config.useMmap) Color(0xFF007BFF) else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            if (!config.pinModelInRam && config.useMmap) "💾 Loaded via disk mmap — safe from low memory / OOM errors." else "⚡ Preloaded into physical RAM — highest inference speed (requires free RAM).",
                                            fontSize = 8.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Dedicated Opt-in Physical RAM Pinning Toggle (RAM-Heavy Mode)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        "Pin Model into High-Speed Physical RAM (RAM-Heavy Mode)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        if (config.pinModelInRam)
                                            "⚡ RAM-Heavy Mode Active: Weights pinned into physical RAM via mlock. Storage streaming disabled."
                                        else
                                            "💾 Default: Zero-Forced-RAM (mmap). Weights stream on-demand from storage via Linux page cache, preserving physical RAM.",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = config.pinModelInRam,
                                    onCheckedChange = { isChecked ->
                                        viewModel.togglePinModelInRam(isChecked, context)
                                    },
                                    enabled = !uiState.localModelLoading
                                )
                            }
                        }
                    }

                    // User-Driven Companion Draft / MTP Model Selection
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "COMPANION DRAFT / MTP MODEL (SPECULATIVE ACCELERATION):",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Option 1: Standalone Execution (No MTP)
                        val isStandalone = config.draftModelPath.isBlank() || config.draftModelPath == "STANDALONE"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isStandalone) primaryContainer else surfaceContainer)
                                .clickable {
                                    viewModel.setDraftModelPath(context, "STANDALONE")
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isStandalone,
                                onClick = {
                                    viewModel.setDraftModelPath(context, "STANDALONE")
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = primary)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Standalone Execution (No Companion Draft)", color = if (isStandalone) onPrimaryContainer else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Standard autoregressive generation without speculative draft", color = if (isStandalone) onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                        }

                        // Option 2: Discovered MTP models and companion drafts (Strictly unique, zero duplicate entries)
                        val distinctMtpModels = (mtpModels + models.filter { it.length() < 1600L * 1024 * 1024 })
                            .filter { it.exists() && !it.name.startsWith(".") && !it.name.equals(File(config.localModelPath).name, ignoreCase = true) }
                            .distinctBy { it.name.lowercase() }
                            .sortedBy { it.length() }
                        distinctMtpModels.forEach { mtpFile ->
                            val isMtpSelected = config.draftModelPath == mtpFile.absolutePath ||
                                (config.draftModelPath.isNotBlank() && config.draftModelPath != "STANDALONE" && File(config.draftModelPath).name.equals(mtpFile.name, ignoreCase = true))

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isMtpSelected) primaryContainer else surfaceContainer)
                                    .clickable {
                                        viewModel.setDraftModelPath(context, mtpFile.absolutePath)
                                    }
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isMtpSelected,
                                        onClick = {
                                            viewModel.setDraftModelPath(context, mtpFile.absolutePath)
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = primary)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            mtpFile.name,
                                            color = if (isMtpSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            formatSize(mtpFile.length()) + " • " + (if (isMtpSelected) (if (config.draftUseMmap) "💾 DISK (mmap)" else "⚡ RAM (locked)") + " • " else "") + mtpFile.parent,
                                            color = if (isMtpSelected) onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                if (isMtpSelected) {
                                    Spacer(Modifier.height(6.dp))
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "DRAFT MEMORY ALLOCATION:",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Option A: RAM (mlock)
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = if (!config.draftUseMmap) Color(0xFF00E676).copy(alpha = 0.25f) else Color.Transparent,
                                                    border = BorderStroke(1.dp, if (!config.draftUseMmap) Color(0xFF00E676) else MaterialTheme.colorScheme.outlineVariant),
                                                    modifier = Modifier.clickable(enabled = !uiState.localModelLoading) {
                                                        viewModel.toggleDraftMmapState(false, context)
                                                    }
                                                ) {
                                                    Text(
                                                        "⚡ RAM (mlock)",
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                        fontSize = 9.5.sp,
                                                        fontWeight = if (!config.draftUseMmap) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (!config.draftUseMmap) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }

                                                // Option B: DISK (mmap)
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = if (config.draftUseMmap) Color(0xFF007BFF).copy(alpha = 0.25f) else Color.Transparent,
                                                    border = BorderStroke(1.dp, if (config.draftUseMmap) Color(0xFF007BFF) else MaterialTheme.colorScheme.outlineVariant),
                                                    modifier = Modifier.clickable(enabled = !uiState.localModelLoading) {
                                                        viewModel.toggleDraftMmapState(true, context)
                                                    }
                                                ) {
                                                    Text(
                                                        "💾 DISK (mmap)",
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                        fontSize = 9.5.sp,
                                                        fontWeight = if (config.draftUseMmap) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (config.draftUseMmap) Color(0xFF007BFF) else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            if (!config.draftUseMmap) "⚡ Draft locked in physical RAM (recommended for <1.5GB draft models to eliminate disk latency)." else "💾 Draft memory-mapped from disk storage (mmap).",
                                            fontSize = 8.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
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
                                acousticModels = ModelScanner.getAcousticModels(context, forceRescan = false)
                                vocoderModels = ModelScanner.getVocoderModels(context, forceRescan = false)
                                speakerModels = ModelScanner.getSpeakerEmbeddings(context, forceRescan = false)
                                isScanning = false
                                Toast.makeText(context, "Discovered ${freshBase.size} base, ${freshMtp.size} MTP", Toast.LENGTH_SHORT).show()
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

            // ==========================================
            // CARD 3: LOCAL INFERENCE TUNING (AUTO-SAVING)
            // ==========================================
            SettingsCard(
                title = "3. LOCAL INFERENCE TUNING",
                titleColor = primary
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Context Length Presets (1K - 8K)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("CONTEXT WINDOW HORIZON (PRESETS):", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(1024, 2048, 4096, 8192).forEach { size ->
                                val label = if (size == 8192) "8K" else if (size == 4096) "4K" else if (size == 2048) "2K" else "1K"
                                val isSelected = config.contextLength == size
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            contextLengthText = size.toString()
                                            val newConfig = config.copy(contextLength = size)
                                            viewModel.saveConfig(context, newConfig)
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) primaryContainer else surfaceContainer,
                                    border = BorderStroke(1.dp, if (isSelected) primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            textAlign = TextAlign.Center
                                        )
                                    }
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
                                    if (len in 1024..32768) {
                                        val newConfig = config.copy(contextLength = len)
                                        viewModel.saveConfig(context, newConfig)
                                    }
                                }
                            },
                            label = { Text("Context Size (up to 32K)") },
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
                        OutlinedTextField(
                            value = gpuLayersText,
                            onValueChange = {
                                gpuLayersText = it
                                it.toIntOrNull()?.let { gl ->
                                    if (gl in 0..999) {
                                        val newConfig = config.copy(gpuLayers = gl)
                                        viewModel.saveConfig(context, newConfig)
                                    }
                                }
                            },
                            enabled = true,
                            label = { Text("GPU (ngl)") },
                            modifier = Modifier.weight(1f),
                            colors = settingsFieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    val isVkSupported = remember { LlamaServerJNI.safeIsVulkanSupported() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val badgeColor = if (isVkSupported) Color(0xFF00E676) else Color(0xFFFFB300)
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Text(
                            text = if (isVkSupported) {
                                "⚡ Vulkan Compute 16-Bit Active • GPU Acceleration Enabled (ngl)"
                            } else {
                                "⚙️ Adreno 610 (No 16-bit storage) • Scaled to High-Performance Big CPU Cores"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "TEMPERATURE & FOCUS DYNAMICS",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                val tempDesc = when {
                                    temperatureState <= 0.20f -> "🎯 Hyper-Focused (Strict Commands & Code)"
                                    temperatureState <= 0.40f -> "⚡ Optimal Logic (Recommended 0.30)"
                                    temperatureState <= 0.70f -> "⚖️ Balanced (General Discussions)"
                                    else -> "🎨 High Creativity (Exploratory)"
                                }
                                Text(tempDesc, color = primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = primaryContainer
                            ) {
                                Text(
                                    text = String.format(java.util.Locale.US, "%.2f", temperatureState),
                                    color = onPrimaryContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Slider(
                            value = temperatureState,
                            onValueChange = { temperatureState = it },
                            onValueChangeFinished = {
                                val newConfig = config.copy(temperature = temperatureState)
                                viewModel.saveConfig(context, newConfig)
                            },
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                Triple("0.15 STRICT", 0.15f, false),
                                Triple("0.30 OPTIMAL", 0.30f, true),
                                Triple("0.60 BALANCED", 0.60f, false)
                            ).forEach { (label, value, _) ->
                                val isSelected = Math.abs(temperatureState - value) < 0.05f
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) primaryContainer else surfaceContainer,
                                    border = BorderStroke(1.dp, if (isSelected) primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            temperatureState = value
                                            val newConfig = config.copy(temperature = value)
                                            viewModel.saveConfig(context, newConfig)
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            textAlign = TextAlign.Center,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // CARD 4: LOCAL IMAGE GENERATION & DIFFUSION (SD TURBO)
            // ==========================================
            SettingsCard(
                title = "4. LOCAL IMAGE GENERATION & DIFFUSION",
                titleColor = primary,
                statusDot = primary,
                statusText = "${config.sdComputeDevice} • " + (if (config.sdSteps == 0) "AUTO-ADAPTIVE" else "${config.sdSteps} STEPS")
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 0. COMPUTE BACKEND (CPU / GPU)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "COMPUTE BACKEND:",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                if (config.sdComputeDevice == "GPU") "GPU (VULKAN ADRENO)" else "CPU (SIMD NEON 4-CORES)",
                                color = primary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        SettingsOptionSelector(
                            options = listOf(
                                "CPU" to "⚡ CPU (NEON)",
                                "GPU" to "🎮 GPU (VULKAN)"
                            ),
                            selectedValue = config.sdComputeDevice,
                            onSelect = { dev ->
                                viewModel.saveConfig(context, config.copy(sdComputeDevice = dev))
                            },
                            buttonHeight = 38.dp,
                            fontSize = 10.sp
                        )
                    }

                    // 1. Inference Steps Control
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "INFERENCE STEPS (DENOISING):",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                if (config.sdSteps == 0) "AUTO (1-2 TURBO / 15 STD)" else "${config.sdSteps} STEPS",
                                color = primary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        SettingsOptionSelector(
                            options = listOf(
                                0 to "AUTO",
                                1 to "1 (TURBO)",
                                4 to "4 (LCM)",
                                15 to "15 (STD)",
                                20 to "20 (HQ)"
                            ),
                            selectedValue = config.sdSteps,
                            onSelect = { steps ->
                                viewModel.saveConfig(context, config.copy(sdSteps = steps))
                            },
                            buttonHeight = 38.dp,
                            fontSize = 8.5.sp
                        )
                    }

                    // 2. CFG Scale (Guidance Scale)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "CFG GUIDANCE SCALE:",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                if (config.sdCfgScale <= 0f) "AUTO (1.0 TURBO / 7.0 STD)" else "${config.sdCfgScale}",
                                color = primary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        SettingsOptionSelector(
                            options = listOf(
                                0.0f to "AUTO",
                                1.0f to "1.0 (TURBO)",
                                2.0f to "2.0 (LCM)",
                                7.0f to "7.0 (STD)"
                            ),
                            selectedValue = config.sdCfgScale,
                            onSelect = { cfg ->
                                viewModel.saveConfig(context, config.copy(sdCfgScale = cfg))
                            },
                            buttonHeight = 38.dp,
                            fontSize = 9.sp
                        )
                    }

                    // 3. Storage Streaming & RAM Usage (Zero-Forced-RAM Option)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "RAM USAGE PROFILE:",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                if (config.sdLowMemoryMode) "ZERO-RAM (DISK STREAM)" else "RAM BUFFER (MMAP)",
                                color = if (config.sdLowMemoryMode) primary else MaterialTheme.colorScheme.secondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        SettingsOptionSelector(
                            options = listOf(
                                true to "ZERO-RAM (DISK STREAM)",
                                false to "RAM BUFFER (MMAP)"
                            ),
                            selectedValue = config.sdLowMemoryMode,
                            onSelect = { isLowMem ->
                                viewModel.saveConfig(context, config.copy(sdLowMemoryMode = isLowMem))
                            },
                            buttonHeight = 38.dp,
                            fontSize = 9.sp
                        )
                    }

                    // 4. Diffusion CPU Threading
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "DIFFUSION CPU THREADS:",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "${config.sdThreads} BIG CORES",
                                color = primary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        SettingsOptionSelector(
                            options = listOf(
                                2 to "2 THREADS (ECO)",
                                4 to "4 (BIG CORES)",
                                6 to "6 THREADS (MAX)"
                            ),
                            selectedValue = config.sdThreads,
                            onSelect = { threads ->
                                viewModel.saveConfig(context, config.copy(sdThreads = threads))
                            },
                            buttonHeight = 38.dp,
                            fontSize = 9.sp
                        )
                    }

                    // 5. Resolution (Width x Height)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "OUTPUT RESOLUTION:",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "${config.sdWidth}x${config.sdHeight}",
                                color = primary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        SettingsOptionSelector(
                            options = listOf(
                                (256 to 256) to "256x256 (FAST)",
                                (384 to 384) to "384x384 (BALANCED)",
                                (512 to 512) to "512x512 (HQ)"
                            ),
                            selectedValue = config.sdWidth to config.sdHeight,
                            onSelect = { dims ->
                                viewModel.saveConfig(context, config.copy(sdWidth = dims.first, sdHeight = dims.second))
                            },
                            buttonHeight = 38.dp,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            // ==========================================
            // CARD 5: UNIFIED INTERNAL PERSONA (GOD MODE)
            // ==========================================
            UnifiedPersonaCard(
                cardNumber = 5,
                primary = primary,
                surfaceContainer = surfaceContainer,
                settingsViewModel = settingsViewModel,
                viewModel = viewModel,
                context = context
            )
        } // End of Local GGUF else block

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun UnifiedPersonaCard(
    cardNumber: Int,
    primary: Color,
    surfaceContainer: Color,
    settingsViewModel: SettingsViewModel,
    viewModel: AgentViewModel,
    context: Context
) {
    SettingsCard(
        title = "$cardNumber. UNIFIED INTERNAL PERSONA",
        titleColor = primary,
        statusText = "GOD MODE ACTIVE",
        isCollapsible = true,
        initialExpanded = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Medlam-AI Unified Core Engine",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Autonomous AI engineer and Android OS administrator. Unified God Mode covers high-precision coding, deep OS/shell control, inline SVG graphics, and dialect-aware reasoning with zero token bloat.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            val customPersonaPromptValue by settingsViewModel.customPersonaPrompt.collectAsState()
            var personaPromptInput by remember(customPersonaPromptValue) { mutableStateOf(customPersonaPromptValue) }

            OutlinedTextField(
                value = personaPromptInput,
                onValueChange = {
                    personaPromptInput = it
                    settingsViewModel.updatePersonaPrompt(it)
                    viewModel.updateCustomPersonaPrompt(it, context)
                },
                label = {
                    Text(
                        "Core Persona & System Prompt",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 380.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.5.sp,
                    color = Color(0xFF79C0FF)
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color(0xFF30363D),
                    focusedTextColor = Color(0xFF79C0FF),
                    unfocusedTextColor = Color(0xFF79C0FF),
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = Color(0xFF090D12),
                    unfocusedContainerColor = Color(0xFF090D12)
                ),
                shape = RoundedCornerShape(8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Saved",
                        tint = Color(0xFF10B981).copy(alpha = 0.85f),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Auto-saved to DataStore",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        fontFamily = FontFamily.Monospace
                    )
                }

                OutlinedButton(
                    onClick = {
                        val def = PersonaDataStore.DEFAULT_PROMPT
                        personaPromptInput = def
                        settingsViewModel.resetPersonaPromptToDefault()
                        viewModel.resetCustomPersonaPrompt(context)
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset Defaults",
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Reset Defaults",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> SettingsOptionSelector(
    options: List<Pair<T, String>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    buttonHeight: Dp = 38.dp,
    fontSize: TextUnit = 9.sp
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val primary = MaterialTheme.colorScheme.primary
        val primaryContainer = MaterialTheme.colorScheme.primaryContainer
        val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
        val isDark = isSystemInDarkTheme()
        val surfaceContainer = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        val outlineVariant = MaterialTheme.colorScheme.outlineVariant

        options.forEach { (value, label) ->
            val isSelected = selectedValue == value
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(buttonHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(value) },
                color = if (isSelected) primaryContainer else surfaceContainer,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    1.dp,
                    if (isSelected) primary else outlineVariant.copy(alpha = 0.35f)
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = fontSize,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSelected) onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
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
    initialExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    val isDark = true
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                    if (isCollapsible) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
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
