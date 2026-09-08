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
import androidx.compose.material.icons.filled.ContentPaste
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
    var isScanning by remember { mutableStateOf(false) }

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

    LaunchedEffect(Unit) {
        // Zero-Overhead: Read exclusively from cached manifests (NO automatic filesystem sweep on resume)
        models = ModelScanner.getBaseModels(context, forceRescan = false)
        mtpModels = ModelScanner.getMtpModels(context, forceRescan = false)
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

                            // Freeform Manual Model Input Field (Dynamic Custom Model Entry & Zero Client-Side Restrictions)
                            OutlinedTextField(
                                value = geminiModelText,
                                onValueChange = { input ->
                                    geminiModelText = input
                                    val newConfig = config.copy(geminiModelName = input.trim())
                                    viewModel.saveConfig(context, newConfig)
                                },
                                label = { Text("GEMINI CLOUD MODEL IDENTIFIER", color = primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
                                placeholder = { Text("e.g. gemini-3.5-flash-lite, gemini-2.5-pro...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
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
                                                        geminiModelText = pasteText.trim()
                                                        val newConfig = config.copy(geminiModelName = geminiModelText)
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
                        models.filter { ModelScanner.isBaseModelFile(it) }.forEach { file ->
                            val isSelected = config.localModelPath == file.absolutePath

                            Row(
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
                                    .padding(10.dp),
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
                                        formatSize(file.length()) + " • " + (if (isSelected) (if (config.useMmap) "mmap (disk)" else "RAM loaded") + " • " else "") + file.parent,
                                        color = if (isSelected) onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                }
                                if (isSelected) {
                                    Spacer(Modifier.width(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                "mmap",
                                                color = onPrimaryContainer,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                if (config.useMmap) "disk" else "RAM",
                                                color = onPrimaryContainer.copy(alpha = 0.75f),
                                                fontSize = 8.5.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        Switch(
                                            checked = config.useMmap,
                                            onCheckedChange = {
                                                val newConfig = config.copy(useMmap = it)
                                                viewModel.saveConfig(context, newConfig)
                                            },
                                            modifier = Modifier.scale(0.75f),
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = primary,
                                                checkedTrackColor = surfaceColor,
                                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                                uncheckedTrackColor = surfaceContainer
                                            )
                                        )
                                    }
                                }
                            }
                        }

                    }

                    // User-Driven Companion Draft / MTP Model Selection
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("COMPANION DRAFT / MTP MODEL:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

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
                                Text("Standalone Execution (No MTP)", color = if (isStandalone) onPrimaryContainer else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Standard autoregressive generation without companion draft", color = if (isStandalone) onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                        }

                        // Option 2: Discovered MTP models from storage
                        mtpModels.forEach { mtpFile ->
                            val isMtpSelected = config.draftModelPath == mtpFile.absolutePath ||
                                (config.draftModelPath.isNotBlank() && config.draftModelPath != "STANDALONE" && File(config.draftModelPath).name == mtpFile.name)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isMtpSelected) primaryContainer else surfaceContainer)
                                    .clickable {
                                        viewModel.setDraftModelPath(context, mtpFile.absolutePath)
                                    }
                                    .padding(10.dp),
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
                                        formatSize(mtpFile.length()) + " • " + (if (isMtpSelected) (if (config.draftUseMmap) "mmap (disk)" else "locked in RAM") + " • " else "") + mtpFile.parent,
                                        color = if (isMtpSelected) onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                }
                                if (isMtpSelected) {
                                    Spacer(Modifier.width(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                "mmap",
                                                color = onPrimaryContainer,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                if (config.draftUseMmap) "disk" else "locked",
                                                color = onPrimaryContainer.copy(alpha = 0.75f),
                                                fontSize = 8.5.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        Switch(
                                            checked = config.draftUseMmap,
                                            onCheckedChange = {
                                                val newConfig = config.copy(draftUseMmap = it)
                                                viewModel.saveConfig(context, newConfig)
                                            },
                                            modifier = Modifier.scale(0.75f),
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = primary,
                                                checkedTrackColor = surfaceColor,
                                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                                uncheckedTrackColor = surfaceContainer
                                            )
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
            // CARD 4: UNIFIED INTERNAL PERSONA (GOD MODE)
            // ==========================================
            SettingsCard(
                title = "4. UNIFIED INTERNAL PERSONA",
                titleColor = primary,
                statusText = "GOD MODE ACTIVE"
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

                    // Monospace preview of the unified rules
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0D1117),
                        border = BorderStroke(1.dp, Color(0xFF30363D))
                    ) {
                        Text(
                            text = UNIFIED_CORE_PROMPT.trim(),
                            color = Color(0xFF58A6FF),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 13.sp,
                            modifier = Modifier.padding(10.dp)
                        )
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
