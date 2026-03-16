package com.synapse.localai

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

// Theme colors matching the brain logo
val NeonCyan     = Color(0xFF00D4FF)
val NeonPurple   = Color(0xFFBB44FF)
val NeonMagenta  = Color(0xFFFF44DD)
val DarkNavy     = Color(0xFF0A0E1A)
val DarkCard     = Color(0xFF0F1328)
val MidNavy      = Color(0xFF151A30)
val AccentGlow   = Color(0xFF3B82F6)

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val isError: Boolean = false
)

class MainActivity : ComponentActivity() {
    private val viewModel = AgentViewModel()
    private companion object { private const val TAG = "SynapseMain" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkStoragePermissions()
        
        setContent {
            SynapseAITheme {
                ChatApp(viewModel)
            }
        }
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivity(intent)
                }
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveConversation(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w(TAG, "onTrimMemory level=$level")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // System is critically low on memory - trim conversation history aggressively
                viewModel.trimMemory(aggressive = true)
                System.gc()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                viewModel.trimMemory(aggressive = false)
            }
        }
    }
}

@Composable
fun SynapseAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonCyan,
            background = DarkNavy,
            surface = DarkCard,
            onPrimary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatApp(viewModel: AgentViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var expandedMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadConfig(context)
    }

    // Auto-scroll to bottom during streaming (instant, no animation to avoid shaking)
    LaunchedEffect(uiState.logs.size, uiState.streamVersion) {
        if (uiState.logs.isNotEmpty()) {
            val lastIndex = uiState.logs.size - 1
            // Only auto-scroll if user is already near bottom (within 3 items)
            val isNearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                it >= lastIndex - 2
            } ?: true
            if (isNearBottom) {
                listState.scrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
            }
        }
    }

    // Minimize app when agent interacts with the device
    LaunchedEffect(uiState.shouldMinimize) {
        if (uiState.shouldMinimize) {
            val activity = context as? android.app.Activity
            activity?.moveTaskToBack(true)
            viewModel.onMinimized()
        }
    }

    if (showSettings) {
        SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
        return
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("Synapse AI Capabilities \uD83E\uDDE0", color = NeonCyan, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Synapse is an advanced, fully autonomous on-device Neural Agent.\n\n" +
                    "• ⚡ 100% Local Inference: Your data never leaves the device.\n" +
                    "• \uD83D\uDC41\uFE0F Visual Perception: Sees screen UI trees instantly.\n" +
                    "• \uD83E\uDD16 Autonomous Control: Operates your device like a human.\n" +
                    "• \uD83D\uDD11 Deep System Hooks: Custom IME and Accessibility tracking.\n" +
                    "• \uD83D\uDE80 Built for Speed: Utilizes mmap and advanced thread batching.",
                    color = Color.White,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("OK", color = NeonPurple)
                }
            },
            containerColor = DarkCard,
            titleContentColor = NeonCyan,
            textContentColor = Color.White
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkNavy)
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())

        // Header with logo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(DarkCard, MidNavy, DarkCard)
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Logo from drawable
                Image(
                    painter = painterResource(id = R.drawable.synapse_logo),
                    contentDescription = "Synapse AI",
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                Column {
                    Text(
                        "SYNAPSE AI",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        letterSpacing = 2.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    when {
                                        uiState.localModelLoaded -> Color(0xFF34D399)
                                        uiState.localModelLoading -> Color(0xFFFBBF24)
                                        else -> Color(0xFFEF4444)
                                    },
                                    RoundedCornerShape(3.dp)
                                )
                        )
                        Text(
                            text = when {
                                uiState.localModelLoading -> "Loading Model... ⏳"
                                uiState.localModelLoaded -> "${uiState.activeModelName} • ${uiState.activeModelType?.name ?: ""}"
                                else -> "No model loaded"
                            },
                            color = NeonCyan.copy(alpha = 0.7f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
            Box {
                IconButton(
                    onClick = { expandedMenu = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(NeonPurple.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .border(1.dp, NeonPurple.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                ) {
                    Text("⋮", fontSize = 20.sp, color = NeonPurple.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                }
                DropdownMenu(
                    expanded = expandedMenu,
                    onDismissRequest = { expandedMenu = false },
                    modifier = Modifier.background(DarkCard)
                ) {
                    DropdownMenuItem(
                        text = { Text("⚙ Settings", color = Color.White) },
                        onClick = {
                            expandedMenu = false
                            showSettings = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("\uD83D\uDDD1 Clear Chat", color = Color(0xFFEF4444)) },
                        onClick = {
                            expandedMenu = false
                            viewModel.clearConversation(context)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("ℹ\uFE0F About Agent", color = NeonCyan) },
                        onClick = {
                            expandedMenu = false
                            showAboutDialog = true
                        }
                    )
                }
            }
        }

        // Subtle separator line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, NeonCyan.copy(alpha = 0.3f), NeonPurple.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
        )

        // Chat Messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(uiState.logs, key = { it.id }) { log ->
                val isLastAgentMessage = uiState.isProcessing && log.id == uiState.logs.lastOrNull { it.sender == "AGENT" }?.id
                ChatBubble(log, isLastAgentMessage = isLastAgentMessage)
            }
            if (uiState.isProcessing && (uiState.logs.isEmpty() || uiState.logs.last().sender != "AGENT" || uiState.logs.last().text.isEmpty())) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(36.dp)
                                .background(
                                    Brush.verticalGradient(listOf(NeonCyan, NeonPurple)),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Column {
                            Text(
                                "SYNAPSE",
                                color = NeonPurple.copy(alpha = 0.5f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                            Text(
                                "Thinking...",
                                color = NeonCyan.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // Input Area (isolated to prevent streaming recomposition lag)
        ChatInputBar(
            isProcessing = uiState.isProcessing,
            onSend = { text -> viewModel.sendCommand(text) },
            onStop = { viewModel.stopAgent() }
        )
    }
}

@Composable
private fun ChatInputBar(
    isProcessing: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, DarkNavy.copy(alpha = 0.95f), DarkNavy)
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        "Ask me anything...",
                        color = Color.White.copy(alpha = 0.15f),
                        fontSize = 13.sp
                    )
                },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan.copy(alpha = 0.3f),
                    unfocusedBorderColor = NeonPurple.copy(alpha = 0.1f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = NeonCyan,
                    focusedContainerColor = MidNavy.copy(alpha = 0.5f),
                    unfocusedContainerColor = DarkCard.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(20.dp),
                singleLine = false,
                maxLines = 3
            )
            Button(
                onClick = {
                    if (isProcessing) {
                        onStop()
                    } else if (inputText.isNotBlank()) {
                        onSend(inputText.trim())
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() || isProcessing,
                modifier = Modifier.size(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isProcessing) Color(0xFFFF3B30) else NeonCyan,
                    disabledContainerColor = NeonCyan.copy(alpha = 0.15f)
                ),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    if (isProcessing) "\u25A0" else "\u25B6",
                    fontSize = 18.sp,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun ChatBubble(log: LogEntry, isLastAgentMessage: Boolean = false) {
    val barBrush: Brush
    val labelColor: Color
    val label: String
    when (log.sender) {
        "USER" -> {
            barBrush = Brush.verticalGradient(listOf(NeonCyan, AccentGlow))
            labelColor = NeonCyan.copy(alpha = 0.6f)
            label = "You"
        }
        "AGENT" -> {
            barBrush = Brush.verticalGradient(listOf(NeonPurple, NeonMagenta))
            labelColor = NeonPurple.copy(alpha = 0.6f)
            label = "Synapse"
        }
        else -> {
            barBrush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.1f), Color.White.copy(alpha = 0.05f)))
            labelColor = Color.White.copy(alpha = 0.2f)
            label = "System"
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 36.dp)
                .background(barBrush, RoundedCornerShape(2.dp))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color = labelColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = log.text,
                modifier = if (isLastAgentMessage) Modifier.semantics { invisibleToUser() } else Modifier,
                color = when {
                    log.isError -> Color(0xFFEF4444)
                    log.sender == "USER" -> Color.White
                    log.sender == "AGENT" -> Color.White.copy(alpha = 0.9f)
                    else -> Color.White.copy(alpha = 0.5f)
                },
                fontFamily = FontFamily.Monospace,
                fontSize = if (log.sender == "USER") 14.sp else 12.sp,
                fontWeight = if (log.sender == "USER") FontWeight.Bold else FontWeight.Normal,
                lineHeight = 19.sp
            )
        }
    }
}
