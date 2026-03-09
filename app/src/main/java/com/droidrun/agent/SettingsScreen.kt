package com.droidrun.agent

import java.io.File
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SCAN_DIRS = listOf(
    "/sdcard/Download",
    "/sdcard/Models",
    "/sdcard/synapse",
    "/sdcard/Documents",
    "/data/local/tmp/synapse"
)

private fun scanForModels(): List<File> {
    val extensions = setOf("litertlm", "bin", "gguf")
    return SCAN_DIRS.flatMap { dir ->
        try {
            val folder = File(dir)
            if (!folder.exists()) return@flatMap emptyList<File>()
            folder.walkTopDown().maxDepth(2).filter { f ->
                f.isFile && f.extension.lowercase() in extensions && f.length() > 1_000_000
            }.toList()
        } catch (_: Exception) { emptyList() }
    }.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
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

    var models by remember { mutableStateOf(scanForModels()) }
    var selectedPath by remember(config.localModelPath) { mutableStateOf(config.localModelPath) }
    var customPath by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkNavy)
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())

        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkCard)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = NeonPurple.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("\u2190 Back", color = NeonCyan, fontSize = 14.sp)
            }
            Text("Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.width(80.dp))
        }

        // Separator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, NeonCyan.copy(alpha = 0.2f), NeonPurple.copy(alpha = 0.2f), Color.Transparent)))
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Model Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("AI MODELS", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        when {
                                            uiState.localModelLoaded -> Color(0xFF34D399)
                                            uiState.localModelLoading -> Color(0xFFFBBF24)
                                            else -> Color(0xFFEF4444)
                                        },
                                        RoundedCornerShape(4.dp)
                                    )
                            )
                            Text(
                                when {
                                    uiState.localModelLoading -> "Loading..."
                                    uiState.localModelLoaded -> "Ready"
                                    else -> "Idle"
                                },
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Divider(color = NeonPurple.copy(alpha = 0.1f))

                    // Models list
                    if (models.isNotEmpty()) {
                        Text("Discovered Models", color = Color.White.copy(alpha = 0.3f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

                        models.forEach { file ->
                            val isSelected = selectedPath == file.absolutePath
                            val isCurrentlyLoaded = isSelected && uiState.localModelLoaded
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
                                // Radio
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
                                    Text(
                                        file.nameWithoutExtension,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${formatSize(file.length())} • $typeLabel",
                                        color = Color.White.copy(alpha = 0.35f),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                // Type badge
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
                                    "Place .gguf or .litertlm files in:\n/sdcard/Download/\n/sdcard/Models/",
                                    color = Color.White.copy(alpha = 0.25f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { models = scanForModels() },
                            shape = RoundedCornerShape(10.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(NeonCyan.copy(alpha = 0.2f))
                            )
                        ) {
                            Text("↻ Rescan", color = NeonCyan.copy(alpha = 0.7f), fontSize = 12.sp)
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

                    // Custom path input
                    if (showCustomInput) {
                        OutlinedTextField(
                            value = customPath,
                            onValueChange = { customPath = it },
                            label = { Text("Full path to model file", color = Color.White.copy(alpha = 0.3f)) },
                            placeholder = { Text("/sdcard/Download/model.gguf", color = Color.White.copy(alpha = 0.12f), fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonPurple.copy(alpha = 0.4f),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = NeonCyan
                            ),
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

                    // Scan dirs info
                    Text(
                        "Scans: ${SCAN_DIRS.joinToString(", ") { it.replace("/sdcard/", "~/") }}",
                        color = Color.White.copy(alpha = 0.15f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Load Button
            Button(
                onClick = {
                    val newConfig = config.copy(useLocalModel = true, localModelPath = selectedPath)
                    viewModel.saveConfig(context, newConfig)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = selectedPath.isNotBlank() && !uiState.localModelLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    disabledContainerColor = NeonCyan.copy(alpha = 0.15f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    if (uiState.localModelLoading) "Loading..." else "Load Selected Model",
                    color = if (uiState.localModelLoading) Color.White.copy(alpha = 0.4f) else DarkNavy,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
